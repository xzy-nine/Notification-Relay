package com.xzyht.notifyrelay.feature.device.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.util.Base64
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import com.xzyht.notifyrelay.feature.audio.AudioRelayPlayer
import com.xzyht.notifyrelay.feature.audio.service.AudioRelayForegroundService
import com.xzyht.notifyrelay.feature.notification.filter.BackendRemoteFilter
import com.xzyht.notifyrelay.feature.media.RemoteMediaSessionManager
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.nativecore.NotifyRelayCore
import com.xzyht.notifyrelay.feature.media.MediaControlUtil
import com.xzyht.notifyrelay.feature.media.service.MediaSessionMonitorService
import com.xzyht.notifyrelay.feature.notification.service.NotifyRelayNotificationListenerService
import com.xzyht.notifyrelay.feature.clipboard.ClipboardProcessor
import com.xzyht.notifyrelay.feature.media.service.MediaProjectionForegroundService
import com.xzyht.notifyrelay.feature.appslist.launch.AppLaunchManager
import com.xzyht.notifyrelay.feature.appslist.sync.AppListSyncManager
import com.xzyht.notifyrelay.sync.ConnectionDiscoveryManager
import com.xzyht.notifyrelay.sync.ConnectionKeepAlive
import com.xzyht.notifyrelay.sync.FtpServerManager
import com.xzyht.notifyrelay.sync.FtpServerManager.StartResult
import com.xzyht.notifyrelay.sync.HeartbeatProcessor
import com.xzyht.notifyrelay.feature.appslist.sync.IconSyncManager
import com.xzyht.notifyrelay.sync.MessageSender
import com.xzyht.notifyrelay.sync.ProtocolSender
import com.xzyht.notifyrelay.sync.notification.NotificationProcessor
import com.xzyht.notifyrelay.sync.notification.StatusProcessor
import com.xzyht.notifyrelay.sync.notification.SuperIslandProcessor
import com.xzyht.notifyrelay.ui.activity.GuideActivity
import github.xzynine.superislandui.common.SuperIslandManager
import io.github.miuzarte.scrcpyforandroid.services.AudioForwardingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import notifyrelay.base.util.DeviceUtils
import notifyrelay.base.util.IntentUtils
import notifyrelay.base.util.Logger
import notifyrelay.core.util.BatteryUtils
import notifyrelay.data.StorageManager
import notifyrelay.data.config.AppConfig
import notifyrelay.data.config.ScrcpyDefaults
import notifyrelay.data.database.repository.DatabaseRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class DeviceInfo(
    val uuid: String,
    val displayName: String, // 前端显示名，优先蓝牙名，其次型号
    val ip: String,
    val port: Int,
    var batteryLevel: Int = -1, // 设备电量，默认-1表示未知
    // 充电状态：使用 '*' 表示未知（与 batteryLevel 使用 -1 表示未知一致），'1' 表示充电，'0' 表示未充电
    var chargingStatus: Char = '*', // 充电状态，默认'*'表示未知
)

object DeviceConnectionManagerUtil {
    // 工具：构造 json 格式的通知数据
    fun buildNotificationJson(
        packageName: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
    ): String {
        val json = JSONObject()
        json.put("packageName", packageName)
        json.put("appName", appName ?: packageName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("time", time)
        return json.toString()
    }

    // 静态缓存，便于 UI 查询 uuid->displayName
    private val globalDeviceNameCache = mutableMapOf<String, String>()

    fun updateGlobalDeviceName(
        uuid: String,
        displayName: String,
    ) {
        synchronized(globalDeviceNameCache) {
            globalDeviceNameCache[uuid] = displayName
            if (globalDeviceNameCache.size > 500) {
                globalDeviceNameCache.remove(globalDeviceNameCache.keys.first())
            }
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
    val displayName: String? = null,
    val lastIp: String? = null,
    val lastPort: Int? = null,
    val deviceType: String? = null,
    val battery: String? = null,
)

// =================== 设备连接管理器主类 ===================
class DeviceConnectionManager(
    private val context: Context,
) {
    companion object {
        private var stopReceiverRegistered = false

        // 静态引用，供 native 回调线程从 Rust 回调中调度到实例方法
        @Volatile
        private var _callbackInstance: DeviceConnectionManager? = null

        internal fun getCallbackInstance(): DeviceConnectionManager? = _callbackInstance

        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): DeviceConnectionManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
    }

    private var audioRelayNotificationReceiver: BroadcastReceiver? = null
    var onRequestMediaProjection: (() -> Unit)? = null

    // 用于比较在线设备缓存是否变化的变量
    private var lastOnlineDevicesCacheJson: String? = null

    // ==================== 握手请求处理接口 ====================

    /**
     * 配对请求处理接口。
     * 当接收到 PAIRING_INIT 时通过此接口通知 UI 层显示配对码输入弹窗。
     */
    interface HandshakeRequestHandler {
        fun onPairingInitRequest(
            deviceInfo: DeviceInfo,
            tmpPublicKey: String,
        )
    }

    /**
     * 配对请求处理器
     */
    var handshakeRequestHandler: HandshakeRequestHandler? = null

    internal fun getLocalDisplayName(): String = DeviceUtils.getLocalDeviceName(context)

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
    private val prefsAuthedDevices = "authed_devices_json"

    // 保持 JNA 回调对象强引用，防止被 GC
    private val rustCallbackRefs = mutableListOf<Any>()

    // 加载已认证设备（密钥已由 Rust 私有库/内存持有，仅恢复 UI 元数据与连接目标）
    private fun loadAuthedDevices() {
        val ctx = rustContext
        // 从 Rust 获取设备列表（含库内持久化的名称/IP），恢复已配对设备
        val json =
            ctx
                ?.let { NativeCore.getDeviceList(it, 0L, 0L) }
                ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val deviceUuid = obj.optString("uuid")
                if (deviceUuid.isEmpty() || deviceUuid == "本机" || deviceUuid == this.uuid) continue
                val paired = obj.optBoolean("paired")
                val name = obj.optString("name")
                val ip = obj.optString("ip")
                val port = obj.optInt("port", 23333)
                if (paired) {
                    synchronized(authenticatedDevices) {
                        authenticatedDevices[deviceUuid] =
                            AuthInfo(
                                publicKey = "",
                                sharedSecret = "",
                                isAccepted = true,
                                displayName = name.takeIf { it.isNotEmpty() },
                                lastIp = ip.takeIf { it.isNotEmpty() },
                                lastPort = port,
                            )
                    }
                    registerReconnectTarget(deviceUuid, ip)
                    if (ip.isNotEmpty()) {
                        registerKnownDevice(deviceUuid, ip)
                    }
                }
                if (name.isNotEmpty()) {
                    DeviceConnectionManagerUtil.updateGlobalDeviceName(deviceUuid, name)
                }
                synchronized(deviceInfoCache) {
                    deviceInfoCache[deviceUuid] =
                        DeviceInfo(
                            uuid = deviceUuid,
                            displayName = name.takeIf { it.isNotEmpty() } ?: "",
                            ip = ip,
                            port = port,
                        )
                }
            }
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "loadAuthedDevices 解析设备列表失败", e)
        }

