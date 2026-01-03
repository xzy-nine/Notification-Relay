package com.xzyht.notifyrelay.feature.device.service

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.xzyht.notifyrelay.common.core.sync.ConnectionDiscoveryManager
import com.xzyht.notifyrelay.common.core.sync.ServerLineRouter
import com.xzyht.notifyrelay.common.core.util.EncryptionManager
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.UUID

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
    // 单例实例
    companion object {
        @Volatile
        private var instance: DeviceConnectionManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: android.content.Context): DeviceConnectionManager {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = DeviceConnectionManager(context.applicationContext)
                    }
                }
            }
            return instance!!
        }
    }
    // 获取本地设备显示名称，优先级按要求：1. 蓝牙 -> 2. Settings.Secure(bluetooth_name) -> 3. Settings.Global(device_name) -> 4. Build.MODEL/DEVICE -> 5. 兜底
    // 不再使用应用持久化或 SharedPreferences 中的 device_name
    private fun getLocalDisplayName(): String {
        try {
            // 1. 蓝牙名称（Android 12+ 需要 BLUETOOTH_CONNECT 权限）
            try {
                val canReadBt = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                } else true
                if (canReadBt) {
                    val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                    val btName = bluetoothManager.adapter?.name
                    if (!btName.isNullOrEmpty()) return sanitizeDisplayName(btName)
                }
            } catch (_: Exception) {}

            // 2. Settings.Secure 中的 bluetooth_name（部分设备/ROM会放在这里）
            try {
                val s = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                if (!s.isNullOrEmpty()) return sanitizeDisplayName(s)
            } catch (_: Exception) {}

            // 3. Settings.Global 中的 device_name
            try {
                val g = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
                if (!g.isNullOrEmpty()) return sanitizeDisplayName(g)
            } catch (_: Exception) {}

            // 4. 设备型号/设备名作为兜底
            try {
                val model = android.os.Build.MODEL
                if (!model.isNullOrEmpty()) return sanitizeDisplayName(model)
                val device = android.os.Build.DEVICE
                if (!device.isNullOrEmpty()) return sanitizeDisplayName(device)
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        return "未知设备"
    }

    // 将显示名称清洗为不可见字符替换、并裁剪（口径较宽）
    private fun sanitizeDisplayName(raw: String): String {
        try {
            var s = raw.replace(Regex("[\\r\\n]"), " ")
            s = s.trim()
            if (s.isEmpty()) return s
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.size <= 64) return s
            var cut = 64
            while (cut > 0 && (bytes[cut - 1].toInt() and 0xC0) == 0x80) cut--
            return String(bytes.copyOfRange(0, cut), Charsets.UTF_8)
        } catch (_: Exception) {
            return raw
        }
    }
