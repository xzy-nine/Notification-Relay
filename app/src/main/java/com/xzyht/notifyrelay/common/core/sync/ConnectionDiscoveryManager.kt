package com.xzyht.notifyrelay.common.core.sync

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 负责「网络环境」与「设备发现」的整体协调：
 *
 * - 监听系统网络变化（注册 NetworkCallback），当切换到局域网 / WLAN 直连时：
 *   - 更新本机 IP（写入 deviceInfoCache）、
 *   - 重启发现流程、
 *   - 对所有已认证设备做一轮主动 connectToDevice 尝试；
 *
 * - 根据当前网络类型选择发现策略：
 *   - 普通局域网：UDP 广播 + UDP 监听，收到 NOTIFYRELAY_DISCOVER 即更新缓存并视情况自动连接；
 *   - WLAN 直连（Wi‑Fi Direct / 热点网关）：扫描 IP 范围 + 对认证设备最后记忆的 IP 做单独 connectToDevice；
 *   - UDP 关闭时：仅依靠「已记忆 IP + 手动发现循环」对认证设备做周期性的主动连接；
 *
 * - 通过 DeviceConnectionManager 暴露的 internal 访问器读写：
 *   - 设备缓存 deviceInfoCache / deviceLastSeen、
 *   - 已认证设备表 authenticatedDevices、
 *   - 心跳状态 heartbeatedDevices、
 *   - 以及 startServer / updateDeviceList 等入口。
 */