        // 更新设备列表和 Flow
        try {
            coroutineScope.launch {
                updateDeviceList()
                _authenticatedDevicesFlow.value = authenticatedDevices.toMap()
                _rejectedDevicesFlow.value = rejectedDevices.toSet()
            }
        } catch (_: Exception) {
        }
    }

    // 保存已认证设备（密钥落盘由 Rust 自动；此处仅刷新 UI 状态）
    private fun saveAuthedDevices() {
        try {
            coroutineScope.launch {
                updateDeviceList()
                _authenticatedDevicesFlow.value = authenticatedDevices.toMap()
                _rejectedDevicesFlow.value = rejectedDevices.toSet()
            }
        } catch (_: Exception) {
        }
    }

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
    internal var uuid: String

    // 认证设备表，key为uuid
    internal val authenticatedDevices = mutableMapOf<String, AuthInfo>()

    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()

    // 挂起握手结果（用于 connectToDevice 等待远端响应）
    private val pendingHandshakeResults = mutableMapOf<String, CompletableDeferred<Boolean>>()

    // 本地 ECDH 公钥（Base64 编码的 65 字节未压缩点）
    internal val localPublicKey: String

    // localPrivateKey 不再使用，ECDH 私钥在 Android Keystore 中，不可直接获取
    @Deprecated("ECDH 私钥在 Keystore 中，不再使用")
    private val localPrivateKey: String = ""
    internal val listenPort: Int = 23333
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val keepAlive = ConnectionKeepAlive(this, coroutineScope)
    private val discoveryManager = ConnectionDiscoveryManager(this, coroutineScope)

    // === 以下为提供给内部组件使用的访问器（保持字段本身 private） ===
    internal val deviceInfoCacheInternal: MutableMap<String, DeviceInfo>
        get() = deviceInfoCache

    internal val rejectedDevicesInternal: MutableSet<String>
        get() = rejectedDevices

    /** 检查指定设备是否已认证 */
    fun isAuthenticatedInternal(uuid: String): Boolean = synchronized(authenticatedDevices) { authenticatedDevices.containsKey(uuid) }

    /** 注册等待握手结果 */
    fun registerHandshakeWaiter(uuid: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pendingHandshakeResults) {
            pendingHandshakeResults[uuid]?.cancel()
            pendingHandshakeResults[uuid] = deferred
        }
        return deferred
    }

    /** 解析挂起的握手结果 */
    fun resolveHandshake(
        uuid: String,
        success: Boolean,
    ) {
        synchronized(pendingHandshakeResults) {
            pendingHandshakeResults.remove(uuid)?.complete(success)
        }
    }

    /** 按 Deferred 实例清理等待器，防止迟到请求完成或移除其他等待器 */
    fun cancelHandshakeWaiter(
        uuid: String,
        deferred: CompletableDeferred<Boolean>,
    ) {
        synchronized(pendingHandshakeResults) {
            if (pendingHandshakeResults[uuid] === deferred) {
                pendingHandshakeResults.remove(uuid)
                deferred.cancel()
            }
        }
    }

    internal val incompatibleDevicesInternal: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    internal val coroutineScopeInternal: CoroutineScope
        get() = coroutineScope

    internal val contextInternal: Context
        get() = context

    internal fun localDisplayNameInternal(): String = getLocalDisplayName()

    // 解码并清洗从网络接收到的名称
    private fun decodeDisplayNameFromTransport(encoded: String): String {
        try {
            if (encoded.isEmpty()) {
                // 处理空字符串情况，返回默认设备名称"错误空"以便排除故障点
                return "错误空"
            }
            val decoded =
                try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (_: Exception) {
                    null
                }
            if (decoded != null) {
                val s = String(decoded, Charsets.UTF_8)
                val sanitized = sanitizeDisplayName(s)
                // 确保解码后的名称不为空，使用默认值"错误空"兜底以便排除故障点
                return sanitized.ifEmpty { "错误空" }
            }
        } catch (_: Exception) {
        }
        // 如果解码失败，尝试直接使用原字符串，确保不为空
        val sanitized = sanitizeDisplayName(encoded)
        return sanitized.ifEmpty { "错误空" }
    }

    internal fun decodeDisplayNameFromTransportInternal(encoded: String): String = decodeDisplayNameFromTransport(encoded)

    internal fun updateDeviceListInternal() = updateDeviceList()

    internal fun saveAuthedDevicesInternal() = saveAuthedDevices()

    internal fun getDeviceInfoInternal(uuid: String): DeviceInfo? = getDeviceInfo(uuid)

    // Rust 原生上下文
    private var rustContext: Pointer? = null
    internal val rustContextInternal: Pointer?
        get() = rustContext

    private var batteryReceiver: BroadcastReceiver? = null

    // 上次同步给 Rust 心跳调度器的带符号电量（正=充电，负=放电），用于防抖
    private var lastSentSignedBattery: Int = Int.MIN_VALUE

    // startCore 防重入节流（避免快速重启时重复启动核心服务）
    private val coreStarted = AtomicBoolean(false)

    // UI全局开关：是否启用设备发现，使用内存缓存避免频繁数据库访问
    var discoveryEnabled: Boolean
        get() = AppConfig.getUdpDiscoveryEnabled(context)
        set(value) {
            AppConfig.setUdpDiscoveryEnabled(context, value)
        }

    init {
        // 设置静态引用供 native 回调使用
        _callbackInstance = this

        // 本机 UUID：
        // - 升级用户：SP 旧 uuid 即输入种子（迁移期间保持一致，经 start_core 写入 Rust 库）
        // - 全新安装：空值，由 Rust 生成/持有（库落盘），平台端不生成不存储
        val legacyUuid = StorageManager.getString(context, "device_uuid")
        uuid = legacyUuid
        // 兼容旧用户：首次运行时如无保存则默认true
        if (!AppConfig.getUdpDiscoveryEnabled(context)) {
            AppConfig.setUdpDiscoveryEnabled(context, true)
        }
        // 初始化 Rust 上下文并获取/生成本机 ECDH 密钥对（持久化由 Rust 私有库管理）
        var initPubKey = ""
        // 旧平台加密状态 blob（迁移源：迁移成功后清除）
        val legacyStateEnc = StorageManager.getString(context, "rust_core_state")
        try {
            rustContext = NativeCore.createContext()
            BackendRemoteFilter.rustContext = rustContext
            NativeCore.setContext(rustContext)
            val ctx = rustContext!!

            // 本机 UUID：
            // - 升级用户（SP 有旧 uuid）：作为迁移种子的旧值，经 start_core(uuid) 写入
            //   Rust 库（旧加密 blob 的密钥由该 uuid 派生，必须先保持一致）
            // - 全新安装：Rust 生成 v4 并落库（getLocalUuid）
            if (uuid.isEmpty()) {
                try {
                    val rustUuid = NativeCore.getLocalUuid(ctx)
                    if (!rustUuid.isNullOrEmpty()) {
                        uuid = rustUuid
                    } else if (legacyUuid.isNotEmpty()) {
                        // 兜底：Rust 库不可用时沿用旧平台 UUID（仅本次进程，不持久化）
                        uuid = legacyUuid
                        Logger.w("死神-NotifyRelay", "Rust 私有库未就绪，沿用旧平台 UUID 兜底")
                    }
                } catch (e: Exception) {
                    if (legacyUuid.isNotEmpty()) {
                        uuid = legacyUuid
                        Logger.w("死神-NotifyRelay", "读取 Rust UUID 失败，沿用旧平台 UUID 兜底", e)
                    }
                }
            }

            // 旧平台存储迁移：加密状态 blob（含本机密钥与设备 AES）导入 Rust 内存
            // （空数据不迁移；核心库自动 load 优先，blob 仅补充/引导导入）
            if (legacyStateEnc.isNotEmpty()) {
                val decrypted = NativeCore.decryptLocalState(ctx, legacyStateEnc, uuid)
                if (decrypted != null) {
                    NativeCore.importState(ctx, decrypted)
                }
            }
            if (!NativeCore.hasKeypair(ctx)) {
                NativeCore.generateKeypair(ctx)
            }
            initPubKey = NativeCore.getPublicKey(ctx) ?: ""
            Logger.d("死神-NotifyRelay", "Rust core 上下文已初始化")
            // 注册 Rust 回调
            setupRustCallbacks()
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "Rust core 初始化失败", e)
        }
        localPublicKey = initPubKey
        loadAuthedDevices()
        // 统一启动核心：TCP、心跳调度、离线检测、发送队列、已知设备扫描、重连状态机
        try {
            rustContext?.let { ctx ->
                if (localPublicKey.isEmpty()) {
                    Logger.e("死神-NotifyRelay", "本机 ECDH 公钥为空，跳过 Rust Core 启动")
                    return@let
                }
                if (uuid.isEmpty()) {
                    Logger.e("死神-NotifyRelay", "本机 UUID 为空，跳过 Rust Core 启动")
                    return@let
                }
                if (!coreStarted.compareAndSet(false, true)) {
                    Logger.w("死神-NotifyRelay", "Rust Core 已启动，跳过重复启动")
                    return@let
                }
                val batteryLevel = BatteryUtils.getBatteryLevel(context)
                val battery = if (BatteryUtils.isCharging(context)) batteryLevel else -batteryLevel
                lastSentSignedBattery = battery
                val started = NativeCore.startCore(
                    ctx = ctx,
                    uuid = uuid,
                    name = getLocalDisplayName(),
                    battery = battery,
                    deviceType = "android",
                    tcpPort = listenPort.toShort(),
                    pubkey = localPublicKey,
                )
                if (!started) {
                    coreStarted.set(false)
                    Logger.e("死神-NotifyRelay", "Rust Core 启动失败，重置 coreStarted 允许重试")
                }
            }
        } catch (e: Exception) {
            coreStarted.set(false)
            Logger.e("死神-NotifyRelay", "启动 Rust Core 失败", e)
        }
        // 旧设备表迁移与平台存储清理（需在本机 uuid 已进入 Rust 后才可保证落盘）
        coroutineScope.launch {
            migrateLegacyDevicesAndCleanup(legacyStateEnc)
            loadAuthedDevices()
        }
        // 电池变化监听：电量/充电状态变化时同步到 Rust 心跳调度器，对端实时显示更新
        registerBatteryChangeReceiver()
        // 新增：初始补全本机 deviceInfoCache，便于反向 connectToDevice
        val displayName = getLocalDisplayName()
        val localIp = discoveryManager.getLocalIpAddressInternal()
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = DeviceInfo(uuid, displayName, localIp, listenPort)
        }
        discoveryManager.registerNetworkCallback()
        // 初始同步心跳模式（锁屏/WLAN直连 → TCP 备用，否则广播主用），并监听锁屏变化
        discoveryManager.syncHeartbeatMode()
        registerLockStateReceiver()
        // 自动重连已移交 Rust 重连状态机（loadAuthedDevices 中已登记认证设备）
    }

    // 锁屏状态变化监听：SCREEN_OFF/ON + USER_PRESENT 覆盖锁屏/解锁切换
    private var lockStateReceiver: BroadcastReceiver? = null

    private fun registerLockStateReceiver() {
        if (lockStateReceiver != null) return
        lockStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF,
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT,
                        -> discoveryManager.syncHeartbeatMode()
                    }
                }
            }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            // 问题 1 修复：RECEIVER_NOT_EXPORTED 语义仅 API 33+ 生效，API 31/32 上等效 exported。
            // 三参数重载 API 26+ 已存在，但显式分支可避免在低版本上误传未定义 flag。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(lockStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(lockStateReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    // 电池变化监听：ACTION_BATTERY_CHANGED 为粘性广播，注册后立即回调一次当前状态；
    // 电量或充电状态变化时调用 Rust 调度器参数更新，使心跳报文携带实时电量（正=充电，负=放电）
    private fun registerBatteryChangeReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                    try {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (level < 0 || scale <= 0) return
                        val batteryLevel = (level * 100 / scale).coerceIn(0, 100)
                        val charging =
                            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        val signed = if (charging) batteryLevel else -batteryLevel
                        if (signed == lastSentSignedBattery) return
                        lastSentSignedBattery = signed
                        rustContext?.let {
                            NativeCore.updateHeartbeatSchedulerParams(it, getLocalDisplayName(), signed, "android")
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        try {
            // 问题 1 修复：同上，RECEIVER_NOT_EXPORTED 仅 API 33+ 生效。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                context.registerReceiver(
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
            }
        } catch (_: Exception) {
        }
    }

    // 统一设备状态管理：设备状态完全由 Rust core 维护，
    // 平台端只消费 nrc_get_device_list 快照并渲染（不做在线判定与显示过滤）
    private fun updateDeviceList() {
        refreshDevicesFromRust()
    }

    /**
     * 触发设备列表刷新（供 Rust 回调调用）。
     * 运行在 JNA 回调线程，必须切到协程执行：避免在 Rust 回调内同步调用 core 接口造成重入。
     */
    internal fun triggerDeviceListRefreshFromCore() {
        coroutineScope.launch { updateDeviceList() }
    }

    /**
     * 从 Rust 拉取设备状态快照（uuid/ip/电量/在线/配对等），回填 deviceInfoCache 并生成 _devices。
     * 在线判定与「是否进入列表」均由 Rust core 决定（传 0/0 使用 core 内建阈值），
     * 平台端只负责展示，不再做任何过滤。
     * 1s 定时器（startOfflineDeviceCleaner）与 core 发现/超时回调共同触发。
     */
    private fun refreshDevicesFromRust() {
        // 节流：心跳/连接回调并发触发时跳过重复刷新，避免同一时刻
        // 多个协程并发进入 JNA getDeviceList（会与 Rust 侧线程风暴叠加）
        if (deviceListRefreshBusy) return
        deviceListRefreshBusy = true
        try {
            refreshDevicesFromRustInternal()
        } finally {
            deviceListRefreshBusy = false
        }
    }

    @Volatile
    private var deviceListRefreshBusy = false

    private fun refreshDevicesFromRustInternal() {
        val ctx = rustContext ?: return
        val json =
            try {
                // 传 0/0：使用 Rust core 内建在线阈值（已认证 12s / 未认证 20s），判定完全归 core
                NativeCore.getDeviceList(ctx, 0L, 0L)
            } catch (_: Exception) {
                null
            } ?: return
        try {
            val arr = org.json.JSONArray(json)
            val authSnapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
            val newMap = mutableMapOf<String, Pair<DeviceInfo, Boolean>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uuid = obj.optString("uuid")
                if (uuid.isEmpty() || uuid == this.uuid) continue
                val online = obj.optBoolean("online")
                val ip = obj.optString("ip")
                val port = obj.optInt("port", listenPort).takeIf { it > 0 } ?: listenPort
                val battery = obj.optInt("battery", -101)
                val oldInfo = synchronized(deviceInfoCache) { deviceInfoCache[uuid] }
                // 未知电量（超出 [-100,100]）沿用缓存旧值，不覆盖已显示的电量/充电状态
                val batteryUnknown = kotlin.math.abs(battery) > 100
                val chargingStatus =
                    if (batteryUnknown) {
                        (oldInfo?.chargingStatus ?: '0')
                    } else if (battery >= 0) {
                        '1'
                    } else {
                        '0'
                    }
                val deviceType = obj.optString("deviceType", "unknown")
                val isAuthed = authSnapshot[uuid]?.isAccepted == true
                val auth = authSnapshot[uuid]
                val displayName =
                    obj
                        .optString("name")
                        .ifEmpty { oldInfo?.displayName ?: auth?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid) }
                // 回填缓存（名称/类型以平台持久化与历史缓存为准，IP/电量来自快照）
                synchronized(deviceInfoCache) {
                    deviceInfoCache[uuid] =
                        DeviceInfo(
                            uuid = uuid,
                            displayName = displayName,
                            ip = ip,
                            port = port,
                            batteryLevel = if (batteryUnknown) (oldInfo?.batteryLevel ?: -1) else kotlin.math.abs(battery),
                            chargingStatus = chargingStatus,
                        )
                }
                // 可见性由 core 判定（未配对且未被平台登记且离线的设备 core 已不再返回），
                // 平台端直接按快照渲染
                synchronized(deviceInfoCache) {
                    deviceInfoCache[uuid]?.let { newMap[uuid] = it to online }
                }
                // 同步已认证设备的 lastIp/deviceType 元数据
                if (isAuthed && auth != null) {
                    val effectiveIp = ip.takeUnless { it == "0.0.0.0" || it.isBlank() }
                    val validType = deviceType.takeUnless { it.isBlank() || it == "unknown" }
                    val ipChanged = effectiveIp != null && auth.lastIp != effectiveIp
                    val typeChanged = validType != null && auth.deviceType != validType
                    if (ipChanged || typeChanged) {
                        synchronized(authenticatedDevices) {
                            authenticatedDevices[uuid] = auth.copy(lastIp = effectiveIp ?: auth.lastIp, deviceType = validType ?: auth.deviceType)
                        }
                        saveAuthedDevices()
                    }
                }
            }
            _devices.value = newMap
            updateOnlineDevicesCache(newMap, authSnapshot)
            Logger.d(
                "死神-NotifyRelay",
                "[refreshDevicesFromRust] 列表: ${newMap.size} 台, 在线: ${
                    newMap.count { it.value.second }
                }, 已认证: ${authSnapshot.size}",
            )
        } catch (_: Exception) {
        }
    }

    private fun updateOnlineDevicesCache(
        deviceMap: Map<String, Pair<DeviceInfo, Boolean>>,
        authSnapshot: Map<String, AuthInfo>,
    ) {
        try {
            val onlineDevices =
                deviceMap
                    .filter { (uuid, pair) ->
                        pair.second && (authSnapshot[uuid]?.isAccepted == true)
                    }.mapNotNull { (uuid, pair) ->
                        val info = pair.first
                        val auth = authSnapshot[uuid]
                        if (info.ip.isNotBlank() && info.ip != "0.0.0.0") {
                            notifyrelay.data.model.OnlineDeviceInfo(
                                uuid = info.uuid,
                                displayName = info.displayName,
                                ip = info.ip,
                                port = info.port,
                                deviceType = auth?.deviceType,
                            )
                        } else {
                            null
                        }
                    }
            val gson = com.google.gson.Gson()
            val json = gson.toJson(onlineDevices)

            // 只有当内容实际变化时才执行存储和快捷方式更新
            if (json != lastOnlineDevicesCacheJson) {
                StorageManager.putString(
                    context,
                    notifyrelay.data.config.ScrcpyPreferenceKeys.ONLINE_DEVICES_CACHE,
                    json,
                    StorageManager.PrefsType.SCRCPY,
                )
                try {
                    io.github.miuzarte.scrcpyforandroid.services.DynamicShortcutManager
                        .updateShortcuts(context)
                } catch (_: Exception) {
                }
                lastOnlineDevicesCacheJson = json
            }
        } catch (_: Exception) {
        }
    }

    private fun getDeviceInfo(uuid: String): DeviceInfo? {
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid]
                ?.takeUnless { it.ip == "0.0.0.0" || it.ip.isBlank() }
                ?.let { return it }
        }
        _devices.value[uuid]
            ?.first
            ?.takeUnless { it.ip == "0.0.0.0" || it.ip.isBlank() }
            ?.let { return it }
        val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
        if (auth != null) {
            val name = auth.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
            val ip = auth.lastIp?.takeUnless { it == "0.0.0.0" || it.isBlank() } ?: ""
            val port = auth.lastPort ?: listenPort
            return DeviceInfo(uuid, name, ip, port)
        }
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
     * 客户端收到 ACCEPT 后，在本机完成配对：派生密钥、导入 Keystore、标记已认证。
     */
    fun completePairing(
        remoteUuid: String,
        remotePubKey: String,
    ) {
        try {
            val ctx = rustContext
            if (ctx != null && !NativeCore.deriveSharedSecret(ctx, remoteUuid, remotePubKey)) {
                Logger.e("死神-NotifyRelay", "客户端配对密钥派生失败: $remoteUuid")
                return
            }
            synchronized(authenticatedDevices) {
                authenticatedDevices[remoteUuid] =
                    AuthInfo(
                        remotePubKey,
                        "",
                        true,
                        "未知设备",
                    )
                saveAuthedDevices()
            }
            updateDeviceList()
            Logger.d("死神-NotifyRelay", "客户端配对完成: $remoteUuid")
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "客户端配对失败: $remoteUuid", e)
        }
    }

    data class PendingPairing(
        val remoteUuid: String,
        val remotePubKey: String,
        val remoteIp: String,
    )

    private val _pendingPairingLock = Any()
    private var _pendingPairing: PendingPairing? = null
    var pendingPairing: PendingPairing?
        get() = synchronized(_pendingPairingLock) { _pendingPairing }
        internal set(value) = synchronized(_pendingPairingLock) { _pendingPairing = value }

    /**
     * 取消当前待处理的配对请求。
     */
    fun cancelPendingPairing() {
        val p = pendingPairing
        if (p != null) {
            Logger.d("死神-NotifyRelay", "取消配对: ${p.remoteUuid}")
        }
        pendingPairing = null
        rustContext?.let { NativeCore.clearPairingCode(it) }
    }

    /**
     * 发起端存储在配对阶段的临时 ECDH 私钥（Base64 编码），
     * 供回调解密接收端回传的配对码。
     * 配对完成后应清空。
     */
    private val _pendingTempPrivKeyB64Lock = Any()
    private var _pendingTempPrivKeyB64: String? = null
    var pendingTempPrivKeyB64: String?
        get() = synchronized(_pendingTempPrivKeyB64Lock) { _pendingTempPrivKeyB64 }
        set(value) = synchronized(_pendingTempPrivKeyB64Lock) { _pendingTempPrivKeyB64 = value }

    /**
     * 使用长期 ECDH 密钥完成标准密钥交换。
     * 配对码验证通过后，双方使用长期 ECDH 密钥派生共享密钥并导入 Keystore。
     *
     * @param uuid 远端设备 UUID
     * @param remoteLtPubKey 远端长期 ECDH 公钥 Base64
     * @param displayName 设备显示名称
     * @param lastIp 设备 IP
     * @return 是否成功
     */
    fun completePairingWithLongTermKeys(
        uuid: String,
        remoteLtPubKey: String,
        displayName: String = "未知设备",
        lastIp: String? = null,
    ): Boolean {
        return try {
            val ctx = rustContext
            if (ctx != null && !NativeCore.deriveSharedSecret(ctx, uuid, remoteLtPubKey)) {
                Logger.e("死神-NotifyRelay", "长期密钥派生失败: $uuid")
                return false
            }
            synchronized(authenticatedDevices) {
                authenticatedDevices[uuid] =
                    AuthInfo(
                        remoteLtPubKey,
                        "",
                        true,
                        displayName,
                        lastIp = lastIp,
                    )
                saveAuthedDevices()
            }
            registerReconnectTarget(uuid, lastIp ?: "")
            // 不能同步调用 updateDeviceList()——此方法从 Rust nrc_connect_device 的回调中触发，
            // 同步调用 nrc_get_device_list 会导致 Rust 核心重入崩溃
            coroutineScope.launch { updateDeviceList() }
            Logger.d("死神-NotifyRelay", "长期密钥配对完成: $uuid")
            true
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "长期密钥配对失败: $uuid", e)
            false
        }
    }

    /**
     * 公开解析设备信息：优先使用缓存/认证信息，缺失IP时使用提供的回退IP。
     */
    fun resolveDeviceInfo(
        uuid: String,
        fallbackIp: String?,
        fallbackPort: Int = 23333,
    ): DeviceInfo? {
        val cached = getDeviceInfo(uuid)
        if (cached != null && cached.ip.isNotEmpty() && cached.ip != "0.0.0.0") return cached
        val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
        val name = auth?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
        val port = cached?.port ?: auth?.lastPort ?: fallbackPort
        return fallbackIp?.let { DeviceInfo(uuid, name, it, port) }
    }

    internal fun isWifiDirectNetworkInternal(): Boolean = discoveryManager.isWifiDirectNetworkInternal()

    // 连接设备
    fun connectToDevice(
        device: DeviceInfo,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        coroutineScope.launch {
            try {
                if (rejectedDevices.contains(device.uuid)) {
                    // Logger.d("死神-NotifyRelay", "connectToDevice: 已被对方拒绝 uuid=${device.uuid}")
                    callback?.invoke(false, "已被对方拒绝")
                    return@launch
                }

                // 重试行为由 Rust nrc_connect_device 内部固定次数控制（3次/5s超时/1s间隔）
                val result = keepAlive.performDeviceConnectionWithRetry(device)
                callback?.invoke(result.first, result.second)
            } catch (e: Exception) {
                Logger.e("死神-NotifyRelay", "connectToDevice异常: ${e.message}")
                e.printStackTrace()
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 公开API：请求远端设备的“用户应用列表”。
     */
    fun requestRemoteAppList(
        device: DeviceInfo,
        scope: String = "user",
    ) {
        try {
            AppListSyncManager.requestAppListFromDevice(context, this, device, scope)
        } catch (_: Exception) {
        }
    }

    /**
     * 公开API：请求远端设备转发音频。
     * @return 是否成功发送请求
     */
    fun requestAudioForwarding(device: DeviceInfo): Boolean {
        try {
            val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"audioRequest\"}"
            ProtocolSender.sendEncrypted(this, device, "DATA_MEDIA_CONTROL", raw, 10000L)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    // 注册 Rust 回调，使用统一的配对和数据回调接口
    private fun setupRustCallbacks() {
        val ctx = rustContext ?: return
        val lib = NotifyRelayCore.instance()

        fun ptr2str(ptr: Pointer?) = NotifyRelayCore.ptrToString(ptr)

        // ---- on_pairing (统一配对回调) ----
        val pairingCb =
            object : NotifyRelayCore.OnPairingCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    msgTypePtr: Pointer?,
                    dataPtr: Pointer?,
                    intValue: Int,
                    extraPtr: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val uuid = ptr2str(uuidPtr) ?: return
                    val msgType = ptr2str(msgTypePtr) ?: return
                    val data = ptr2str(dataPtr)
                    val extra = ptr2str(extraPtr)

                    try {
                        when (msgType) {
                            "HANDSHAKE" -> {
                                val dm = _callbackInstance ?: return
                                var pubKey = ""
                                var ip = ""
                                var deviceType = "unknown"
                                var autoAccept = false
                                data?.let {
                                    try {
                                        val json = JSONObject(it)
                                        pubKey = json.optString("pub_key", "")
                                        ip = json.optString("ip", "")
                                        deviceType = json.optString("device_type", "unknown")
                                        autoAccept = json.optBoolean("auto_accept", false)
                                    } catch (_: Exception) {
                                    }
                                }
                                synchronized(dm.deviceInfoCacheInternal) {
                                    val old = dm.deviceInfoCacheInternal[uuid]
                                    val displayName = old?.displayName ?: "未知设备"
                                    dm.deviceInfoCacheInternal[uuid] = DeviceInfo(uuid, displayName, ip, old?.port ?: 23333)
                                }
                                synchronized(dm.authenticatedDevices) {
                                    val auth = dm.authenticatedDevices[uuid]
                                    if (auth != null) {
                                        dm.authenticatedDevices[uuid] = auth.copy(lastIp = ip)
                                        dm.saveAuthedDevicesInternal()
                                    }
                                }
                                dm.registerReconnectTarget(uuid, ip)
                                dm.registerKnownDevice(uuid, ip)
                                // Rust 侧已对已配对设备自动发送 ACCEPT（auto_accept=true），
                                // 平台侧仅做 UI 持久化/登记，无需重复发送 ACCEPT 或 REJECT
                                if (autoAccept) {
                                    // 自动闭环分支同样清理不兼容标记并持久化 deviceType，避免标记残留
                                    synchronized(dm.incompatibleDevicesInternal) { dm.incompatibleDevicesInternal.remove(uuid) }
                                    synchronized(dm.authenticatedDevices) {
                                        val auth = dm.authenticatedDevices[uuid]
                                        if (auth != null && auth.deviceType != deviceType && deviceType != "unknown") {
                                            dm.authenticatedDevices[uuid] = auth.copy(deviceType = deviceType)
                                            dm.saveAuthedDevicesInternal()
                                        }
                                    }
                                    Logger.d("CoreCb", "HANDSHAKE 已自动闭环(Rust auto_accept): $uuid")
                                    return
                                }
                                val alreadyAuthed =
                                    synchronized(dm.authenticatedDevices) {
                                        dm.authenticatedDevices[uuid]?.isAccepted == true
                                    }
                                if (alreadyAuthed) {
                                    synchronized(dm.authenticatedDevices) {
                                        val existingAuth = dm.authenticatedDevices[uuid]
                                        if (existingAuth != null && existingAuth.publicKey != pubKey) {
                                            val rejectCtx = dm.rustContextInternal!!
                                            val rejectUuid = dm.uuid
                                            dm.coroutineScopeInternal.launch { NativeCore.sendReject(rejectCtx, rejectUuid) }
                                            Logger.w("CoreCb", "已认证设备公钥变化，要求重新配对: $uuid")
                                            return
                                        }
                                    }
                                    synchronized(dm.incompatibleDevicesInternal) { dm.incompatibleDevicesInternal.remove(uuid) }
                                    val localIp = getLocalIpAddress()
                                    val acceptCtx = dm.rustContextInternal!!
                                    val acceptUuid = dm.uuid
                                    val acceptPub = dm.localPublicKey
                                    val battery = BatteryUtils.getBatteryLevel(dm.contextInternal)
                                    dm.coroutineScopeInternal.launch {
                                        NativeCore.sendAccept(acceptCtx, acceptUuid, acceptPub, localIp, battery, deviceType)
                                    }
                                    synchronized(dm.authenticatedDevices) {
                                        val auth = dm.authenticatedDevices[uuid]
                                        if (auth != null) {
                                            dm.authenticatedDevices[uuid] = auth.copy(deviceType = deviceType, lastIp = ip)
                                            dm.saveAuthedDevicesInternal()
                                        }
                                    }
                                } else {
                                    val rejectCtx = dm.rustContextInternal!!
                                    val rejectUuid = dm.uuid
                                    dm.coroutineScopeInternal.launch { NativeCore.sendReject(rejectCtx, rejectUuid) }
                                }
                            }
                            "PAIRING_INIT" -> {
                                val dm = _callbackInstance ?: return
                                var spake2Pub = ""
                                var ip = ""
                                data?.let {
                                    try {
                                        val json = JSONObject(it)
                                        spake2Pub = json.optString("spake2_pub", "")
                                        ip = json.optString("ip", "")
                                    } catch (_: Exception) {
                                    }
                                }
                                synchronized(dm.rejectedDevicesInternal) {
                                    if (dm.rejectedDevicesInternal.contains(uuid)) {
                                        val rejectCtx = dm.rustContextInternal!!
                                        val rejectUuid = dm.uuid
                                        dm.coroutineScopeInternal.launch { NativeCore.sendReject(rejectCtx, rejectUuid) }
                                        return
                                    }
                                }
                                val displayName: String
                                synchronized(dm.deviceInfoCacheInternal) {
                                    displayName = dm.deviceInfoCacheInternal[uuid]?.displayName ?: "未知设备"
                                    dm.deviceInfoCacheInternal[uuid] = DeviceInfo(uuid, displayName, ip, 23333)
                                }
                                dm.pendingPairing = PendingPairing(remoteUuid = uuid, remotePubKey = spake2Pub, remoteIp = ip)
                                val remoteDevice = DeviceInfo(uuid, displayName, ip, 23333)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    dm.handshakeRequestHandler?.onPairingInitRequest(remoteDevice, spake2Pub)
                                }
                                Logger.d("CoreCb", "PAIRING_INIT 已处理: $uuid")
                            }
                            "PAIRING_RESP" -> {
                                Logger.w("CoreCb", "收到意外的 PAIRING_RESP: $uuid")
                            }
                            "ACCEPT" -> {
                                val dm = _callbackInstance ?: return
                                var ltPubKey = ""
                                var ip = ""
                                data?.let {
                                    try {
                                        val json = JSONObject(it)
                                        ltPubKey = json.optString("lt_pub_key", "")
                                        ip = json.optString("ip", "")
                                    } catch (_: Exception) {
                                    }
                                }
                                val ok = dm.completePairingWithLongTermKeys(uuid, ltPubKey, lastIp = ip)
                                dm.resolveHandshake(uuid, ok)
                                if (ok) {
                                    // 登记已知设备（uuid+ip），心跳由 Rust 调度器自动启动
                                    dm.registerKnownDevice(uuid, ip)
                                }
                                Logger.d("CoreCb", "ACCEPT 已处理: $uuid")
                            }
                            "REJECT" -> {
                                val dm = _callbackInstance ?: return
                                dm.resolveHandshake(uuid, false)
                                synchronized(dm.rejectedDevicesInternal) {
                                    dm.rejectedDevicesInternal.add(uuid)
                                }
                                Logger.w("CoreCb", "REJECT 已处理: $uuid")
                            }
                            "RESULT" -> {
                                val err = extra ?: ""
                                if (intValue == 0) {
                                    Logger.w("CoreCb", "配对失败: $uuid, error=$err")
                                    _callbackInstance?.resolveHandshake(uuid, false)
                                } else {
                                    Logger.d("CoreCb", "配对成功: $uuid")
                                    val dm = _callbackInstance ?: return
                                    val keyJson = dm.rustContext?.let { NativeCore.exportDeviceKey(it, uuid) }
                                    if (keyJson != null) {
                                        val json = JSONObject(keyJson)
                                        val ltPub = json.optString("remote_pub_key", "")
                                        if (ltPub.isNotEmpty()) {
                                            dm.completePairingWithLongTermKeys(uuid, ltPub)
                                            // 登记已知设备（uuid+ip），心跳由 Rust 调度器自动启动
                                            val cachedIp =
                                                synchronized(dm.deviceInfoCacheInternal) {
                                                    dm.deviceInfoCacheInternal[uuid]?.ip
                                                }
                                            dm.registerKnownDevice(uuid, cachedIp ?: "")
                                        }
                                    }
                                    dm.resolveHandshake(uuid, true)
                                }
                            }
                            "HEARTBEAT_TCP" -> {
                                val dm = _callbackInstance ?: return
                                val remoteName = extra ?: return
                                var deviceType = "unknown"
                                var ip = ""
                                data?.let {
                                    try {
                                        val json = JSONObject(it)
                                        deviceType = json.optString("device_type", "unknown")
                                        ip = json.optString("ip", "")
                                    } catch (_: Exception) {
                                    }
                                }
                                val info =
                                    HeartbeatProcessor.HeartbeatInfo(
                                        uuid = uuid,
                                        displayName = remoteName,
                                        port = 23333,
                                        batteryLevel = kotlin.math.abs(intValue),
                                        isCharging = intValue >= 0,
                                        deviceType = deviceType,
                                        ip = ip,
                                    )
                                if (info.uuid != dm.uuid) {
                                    HeartbeatProcessor.processHeartbeat(info, dm)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("CoreCb", "on_pairing error: ${e.message}")
                    }
                }
            }
        lib.nrc_set_on_pairing_cb(ctx, pairingCb)
        rustCallbackRefs.add(pairingCb)

        // ---- on_data (统一数据回调) ----
        val dataCb =
            object : NotifyRelayCore.OnDataCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    msgTypePtr: Pointer?,
                    plaintextPtr: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val uuid = ptr2str(uuidPtr) ?: return
                    val msgType = ptr2str(msgTypePtr) ?: return
                    val text = ptr2str(plaintextPtr) ?: return
                    val authed =
                        synchronized(authenticatedDevices) {
                            authenticatedDevices[uuid]?.isAccepted == true
                        }
                    Logger.d("CoreCb", "on_data: type=$msgType, authed=$authed, text_len=${text.length}")
                    if (!authed && msgType != "DATA_UNKNOWN") return

                    try {
                        when (msgType) {
                            "NOTIFICATION" -> {
                                NotificationProcessor.process(
                                    context,
                                    this@DeviceConnectionManager,
                                    coroutineScope,
                                    NotificationProcessor.NotificationInput("DATA_NOTIFICATION", text, uuid),
                                    notificationDataReceivedCallbacksInternal,
                                )
                            }
                            "MEDIAPLAY" -> {
                                val json = JSONObject(text)
                                resolveDeviceInfo(uuid, "", 23333)?.let {
                                    RemoteMediaSessionManager.onMediaMessageReceived(context, json, it)
                                }
                            }
                            "ICON_REQUEST" -> {
                                resolveDeviceInfo(uuid, "", 23333)?.let {
                                    IconSyncManager.handleIconRequest(text, this@DeviceConnectionManager, it, context)
                                }
                            }
                            "ICON_RESPONSE" -> {
                                IconSyncManager.handleIconResponse(text, context)
                            }
                            "APP_LIST_REQUEST" -> {
                                resolveDeviceInfo(uuid, "", 23333)?.let {
                                    AppListSyncManager.handleAppListRequest(text, this@DeviceConnectionManager, it, context)
                                }
                            }
                            "APP_LIST_RESPONSE" -> {
                                AppListSyncManager.handleAppListResponse(text, context, uuid, this@DeviceConnectionManager)
                            }
                            "MEDIA_CONTROL" -> {
                                val json = JSONObject(text)
                                val action = json.getString("action")
                                when (action) {
                                    "playPause" ->
                                        try {
                                            MediaControlUtil.playPause()
                                            sendMediaControlResponse(uuid, "playPause", "success", null)
                                        } catch (e: Exception) {
                                            sendMediaControlResponse(uuid, "playPause", "error", e.message)
                                        }
                                    "next" ->
                                        try {
                                            MediaControlUtil.next()
                                            sendMediaControlResponse(uuid, "next", "success", null)
                                        } catch (e: Exception) {
                                            sendMediaControlResponse(uuid, "next", "error", e.message)
                                        }
                                    "previous" ->
                                        try {
                                            MediaControlUtil.previous()
                                            sendMediaControlResponse(uuid, "previous", "success", null)
                                        } catch (e: Exception) {
                                            sendMediaControlResponse(uuid, "previous", "error", e.message)
                                        }
                                    "audioRequest" -> {
                                        val relayMode = StorageManager.getInt(context, "audio_relay_mode", 0)
                                        if (relayMode == 1) {
                                            // 中继模式：Rust 内部自动发控制消息
                                            val device = resolveDeviceInfo(uuid, "", 23333)
                                            device?.let {
                                                pendingAudioRelaySend = PendingAudioSend(it.ip, it.displayName, uuid)
                                                onRequestMediaProjection?.invoke()
                                            }
                                        } else {
                                            // scrcpy 模式：现有逻辑
                                            val device = resolveDeviceInfo(uuid, "", 23333)
                                            val ok = device?.let { AudioForwardingService.startAudioForwarding(context, it.ip, ScrcpyDefaults.ADB_PORT, it.displayName) } == true
                                            val result = if (ok) "accepted" else "rejected"
                                            val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"audioResponse\",\"result\":\"$result\"}"
                                            device?.let { ProtocolSender.sendEncrypted(this@DeviceConnectionManager, it, "DATA_MEDIA_CONTROL", raw) }
                                        }
                                    }
                                    "audioResponse" -> {
                                        if (json.optString("result", "rejected") != "accepted") {
                                            coroutineScope.launch {
                                                notifyrelay.base.util.ToastUtils
                                                    .showShortToast(context, "音频转发请求被拒绝")
                                            }
                                        }
                                    }
                                    "audioStart" -> {
                                        coroutineScope.launch {
                                            val sr = json.optInt("sampleRate", 48000)
                                            val ch = json.optInt("channels", 2)
                                            val device = resolveDeviceInfo(uuid, "", 23333)
                                            val ip = device?.ip ?: ""
                                            audioRelayPlayer.start("recv", sr, ch, remoteUuid = uuid)
                                            showAudioRelayNotification(device?.displayName ?: ip, uuid)
                                        }
                                    }
                                    "audioStop" -> {
                                        coroutineScope.launch {
                                            audioRelayPlayer.stop()
                                            cancelAudioRelayNotification()
                                            cleanupMediaProjection()
                                            if (json.optString("result", "").isNotEmpty()) return@launch
                                            val device = resolveDeviceInfo(uuid, "", 23333)
                                            if (device != null) {
                                                val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"audioStop\",\"result\":\"ok\"}"
                                                try {
                                                    ProtocolSender.sendEncrypted(this@DeviceConnectionManager, device, "DATA_MEDIA_CONTROL", raw)
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "FTP" -> {
                                val isPc =
                                    synchronized(authenticatedDevices) {
                                        authenticatedDevices[uuid]?.deviceType?.lowercase() == "pc"
                                    }
                                if (!isPc) return
                                coroutineScope.launch {
                                    try {
                                        val json = JSONObject(text)
                                        when (json.optString("action", "")) {
                                            "start" -> {
                                                val pcUser = json.optString("username", null)
                                                val pcPass = json.optString("password", null)
                                                val result = FtpServerManager.start(getLocalDisplayName(), context, pcUser, pcPass)
                                                when (result.status) {
                                                    StartResult.SUCCESS, StartResult.ALREADY_RUNNING -> {
                                                        result.serverInfo?.let { info ->
                                                            val raw =
                                                                JSONObject()
                                                                    .apply {
                                                                        put("action", "started")
                                                                        put("ipAddress", info.ipAddress)
                                                                        put("port", info.port)
                                                                    }.toString()
                                                            resolveDeviceInfo(uuid, "", 23333)?.let {
                                                                ProtocolSender.sendEncrypted(this@DeviceConnectionManager, it, "DATA_FTP", raw)
                                                            }
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                                                val intent = IntentUtils.createIntent(context, GuideActivity::class.java)
                                                                intent.putExtra("fromftp", true)
                                                                intent.putExtra("fromInternal", true)
                                                                IntentUtils.startActivity(context, intent, true)
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        val err =
                                                            when (result.status) {
                                                                StartResult.PERMISSION_DENIED -> "PERMISSION_DENIED"
                                                                StartResult.PORT_IN_USE -> "PORT_IN_USE"
                                                                StartResult.CONFIG_ERROR -> "CONFIG_ERROR"
                                                                else -> "FAILED"
                                                            }
                                                        val raw =
                                                            JSONObject()
                                                                .apply {
                                                                    put("originalHeader", "DATA_FTP")
                                                                    put("action", "start")
                                                                    put("result", "error")
                                                                    put("errorCode", err)
                                                                }.toString()
                                                        resolveDeviceInfo(uuid, "", 23333)?.let {
                                                            ProtocolSender.sendEncrypted(this@DeviceConnectionManager, it, "DATA_STATUS", raw)
                                                        }
                                                    }
                                                }
                                            }
                                            "stop" -> {
                                                FtpServerManager.stop()
                                                val raw = JSONObject().apply { put("action", "stopped") }.toString()
                                                resolveDeviceInfo(uuid, "", 23333)?.let {
                                                    ProtocolSender.sendEncrypted(this@DeviceConnectionManager, it, "DATA_FTP", raw)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("CoreCb", "DATA_FTP", e)
                                    }
                                }
                            }
                            "CLIPBOARD" -> {
                                ClipboardProcessor.process(context, ClipboardProcessor.ClipboardInput("DATA_CLIPBOARD", text, ""))
                            }
                            "STATUS" -> {
                                StatusProcessor.process(
                                    context,
                                    this@DeviceConnectionManager,
                                    coroutineScope,
                                    StatusProcessor.StatusInput("DATA_STATUS", text, uuid),
                                    notificationDataReceivedCallbacksInternal,
                                )
                            }
                            "APP_LAUNCH" -> {
                                resolveDeviceInfo(uuid, "", 23333)?.let {
                                    AppLaunchManager.handleAppLaunchRequest(text, this@DeviceConnectionManager, it, context)
                                }
                            }
                            "SUPERISLAND" -> {
                                SuperIslandProcessor.process(context, this@DeviceConnectionManager, text, uuid)
                            }
                            else -> {
                                Logger.d("CoreCb", "未知DATA通道: type=$msgType, uuid=$uuid, size=${text.length}")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("CoreCb", "on_data error: ${e.message}")
                    }
                }
            }
        lib.nrc_set_on_data_cb(ctx, dataCb)
        rustCallbackRefs.add(dataCb)

        // ---- on_device_discovered (TCP 扫描发现) ----
        // 设备状态（自身过滤、名称解码、状态登记、可见性）全部由 Rust core 负责，
        // 平台端不解析业务字段，仅触发一次快照刷新
        val deviceDiscoveredCb =
            object : NotifyRelayCore.OnDeviceDiscoveredCb {
                override fun invoke(
                    uuid: Pointer?,
                    name: Pointer?,
                    port: Short,
                    battery: Int,
                    deviceType: Pointer?,
                    ip: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val dm = _callbackInstance ?: return
                    val remoteUuid = ptr2str(uuid) ?: return
                    try {
                        Logger.d(
                            "死神-NotifyRelay",
                            "[on_device_discovered] uuid=$remoteUuid, ip=${ptr2str(ip) ?: ""}, port=$port, battery=$battery",
                        )
                        // 运行在 Rust 扫描线程：不得同步调用 nrc_get_device_list（会与 core 重入），
                        // 交由协程异步刷新
                        dm.triggerDeviceListRefreshFromCore()
                    } catch (e: Exception) {
                        Logger.e("CoreCb", "on_device_discovered error", e)
                    }
                }
            }
        lib.nrc_set_on_device_discovered_cb(ctx, deviceDiscoveredCb)
        rustCallbackRefs.add(deviceDiscoveredCb)

        // ---- on_device_timeout (设备心跳超时回调) ----
        val deviceTimeoutCb =
            object : NotifyRelayCore.OnDeviceTimeoutCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                    // 超时离线状态由 Rust DeviceRegistry 维护，此处仅重新登记重连目标
                    val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
                    if (auth != null) registerReconnectTarget(uuid, auth.lastIp ?: "")
                    // 触发快照刷新，UI 立即反映离线状态（替代原 1s 轮询的离线更新职责）
                    runCatching { updateDeviceList() }
                }
            }
        lib.nrc_set_on_device_timeout_cb(ctx, deviceTimeoutCb)
        rustCallbackRefs.add(deviceTimeoutCb)

        // ---- on_device_connected (TCP 连接建立回调) ----
        val deviceConnectedCb =
            object : NotifyRelayCore.OnDeviceConnectedCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    ipPtr: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val dm = _callbackInstance ?: return
                    val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                    if (uuid == dm.uuid) return
                    val ip = NotifyRelayCore.ptrToString(ipPtr) ?: ""
                    // 回填连接来源 IP 并触发快照刷新（替代原 1s 轮询的在线更新职责）
                    synchronized(dm.deviceInfoCache) {
                        val existing = dm.deviceInfoCache[uuid]
                        if (existing != null && ip.isNotBlank() && ip != "0.0.0.0") {
                            dm.deviceInfoCache[uuid] = existing.copy(ip = ip)
                        }
                    }
                    runCatching { dm.updateDeviceList() }
                }
            }
        lib.nrc_set_on_device_connected_cb(ctx, deviceConnectedCb)
        rustCallbackRefs.add(deviceConnectedCb)

        // ---- on_device_disconnected (TCP 断开回调) ----
        // 设备主动断开 TCP 时立即刷新快照，UI 无需等 12 秒离线超时
        val deviceDisconnectedCb =
            object : NotifyRelayCore.OnDeviceDisconnectedCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    userData: Pointer?,
                ) {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val dm = _callbackInstance ?: return
                    val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                    if (uuid == dm.uuid) return
                    runCatching { dm.updateDeviceList() }
                }
            }
        lib.nrc_set_on_device_disconnected_cb(ctx, deviceDisconnectedCb)
        rustCallbackRefs.add(deviceDisconnectedCb)

        // ---- on_state_query (超级岛/媒体心跳查询回调：0=不存在 / 1=存在无变更 / 2=存在有变更) ----
        // 运行在 Rust 心跳线程且锁已释放，回调内可直接调用 nrc_push_*（isQuery=1）响应变更
        val stateQueryCb =
            object : NotifyRelayCore.OnStateQueryCb {
                override fun invoke(
                    uuidPtr: Pointer?,
                    featureIdPtr: Pointer?,
                    isMedia: Int,
                    userData: Pointer?,
                ): Int {
                    Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                    val remoteUuid = ptr2str(uuidPtr) ?: return 0
                    val featureId = ptr2str(featureIdPtr) ?: return 0
                    val dm = _callbackInstance ?: return 0
                    return try {
                        if (isMedia != 0) {
                            dm.handleMediaStateQuery(remoteUuid, featureId)
                        } else {
                            dm.handleSuperIslandStateQuery(remoteUuid, featureId)
                        }
                    } catch (e: Exception) {
                        Logger.e("CoreCb", "on_state_query error: ${e.message}")
                        1 // 异常保守保活，等待下一次查询
                    }
                }
            }
        lib.nrc_set_on_state_query_cb(ctx, stateQueryCb)
        rustCallbackRefs.add(stateQueryCb)

        // ---- 日志回调（接入 Logger.currentLevel 等级控制） ----
        NativeCore.setLogCallback(ctx)
    }

    // ---- 状态查询回调处理（运行在 Rust 心跳线程，锁已释放；回调内可直接调 push(isQuery=1)） ----
    private val mediaFeatureId = "media_global" // 与 Rust MEDIA_KEY 一致

    // 状态查询轻量比较键缓存：键 = "$remoteUuid|$featureId"，值 = 轻量内容键；
    // 心跳查询时先比较轻量键，未变化则跳过昂贵的 runBlocking/fullJson 构造与推送
    private val stateQueryKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun removeStateQueryKey(
        remoteUuid: String,
        featureId: String,
    ) {
        stateQueryKeys.remove("$remoteUuid|$featureId")
    }

    private fun trimStateQueryKeys(remoteUuid: String) {
        if (stateQueryKeys.size <= 200) return
        stateQueryKeys.keys.removeIf { !it.startsWith("$remoteUuid|") }
        if (stateQueryKeys.size > 200) stateQueryKeys.clear()
    }

    /** 超级岛查询：扫描活跃通知，重算 featureId（与 Rust 算法一致，iid 传空）匹配后对比推送 */
    private fun handleSuperIslandStateQuery(
        remoteUuid: String,
        featureId: String,
    ): Int {
        val listener = NotifyRelayNotificationListenerService.instance ?: return 0
        val actives = listener.activeNotifications ?: return 0
        for (sbn in actives) {
            if (sbn.packageName == context.packageName) continue
            val superData =
                try {
                    SuperIslandManager.extractSuperIslandData(sbn, context)
                } catch (_: Exception) {
                    null
                } ?: continue
            val superPkg = superData.sourcePackage ?: continue
            // 重算 featureId：与 Rust 会话 key 及发送端严格一致（sbn.key 稳定值）
            val computedId = listener.getNotificationKey(sbn, "")
            if (computedId != featureId) continue
            // 轻量比较键（不含图片资源）：未变化时跳过昂贵的 runBlocking/fullJson 构造与推送
            val lightKey = "$superPkg|${superData.appName}|${superData.title}|${superData.text}|${sbn.postTime}|${superData.paramV2Raw}"
            val cacheKey = "$remoteUuid|$featureId"
            if (stateQueryKeys[cacheKey] == lightKey) return 1 // 存在无变更，保活等待下次查询
            // 匹配：组装 full（与 sendSuperIslandData 同格式）并对比推送。
            // 该回调运行在 Rust 心跳线程且必须同步返回 0/1/2，无法异步处理；
            // 查询路径的 picMap 来自已提取的活跃通知（多为 data URI/http，无 IO），
            // 仅本地 file/content URI 时才会发生实际文件读取，故在此包装 runBlocking 是必要且可接受的。
            val content =
                kotlinx.coroutines.runBlocking {
                    MessageSender.buildSuperIslandFullContent(
                        context,
                        superPkg,
                        superData.appName ?: "超级岛",
                        superData.title,
                        superData.text,
                        sbn.postTime,
                        superData.paramV2Raw,
                        superData.picMap ?: emptyMap(),
                        featureId,
                    )
                }
            val result = compareAndPushState(remoteUuid, featureId, content, isMedia = false)
            trimStateQueryKeys(remoteUuid)
            stateQueryKeys[cacheKey] = lightKey
            return result
        }
        // 无匹配：平台上不存在该会话
        Logger.w("CoreCb", "状态查询: 超级岛会话不存在, fid=$featureId")
        return 0
    }

    private var cachedMediaBitmap: Bitmap? = null
    private var cachedMediaCoverUrl: String? = null
    private val mediaCoverCacheLock = Any()

    /** 媒体查询：读取当前媒体会话组装 full 并对比推送；无媒体会话返回 0 */
    private fun handleMediaStateQuery(
        remoteUuid: String,
        featureId: String,
    ): Int {
        if (featureId != mediaFeatureId) return 0
        val monitor = MediaSessionMonitorService.instance ?: return 0
        val primary = monitor.getPrimaryController() ?: return 0
        val pkg = primary.packageName
        val mediaData =
            NotifyRelayNotificationListenerService.getMediaSessionData(pkg)
                ?: return 1 // 有媒体会话但数据未就绪，保守保活等待下次查询
        val coverUrl =
            synchronized(mediaCoverCacheLock) {
                val bmp = mediaData.artBitmap
                if (bmp !== cachedMediaBitmap) {
                    cachedMediaBitmap = bmp
                    cachedMediaCoverUrl =
                        if (bmp == null) {
                            null
                        } else {
                            try {
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            } catch (_: Exception) {
                                null
                            }
                        }
                }
                cachedMediaCoverUrl
            }
        // 轻量比较键：仅标题/艺术家/封面变化才重新构造 fullJson 并推送（isPlaying 由 Rust 媒体心跳处理）
        val lightKey = "$pkg|${mediaData.title}|${mediaData.artist}|$coverUrl"
        val cacheKey = "$remoteUuid|$featureId"
        if (stateQueryKeys[cacheKey] == lightKey) return 1 // 存在无变更，保活等待下次查询
        val content =
            MessageSender.buildMediaFullContent(
                context,
                pkg,
                pkg,
                mediaData.title,
                mediaData.artist,
                coverUrl,
                System.currentTimeMillis(),
            )
        val result = compareAndPushState(remoteUuid, featureId, content, isMedia = true)
        trimStateQueryKeys(remoteUuid)
        stateQueryKeys[cacheKey] = lightKey
        return result
    }

    /**
     * 查询响应推送：差异计算（FULL/DELTA）与保活由 Rust 合并引擎内部完成（无变更时发空差量保活），
     * 平台无需本地对比缓存，直接推送全量并返回 2（存在有变更）。
     */
    private fun compareAndPushState(
        remoteUuid: String,
        featureId: String,
        fullJson: String,
        isMedia: Boolean,
    ): Int {
        pushQueryResponse(remoteUuid, featureId, fullJson, isMedia)
        return 2
    }

    /** 查询响应推送：仅推给查询对应的远端设备（isQuery=1） */
    private fun pushQueryResponse(
        remoteUuid: String,
        featureId: String,
        fullJson: String,
        isMedia: Boolean,
    ) {
        try {
            val ctx = rustContextInternal ?: return
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return
            if (isMedia) {
                NativeCore.pushMediaState(ctx, queuePtr, remoteUuid, fullJson, false, true)
            } else {
                NativeCore.pushSuperislandState(ctx, queuePtr, remoteUuid, fullJson, false, true)
            }
        } catch (e: Exception) {
            Logger.w("CoreCb", "查询响应推送失败: $remoteUuid fid=$featureId", e)
        }
    }

    // 辅助方法：发送媒体控制响应（由回调使用）
    private fun sendMediaControlResponse(
        remoteUuid: String,
        action: String,
        result: String,
        errorMessage: String?,
    ) {
        try {
            val raw =
                JSONObject()
                    .apply {
                        put("originalHeader", "DATA_MEDIA_CONTROL")
                        put("action", action)
                        put("result", result)
                        if (errorMessage != null) put("errorMessage", errorMessage)
                    }.toString()
            resolveDeviceInfo(remoteUuid, "", 23333)?.let {
                ProtocolSender.sendEncrypted(this, it, "DATA_STATUS", raw)
            }
        } catch (e: Exception) {
            Logger.e("CoreCb", "sendMediaControlResponse", e)
        }
    }

    /**
     * 一次性迁移旧平台存储到 Rust 私有库（uuid 进入 Rust 后调用）：
     * - device_migration 表设备行：名称 → renameDevice；base64 AES → migrateSharedSecret；
     *   旧明文密钥 → removeDevice（不兼容，清除配对）
     * - 旧加密状态 blob（SP rust_core_state）经 importState 已导入内存，
     *   getLocalUuid 校验落盘成功后清除
     * 空数据一律不传入 Rust（无证明已迁移的数据不喂给 Rust）
     */
    private suspend fun migrateLegacyDevicesAndCleanup(legacyStateEnc: String) {
        val ctx = rustContext ?: return
        try {
            val rows =
                DatabaseRepository.getInstance(context).queryDeviceMigrationRows()
            var migratedAny = false
            var migrationFailed = false
            for (row in rows) {
                if (row.uuid.isBlank() || row.uuid == "本机") continue
                if (row.displayName.isNotBlank()) {
                    if (!NativeCore.renameDevice(ctx, row.uuid, row.displayName)) {
                        Logger.w("死神-NotifyRelay", "设备名称迁移失败 ${row.uuid}，暂缓清理旧平台存储")
                        migrationFailed = true
                    }
                    migratedAny = true
                }
                if (row.sharedSecret.isNotBlank()) {
                    val keyBytes =
                        try {
                            Base64.decode(row.sharedSecret, Base64.NO_WRAP)
                        } catch (_: Exception) {
                            null
                        }
                    if (keyBytes != null && keyBytes.size == 32) {
                        if (!NativeCore.migrateSharedSecret(ctx, row.uuid, keyBytes)) {
                            Logger.w("死神-NotifyRelay", "设备密钥迁移失败 ${row.uuid}，暂缓清理旧平台存储")
                            migrationFailed = true
                        }
                        migratedAny = true
                    } else {
                        // 旧版明文密钥（C#/Kotlin ECDH），与 Rust HKDF 不兼容，清除配对
                        if (!NativeCore.removeDevice(ctx, row.uuid)) {
                            Logger.w("死神-NotifyRelay", "旧明文密钥设备清除失败 ${row.uuid}，暂缓清理旧平台存储")
                            migrationFailed = true
                        }
                        migratedAny = true
                    }
                }
                if (row.lastIp.isNotBlank()) {
                    try {
                        NativeCore.addKnownDevice(ctx, row.uuid, row.lastIp)
                    } catch (_: Exception) {
                    }
                }
            }

            // 触发落盘并校验（读取接口前自动 flush；flush 失败返回 null）
            val persistedUuid = NativeCore.getLocalUuid(ctx)
            if (persistedUuid.isNullOrEmpty()) {
                Logger.w("死神-NotifyRelay", "Rust 持久化未就绪，暂缓清理旧平台存储")
                return
            }
            if (persistedUuid != uuid) {
                Logger.i("死神-NotifyRelay", "UUID 以 Rust 持久化为准: $persistedUuid (原: $uuid)")
                uuid = persistedUuid
            }
            // 任一迁移行失败：保留旧存储（SP blob/device_migration 表），下次启动重试
            if (migrationFailed) {
                Logger.w("死神-NotifyRelay", "存在迁移失败行，暂缓清理旧平台存储，下次启动重试")
                return
            }
            // 迁移完成：清理旧平台存储（密钥已由 Rust 私有库持有）
            if (legacyStateEnc.isNotEmpty()) {
                StorageManager.remove(context, "rust_core_state")
            }
            if (rows.isNotEmpty()) {
                DatabaseRepository.getInstance(context).dropDeviceMigrationTable()
            }
            if (migratedAny) {
                Logger.i("死神-NotifyRelay", "旧设备数据已迁移至 Rust 持久化")
            }
        } catch (e: Exception) {
            Logger.e("死神-NotifyRelay", "旧设备数据迁移失败", e)
        }
    }

    // 封装设备信息缓存更新操作
    private fun updateDeviceInfoCache(
        uuid: String,
        deviceInfo: DeviceInfo,
    ) {
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid] = deviceInfo
        }
    }

    // 将认证设备登记到 Rust 重连状态机（先移除再添加，重置重试周期）
    private fun registerReconnectTarget(
        uuid: String,
        ip: String,
    ) {
        try {
            if (uuid == this.uuid) return
            val ctx = rustContext ?: return
            if (ip.isNullOrEmpty() || ip == "0.0.0.0") return
            NativeCore.reconnectRemoveTarget(ctx, uuid)
            NativeCore.reconnectAddTarget(ctx, uuid, ip)
        } catch (_: Exception) {
        }
    }

    // 重新登记所有认证设备到 Rust 重连状态机（网络恢复后调用）
    internal fun refreshAllReconnectTargetsInternal() {
        val snapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
        for ((uuid, auth) in snapshot) {
            if (uuid == "本机") continue
            registerReconnectTarget(uuid, auth.lastIp ?: "")
        }
    }

    // 从 Rust 重连状态机移除目标
    private fun removeReconnectTarget(uuid: String) {
        try {
            val ctx = rustContext ?: return
            NativeCore.reconnectRemoveTarget(ctx, uuid)
        } catch (_: Exception) {
        }
    }

    // 将认证设备登记到 Rust 已知设备扫描器（known_device_scanner）
    private fun registerKnownDevice(
        uuid: String,
        ip: String,
    ) {
        try {
            if (uuid == this.uuid) return
            val ctx = rustContext ?: return
            if (ip.isNullOrEmpty() || ip == "0.0.0.0") return
            NativeCore.removeKnownDevice(ctx, uuid)
            NativeCore.addKnownDevice(ctx, uuid, ip)
        } catch (_: Exception) {
        }
    }

    // 从 Rust 已知设备扫描器移除
    private fun removeKnownDevice(uuid: String) {
        try {
            val ctx = rustContext ?: return
            NativeCore.removeKnownDevice(ctx, uuid)
        } catch (_: Exception) {
        }
    }

    // 封装设备信息缓存IP更新操作（保持其他信息不变）
    private fun updateDeviceInfoCacheIp(
        uuid: String,
        newIp: String,
    ) {
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

    // 获取本机 IP 地址
    private fun getLocalIpAddress(): String = NativeCore.getLocalIp() ?: "0.0.0.0"

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
            val devsSnapshot = devices.value
            val authSnapshot = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
            val deviceInfoSnapshot = synchronized(deviceInfoCache) { deviceInfoCache.toMap() }

            // 调试日志
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 认证设备: ${authSnapshot.size} 个设备")
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 设备信息缓存: ${deviceInfoSnapshot.size} 个设备")
            Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 设备列表: ${devsSnapshot.size} 个设备")

            // 获取同时在线且已认证的设备列表
            val result =
                devsSnapshot
                    .filter { (uuid, pair) ->
                        val isOnline = pair.second
                        isOnline && (authSnapshot[uuid]?.isAccepted == true)
                    }.mapNotNull { (uuid, pair) ->
                        // 从设备信息缓存中获取设备信息
                        var deviceInfo = deviceInfoSnapshot[uuid]

                        // 如果缓存中没有，使用设备列表中的设备信息
                        if (deviceInfo == null) {
                            deviceInfo = pair.first
                        }

                        Logger.d("死神-NotifyRelay", "[getAuthenticatedOnlineDevices] 在线且已认证设备: $uuid, name=${deviceInfo?.displayName}, ip=${deviceInfo?.ip}")
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
     * - 从 Rust 上下文移除设备密钥（registry 状态随之清理，删除结果落库失败则中止）
     * - 从 Rust 重连状态机与已知设备扫描器移除（心跳调度随之停止）
     * - 断开会话（TCP 状态清理，避免已删设备经活跃连接重新出现在列表）
     * - 从内存中移除设备信息
     * - 触发 updateDeviceList() 以通知观察者
     * 返回 true 表示存在并已移除，false 表示没有该uuid 或 Rust 持久化删除未完成
     */
    fun removeAuthenticatedDevice(
        uuid: String,
        deleteHistory: Boolean = false,
    ): Boolean {
        try {
            var existed = false

            // 从协议不兼容设备集合移除
            try {
                incompatibleDevicesInternal.remove(uuid)
            } catch (_: Exception) {
            }

            // 从 Rust 上下文移除设备密钥（同时清理 registry 状态）
            // 落库失败（返回 false）时中止平台侧清理：内存已移除但库未同步，
            // 若继续删除平台记录，重启后设备经旧 state/行复活将造成两端不一致
            try {
                val ctx = rustContext
                if (ctx != null && !NativeCore.removeDevice(ctx, uuid)) {
                    Logger.w("死神-NotifyRelay", "removeAuthenticatedDevice: Rust 删除未完成，中止平台侧清理: $uuid")
                    return false
                }
            } catch (_: Exception) {
            }

            // 断开会话与重连/扫描器登记（心跳调度随之停止）
            try {
                val ctx = rustContext
                if (ctx != null) {
                    NativeCore.removeDeviceSession(ctx, uuid)
                }
            } catch (_: Exception) {
            }

            // 从 Rust 重连状态机移除目标
            removeReconnectTarget(uuid)

            // 从 Rust 已知设备扫描器移除（调度器随之停止该设备心跳）
            removeKnownDevice(uuid)

            synchronized(authenticatedDevices) {
                if (authenticatedDevices.containsKey(uuid)) {
                    // 删除设备迁移残留行（若旧表数据尚未迁移）及关联数据
                    coroutineScope.launch {
                        val repository = DatabaseRepository.getInstance(context)
                        repository.deleteDeviceMigrationByUuid(uuid)
                        if (deleteHistory) {
                            repository.deleteNotificationsByDevice(uuid)
                            repository.deleteAppDeviceAssociationsByDeviceUuid(uuid)
                        }
                    }

                    // 从内存中移除
                    authenticatedDevices.remove(uuid)
                    existed = true
                }
            }

            // 清理 deviceInfoCache
            try {
                synchronized(deviceInfoCache) {
                    deviceInfoCache.remove(uuid)
                }
            } catch (_: Exception) {
            }

            stateQueryKeys.keys.removeIf { it.startsWith("$uuid|") }

            // 触发更新，确保 StateFlow 与回调被通知
            try {
                coroutineScope.launch {
                    updateDeviceList()
                    // 更新Flow值（在同步块内读取，确保一致性）
                    val authMap = synchronized(authenticatedDevices) { authenticatedDevices.toMap() }
                    val rejectedSet = synchronized(rejectedDevices) { rejectedDevices.toSet() }
                    _authenticatedDevicesFlow.value = authMap
                    _rejectedDevicesFlow.value = rejectedSet
                }
            } catch (_: Exception) {
            }
            return existed
        } catch (e: Exception) {
            Logger.w("死神-NotifyRelay", "removeAuthenticatedDevice failed: ${e.message}")
            return false
        }
    }

    val audioRelayPlayer by lazy { AudioRelayPlayer(context) }
    private var currentAudioRelayUuid: String? = null

    private data class PendingAudioSend(
        val deviceIp: String,
        val deviceName: String,
        val remoteUuid: String,
    )

    private var pendingAudioRelaySend: PendingAudioSend? = null

    fun startSendTo(
        deviceIp: String,
        deviceName: String,
        remoteUuid: String,
    ) {
        pendingAudioRelaySend = PendingAudioSend(deviceIp, deviceName, remoteUuid)
        onRequestMediaProjection?.invoke()
    }

    fun startPendingAudioRelaySend() {
        val pending = pendingAudioRelaySend ?: return
        pendingAudioRelaySend = null
        if (NativeCore.mediaProjection != null) {
            startAudioRelaySend(pending.deviceIp, pending.deviceName, pending.remoteUuid)
        }
    }

    private fun startAudioRelaySend(
        deviceIp: String,
        deviceName: String,
        remoteUuid: String,
    ) {
        val projection = NativeCore.mediaProjection ?: return
        currentAudioRelayUuid = remoteUuid
        audioRelayPlayer.start("send", remoteUuid = remoteUuid)
        audioRelayPlayer.startSendCapture(projection)
        showAudioRelayNotification(deviceName, direction = "send")
    }

    private fun showAudioRelayNotification(
        deviceName: String,
        remoteUuid: String = "",
        direction: String = "recv",
    ) {
        if (remoteUuid.isNotEmpty()) {
            currentAudioRelayUuid = remoteUuid
        }
        registerAudioRelayStopReceiver()
        AudioRelayForegroundService
            .start(context, deviceName, direction)
    }

    private fun registerAudioRelayStopReceiver() {
        if (audioRelayNotificationReceiver != null) return
        audioRelayNotificationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == AudioRelayForegroundService.STOP_ACTION) {
                        stopAudioRelay()
                    }
                }
            }
        try {
            context.registerReceiver(
                audioRelayNotificationReceiver,
                IntentFilter(AudioRelayForegroundService.STOP_ACTION),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
    }

    fun stopAudioRelay() {
        val uuid = currentAudioRelayUuid
        if (!uuid.isNullOrEmpty()) {
            val device = resolveDeviceInfo(uuid, "", 23333)
            if (device != null) {
                val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"audioStop\"}"
                try {
                    ProtocolSender.sendEncrypted(this@DeviceConnectionManager, device, "DATA_MEDIA_CONTROL", raw)
                } catch (_: Exception) {
                }
            }
        }
        audioRelayPlayer.stop()
        cancelAudioRelayNotification()
        cleanupMediaProjection()
    }

    private fun cleanupMediaProjection() {
        // 停止并清空投影，避免下次授权时 stop 旧投影触发 onStop 回调干扰新会话
        try {
            NativeCore.mediaProjection?.stop()
        } catch (_: Exception) {
        }
        NativeCore.mediaProjection = null
    }

    private fun cancelAudioRelayNotification() {
        try {
            audioRelayNotificationReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        audioRelayNotificationReceiver = null
        AudioRelayForegroundService
            .stop(context)
        MediaProjectionForegroundService
            .stop(context)
    }
}
