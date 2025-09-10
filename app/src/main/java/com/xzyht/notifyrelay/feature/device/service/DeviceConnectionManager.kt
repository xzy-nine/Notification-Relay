package com.xzyht.notifyrelay.feature.device.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import com.xzyht.notifyrelay.common.data.StorageManager
import kotlinx.coroutines.delay
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.xzyht.notifyrelay.core.util.EncryptionManager
import com.xzyht.notifyrelay.BuildConfig

data class DeviceInfo(
    val uuid: String,
    val displayName: String, // 前端显示名，优先蓝牙名，其次型号
    val ip: String,
    val port: Int
)

object DeviceConnectionManagerUtil {
    // 工具：构造 json 格式的通知数据
    fun buildNotificationJson(packageName: String, appName: String?, title: String?, text: String?, time: Long): String {
        val json = org.json.JSONObject()
        json.put("packageName", packageName)
        json.put("appName", appName ?: packageName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("time", time)
        return json.toString()
    }

    // 静态缓存，便于 UI 查询 uuid->displayName
    private val globalDeviceNameCache = mutableMapOf<String, String>()
    fun updateGlobalDeviceName(uuid: String, displayName: String) {
        synchronized(globalDeviceNameCache) {
            globalDeviceNameCache[uuid] = displayName
        }
    }
    fun getDisplayNameByUuid(uuid: String?): String {
        if (uuid == null) return "未知设备"
        if (uuid == "本机") return "本机"
        synchronized(globalDeviceNameCache) {
            return globalDeviceNameCache[uuid] ?: uuid
        }
    }
}

data class AuthInfo(
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean,
    val displayName: String? = null, // 新增：持久化设备名
    val lastIp: String? = null,
    val lastPort: Int? = null
)

// =================== 设备连接管理器主类 ===================
class DeviceConnectionManager(private val context: android.content.Context) {
    // 获取本机局域网IP（非127.0.0.1），优先选择热点或Wi-Fi接口
    private fun getLocalIpAddress(): String {
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
                            // 优先选择私有IP
                            if (bestIp == null || ip.startsWith("192.168.43.")) { // 热点通常是192.168.43.x
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
    // 新增：UDP关闭时对已认证设备发送加密UDP唤醒包
    private var manualDiscoveryJob: kotlinx.coroutines.Job? = null
    private val manualDiscoveryTimeout = 60_000L // 增加到60秒
    private val manualDiscoveryInterval = 2000L // 2秒
    private val manualDiscoveryPromptCount = 2
    // 记录已建立心跳的设备
    private val heartbeatedDevices = mutableSetOf<String>()

    // 手动发现失败等提示不再通过回调UI，直接Log输出
    /**
     * 停止所有后台线程和网络服务，释放资源，供 Service onDestroy 调用
     */
    fun stopAll() {
        try {
            // 停止UDP广播线程
            broadcastThread?.interrupt()
            broadcastThread = null
        } catch (_: Exception) {}
        try {
            // 停止UDP监听线程
            listenThread?.interrupt()
            listenThread = null
        } catch (_: Exception) {}
        try {
            // 关闭TCP服务端口
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        // 注销网络监听器
        try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
        // 其他清理（如定时任务）
        // coroutineScope.cancel() 不建议直接调用，避免影响外部协程
    }
    // 设备信息缓存，解决未认证设备无法显示详细信息问题
    private val deviceInfoCache = mutableMapOf<String, DeviceInfo>()
    //private fun logDeviceCache(tag: String) {
    //    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "[$tag] deviceInfoCache: ${deviceInfoCache.keys}")
    //    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "[$tag] deviceLastSeen: ${deviceLastSeen.keys}")
    //    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "[$tag] authenticatedDevices: ${authenticatedDevices.keys}")
    //    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "[$tag] rejectedDevices: ${rejectedDevices}")
    //    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "[$tag] _devices: ${_devices.value.keys}")
    //}
    // 持久化认证设备表的key
    // 持久化认证设备表的key
    private val PREFS_AUTHED_DEVICES = "authed_devices_json"

    // 加载已认证设备
    private fun loadAuthedDevices() {
        val json = StorageManager.getString(context, PREFS_AUTHED_DEVICES) ?: return
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uuid = obj.getString("uuid")
                val publicKey = obj.getString("publicKey")
                val sharedSecret = obj.getString("sharedSecret")
                val isAccepted = obj.optBoolean("isAccepted", true)
                val displayName = obj.optString("displayName").takeIf { it.isNotEmpty() }
                val lastIp = obj.optString("lastIp").takeIf { it.isNotEmpty() }
                val lastPort = if (obj.has("lastPort")) obj.optInt("lastPort", 23333) else null
                authenticatedDevices[uuid] = AuthInfo(publicKey, sharedSecret, isAccepted, displayName, lastIp, lastPort)
                // 新增：恢复设备名到缓存
                if (!displayName.isNullOrEmpty()) {
                    DeviceConnectionManagerUtil.updateGlobalDeviceName(uuid, displayName)
                }
                // 新增：恢复ip到deviceInfoCache
                if (!lastIp.isNullOrEmpty()) {
                    synchronized(deviceInfoCache) {
                        deviceInfoCache[uuid] = DeviceInfo(uuid, displayName ?: "已认证设备", lastIp, lastPort ?: 23333)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // 保存已认证设备
    private fun saveAuthedDevices() {
        try {
            val arr = org.json.JSONArray()
            for ((uuid, auth) in authenticatedDevices) {
                if (auth.isAccepted) {
                    val obj = org.json.JSONObject()
                    obj.put("uuid", uuid)
                    obj.put("publicKey", auth.publicKey)
                    obj.put("sharedSecret", auth.sharedSecret)
                    obj.put("isAccepted", auth.isAccepted)
                    // 新增：持久化 displayName
                    val name = auth.displayName ?: deviceInfoCache[uuid]?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
                    obj.put("displayName", name)
                    // 新增：持久化ip和port
                    val info = deviceInfoCache[uuid]
                    obj.put("lastIp", info?.ip ?: auth.lastIp ?: "")
                    obj.put("lastPort", info?.port ?: auth.lastPort ?: 23333)
                    arr.put(obj)
                }
            }
            StorageManager.putString(context, PREFS_AUTHED_DEVICES, arr.toString())
        } catch (_: Exception) {}
    }
    /**
     * 新增：服务端握手请求回调，UI层应监听此回调并弹窗确认，参数为请求设备uuid/displayName/公钥，回调参数true=同意，false=拒绝
     */
    var onHandshakeRequest: ((DeviceInfo, String, (Boolean) -> Unit) -> Unit)? = null
    /**
     * 设备发现/连接/数据发送/接收，全部本地实现。
     */
    private val notificationDataReceivedCallbacks = mutableSetOf<(String) -> Unit>()

    /**
     * 注册通知数据接收回调
     */
    fun registerOnNotificationDataReceived(callback: (String) -> Unit) {
        notificationDataReceivedCallbacks.add(callback)
    }

    /**
     * 注销通知数据接收回调
     */
    fun unregisterOnNotificationDataReceived(callback: (String) -> Unit) {
        notificationDataReceivedCallbacks.remove(callback)
    }
    private val _devices = MutableStateFlow<Map<String, Pair<DeviceInfo, Boolean>>>(emptyMap())
    /**
     * 设备状态流：key为uuid，value为(DeviceInfo, isOnline)
     * 只要认证过的设备会一直保留，未认证设备3秒未发现则消失
     */
    val devices: StateFlow<Map<String, Pair<DeviceInfo, Boolean>>> = _devices
    private val uuid: String
    // 认证设备表，key为uuid
    private val authenticatedDevices = mutableMapOf<String, AuthInfo>()
    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()
    // 本地密钥对（简单字符串模拟，实际应用可用RSA/ECDH等）
    private val localPublicKey: String
    private val localPrivateKey: String
    private val listenPort: Int = 23333
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val deviceLastSeen = mutableMapOf<String, Long>()
    // 广播发现线程
    private var broadcastThread: Thread? = null
    // 监听线程
    private var listenThread: Thread? = null
    // 心跳定时任务
    private val heartbeatJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    // 新增：网络变化监听器
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    // UI全局开关：是否启用UDP发现
    var udpDiscoveryEnabled: Boolean
        get() = StorageManager.getBoolean(context, "udp_discovery_enabled", true)
        set(value) {
            StorageManager.putBoolean(context, "udp_discovery_enabled", value)
        }

    init {
        val savedUuid = StorageManager.getString(context, "device_uuid")
        if (savedUuid.isNotEmpty()) {
            uuid = savedUuid
        } else {
            val newUuid = UUID.randomUUID().toString()
            StorageManager.putString(context, "device_uuid", newUuid)
            uuid = newUuid
        }
        // 公钥持久化
        val savedPub = StorageManager.getString(context, "device_pubkey")
        if (savedPub.isNotEmpty()) {
            localPublicKey = savedPub
        } else {
            val newPub = UUID.randomUUID().toString().replace("-", "")
            StorageManager.putString(context, "device_pubkey", newPub)
            localPublicKey = newPub
        }
        // 私钥可临时
        localPrivateKey = UUID.randomUUID().toString().replace("-", "")
        // 兼容旧用户：首次运行时如无保存则默认true
        if (!StorageManager.getBoolean(context, "udp_discovery_enabled", false)) {
            StorageManager.putBoolean(context, "udp_discovery_enabled", true)
        }
        loadAuthedDevices()
        // 新增：补全本机 deviceInfoCache，便于反向 connectToDevice
        val displayName = try {
            val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
        } catch (_: Exception) {
            android.os.Build.MODEL
        }
        val localIp = getLocalIpAddress()
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = DeviceInfo(uuid, displayName, localIp, listenPort)
        }
        startOfflineDeviceCleaner()
        // 新增：注册网络变化监听器
        registerNetworkCallback()
    }

    // 新增：注册网络变化监听器
    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "网络可用，重新获取IP并启动发现")
                updateLocalIpAndRestartDiscovery()
            }

            override fun onLost(network: android.net.Network) {
                if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "网络丢失")
            }

            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: NetworkCapabilities) {
                if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "网络能力变化，重新获取IP")
                updateLocalIpAndRestartDiscovery()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    // 新增：更新本地IP并重新启动发现
    private fun updateLocalIpAndRestartDiscovery() {
        val newIp = getLocalIpAddress()
        val displayName = try {
            val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
        } catch (_: Exception) {
            android.os.Build.MODEL
        }
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = DeviceInfo(uuid, displayName, newIp, listenPort)
        }
        if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "本地IP更新为: $newIp")
        // 重新启动发现
        stopDiscovery()
        startDiscovery()
    }