class ConnectionDiscoveryManager(
    private val deviceManager: DeviceConnectionManager,
    private val scope: CoroutineScope
) {
    private val context get() = deviceManager.contextInternal
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null
    private var manualDiscoveryJob: kotlinx.coroutines.Job? = null
    
    // 添加内部变量控制线程运行状态，避免频繁访问deviceManager.udpDiscoveryEnabled
    private var isDiscoveryRunning = false

    private val manualDiscoveryInterval = 2000L

    /**
     * 更新设备信息缓存并触发设备列表更新
     */
    private fun updateDeviceInfoCache(device: DeviceInfo) {
        deviceManager.deviceLastSeenInternal[device.uuid] = System.currentTimeMillis()
        
        synchronized(deviceManager.deviceInfoCacheInternal) {
            deviceManager.deviceInfoCacheInternal[device.uuid] = device
        }
        
        // 同时更新已认证设备表中的设备名称
        synchronized(deviceManager.authenticatedDevices) {
            val auth = deviceManager.authenticatedDevices[device.uuid]
            if (auth != null && auth.displayName != device.displayName) {
                deviceManager.authenticatedDevices[device.uuid] = auth.copy(displayName = device.displayName)
                deviceManager.saveAuthedDevicesInternal()
            }
        }
        
        com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerUtil.updateGlobalDeviceName(device.uuid, device.displayName)
        scope.launch { deviceManager.updateDeviceListInternal() }
    }
    
    /**
     * 连接到已认证设备
     */
    private fun connectToAuthedDevice(device: DeviceInfo) {
        val isAuthed = synchronized(deviceManager.authenticatedDevices) { 
            deviceManager.authenticatedDevices.containsKey(device.uuid) 
        }
        if (isAuthed && !deviceManager.heartbeatedDevicesInternal.contains(device.uuid)) {
            deviceManager.connectToDevice(device)
        }
    }
    
    /**
     * 提取UDP监听线程创建逻辑
     */
    private fun extractUdpListenThread(threadName: String) {
        // 先停止旧线程，确保状态正确
        try {
            listenThread?.interrupt()
            listenThread = null
        } catch (_: Exception) {}
        
        listenThread = Thread {
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket(23334)
                socket.soTimeout = 1000 // 设置超时，避免线程阻塞在receive()上
                val buf = ByteArray(256)
                
                while (isDiscoveryRunning || deviceManager.isWifiDirectNetworkInternal()) {
                    try {
                        val packet = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        val ip = packet.address.hostAddress
                        if (msg.startsWith("NOTIFYRELAY_DISCOVER:")) {
                            val parts = msg.split(":")
                            if (parts.size >= 4) {
                                val uuid = parts[1]
                                val rawDisplay = parts[2]
                                val displayName = try {
                                    deviceManager.decodeDisplayNameFromTransportInternal(rawDisplay)
                                } catch (_: Exception) {
                                    rawDisplay
                                }
                                val port = parts[3].toIntOrNull() ?: deviceManager.listenPort
                                if (!uuid.isNullOrEmpty() && uuid != deviceManager.uuid && !ip.isNullOrEmpty()) {
                                    val device = DeviceInfo(uuid, displayName, ip, port)
                                    updateDeviceInfoCache(device)
                                    connectToAuthedDevice(device)
                                }
                            }
                        } else if (msg.startsWith("HEARTBEAT:")) {
                            // 处理UDP心跳
                            // 心跳格式：HEARTBEAT:<deviceUuid><设备电量%><设备类型>
                            // UUID固定为36个字符（8-4-4-4-12格式），电量在UUID后直接拼接，设备类型在电量后直接拼接
                            val heartbeatPrefix = "HEARTBEAT:" 
                            if (msg.length > heartbeatPrefix.length + 36) {
                                val remoteUuid = msg.substring(heartbeatPrefix.length, heartbeatPrefix.length + 36)
                                val suffix = msg.substring(heartbeatPrefix.length + 36)
                                
                                // 解析电量和设备类型
                                // 设备类型可能是pc或android，固定长度为2-7个字符
                                var batteryStr = suffix
                                var deviceType = "unknown"
                                
                                // 尝试提取设备类型（从字符串末尾）
                                if (suffix.endsWith("pc", ignoreCase = true)) {
                                    batteryStr = suffix.substring(0, suffix.length - 2)
                                    deviceType = "pc"
                                } else if (suffix.endsWith("android", ignoreCase = true)) {
                                    batteryStr = suffix.substring(0, suffix.length - 7)
                                    deviceType = "android"
                                }
                                
                                // 解析电量，确保在0-100之间
                                val batteryLevel = try {
                                    batteryStr.toInt().coerceIn(0, 100)
                                } catch (e: NumberFormatException) {
                                    0
                                }
                                
                                // 仅已在认证表中的设备才接受心跳
                                val isAuthed = synchronized(deviceManager.authenticatedDevices) {
                                    deviceManager.authenticatedDevices.containsKey(remoteUuid)
                                }
                                
                                if (isAuthed && !ip.isNullOrEmpty() && remoteUuid != deviceManager.uuid) {
                                    // 1. 用最新 IP 更新设备缓存
                                    synchronized(deviceManager.deviceInfoCacheInternal) {
                                        val old = deviceManager.deviceInfoCacheInternal[remoteUuid]
                                        val displayName = old?.displayName ?: "未知设备"
                                        deviceManager.deviceInfoCacheInternal[remoteUuid] = DeviceInfo(
                                            remoteUuid,
                                            displayName,
                                            ip,
                                            old?.port ?: 23333,
                                            batteryLevel
                                        )
                                    }
                                    
                                    // 2. 更新认证信息中的 lastIp 和 deviceType
                                    synchronized(deviceManager.authenticatedDevices) {
                                        val auth = deviceManager.authenticatedDevices[remoteUuid]
                                        if (auth != null) {
                                            deviceManager.authenticatedDevices[remoteUuid] = auth.copy(
                                                lastIp = ip,
                                                deviceType = deviceType
                                            )
                                            deviceManager.saveAuthedDevicesInternal()
                                        }
                                    }
                                    
                                    // 3. 用心跳驱动在线状态：刷新 lastSeen + 标记已建立心跳
                                    deviceManager.deviceLastSeenInternal[remoteUuid] = System.currentTimeMillis()
                                    deviceManager.heartbeatedDevicesInternal.add(remoteUuid)
                                    deviceManager.coroutineScopeInternal.launch { 
                                        deviceManager.updateDeviceListInternal() 
                                    }
                                    
                                    // 4. 若本端尚未给对方发心跳，则自动反向 connectToDevice
                                    if (!deviceManager.heartbeatJobsInternal.containsKey(remoteUuid)) {
                                        val info = deviceManager.getDeviceInfoInternal(remoteUuid)
                                        if (info != null && info.ip.isNotEmpty() && info.ip != "0.0.0.0") {
                                            deviceManager.connectToDevice(info)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 超时异常，继续循环检查条件
                        continue
                    }
                }
                Logger.i("卢西奥-死神-NotifyRelay", "$threadName 已关闭")
            } catch (e: InterruptedException) {
                // 正常中断，退出线程
                Logger.i("卢西奥-死神-NotifyRelay", "$threadName 被中断")
            } catch (e: Exception) {
                if (socket != null && socket.isClosed) {
                    Logger.i("卢西奥-死神-NotifyRelay", "$threadName 正常关闭")
                } else {
                    Logger.e("卢西奥-死神-NotifyRelay", "$threadName 异常: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                listenThread = null
            }
        }
        listenThread?.isDaemon = true
        listenThread?.start()
    }

    enum class NetworkType {
        REGULAR,
        HOTSPOT,
        WIFI_DIRECT
    }

    internal fun getLocalIpAddressInternal(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            var bestIp: String? = null
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: "0.0.0.0"
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            if (bestIp == null || ip.startsWith("192.168.43.")) {
                                bestIp = ip
                            }
                        }
                    }
                }
            }
            return bestIp ?: "0.0.0.0"
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
    private fun getCurrentNetworkType(): NetworkType {
        try {
            val cm = connectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)

            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {

                return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)) {
                    NetworkType.WIFI_DIRECT
                } else {
                    NetworkType.HOTSPOT
                }
            }
        } catch (_: Exception) {
        }
        return NetworkType.REGULAR
    }

    internal fun isWifiDirectNetworkInternal(): Boolean {
        return getCurrentNetworkType() == NetworkType.WIFI_DIRECT
    }

    // 获取WLAN直连下的设备IP范围（通常是192.168.49.x或类似）
    internal fun getWifiDirectIpRangeInternal(): List<String> {
        val possibleRanges = listOf("192.168.49.", "192.168.43.", "192.168.42.", "10.0.0.")
        val ips = mutableListOf<String>()
        for (range in possibleRanges) {
            for (i in 1..254) {
                ips.add("$range$i")
            }
        }
        return ips
    }

   
    /**
     * 编码用于 UDP/TCP 简单传输：
     * - 目前使用 Base64(NO_WRAP)，避免与文本协议中的冒号 / 换行冲突；
     * - 具体实现仍在 DeviceConnectionManager 中，通过 internal 复用。
     */
    internal fun encodeDisplayNameForTransportInternal(name: String): String {
        return try {
            deviceManager.encodeDisplayNameForTransportInternal(name)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 解码并清洗从网络接收到的名称：
     * - Base64 解码后再走 sanitizeDisplayNameInternal，保证入库/展示口径一致。
     */
    internal fun decodeDisplayNameFromTransportInternal(encoded: String): String {
        return try {
            deviceManager.decodeDisplayNameFromTransportInternal(encoded)
        } catch (_: Exception) {
            encoded
        }
    }

    fun stopAll() {
        try {
            broadcastThread?.interrupt()
            broadcastThread = null
        } catch (_: Exception) {}
        try {
            listenThread?.interrupt()
            listenThread = null
        } catch (_: Exception) {}
        try {
            val cm = connectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
        manualDiscoveryJob?.cancel()
    }

    fun registerNetworkCallback() {
        val cm = connectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var wasLanNetwork = false

            override fun onAvailable(network: android.net.Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                val isLanNetwork = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true

                //Logger.d("死神-NotifyRelay", "网络可用，类型: ${if (isLanNetwork) "局域网/WLAN直连" else "非局域网"}")

                if (isLanNetwork && !wasLanNetwork) {
                    //Logger.d("死神-NotifyRelay", "检测到从非局域网切换到局域网/WLAN直连，主动重新连接设备")
                    updateLocalIpAndRestartDiscovery()
                } else {
                    updateLocalIpAndRestartDiscovery()
                }

                wasLanNetwork = isLanNetwork
            }

            override fun onLost(network: android.net.Network) {
                //Logger.d("死神-NotifyRelay", "网络丢失")
                wasLanNetwork = false
            }

            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: NetworkCapabilities) {
                val isLanNetwork = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)

                if (isLanNetwork && !wasLanNetwork) {
                    //Logger.d("死神-NotifyRelay", "网络能力变化，检测到切换到局域网/WLAN直连，主动重新连接设备")
                    updateLocalIpAndRestartDiscovery()
                }

                wasLanNetwork = isLanNetwork
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback!!)
    }

    private fun updateLocalIpAndRestartDiscovery() {
        val newIp = getLocalIpAddressInternal()
        val displayName = deviceManager.localDisplayNameInternal()
        synchronized(deviceManager.deviceInfoCacheInternal) {
            deviceManager.deviceInfoCacheInternal[deviceManager.uuid] = DeviceInfo(deviceManager.uuid, displayName, newIp, deviceManager.listenPort)
        }
        //Logger.d("死神-NotifyRelay", "本地IP更新为: $newIp")
        stopDiscovery()
        startDiscovery()

        val hasValidNetwork = newIp != "0.0.0.0" && newIp.isNotEmpty()
        if (hasValidNetwork) {
            scope.launch {
                delay(1000)
                val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
                //Logger.d("死神-NotifyRelay", "网络恢复，主动连接 ${authed.size} 个已认证设备")
                for ((deviceUuid, auth) in authed) {
                    if (deviceUuid == deviceManager.uuid) continue
                    if (deviceManager.heartbeatedDevicesInternal.contains(deviceUuid)) continue

                    val info = deviceManager.getDeviceInfoInternal(deviceUuid)
                    val ip = info?.ip ?: auth.lastIp
                    val port = info?.port ?: auth.lastPort ?: deviceManager.listenPort

                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        //Logger.d("死神-NotifyRelay", "网络恢复后主动connectToDevice: $deviceUuid, $ip:$port")
                        deviceManager.connectToDevice(DeviceInfo(deviceUuid, auth.displayName ?: "已认证设备", ip, port))
                        delay(500)
                    }
                }

                if (deviceManager.isWifiDirectNetworkInternal()) {
                    //Logger.d("死神-NotifyRelay", "WLAN直连模式，启动额外重连逻辑")
                    delay(2000)
                    for ((deviceUuid, auth) in authed) {
                        if (deviceUuid == deviceManager.uuid) continue
                        val info = deviceManager.getDeviceInfoInternal(deviceUuid)
                        val ip = info?.ip ?: auth.lastIp
                        val port = info?.port ?: auth.lastPort ?: deviceManager.listenPort

                        if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                            //Logger.d("死神-NotifyRelay", "WLAN直连额外重连尝试: $deviceUuid, $ip:$port")
                            deviceManager.connectToDevice(DeviceInfo(deviceUuid, auth.displayName ?: "WLAN直连设备", ip, port))
                            delay(1000)
                        }
                    }
                }
            }
        }
    }

    fun stopDiscovery() {
        isDiscoveryRunning = false
        
        try {
            broadcastThread?.interrupt()
            broadcastThread = null
        } catch (_: Exception) {}
        try {
            listenThread?.interrupt()
            listenThread = null
        } catch (_: Exception) {}
        manualDiscoveryJob?.cancel()
    }

    fun startDiscovery() {
        if (deviceManager.isWifiDirectNetworkInternal()) {
            //Logger.d("死神-NotifyRelay", "检测到WLAN直连模式，启动WLAN直连发现")
            startWifiDirectDiscovery(deviceManager.localDisplayNameInternal())
            deviceManager.startServerInternal()
            return
        }

        val udpEnabled = deviceManager.udpDiscoveryEnabled
        
        // 如果UDP启用
        if (udpEnabled) {
            isDiscoveryRunning = true
            
            // 确保广播线程运行
            if (broadcastThread == null) {
                broadcastThread = Thread {
                    try {
                        val displayName = deviceManager.encodeDisplayNameForTransportInternal(deviceManager.localDisplayNameInternal())
                        while (isDiscoveryRunning) {
                            DiscoveryBroadcaster.sendBroadcast(deviceManager, displayName, "255.255.255.255")
                            Thread.sleep(2000)
                        }
                        Logger.i("卢西奥-死神-NotifyRelay", "UDP广播线程已关闭")
                    } catch (e: InterruptedException) {
                        // 正常中断，退出线程
                        Logger.i("卢西奥-死神-NotifyRelay", "UDP广播线程被中断")
                    } catch (e: Exception) {
                        Logger.e("卢西奥-死神-NotifyRelay", "UDP广播异常: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        broadcastThread = null
                    }
                }
                broadcastThread?.isDaemon = true
                broadcastThread?.start()
            }
            
            // 确保监听线程运行（无论之前状态如何，都检查并创建）
            extractUdpListenThread("UDP监听线程")
            manualDiscoveryJob?.cancel()
        } 
        // 如果UDP禁用且发现正在运行，则停止发现
        else if (!udpEnabled && isDiscoveryRunning) {
            stopDiscovery()
            
            scope.launch {
                val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
                for ((uuid, _) in authed) {
                    if (uuid == deviceManager.uuid) continue
                    if (deviceManager.heartbeatedDevicesInternal.contains(uuid)) continue
                    val info = deviceManager.getDeviceInfoInternal(uuid)
                    val ip = info?.ip
                    val port = info?.port ?: deviceManager.listenPort
                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        //Logger.d("死神-NotifyRelay", "UDP关闭时自动connectToDevice: $uuid, $ip")
                        connectToAuthedDevice(DeviceInfo(uuid, info.displayName, ip, port))
                    }
                }
            }
            startManualDiscoveryForAuthedDevices(deviceManager.localDisplayNameInternal())
        }
        deviceManager.startServerInternal()
    }

    private fun startManualDiscoveryForAuthedDevices(localDisplayName: String) {
        manualDiscoveryJob?.cancel()
        manualDiscoveryJob = scope.launch {
            var promptCount = 0
            val failMap = mutableMapOf<String, Int>()
            val maxFail = 5
            while (true) {
                val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
                for ((uuid, _) in authed) {
                    if (uuid == deviceManager.uuid) continue
                    if (deviceManager.heartbeatedDevicesInternal.contains(uuid)) continue
                    if (failMap[uuid] != null && failMap[uuid]!! >= maxFail) continue
                    val info = deviceManager.getDeviceInfoInternal(uuid)
                    val ip = info?.ip
                    val port = info?.port ?: deviceManager.listenPort
                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        val device = DeviceInfo(uuid, info.displayName, ip, port)
                        connectToAuthedDevice(device)
                        deviceManager.connectToDevice(device) { success, _ ->
                            if (success) {
                                failMap.remove(uuid)
                            } else {
                                val count = (failMap[uuid] ?: 0) + 1
                                failMap[uuid] = count
                            }
                        }
                    }
                }
                promptCount++
                delay(manualDiscoveryInterval)
                if (promptCount % 10 == 0) {
                    //Logger.d("死神-NotifyRelay", "手动发现持续运行中，promptCount=$promptCount")
                }
            }
        }
    }

    private fun startWifiDirectDiscovery(localDisplayName: String) {
        scope.launch {
            val ips = getWifiDirectIpRangeInternal()
            val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
            //Logger.d("死神-NotifyRelay", "WLAN直连发现：扫描${ips.size}个IP，认证设备数量：${authed.size}")

            for ((uuid, auth) in authed) {
                if (uuid == deviceManager.uuid) continue
                if (deviceManager.heartbeatedDevicesInternal.contains(uuid)) continue
                val ip = auth.lastIp
                val port = auth.lastPort ?: deviceManager.listenPort
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    //Logger.d("死神-NotifyRelay", "WLAN直连：尝试连接已认证设备 $uuid at $ip:$port")
                    deviceManager.connectToDevice(DeviceInfo(uuid, auth.displayName ?: "WLAN直连设备", ip, port))
                    delay(500)
                }
            }

            val encodedName = deviceManager.encodeDisplayNameForTransportInternal(localDisplayName)
            for (ip in ips) {
                try {
                    DiscoveryBroadcaster.sendBroadcast(deviceManager, encodedName, ip)
                    delay(10)
                } catch (_: Exception) {
                }
            }

            //Logger.d("死神-NotifyRelay", "WLAN直连发现完成")
        }
        
        // 在WLAN直连模式下也启动监听线程，确保能接收其他设备的广播消息
        extractUdpListenThread("WLAN直连UDP监听线程")
    }
}