// 设备信息缓存，解决未认证设备无法显示详细信息问题
    private val deviceInfoCache = mutableMapOf<String, DeviceInfo>()
    private val PREFS_AUTHED_DEVICES = "authed_devices_json"

    // 加载已认证设备
    private fun loadAuthedDevices() {
        // 从Room数据库加载设备信息
        val devices = kotlinx.coroutines.runBlocking {
            com.xzyht.notifyrelay.common.data.database.repository.DatabaseRepository.getInstance(context).getDevices()
        }
        
        for (device in devices) {
            authenticatedDevices[device.uuid] = AuthInfo(
                publicKey = device.publicKey,
                sharedSecret = device.sharedSecret,
                isAccepted = device.isAccepted,
                displayName = device.displayName,
                lastIp = device.lastIp,
                lastPort = device.lastPort
            )
            // 恢复设备名到缓存
            DeviceConnectionManagerUtil.updateGlobalDeviceName(device.uuid, device.displayName)
            // 恢复ip到deviceInfoCache
            synchronized(deviceInfoCache) {
                deviceInfoCache[device.uuid] = DeviceInfo(
                    uuid = device.uuid,
                    displayName = device.displayName,
                    ip = device.lastIp,
                    port = device.lastPort
                )
            }
        }
        
        // 认证设备加载完成后，更新设备列表状态，确保 listeners（例如通知服务）能及时感知认证状态
        try {
            coroutineScope.launch {
                updateDeviceList()
                // 更新Flow值
                _authenticatedDevicesFlow.value = authenticatedDevices.toMap()
                _rejectedDevicesFlow.value = rejectedDevices.toSet()
            }
        } catch (_: Exception) {}
    }

    // 保存已认证设备
    private fun saveAuthedDevices() {
        try {
            // 保存到Room数据库
            val deviceEntities = mutableListOf<com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity>()
            for ((uuid, auth) in authenticatedDevices) {
                if (auth.isAccepted) {
                    val name = auth.displayName ?: deviceInfoCache[uuid]?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
                    val info = deviceInfoCache[uuid]
                    val deviceEntity = com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity(
                        uuid = uuid,
                        publicKey = auth.publicKey,
                        sharedSecret = auth.sharedSecret,
                        isAccepted = auth.isAccepted,
                        displayName = name,
                        lastIp = info?.ip ?: auth.lastIp ?: "",
                        lastPort = info?.port ?: auth.lastPort ?: 23333
                    )
                    deviceEntities.add(deviceEntity)
                }
            }
            
            // 异步保存到数据库
            coroutineScope.launch {
                val repository = com.xzyht.notifyrelay.common.data.database.repository.DatabaseRepository.getInstance(context)
                
                // 获取当前数据库中的所有设备
                val currentDevices = repository.getDevices()
                currentDevices.map { it.uuid }.toSet()
                
                // 要保存的设备UUID列表
                val deviceUuidsToSave = deviceEntities.map { it.uuid }.toSet()
                
                // 删除数据库中存在但内存中不存在的设备
                currentDevices.forEach {
                    if (!deviceUuidsToSave.contains(it.uuid)) {
                        repository.deleteDevice(it)
                    }
                }
                
                // 保存或更新设备
                deviceEntities.forEach {
                    repository.saveDevice(it)
                }
                
                // 更新Flow值
                _authenticatedDevicesFlow.value = authenticatedDevices.toMap()
                _rejectedDevicesFlow.value = rejectedDevices.toSet()
            }
        } catch (_: Exception) {}
    }
    /**
     * 握手请求处理接口
     */
    interface HandshakeRequestHandler {
        fun onHandshakeRequest(deviceInfo: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit)
    }

    /**
     * 握手请求处理器
     */
    var handshakeRequestHandler: HandshakeRequestHandler? = null

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
    internal val notificationDataReceivedCallbacksInternal: Collection<(String) -> Unit>
        get() = notificationDataReceivedCallbacks
    private val _devices = MutableStateFlow<Map<String, Pair<DeviceInfo, Boolean>>>(emptyMap())
    /**
     * 设备状态流：key为uuid，value为(DeviceInfo, isOnline)
     * 只要认证过的设备会一直保留，未认证设备3秒未发现则消失
     */
    val devices: StateFlow<Map<String, Pair<DeviceInfo, Boolean>>> = _devices
    
    private val _authenticatedDevicesFlow = MutableStateFlow<Map<String, AuthInfo>>(emptyMap())
    /**
     * 已认证设备状态流
     */
    val authenticatedDevicesFlow: StateFlow<Map<String, AuthInfo>> = _authenticatedDevicesFlow
    
    private val _rejectedDevicesFlow = MutableStateFlow<Set<String>>(emptySet())
    /**
     * 已拒绝设备状态流
     */
    val rejectedDevicesFlow: StateFlow<Set<String>> = _rejectedDevicesFlow
    internal val uuid: String

    // 认证设备表，key为uuid
    internal val authenticatedDevices = mutableMapOf<String, AuthInfo>()
    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()
    // 本地密钥对（简单字符串模拟，实际应用可用RSA/ECDH等）
    internal val localPublicKey: String
    private val localPrivateKey: String
    internal val listenPort: Int = 23333
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val keepAlive = com.xzyht.notifyrelay.common.core.sync.ConnectionKeepAlive(this, coroutineScope)
    private val discoveryManager = ConnectionDiscoveryManager(this, coroutineScope)

    // === 以下为提供给 ServerLineRouter 等内部组件使用的访问器（保持字段本身 private） ===
    internal val deviceInfoCacheInternal: MutableMap<String, DeviceInfo>
        get() = deviceInfoCache

    internal val deviceLastSeenInternal: MutableMap<String, Long>
        get() = deviceLastSeen

    internal val rejectedDevicesInternal: MutableSet<String>
        get() = rejectedDevices

    internal val coroutineScopeInternal: CoroutineScope
        get() = coroutineScope

    private val heartbeatedDevices = mutableSetOf<String>()

    internal val heartbeatedDevicesInternal: MutableSet<String>
        get() = heartbeatedDevices

    internal val heartbeatJobsInternal: MutableMap<String, kotlinx.coroutines.Job>
        get() = heartbeatJobs

    internal val contextInternal: android.content.Context
        get() = context

    internal fun localDisplayNameInternal(): String = getLocalDisplayName()

    // 编码用于 UDP/TCP 简单传输（避免冒号分隔冲突）。使用 Base64 无换行。
    private fun encodeDisplayNameForTransport(name: String): String {
        try {
            val clean = sanitizeDisplayName(name)
            if (clean.isEmpty()) {
                // 确保设备名称不为空，使用默认值"错误空"以便排除故障点
                return android.util.Base64.encodeToString("错误空".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            }
            return android.util.Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            // 编码失败时返回默认设备名称的Base64编码，确保UDP广播消息格式正确
            return android.util.Base64.encodeToString("错误空2".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
    }

    internal fun encodeDisplayNameForTransportInternal(name: String): String = encodeDisplayNameForTransport(name)

    // 解码并清洗从网络接收到的名称
    private fun decodeDisplayNameFromTransport(encoded: String): String {
        try {
            if (encoded.isEmpty()) {
                // 处理空字符串情况，返回默认设备名称"错误空"以便排除故障点
                return "错误空"
            }
            val decoded = try { android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
            if (decoded != null) {
                val s = String(decoded, Charsets.UTF_8)
                val sanitized = sanitizeDisplayName(s)
                // 确保解码后的名称不为空，使用默认值"错误空"兜底以便排除故障点
                return if (sanitized.isNotEmpty()) sanitized else "错误空"
            }
        } catch (_: Exception) {}
        // 如果解码失败，尝试直接使用原字符串，确保不为空
        val sanitized = sanitizeDisplayName(encoded)
        return if (sanitized.isNotEmpty()) sanitized else "错误空"
    }

    internal fun decodeDisplayNameFromTransportInternal(encoded: String): String = decodeDisplayNameFromTransport(encoded)

    internal fun startServerInternal() = startServer()

    internal fun updateDeviceListInternal() = updateDeviceList()

    internal fun saveAuthedDevicesInternal() = saveAuthedDevices()

    internal fun decryptDataInternal(input: String, key: String): String = decryptData(input, key)

    internal fun getDeviceInfoInternal(uuid: String): DeviceInfo? = getDeviceInfo(uuid)
    private var serverSocket: ServerSocket? = null
    private val deviceLastSeen = mutableMapOf<String, Long>()
    // 心跳定时任务
    private val heartbeatJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    // UI全局开关：是否启用UDP发现，使用内存缓存避免频繁数据库访问
    // 使用AppConfig管理UDP发现配置
    var udpDiscoveryEnabled: Boolean
        get() {
            return com.xzyht.notifyrelay.common.core.util.AppConfig.getUdpDiscoveryEnabled(context)
        }
        set(value) {
            com.xzyht.notifyrelay.common.core.util.AppConfig.setUdpDiscoveryEnabled(context, value)
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
        if (!com.xzyht.notifyrelay.common.core.util.AppConfig.getUdpDiscoveryEnabled(context)) {
            com.xzyht.notifyrelay.common.core.util.AppConfig.setUdpDiscoveryEnabled(context, true)
        }
        loadAuthedDevices()
        // 新增：初始补全本机 deviceInfoCache，便于反向 connectToDevice
        val displayName = getLocalDisplayName()
        val localIp = discoveryManager.getLocalIpAddressInternal()
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = DeviceInfo(uuid, displayName, localIp, listenPort)
        }
        startOfflineDeviceCleaner()
        discoveryManager.registerNetworkCallback()
        startWifiDirectReconnectionChecker()
    }

    // 统一设备状态管理：3秒未发现未认证设备直接移除，已认证设备置灰
    private fun startOfflineDeviceCleaner() {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                try {
                    updateDeviceList()
                } catch (e: Exception) {
                    Logger.e("死神-NotifyRelay", "startOfflineDeviceCleaner定时器异常: ${e.message}")
                }
            }
        }
    }

    private fun updateDeviceList() {
        val now = System.currentTimeMillis()
        //Logger.d("死神-NotifyRelay", "[updateDeviceList] invoked at $now")
    val authSnapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
    val authed = authSnapshot.keys.toSet()
        val allUuids = (deviceLastSeen.keys + authed).toSet()
        val newMap = mutableMapOf<String, Pair<DeviceInfo, Boolean>>()
        val unauthedTimeout = 5000L // 未认证设备保留两次UDP广播周期（2*2000ms）
        val authedHeartbeatTimeout = 12_000L // 已认证设备心跳超时阈值
        val oldMap = _devices.value
        // 计算旧的已认证且在线数量快照
        val oldAuthOnlineCount = try {
            oldMap.count { (uuid, pair) -> pair.second && (authSnapshot[uuid]?.isAccepted == true) }
        } catch (_: Exception) { 0 }
        for (uuid in allUuids) {
            val lastSeen = deviceLastSeen[uuid]
            val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
            // 检查时钟回拨
            var safeLastSeen = lastSeen
            if (lastSeen != null && now < lastSeen) {
                Logger.w("死神-NotifyRelay", "检测到时钟回拨: now=$now, lastSeen=$lastSeen, uuid=$uuid，强制重置lastSeen=now")
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
                    Logger.i("天使-死神-NotifyRelay", "[updateDeviceList] 已认证设备状态变化: uuid=$uuid, isOnline=$isOnline, lastSeen=$safeLastSeen, diff=$diff")
                }
                newMap[uuid] = info to isOnline
            } else {
                val diff = if (safeLastSeen != null) now - safeLastSeen else -1L
                val isOnline = safeLastSeen != null && diff <= unauthedTimeout
                val info = getDeviceInfo(uuid)
                val oldOnline = oldMap[uuid]?.second
                if (oldOnline != null && oldOnline != isOnline) {
                    Logger.i("死神-NotifyRelay", "[updateDeviceList] 未认证设备状态变化: uuid=$uuid, isOnline=$isOnline, lastSeen=$safeLastSeen, diff=$diff")
                }
                if (isOnline) {
                    if (info != null) newMap[uuid] = info to true
                } else {
                    deviceLastSeen.remove(uuid)
                }
            }
        }
        // 仅在设备列表或在线状态发生实际变化时触发回调，避免频繁刷新
        // 计算新的已认证且在线数量快照
        val newAuthOnlineCount = try {
            newMap.count { (uuid, pair) -> pair.second && (authSnapshot[uuid]?.isAccepted == true) }
        } catch (_: Exception) { 0 }

        // 直接更新Flow值，UI层通过Flow订阅获取变化
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
            val displayName = getLocalDisplayName()
            val localIp = discoveryManager.getLocalIpAddressInternal()
            return DeviceInfo(uuid, displayName, localIp, listenPort)
        }
        return null
    }

    /**
     * 获取已认证设备列表
     */
    fun getAuthenticatedDevices(): Map<String, AuthInfo> {
        synchronized(authenticatedDevices) {
            return authenticatedDevices.toMap()
        }
    }

    /**
     * 获取已拒绝设备列表
     */
    fun getRejectedDevices(): Set<String> {
        synchronized(rejectedDevices) {
            return rejectedDevices.toSet()
        }
    }

    /**
     * 保存已认证设备（公开API）
     */
    fun saveAuthedDevicesPublic() {
        saveAuthedDevices()
    }

    /**
     * 更新设备列表（公开API）
     */
    fun updateDeviceListPublic() {
        updateDeviceList()
    }

    /**
     * 公开解析设备信息：优先使用缓存/认证信息，缺失IP时使用提供的回退IP。
     */
    fun resolveDeviceInfo(uuid: String, fallbackIp: String, fallbackPort: Int = 23333): DeviceInfo {
        val cached = getDeviceInfo(uuid)
        if (cached != null && cached.ip.isNotEmpty() && cached.ip != "0.0.0.0") return cached
        val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
        val name = auth?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
        val port = cached?.port ?: auth?.lastPort ?: fallbackPort
        return DeviceInfo(uuid, name, fallbackIp, port)
    }

    internal fun isWifiDirectNetworkInternal(): Boolean {
        return discoveryManager.isWifiDirectNetworkInternal()
    }

    // 连接设备
    fun connectToDevice(device: DeviceInfo, callback: ((Boolean, String?) -> Unit)? = null) {
        coroutineScope.launch {
            try {
                if (rejectedDevices.contains(device.uuid)) {
                    //Logger.d("死神-NotifyRelay", "connectToDevice: 已被对方拒绝 uuid=${device.uuid}")
                    callback?.invoke(false, "已被对方拒绝")
                    return@launch
                }

                // 新增：WLAN直连模式下增加重试次数
                val maxRetries = if (isWifiDirectNetworkInternal()) 3 else 1
                val result = keepAlive.performDeviceConnectionWithRetry(device, maxRetries)
                callback?.invoke(result.first, result.second)
            } catch (e: Exception) {
                Logger.e("死神-NotifyRelay", "connectToDevice异常: ${e.message}")
                e.printStackTrace()
                callback?.invoke(false, e.message)
            }
        }
    }

    // 设备连接重试逻辑已迁移到 ConnectionKeepAlive.performDeviceConnectionWithRetry
    // 使用加密管理器进行数据加密
    internal fun encryptData(input: String, key: String): String {
        return EncryptionManager.encrypt(input, key)
    }

    // 使用加密管理器进行数据解密（对 ProtocolRouter 开放）
    internal fun decryptData(input: String, key: String): String {
        return EncryptionManager.decrypt(input, key)
    }

    // 发送通知数据（加密）
    fun sendNotificationData(device: DeviceInfo, data: String) {
        coroutineScope.launch {
            try {
                val auth = authenticatedDevices[device.uuid]
                if (auth == null || !auth.isAccepted) {
                    //Logger.d("死神-NotifyRelay", "未认证设备，禁止发送")
                    return@launch
                }
                com.xzyht.notifyrelay.common.core.sync.ProtocolSender.sendEncrypted(this@DeviceConnectionManager, device, "DATA_NOTIFICATION", data, 10000L)
            } catch (e: Exception) {
                Logger.e("死神-NotifyRelay", "发送通知数据失败", e)
            }
        }
    }

    /**
     * 公开API：请求远端设备的“用户应用列表”。
     */
    fun requestRemoteAppList(device: DeviceInfo, scope: String = "user") {
        try {
            com.xzyht.notifyrelay.common.core.sync.AppListSyncManager.requestAppListFromDevice(context, this, device, scope)
        } catch (_: Exception) {}
    }

    /**
     * 公开API：请求远端设备转发音频。
     * @return 是否成功发送请求
     */
    fun requestAudioForwarding(device: DeviceInfo): Boolean {
        try {
            val request = "{\"type\":\"AUDIO_REQUEST\"}"
            com.xzyht.notifyrelay.common.core.sync.ProtocolSender.sendEncrypted(this, device, "DATA_AUDIO_REQUEST", request, 10000L)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    init {
        // 启动图标同步过期请求清理协程
        coroutineScope.launch {
            while (true) {
                delay(60000) // 每分钟清理一次
                com.xzyht.notifyrelay.common.core.sync.IconSyncManager.cleanupExpiredRequests()
            }
        }
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
                                ServerLineRouter.handleClientLine(line, client, reader, this@DeviceConnectionManager, context)
                            } else {
                                try { reader.close() } catch (_: Exception) {}
                                try { client.close() } catch (_: Exception) {}
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

    // 封装设备信息缓存更新操作
    private fun updateDeviceInfoCache(uuid: String, deviceInfo: DeviceInfo) {
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = deviceInfo
        }
    }

    // 封装设备信息缓存IP更新操作（保持其他信息不变）
    private fun updateDeviceInfoCacheIp(uuid: String, newIp: String) {
        synchronized(deviceInfoCache) {
            val old = deviceInfoCache[uuid]
            val displayName = old?.displayName ?: "未知设备"
            val port = old?.port ?: 23333
            deviceInfoCache[uuid] = DeviceInfo(uuid, displayName, newIp, port)
        }
    }

    // 封装设备信息缓存获取操作
    private fun getDeviceInfoFromCache(uuid: String): DeviceInfo? {
        synchronized(deviceInfoCache) {
            return deviceInfoCache[uuid]
        }
    }

    /**
     * 获取在线且已认证的设备数量（线程安全）。
     * 该方法读取当前设备列表快照和认证表快照，只把同时在线并且认证通过（isAccepted==true）的设备计入。
     */
    fun getAuthenticatedOnlineCount(): Int {
        try {
            val devsSnapshot = devices.value
            val authSnapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
            return devsSnapshot.count { (uuid, pair) ->
                val isOnline = pair.second
                isOnline && (authSnapshot[uuid]?.isAccepted == true)
            }
        } catch (_: Exception) {
            return 0
        }
    }

    /**
     * 获取在线且已认证的设备列表（线程安全）。
     * 该方法读取当前设备列表快照和认证表快照，返回同时在线并且认证通过（isAccepted==true）的设备列表。
     */
    fun getAuthenticatedOnlineDevices(): List<DeviceInfo> {
        try {
            val authSnapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
            val deviceInfoSnapshot = synchronized(deviceInfoCache) { deviceInfoCache.toMap() }
            val devsSnapshot = devices.value
            
            // 调试日志
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 认证设备: ${authSnapshot.size} 个设备")
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 设备信息缓存: ${deviceInfoSnapshot.size} 个设备")
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 设备列表: ${devsSnapshot.size} 个设备")
            
            // 首先获取所有已认证设备
            val allAuthenticatedDevices = authSnapshot.filter { (_, auth) -> auth.isAccepted }
            
            // 从所有已认证设备中构建设备列表
            val result = allAuthenticatedDevices.mapNotNull { (uuid, auth) ->
                // 从设备信息缓存中获取设备信息
                var deviceInfo = deviceInfoSnapshot[uuid]
                
                // 如果缓存中没有，从设备列表中获取
                if (deviceInfo == null) {
                    deviceInfo = devsSnapshot[uuid]?.first
                }
                
                // 如果还是没有，从认证信息中构建
                if (deviceInfo == null) {
                    val name = auth.displayName ?: "已认证设备"
                    val ip = auth.lastIp ?: ""
                    val port = auth.lastPort ?: listenPort
                    deviceInfo = DeviceInfo(uuid, name, ip, port)
                }
                
                Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 已认证设备: $uuid, name=${deviceInfo.displayName}, ip=${deviceInfo.ip}")
                deviceInfo
            }
            
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 返回结果: ${result.size} 个设备")
            return result
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 出错: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * 公开API：移除已认证设备（线程安全）。
     * - 取消与该设备相关的心跳任务
     * - 直接从数据库中删除设备
     * - 从内存中移除设备信息
     * - 触发 updateDeviceList() 以通知观察者
     * 返回 true 表示存在并已移除，false 表示没有该uuid
     */
    fun removeAuthenticatedDevice(uuid: String): Boolean {
        try {
            var existed = false
            
            // 取消心跳任务
            try {
                heartbeatJobs[uuid]?.cancel()
                heartbeatJobs.remove(uuid)
            } catch (_: Exception) {}
            
            // 从已建立心跳集合移除
            try { heartbeatedDevices.remove(uuid) } catch (_: Exception) {}

            synchronized(authenticatedDevices) {
                if (authenticatedDevices.containsKey(uuid)) {
                    // 直接从数据库中删除设备
                    coroutineScope.launch {
                        val repository = com.xzyht.notifyrelay.common.data.database.repository.DatabaseRepository.getInstance(context)
                        repository.deleteDeviceByUuid(uuid)
                    }
                    
                    // 从内存中移除
                    authenticatedDevices.remove(uuid)
                    existed = true
                }
            }

            // 清理 deviceLastSeen
            try { deviceLastSeen.remove(uuid) } catch (_: Exception) {}
            
            // 清理 deviceInfoCache
            try {
                synchronized(deviceInfoCache) {
                    deviceInfoCache.remove(uuid)
                }
            } catch (_: Exception) {}

            // 触发更新，确保 StateFlow 与回调被通知
            try { 
                coroutineScope.launch { 
                    updateDeviceList() 
                    // 更新Flow值
                    _authenticatedDevicesFlow.value = authenticatedDevices.toMap()
                    _rejectedDevicesFlow.value = rejectedDevices.toSet()
                } 
            } catch (_: Exception) {}
            return existed
        } catch (e: Exception) {
            Logger.w("死神-NotifyRelay", "removeAuthenticatedDevice failed: ${e.message}")
            return false
        }
    }

    // 设置加密类型（可通过UI调用）
    fun setEncryptionType(type: EncryptionManager.EncryptionType) {
        EncryptionManager.setEncryptionType(type)
        //Logger.d("死神-NotifyRelay", "加密类型已设置为: $type")
    }

    // 获取当前加密类型
    fun getCurrentEncryptionType(): EncryptionManager.EncryptionType {
        return EncryptionManager.getCurrentEncryptionType()
    }

    // 发送超级岛ACK（包含接收的hash），用于发送方确认
    private fun sendSuperIslandAck(remoteUuid: String?, sharedSecret: String?, hash: String, featureKeyValue: String?, mappedPkg: String?) {
        try {
            if (remoteUuid.isNullOrEmpty() || sharedSecret.isNullOrEmpty()) return
            val device = getDeviceInfo(remoteUuid)
            val auth = synchronized(authenticatedDevices) { authenticatedDevices[remoteUuid] }
            val ip = device?.ip ?: auth?.lastIp
            val port = device?.port ?: (auth?.lastPort ?: 23333)
            if (ip.isNullOrEmpty() || ip == "0.0.0.0") return

            val ackObj = org.json.JSONObject().apply {
                put("packageName", mappedPkg ?: "superisland:ack")
                put("type", "SI_ACK")
                put("hash", hash)
                if (!featureKeyValue.isNullOrEmpty()) {
                    put("featureKeyName", SuperIslandProtocol.FEATURE_KEY_NAME)
                    put("featureKeyValue", featureKeyValue)
                }
                put("time", System.currentTimeMillis())
            }

            // 通过统一加密发送器发回对端
            val deviceInfo = DeviceInfo(remoteUuid, DeviceConnectionManagerUtil.getDisplayNameByUuid(remoteUuid), ip, port)
            com.xzyht.notifyrelay.common.core.sync.ProtocolSender.sendEncrypted(this, deviceInfo, "DATA_SUPERISLAND", ackObj.toString(), 3000L)
        } catch (_: Exception) {
        }
    }

    // 提供给 NotificationProcessor 的内部包装，简化 ACK 调用
    internal fun sendSuperIslandAckInternal(
        remoteUuid: String,
        sharedSecret: String?,
        recvHash: String,
        featureId: String,
        mappedPkg: String?
    ) {
        try {
            sendSuperIslandAck(remoteUuid, sharedSecret, recvHash, featureId, mappedPkg)
        } catch (_: Exception) {
        }
    }

    // 新增：WLAN直连定期重连检查器
    private fun startWifiDirectReconnectionChecker() {
        keepAlive.startWifiDirectReconnectionChecker()
    }
}