    // 新增：停止发现
    private fun stopDiscovery() {
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
        // 优先获取蓝牙设备名，获取不到则用型号
        fun getLocalDisplayName(): String {
            return try {
                val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
            } catch (_: Exception) {
                android.os.Build.MODEL
            }
        }

        // 新增：检测WLAN直连模式
        if (isWifiDirectNetwork()) {
            if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "检测到WLAN直连模式，启动WLAN直连发现")
            startWifiDirectDiscovery(getLocalDisplayName())
            startServer()
            return
        }

        if (udpDiscoveryEnabled) {
             
            if (broadcastThread == null) {
                broadcastThread = Thread {
                    var socket: java.net.DatagramSocket? = null
                    try {
                        socket = java.net.DatagramSocket()
                        val displayName = getLocalDisplayName()
                        val group = java.net.InetAddress.getByName("255.255.255.255")
                        while (udpDiscoveryEnabled) {
                            val buf = ("NOTIFYRELAY_DISCOVER:${uuid}:${displayName}:${listenPort}").toByteArray()
                            val packet = java.net.DatagramPacket(buf, buf.size, group, 23334)
                            socket.send(packet)
                            if (BuildConfig.DEBUG) Log.d("卢西奥-死神-NotifyRelay", "UDP广播已发送: $displayName, uuid=$uuid, port=$listenPort")
                            Thread.sleep(2000)
                        }
                        if (BuildConfig.DEBUG) Log.i("卢西奥-死神-NotifyRelay", "UDP广播线程已关闭")
                    } catch (e: Exception) {
                        if (socket != null && socket.isClosed) {
                            if (BuildConfig.DEBUG) Log.i("卢西奥-死神-NotifyRelay", "UDP广播线程正常关闭")
                        } else {
                            if (BuildConfig.DEBUG) Log.e("卢西奥-死神-NotifyRelay", "UDP广播异常: ${e.message}")
                            e.printStackTrace()
                        }
                    } finally {
                        try { socket?.close() } catch (_: Exception) {}
                    }
                }
                broadcastThread?.isDaemon = true
                broadcastThread?.start()
            }
            if (listenThread == null) {
                listenThread = Thread {
                    var socket: java.net.DatagramSocket? = null
                    try {
                        socket = java.net.DatagramSocket(23334)
                        val buf = ByteArray(256)
                        while (udpDiscoveryEnabled) {
                            val packet = java.net.DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length)
                            val ip = packet.address.hostAddress
                            if (msg.startsWith("NOTIFYRELAY_DISCOVER:")) {
                                val parts = msg.split(":")
                                if (parts.size >= 4) {
                                    val uuid = parts[1]
                                    val displayName = parts[2]
                                    val port = parts[3].toIntOrNull() ?: 23333
                                    if (!uuid.isNullOrEmpty() && uuid != this@DeviceConnectionManager.uuid && !ip.isNullOrEmpty()) {
                                        if (BuildConfig.DEBUG) Log.d("卢西奥-死神-NotifyRelay", "收到UDP广播: $msg, ip=$ip, 设备uuid为=${this@DeviceConnectionManager.uuid}")
                                        val device = DeviceInfo(uuid, displayName, ip, port)
                                        deviceLastSeen[uuid] = System.currentTimeMillis()
                                        if (BuildConfig.DEBUG) Log.i("卢西奥-死神-NotifyRelay", "收到UDP，已重置 deviceLastSeen[$uuid] = ${deviceLastSeen[uuid]}")
                                        synchronized(deviceInfoCache) {
                                            deviceInfoCache[uuid] = device
                                        }
                                        DeviceConnectionManagerUtil.updateGlobalDeviceName(uuid, displayName)
                                        // 新增：已认证设备自动connectToDevice建立心跳
                                        val isAuthed = synchronized(authenticatedDevices) { authenticatedDevices.containsKey(uuid) }
                                        if (isAuthed && !heartbeatedDevices.contains(uuid)) {
                                            if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "已认证设备收到UDP，自动尝试connectToDevice: $uuid, $ip")
                                            connectToDevice(DeviceInfo(uuid, displayName, ip, port))
                                        }
                                        coroutineScope.launch {
                                            updateDeviceList()
                                        }
                                    }
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) Log.i("卢西奥-死神-NotifyRelay", "UDP监听线程已关闭")
                    } catch (e: Exception) {
                        if (socket != null && socket.isClosed) {
                            if (BuildConfig.DEBUG) Log.i("卢西奥-死神-NotifyRelay", "UDP监听线程正常关闭")
                        } else {
                            if (BuildConfig.DEBUG) Log.e("卢西奥-死神-NotifyRelay", "UDP监听异常: ${e.message}")
                            e.printStackTrace()
                        }
                    } finally {
                        try { socket?.close() } catch (_: Exception) {}
                    }
                }
                listenThread?.isDaemon = true
                listenThread?.start()
            }
            // 停止手动发现任务
            manualDiscoveryJob?.cancel()
        } else {
            // UDP关闭时，主动对所有已认证设备尝试connectToDevice（只要未建立心跳且有ip）
            coroutineScope.launch {
                val authed = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
                for ((uuid, auth) in authed) {
                    if (uuid == this@DeviceConnectionManager.uuid) continue
                    if (heartbeatedDevices.contains(uuid)) continue
                    val info = getDeviceInfo(uuid)
                    val ip = info?.ip
                    val port = info?.port ?: 23333
                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "UDP关闭时自动connectToDevice: $uuid, $ip")
                        connectToDevice(DeviceInfo(uuid, info.displayName, ip, port))
                    }
                }
            }
            startManualDiscoveryForAuthedDevices(getLocalDisplayName())
        }
        startServer()
    }

    // 启动手动发现任务
    private fun startManualDiscoveryForAuthedDevices(localDisplayName: String) {
        manualDiscoveryJob?.cancel()
        manualDiscoveryJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            var promptCount = 0
            val failMap = mutableMapOf<String, Int>()
            val maxFail = 5 // 增加到5次
            while (true) { // 改为无限循环，直到手动取消或成功
                val authed = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
                var anySent = false
                for ((uuid, auth) in authed) {
                    if (uuid == this@DeviceConnectionManager.uuid) continue
                    if (heartbeatedDevices.contains(uuid)) continue
                    if (failMap[uuid] != null && failMap[uuid]!! >= maxFail) continue
                    val info = getDeviceInfo(uuid)
                    val ip = info?.ip
                    val port = info?.port ?: 23333
                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        connectToDevice(DeviceInfo(uuid, info.displayName, ip, port)) { success, _ ->
                            if (success) {
                                failMap.remove(uuid)
                            } else {
                                val count = (failMap[uuid] ?: 0) + 1
                                failMap[uuid] = count
                            }
                        }
                        anySent = true
                    }
                }
                promptCount++
                delay(manualDiscoveryInterval)
                // 每10次提示一次，避免过多日志
                if (promptCount % 10 == 0) {
                    if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "手动发现持续运行中，promptCount=$promptCount")
                }
            }
        }
    }

    // 统一设备状态管理：3秒未发现未认证设备直接移除，已认证设备置灰
    private fun startOfflineDeviceCleaner() {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                try {
                    updateDeviceList()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("死神-NotifyRelay", "startOfflineDeviceCleaner定时器异常: ${e.message}")
                }
            }
        }
    }

    private fun updateDeviceList() {
        val now = System.currentTimeMillis()
        val authed = synchronized(authenticatedDevices) { authenticatedDevices.keys.toSet() }
        val allUuids = (deviceLastSeen.keys + authed).toSet()
        val newMap = mutableMapOf<String, Pair<DeviceInfo, Boolean>>()
        val unauthedTimeout = 5000L // 未认证设备保留两次UDP广播周期（2*2000ms）
        val authedHeartbeatTimeout = 12_000L // 已认证设备心跳超时阈值
        val oldMap = _devices.value
        for (uuid in allUuids) {
            val lastSeen = deviceLastSeen[uuid]
            val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
            // 检查时钟回拨
            var safeLastSeen = lastSeen
            if (lastSeen != null && now < lastSeen) {
                if (BuildConfig.DEBUG) Log.w("死神-NotifyRelay", "检测到时钟回拨: now=$now, lastSeen=$lastSeen, uuid=$uuid，强制重置lastSeen=now")
                deviceLastSeen[uuid] = now
                safeLastSeen = now
            }
            if (auth != null) {
                // 仅基于心跳包判定在线
                val diff = if (safeLastSeen != null) now - safeLastSeen else -1L
                val isOnline = safeLastSeen != null && diff <= authedHeartbeatTimeout
                val info = getDeviceInfo(uuid) ?: DeviceInfo(uuid, auth.displayName ?: "已认证设备", "", listenPort)
                val oldOnline = oldMap[uuid]?.second
                if (oldOnline != null && oldOnline != isOnline) {
                    if (BuildConfig.DEBUG) Log.i("天使-死神-NotifyRelay", "[updateDeviceList] 已认证设备状态变化: uuid=$uuid, isOnline=$isOnline, lastSeen=$safeLastSeen, diff=$diff")
                }
                newMap[uuid] = info to isOnline
            } else {
                val diff = if (safeLastSeen != null) now - safeLastSeen else -1L
                val isOnline = safeLastSeen != null && diff <= unauthedTimeout
                val info = getDeviceInfo(uuid)
                val oldOnline = oldMap[uuid]?.second
                if (oldOnline != null && oldOnline != isOnline) {
                    if (BuildConfig.DEBUG) Log.i("死神-NotifyRelay", "[updateDeviceList] 未认证设备状态变化: uuid=$uuid, isOnline=$isOnline, lastSeen=$safeLastSeen, diff=$diff")
                }
                if (isOnline) {
                    if (info != null) newMap[uuid] = info to true
                } else {
                    deviceLastSeen.remove(uuid)
                }
            }
        }
        _devices.value = newMap
    }

    private fun getDeviceInfo(uuid: String): DeviceInfo? {
        // 优先从缓存取（含真实ip）
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid]?.let { return it }
        }
        // 其次从设备流取
        _devices.value[uuid]?.first?.let { return it }
        // 最后从认证表补全（无ip）
        val auth = authenticatedDevices[uuid]
        if (auth != null) {
            val name = auth.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
            val ip = auth.lastIp ?: ""
            val port = auth.lastPort ?: listenPort
            return DeviceInfo(uuid, name, ip, port)
        }
        // 新增：本机兜底逻辑
        if (uuid == this.uuid) {
            val displayName = try {
                val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
            } catch (_: Exception) {
                android.os.Build.MODEL
            }
            val localIp = getLocalIpAddress()
            return DeviceInfo(uuid, displayName, localIp, listenPort)
        }
        return null
    }

    // 连接设备
    fun connectToDevice(device: DeviceInfo, callback: ((Boolean, String?) -> Unit)? = null) {
        coroutineScope.launch {
            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "connectToDevice called: device=$device, rejected=${rejectedDevices.contains(device.uuid)}")
            try {
                if (rejectedDevices.contains(device.uuid)) {
                    if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "connectToDevice: 已被对方拒绝 uuid=${device.uuid}")
                    callback?.invoke(false, "已被对方拒绝")
                    return@launch
                }

                // 新增：WLAN直连模式下增加重试次数
                val maxRetries = if (isWifiDirectNetwork()) 3 else 1
                var lastException: Exception? = null

                for (retry in 0 until maxRetries) {
                    try {
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(device.ip, device.port), 3000) // 3秒超时
                        val writer = OutputStreamWriter(socket.getOutputStream())
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        writer.write("HANDSHAKE:${uuid}:${localPublicKey}\n")
                        writer.flush()
                        val resp = reader.readLine()
                        if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "connectToDevice: handshake resp=$resp")

                        if (resp != null && resp.startsWith("ACCEPT:")) {
                            val parts = resp.split(":")
                            if (parts.size >= 3) {
                                val remotePubKey = parts[2]
                                val sharedSecret = EncryptionManager.generateSharedSecret(localPublicKey, remotePubKey)
                                synchronized(authenticatedDevices) {
                                    authenticatedDevices.remove(device.uuid)
                                    authenticatedDevices[device.uuid] = AuthInfo(remotePubKey, sharedSecret, true, device.displayName, device.ip, device.port)
                                    saveAuthedDevices()
                                }
                                // 强制更新deviceInfoCache，确保ip为最新
                                synchronized(deviceInfoCache) {
                                    deviceInfoCache[device.uuid] = device
                                }
                                if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "认证成功，启动心跳: uuid=${device.uuid}, ip=${device.ip}, port=${device.port}")
                                // 启动心跳定时任务
                                startHeartbeatToDevice(device.uuid, device.ip, device.port, remotePubKey, sharedSecret)
                                // 立即刷新lastSeen，保证认证后立刻在线
                                deviceLastSeen[device.uuid] = System.currentTimeMillis()
                                // 自动反向connectToDevice本机，确保双向链路
                                if (device.uuid != this@DeviceConnectionManager.uuid) {
                                    val myInfo = getDeviceInfo(this@DeviceConnectionManager.uuid)
                                    if (myInfo != null) {
                                        // 避免死循环，仅在对方未主动连接时尝试
                                        if (!heartbeatedDevices.contains(device.uuid)) {
                                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "认证成功后自动反向connectToDevice: myInfo=$myInfo, peer=${device.uuid}")
                                            connectToDevice(myInfo)
                                        } else {
                                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "对方已建立心跳，不再反向connectToDevice: peer=${device.uuid}")
                                        }
                                    } else {
                                        if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "本机getDeviceInfo返回null，无法反向connectToDevice")
                                    }
                                }
                                writer.close()
                                reader.close()
                                socket.close()
                                callback?.invoke(true, null)
                                return@launch
                            } else {
                                if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "认证响应格式错误: $resp")
                                callback?.invoke(false, "认证响应格式错误")
                                return@launch
                            }
                        } else if (resp != null && resp.startsWith("REJECT:")) {
                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "对方拒绝连接: uuid=${device.uuid}")
                            rejectedDevices.add(device.uuid)
                            callback?.invoke(false, "对方拒绝连接")
                            return@launch
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "认证失败: resp=$resp")
                            callback?.invoke(false, "认证失败")
                            return@launch
                        }
                    } catch (e: Exception) {
                        lastException = e
if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "connectToDevice重试 $retry 失败: ${e.message}")
                        if (retry < maxRetries - 1) {
                            delay(1000) // 重试前等待1秒
                        }
                    }
                }

                // 所有重试失败
                if (BuildConfig.DEBUG) android.util.Log.e("死神-NotifyRelay", "connectToDevice所有重试失败: ${lastException?.message}")
                callback?.invoke(false, lastException?.message ?: "连接失败")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.e("死神-NotifyRelay", "connectToDevice异常: ${e.message}")
                e.printStackTrace()
                callback?.invoke(false, e.message)
            }
        }
    }

    // 启动心跳定时任务
    private fun startHeartbeatToDevice(uuid: String, ip: String, port: Int, remotePubKey: String, sharedSecret: String) {
        heartbeatJobs[uuid]?.cancel()
        heartbeatedDevices.add(uuid) // 标记已建立心跳
        if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "startHeartbeatToDevice: uuid=$uuid, ip=$ip, port=$port")
        val job = coroutineScope.launch {
            var failCount = 0
            val maxFail = 5 // 增加到5次
            while (true) {
                var success = false
                try {
                    // 每次取最新ip和port，避免ip变更后心跳丢失
                    val info = synchronized(deviceInfoCache) { deviceInfoCache[uuid] }
                    val targetIp = info?.ip?.takeIf { it.isNotEmpty() && it != "0.0.0.0" } ?: ip
                    val targetPort = info?.port ?: port
                    if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "心跳目标ip=$targetIp, port=$targetPort, uuid=$uuid")
                    val socket = Socket(targetIp, targetPort)
                    val writer = OutputStreamWriter(socket.getOutputStream())
                    writer.write("HEARTBEAT:${this@DeviceConnectionManager.uuid}:${localPublicKey}\n")
                    writer.flush()
                    writer.close()
                    socket.close()
                    // 不再本地刷新 deviceLastSeen，只有收到对方心跳包时才刷新
                    success = true
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "心跳发送失败: $uuid, ${e.message}")
                }
                if (success) {
                    failCount = 0
                } else {
                    failCount++
                    if (failCount >= maxFail) {
                        if (BuildConfig.DEBUG) android.util.Log.w("死神-NotifyRelay", "心跳连续失败${failCount}次，自动停止心跳并进入手动发现: $uuid")
                        // 停止心跳任务
                        heartbeatJobs[uuid]?.cancel()
                        heartbeatJobs.remove(uuid)
                        heartbeatedDevices.remove(uuid)
                        // 直接Toast提示（主线程）
                        val msg = "设备[${DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)}]离线，已自动进入手动发现模式，请检查网络或重新发现设备"
                        val ctx = context
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        // 启动手动发现
                        val displayName = try {
                            val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                            if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
                        } catch (_: Exception) {
                            android.os.Build.MODEL
                        }
                        startManualDiscoveryForAuthedDevices(displayName)
                        break
                    }
                }
                delay(4000) // 心跳周期4秒
            }
        }
        heartbeatJobs[uuid] = job
    }

    // 使用加密管理器进行数据加密
    private fun encryptData(input: String, key: String): String {
        return EncryptionManager.encrypt(input, key)
    }

    // 使用加密管理器进行数据解密
    private fun decryptData(input: String, key: String): String {
        return EncryptionManager.decrypt(input, key)
    }

    // 发送通知数据（加密）
    fun sendNotificationData(device: DeviceInfo, data: String) {
        // data 必须为 json 字符串，包含 packageName, title, text, time
        coroutineScope.launch {
            try {
                val auth = authenticatedDevices[device.uuid]
                if (auth == null || !auth.isAccepted) {
                    if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "未认证设备，禁止发送")
                    return@launch
                }
                val socket = Socket(device.ip, device.port)
                val writer = OutputStreamWriter(socket.getOutputStream())
                // 加密数据
                val encryptedData = encryptData(data, auth.sharedSecret)
                // 标记 json
                val payload = "DATA_JSON:${uuid}:${localPublicKey}:${auth.sharedSecret}:${encryptedData}"
                writer.write(payload + "\n")
                writer.flush()
                writer.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 接收通知数据（解密）
    fun handleNotificationData(data: String, sharedSecret: String? = null, remoteUuid: String? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d("秩序之光 死神-NotifyRelay", "收到通知数据: $data")
        val decrypted = if (sharedSecret != null) {
            try {
                decryptData(data, sharedSecret)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.d("秩序之光 死神-NotifyRelay", "解密失败: ${e.message}")
                data
            }
        } else data
        // 只处理 json 格式
        try {
            if (remoteUuid != null) {
                val json = org.json.JSONObject(decrypted)
                val pkg = json.optString("packageName")
                val appName = json.optString("appName")
                val title = json.optString("title")
                val text = json.optString("text")
                val time = json.optLong("time", System.currentTimeMillis())
                // 尝试获取设备名并更新缓存（如有）
                val displayName = DeviceConnectionManagerUtil.getDisplayNameByUuid(remoteUuid)
                if (!displayName.isNullOrEmpty() && displayName != remoteUuid) {
                    DeviceConnectionManagerUtil.updateGlobalDeviceName(remoteUuid, displayName)
                }
                val repoClass = Class.forName("com.xzyht.notifyrelay.feature.device.model.NotificationRepository")
                val addMethod = repoClass.getDeclaredMethod("addRemoteNotification", String::class.java, String::class.java, String::class.java, String::class.java, Long::class.java, String::class.java, android.content.Context::class.java)
                // 修复：NotificationRepository是object（单例），需要获取实例
                val repoInstance = repoClass.getDeclaredField("INSTANCE").get(null)
                addMethod.invoke(repoInstance, pkg, appName, title, text, time, remoteUuid, context)
                // 强制刷新设备列表和UI
                val scanMethod = repoClass.getDeclaredMethod("scanDeviceList", android.content.Context::class.java)
                scanMethod.invoke(repoInstance, context)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("秩序之光 死神-NotifyRelay", "存储远程通知失败: ${e.message}")
        }
        // 不再直接发系统通知，由 UI 层渲染
        notificationDataReceivedCallbacks.forEach { it.invoke(decrypted) }
    }

    // 启动TCP服务监听，接收其他设备的通知
    private fun startServer() {
        coroutineScope.launch {
            try {
                serverSocket = ServerSocket(listenPort)
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    coroutineScope.launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val line = reader.readLine()
                            if (line != null) {
                                if (line.startsWith("HANDSHAKE:")) {
                                    val parts = line.split(":")
                                    if (parts.size >= 3) {
                                        val remoteUuid = parts[1]
                                        val remotePubKey = parts[2]
                                        val ip: String = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                                        // 同步更新 deviceInfoCache，保证ip为最新
                                        synchronized(deviceInfoCache) {
                                            val old = deviceInfoCache[remoteUuid]
                                            val displayName = old?.displayName ?: "未知设备"
                                            // 只更新IP，不更新端口，端口保持原有或默认
                                            deviceInfoCache[remoteUuid] = DeviceInfo(
                                                remoteUuid,
                                                displayName,
                                                ip,
                                                old?.port ?: 23333
                                            )
                                        }
                                        // 只更新认证表中的IP，不更新端口
                                        synchronized(authenticatedDevices) {
                                            val auth = authenticatedDevices[remoteUuid]
                                            if (auth != null) {
                                                authenticatedDevices[remoteUuid] = auth.copy(lastIp = ip)
                                                saveAuthedDevices()
                                            }
                                        }
                                        val remoteDevice = deviceInfoCache[remoteUuid]!!
                                        // 新增：已认证设备自动ACCEPT
                                        val alreadyAuthed = synchronized(authenticatedDevices) {
                                            authenticatedDevices[remoteUuid]?.isAccepted == true
                                        }
                                        if (alreadyAuthed) {
                                            coroutineScope.launch {
                                                val writer = OutputStreamWriter(client.getOutputStream())
                                                writer.write("ACCEPT:${uuid}:${localPublicKey}\n")
                                                writer.flush()
                                                writer.close()
                                                reader.close()
                                                client.close()
                                            }
                                        } else if (onHandshakeRequest != null) {
                                            onHandshakeRequest!!.invoke(remoteDevice, remotePubKey) { accepted ->
                                                if (accepted) {
                                                    val sharedSecret = EncryptionManager.generateSharedSecret(localPublicKey, remotePubKey)
                                                    synchronized(authenticatedDevices) {
                                                        authenticatedDevices.remove(remoteUuid)
                                                        authenticatedDevices[remoteUuid] = AuthInfo(remotePubKey, sharedSecret, true, remoteDevice.displayName)
                                                        saveAuthedDevices()
                                                    }
                                                } else {
                                                    synchronized(rejectedDevices) {
                                                        rejectedDevices.add(remoteUuid)
                                                    }
                                                }
                                                coroutineScope.launch {
                                                    val writer = OutputStreamWriter(client.getOutputStream())
                                                    if (accepted) {
                                                        writer.write("ACCEPT:${uuid}:${localPublicKey}\n")
                                                    } else {
                                                        writer.write("REJECT:${uuid}\n")
                                                    }
                                                    writer.flush()
                                                    writer.close()
                                                    reader.close()
                                                    client.close()
                                                }
                                            }
                                        } else {
                                            val writer = OutputStreamWriter(client.getOutputStream())
                                            writer.write("REJECT:${uuid}\n")
                                            writer.flush()
                                            writer.close()
                                            reader.close()
                                            client.close()
                                        }
                                    } else {
                                        val writer = OutputStreamWriter(client.getOutputStream())
                                        writer.write("REJECT:${uuid}\n")
                                        writer.flush()
                                        writer.close()
                                        reader.close()
                                        client.close()
                                    }
                                } else if (line.startsWith("HEARTBEAT:")) {
                                    val parts = line.split(":")
                                    if (parts.size >= 3) {
                                        val remoteUuid = parts[1]
                                        val isAuthed = synchronized(authenticatedDevices) { authenticatedDevices.containsKey(remoteUuid) }
                                        if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "收到HEARTBEAT: remoteUuid=$remoteUuid, isAuthed=$isAuthed, authedKeys=${authenticatedDevices.keys}")
                                        if (isAuthed) {
                                            val ip: String = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                                            // 同步更新 deviceInfoCache，保证ip为最新
                                            synchronized(deviceInfoCache) {
                                                val old = deviceInfoCache[remoteUuid]
                                                val displayName = old?.displayName ?: "未知设备"
                                                // 只更新IP，不更新端口
                                                deviceInfoCache[remoteUuid] = DeviceInfo(
                                                    remoteUuid,
                                                    displayName,
                                                    ip,
                                                    old?.port ?: 23333
                                                )
                                            }
                                            // 只更新认证表中的IP，不更新端口
                                            synchronized(authenticatedDevices) {
                                                val auth = authenticatedDevices[remoteUuid]
                                                if (auth != null) {
                                                    authenticatedDevices[remoteUuid] = auth.copy(lastIp = ip)
                                                    saveAuthedDevices()
                                                }
                                            }
                                        deviceLastSeen[remoteUuid] = System.currentTimeMillis()
                                        heartbeatedDevices.add(remoteUuid) // 标记已建立心跳
                                        if (BuildConfig.DEBUG) android.util.Log.i("死神-NotifyRelay", "收到HEARTBEAT，已更新lastSeen: $remoteUuid -> ${deviceLastSeen[remoteUuid]}")
                                        coroutineScope.launch { updateDeviceList() }
                                    // 新增：收到对方心跳时，若本地发送心跳给对方，则自动connectToDevice对方，确保双向链路
                                    if (remoteUuid != uuid && !heartbeatJobs.containsKey(remoteUuid)) {
                                        val info = getDeviceInfo(remoteUuid)
                                        if (info != null && info.ip.isNotEmpty() && info.ip != "0.0.0.0") {
                                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "收到HEARTBEAT后自动反向connectToDevice: $info")
                                            connectToDevice(info)
                                        }
                                    }
                                        } else {
                                            if (BuildConfig.DEBUG) android.util.Log.w("死神-NotifyRelay", "收到HEARTBEAT但未认证: remoteUuid=$remoteUuid, authedKeys=${authenticatedDevices.keys}")
                                        }
                                    } else {
                                        if (BuildConfig.DEBUG) android.util.Log.w("死神-NotifyRelay", "收到HEARTBEAT格式异常: $line")
                                    }
                                    reader.close()
                                    client.close()
                                } else if (line.startsWith("DATA:") || line.startsWith("DATA_JSON:")) {
                                     
                                    val isJson = line.startsWith("DATA_JSON:")
                                    val parts = line.split(":", limit = 5)
                                    if (parts.size >= 5) {
                                        val remoteUuid = parts[1]
                                        val remotePubKey = parts[2]
                                        val sharedSecret = parts[3]
                                        val payload = parts[4]
                                        val auth = authenticatedDevices[remoteUuid]
                                        if (auth != null && auth.sharedSecret == sharedSecret && auth.isAccepted) {
                                            handleNotificationData(payload, sharedSecret, remoteUuid)
                                        } else {
                                            if (auth == null) {
                                                if (BuildConfig.DEBUG) android.util.Log.d("秩序之光 死神-NotifyRelay", "认证失败：无此uuid(${remoteUuid})的认证记录")
                                            } else {
                                                val reason = buildString {
                                                    if (auth.sharedSecret != sharedSecret) append("sharedSecret不匹配; ")
                                                    if (!auth.isAccepted) append("isAccepted=false; ")
                                                }
                                                if (BuildConfig.DEBUG) android.util.Log.d("秩序之光 死神-NotifyRelay", "认证失败，拒绝处理数据，uuid=${remoteUuid}, 本地sharedSecret=${auth.sharedSecret}, 对方sharedSecret=${sharedSecret}, isAccepted=${auth.isAccepted}，原因: $reason")
                                            }
                                        }
                                    }
                                    reader.close()
                                    client.close()
                                } else {
                                    // 新增：手动发现UDP包（加密）
                                    try {
                                        val authed = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
                                        for ((uuid, auth) in authed) {
                                            if (uuid == this@DeviceConnectionManager.uuid) continue
                                            try {
                                                val decrypted = try { decryptData(line, auth.sharedSecret) } catch (_: Exception) { null }
                                                if (decrypted != null && decrypted.startsWith("NOTIFYRELAY_DISCOVER_MANUAL:")) {
                                                    val parts = decrypted.split(":")
                                                    if (parts.size >= 5) {
                                                        val remoteUuid = parts[1]
                                                        val displayName = parts[2]
                                                        val port = parts[3].toIntOrNull() ?: 23333
                                                        val sharedSecret = parts[4]
                                                        // 仅已认证设备才处理
                                                        if (auth.sharedSecret == sharedSecret) {
                                                            // 更新设备缓存
                                                            val ip = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                                                            val device = DeviceInfo(remoteUuid, displayName, ip, port)
                                                            deviceLastSeen[remoteUuid] = System.currentTimeMillis()
                                                            synchronized(deviceInfoCache) { deviceInfoCache[remoteUuid] = device }
                                                            // 只在手动发现包里才允许更新端口
                                                            synchronized(authenticatedDevices) {
                                                                val auth = authenticatedDevices[remoteUuid]
                                                                if (auth != null) {
                                                                    authenticatedDevices[remoteUuid] = auth.copy(lastIp = ip, lastPort = port)
                                                                    saveAuthedDevices()
                                                                }
                                                            }
                                                            DeviceConnectionManagerUtil.updateGlobalDeviceName(remoteUuid, displayName)
                                                            coroutineScope.launch { updateDeviceList() }
                                                            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "收到手动发现UDP: $decrypted, ip=$ip, uuid=$remoteUuid")
                                                        }
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    } catch (_: Exception) {}
                                    if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "未知请求: $line")
                                    reader.close()
                                    client.close()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 新增：检测当前是否为热点网络
    private fun isHotspotNetwork(): Boolean {
        try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                   !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            return false
        }
    }

    // 新增：检测当前是否为WLAN直连网络（Wi-Fi Direct）
    private fun isWifiDirectNetwork(): Boolean {
        try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                   !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)
        } catch (_: Exception) {
            return false
        }
    }

    // 新增：获取WLAN直连下的设备IP范围（通常是192.168.49.x或类似）
    private fun getWifiDirectIpRange(): List<String> {
        val possibleRanges = listOf("192.168.49.", "192.168.43.", "192.168.42.", "10.0.0.")
        val ips = mutableListOf<String>()
        for (range in possibleRanges) {
            for (i in 1..254) {
                ips.add("$range$i")
            }
        }
        return ips
    }

    // 新增：WLAN直连发现函数
    private fun startWifiDirectDiscovery(localDisplayName: String) {
        coroutineScope.launch {
            val ips = getWifiDirectIpRange()
            val authed = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "WLAN直连发现：扫描${ips.size}个IP，认证设备数量：${authed.size}")

            // 首先尝试连接已认证设备的lastIp
            for ((uuid, auth) in authed) {
                if (uuid == this@DeviceConnectionManager.uuid) continue
                if (heartbeatedDevices.contains(uuid)) continue
                val ip = auth.lastIp
                val port = auth.lastPort ?: 23333
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "WLAN直连：尝试连接已认证设备 $uuid at $ip:$port")
                    connectToDevice(DeviceInfo(uuid, auth.displayName ?: "WLAN直连设备", ip, port))
                    delay(500) // 短暂延迟避免并发过多
                }
            }

            // 然后扫描IP范围，发送UDP单播发现包
            for (ip in ips) {
                try {
                    val socket = java.net.DatagramSocket()
                    val displayName = localDisplayName
                    val buf = ("NOTIFYRELAY_DISCOVER:${uuid}:${displayName}:${listenPort}").toByteArray()
                    val packet = java.net.DatagramPacket(buf, buf.size, java.net.InetAddress.getByName(ip), 23334)
                    socket.send(packet)
                    socket.close()
                    delay(10) // 每10ms发送一个，避免过快
                } catch (_: Exception) {
                    // 忽略发送失败
                }
            }

            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "WLAN直连发现完成")
        }
    }

    // 新增：使用系统API获取准确的热点网关IP
    private fun getHotspotGatewayIp(): String {
        try {
            // 使用Android系统API获取DHCP信息中的网关IP
            val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            if (dhcpInfo.gateway != 0) {
                // 将整数IP转换为字符串格式
                val gatewayIp = android.text.format.Formatter.formatIpAddress(dhcpInfo.gateway)
                if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "通过WifiManager.dhcpInfo.gateway获取网关IP: $gatewayIp")
                return gatewayIp
            }
        } catch (_: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "获取DHCP网关IP失败，使用备选方案")
        }
        
        // 备选方案：枚举常见热点IP（仅在系统API失败时使用）
        if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "使用备选方案枚举常见热点IP")
        val possibleIps = listOf("192.168.43.1", "192.168.42.1", "192.168.49.1", "10.0.0.1")
        for (ip in possibleIps) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ip, listenPort), 1000) // 1秒超时
                socket.close()
                if (BuildConfig.DEBUG) android.util.Log.d("死神-NotifyRelay", "检测到热点网关IP: $ip")
                return ip
            } catch (_: Exception) {
                // 继续尝试下一个
            }
        }
        return "192.168.43.1" // 默认值
    }

    // 设置加密类型（可通过UI调用）
    fun setEncryptionType(type: com.xzyht.notifyrelay.core.util.EncryptionManager.EncryptionType) {
        com.xzyht.notifyrelay.core.util.EncryptionManager.setEncryptionType(type)
        if (BuildConfig.DEBUG) Log.d("死神-NotifyRelay", "加密类型已设置为: $type")
    }

    // 获取当前加密类型
    fun getCurrentEncryptionType(): com.xzyht.notifyrelay.core.util.EncryptionManager.EncryptionType {
        return com.xzyht.notifyrelay.core.util.EncryptionManager.getCurrentEncryptionType()
    }
}