package com.xzyht.notifyrelay.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerUtil
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper

/**
 * 负责「网络环境」与「设备发现」的整体协调：
 *
 * - 监听系统网络变化（注册 NetworkCallback），当切换到局域网 / WLAN 直连时：
 *   - 更新本机 IP（写入 deviceInfoCache）、
 *   - 重启发现流程、
 *   - 对所有已认证设备做一轮主动 connectToDevice 尝试；
 *
 * - 根据当前网络类型选择发现策略：
 *   - 普通局域网：TCP 扫描发现；
 *   - WLAN 直连（Wi‑Fi Direct / 热点网关）：扫描 IP 范围 + 对认证设备最后记忆的 IP 做单独 connectToDevice；
 *
 * - 通过 DeviceConnectionManager 暴露的 internal 访问器读写：
 *   - 设备缓存 deviceInfoCache、
 *   - 已认证设备表 authenticatedDevices、
 *   - 以及 updateDeviceList 等入口。
 *   - 心跳状态（heartbeatedDevices/deviceLastSeen）已迁移至 Rust DeviceRegistry。
 */
class ConnectionDiscoveryManager(
    private val deviceManager: DeviceConnectionManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "死神-Discovery"
    }

    private val context get() = deviceManager.contextInternal
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectRefreshJob: Job? = null

    /**
     * 更新设备信息缓存并触发设备列表更新
     */
    private fun updateDeviceInfoCache(device: DeviceInfo) {
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

        DeviceConnectionManagerUtil.updateGlobalDeviceName(device.uuid, device.displayName)
        scope.launch { deviceManager.updateDeviceListInternal() }
    }

    /**
     * 连接到已认证设备
     */
    private fun connectToAuthedDevice(device: DeviceInfo) {
        val isAuthed =
            synchronized(deviceManager.authenticatedDevices) {
                deviceManager.authenticatedDevices.containsKey(device.uuid)
            }
        val isOnline = deviceManager.devices.value[device.uuid]?.second == true
        if (isAuthed && !isOnline) {
            deviceManager.connectToDevice(device)
        }
    }

    enum class NetworkType {
        REGULAR,
        HOTSPOT,
        WIFI_DIRECT,
    }

    internal fun getLocalIpAddressInternal(): String = NativeCore.getLocalIp() ?: "0.0.0.0"

    private fun getCurrentNetworkType(): NetworkType {
        try {
            val cm = connectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)

            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
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

    internal fun isWifiDirectNetworkInternal(): Boolean = getCurrentNetworkType() == NetworkType.WIFI_DIRECT

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
     * 解码并清洗从网络接收到的名称：
     * - Base64 解码后再走 sanitizeDisplayNameInternal，保证入库/展示口径一致。
     */
    internal fun decodeDisplayNameFromTransportInternal(encoded: String): String =
        try {
            deviceManager.decodeDisplayNameFromTransportInternal(encoded)
        } catch (_: Exception) {
            encoded
        }

    fun registerNetworkCallback() {
        val cm = connectivityManager
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                private var wasLanNetwork = false

                override fun onAvailable(network: Network) {
                    val capabilities = cm.getNetworkCapabilities(network)
                    val isLanNetwork =
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true

                    // Logger.d("死神-NotifyRelay", "网络可用，类型: ${if (isLanNetwork) "局域网/WLAN直连" else "非局域网"}")

                    if (isLanNetwork && !wasLanNetwork) {
                        // Logger.d("死神-NotifyRelay", "检测到从非局域网切换到局域网/WLAN直连，主动重新连接设备")
                        updateLocalIpAndRestartDiscovery()
                    } else {
                        updateLocalIpAndRestartDiscovery()
                    }

                    wasLanNetwork = isLanNetwork
                }

                override fun onLost(network: Network) {
                    // Logger.d("死神-NotifyRelay", "网络丢失")
                    wasLanNetwork = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val isLanNetwork =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)

                    if (isLanNetwork && !wasLanNetwork) {
                        // Logger.d("死神-NotifyRelay", "网络能力变化，检测到切换到局域网/WLAN直连，主动重新连接设备")
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
        // Logger.d("死神-NotifyRelay", "本地IP更新为: $newIp")
        // 通知 Rust core 网络变化
        val ctx = deviceManager.rustContextInternal
        if (ctx != null) {
            NativeCore.onNetworkChanged(ctx, newIp)
        }
        stopDiscovery()
        startDiscovery()
        // 网络类型变化（含 WLAN 直连切换）后同步心跳模式
        syncHeartbeatMode()

        val hasValidNetwork = newIp != "0.0.0.0" && newIp.isNotEmpty()
        if (hasValidNetwork) {
            // 网络恢复后的自动重连交由 Rust 重连状态机处理：重新登记所有认证设备，重置重试周期
            reconnectRefreshJob?.cancel()
            reconnectRefreshJob =
                scope.launch {
                    delay(1000)
                    deviceManager.refreshAllReconnectTargetsInternal()
                }
        }
    }

    /*
     * 同步连接模式说明：TCP 扫描发现模式。
     * TCP 扫描发现由 Rust 核心库内部处理，平台端只需调用 periodicBroadcast 启动/停止。
     * 已认证设备的重连由 Rust 重连状态机独立处理，不受此开关影响。
     */

    /**
     * 获取带符号电量（正=充电，负=放电），与 BatteryReceiver 约定一致。
     * Rust 侧：battery > 0 视为充电中，battery < 0 视为放电中。
     */
    private fun getSignedBatteryLevel(): Int {
        val ctx = deviceManager.contextInternal
        val level =
            notifyrelay.core.util.BatteryUtils
                .getBatteryLevel(ctx)
        return if (notifyrelay.core.util.BatteryUtils
                .isCharging(ctx)
        ) {
            level
        } else {
            -level
        }
    }

    internal fun syncHeartbeatMode() {
        val ctx = deviceManager.rustContextInternal
        if (ctx == null) {
            Logger.w(TAG, "[syncHeartbeatMode] rustContext 为 null，跳过")
            return
        }
        try {
            Logger.i(TAG, "[syncHeartbeatMode] 开始同步心跳模式")
            val isLocked = PermissionHelper.isDeviceLocked(context)
            val isWifiDirect = isWifiDirectNetworkInternal()
            Logger.i(TAG, "[syncHeartbeatMode] isLocked=$isLocked, isWifiDirect=$isWifiDirect, discoveryEnabled=${deviceManager.discoveryEnabled}")
            // 根据开关控制 TCP 扫描发现的启停（不影响已认证设备的重连）
            if (deviceManager.discoveryEnabled) {
                val displayName = deviceManager.localDisplayNameInternal()
                val battery = getSignedBatteryLevel()
                Logger.i(TAG, "[syncHeartbeatMode] 调用 periodicBroadcast 启动扫描发现: uuid=${deviceManager.uuid}, name=$displayName, battery=$battery")
                val result = NativeCore.periodicBroadcast(ctx, 1, deviceManager.uuid, displayName, battery, "android")
                Logger.i(TAG, "[syncHeartbeatMode] periodicBroadcast 返回: $result")
            } else {
                // 用户关闭发现时停止扫描（已认证设备的重连由 Rust 重连状态机处理）
                Logger.i(TAG, "[syncHeartbeatMode] 发现已关闭，停止扫描")
                NativeCore.periodicBroadcast(ctx, 0)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "[syncHeartbeatMode] 异常", e)
        }
    }

    fun stopDiscovery() {
        val ctx = deviceManager.rustContextInternal
        if (ctx != null) {
            NativeCore.periodicBroadcast(ctx, 0)
        }
    }

    fun startDiscovery() {
        syncHeartbeatMode()

        if (deviceManager.isWifiDirectNetworkInternal()) {
            // WLAN 直连模式下的持续重连/发现交由 Rust known_device_scanner 处理
            return
        }

        // 连接到已认证设备
        scope.launch {
            val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
            for ((uuid, _) in authed) {
                if (uuid == deviceManager.uuid) continue
                val info = deviceManager.getDeviceInfoInternal(uuid)
                val ip = info?.ip
                val port = info?.port ?: deviceManager.listenPort
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    connectToAuthedDevice(DeviceInfo(uuid, info.displayName, ip, port))
                }
            }
        }
    }
}
