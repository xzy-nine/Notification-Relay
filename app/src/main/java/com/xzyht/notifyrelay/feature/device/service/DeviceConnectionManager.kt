package com.xzyht.notifyrelay.feature.device.service

import android.content.Context
import com.sun.jna.Pointer
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.feature.device.model.AuthInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceNameCache
import com.xzyht.notifyrelay.feature.device.model.DeviceSnapshot
import com.xzyht.notifyrelay.feature.device.model.PendingPairing
import com.xzyht.notifyrelay.feature.device.service.audio.AudioRelayController
import com.xzyht.notifyrelay.feature.device.service.callback.DeviceCallbackHost
import com.xzyht.notifyrelay.feature.device.service.callback.HandshakeRequestHandler
import com.xzyht.notifyrelay.feature.device.service.core.RustCoreSession
import com.xzyht.notifyrelay.feature.device.service.pairing.HandshakeWaiterRegistry
import com.xzyht.notifyrelay.feature.device.service.pairing.PairingCoordinator
import com.xzyht.notifyrelay.feature.device.service.state.DeviceDirectory
import com.xzyht.notifyrelay.feature.device.service.state.DeviceQuery
import com.xzyht.notifyrelay.feature.device.service.state.DeviceSnapshotStore
import com.xzyht.notifyrelay.feature.device.service.state.DeviceTargetRegistry
import com.xzyht.notifyrelay.feature.device.service.state.OnlineDevicesCache
import com.xzyht.notifyrelay.feature.device.service.state.PairedDeviceRemover
import com.xzyht.notifyrelay.feature.device.service.statequery.StateQueryResponder
import com.xzyht.notifyrelay.feature.device.service.system.LegacyDeviceMigrator
import com.xzyht.notifyrelay.feature.device.service.system.SystemStateMonitor
import com.xzyht.notifyrelay.sync.ConnectionDiscoveryManager
import com.xzyht.notifyrelay.sync.ConnectionKeepAlive
import com.xzyht.notifyrelay.sync.ProtocolSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import notifyrelay.base.util.DeviceUtils
import notifyrelay.base.util.Logger
import notifyrelay.core.util.BatteryUtils
import notifyrelay.data.config.AppConfig

// =================== 设备连接管理器主类 ===================
class DeviceConnectionManager(
    private val context: Context,
) : DeviceCallbackHost {
    companion object {
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): DeviceConnectionManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
    }

    // ==================== 握手请求处理 ====================

    /**
     * 配对请求处理器
     */
    override var handshakeRequestHandler: HandshakeRequestHandler? = null

    internal fun getLocalDisplayName(): String = DeviceUtils.getLocalDeviceName(context)

    /**
     * 从 core 首帧快照恢复「已配对设备」的平台侧记录，并把连接目标重灌进 core。
     *
     * core 的 `crypto.device_keys` / SQLite `devices` 表已持久化配对关系，平台端只做两件事：
     * 1. 同步 `authenticatedDevices`（仅密钥/展示元数据，**不镜像 ip/电量/在线**）；
     * 2. 重灌重连状态机与已知设备扫描器（core 侧为内存态、不落盘，不重灌则重启后不再自动重连）。
     */
    private fun loadAuthedDevices() {
        snapshotStore.refresh()
        val paired = snapshotStore.pairedSnapshots()
        for (snap in paired) {
            if (snap.uuid == uuid) continue
            synchronized(authenticatedDevices) {
                val existing = authenticatedDevices[snap.uuid]
                authenticatedDevices[snap.uuid] =
                    existing?.copy(isAccepted = true) ?: AuthInfo(
                        publicKey = "",
                        sharedSecret = "",
                        isAccepted = true,
                    )
            }
            if (snap.name.isNotEmpty()) {
                DeviceNameCache.updateGlobalDeviceName(snap.uuid, snap.name)
            }
        }
        targetRegistry.rehydrateFromCore(paired)
        // 首帧快照的寻址/展示元数据立即回填（刷新回调早于本表写入，需在此补一次）
        syncAuthenticatedDeviceMetadata(snapshotStore.snapshots())
    }

    // 保存已认证设备（密钥落盘由 Rust 自动；此处仅刷新快照与 UI 状态）
    override fun saveAuthedDevices() {
        snapshotStore.requestRefresh()
    }

    /**
     * 把 core 快照中的寻址/展示元数据回填到已认证设备表。
     *
     * 认证表记录的是「平台侧决策」（isAccepted / publicKey），但 lastIp / deviceType / displayName
     * 仍被超时回调（重新登记重连目标）与 DATA_FTP 处理（PC 判定）直接读取，因此必须随快照刷新，
     * 否则进程重启后这些字段会长期为空：超时重连登记退化为空操作、PC 发起的 FTP 请求被静默丢弃。
     *
     * 由 [DeviceSnapshotStore] 在每次刷新后回调，纯内存更新，不再触发额外刷新。
     */
    internal fun syncAuthenticatedDeviceMetadata(snapshots: Map<String, DeviceSnapshot>) {
        synchronized(authenticatedDevices) {
            for ((deviceUuid, snap) in snapshots) {
                if (!snap.paired) continue
                val auth = authenticatedDevices[deviceUuid] ?: continue
                val effectiveIp = snap.ip.takeIf { it.isNotEmpty() && it != "0.0.0.0" }
                val knownType = snap.deviceType.takeIf { it.isNotBlank() && it != DeviceSnapshot.UNKNOWN_DEVICE_TYPE }
                val knownName = snap.name.takeIf { it.isNotBlank() }
                val updated =
                    auth.copy(
                        displayName = knownName ?: auth.displayName,
                        lastIp = effectiveIp ?: auth.lastIp,
                        lastPort = snap.port,
                        deviceType = knownType ?: auth.deviceType,
                    )
                if (updated != auth) authenticatedDevices[deviceUuid] = updated
            }
        }
    }

    /**
     * 设备发现/连接/数据发送/接收，全部本地实现。
     */
    override val notificationDataReceivedCallbacks = mutableSetOf<(String) -> Unit>()

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

    /**
     * 设备状态流：key为uuid，value为(DeviceInfo, isOnline)。
     *
     * 数据由 [DeviceSnapshotStore] 从 core 快照派生，平台端不再维护第二份真相。
     */
    val devices: StateFlow<Map<String, Pair<DeviceInfo, Boolean>>> get() = snapshotStore.devices

    internal var uuid: String

    // 认证设备表，key为uuid
    internal val authenticatedDevices = mutableMapOf<String, AuthInfo>()

    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()

    override val authenticatedDeviceTable: MutableMap<String, AuthInfo>
        get() = authenticatedDevices

    override val rejectedDeviceIds: MutableSet<String>
        get() = rejectedDevices

    /** 握手结果等待器登记表（UI 挂起等待远端响应）。 */
    override val handshakeWaiters = HandshakeWaiterRegistry()

    // 本地 ECDH 公钥（Base64 编码的 65 字节未压缩点）
    override val localPublicKey: String

    internal val listenPort: Int = 23333
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val keepAlive = ConnectionKeepAlive(context, this, coroutineScope)
    private val discoveryManager = ConnectionDiscoveryManager(context, this, coroutineScope)

    /**
     * 系统状态广播监听：core 无主动拉取本机锁屏/电量能力，必须由平台采集后推送。
     */
    private val systemStateMonitor =
        SystemStateMonitor(
            context = context,
            localDisplayName = { getLocalDisplayName() },
            onLockStateChanged = { discoveryManager.syncHeartbeatMode() },
        )

    /** 在线且已配对设备列表的持久化缓存（供 scrcpy 等外部能力读取），由快照存储驱动。 */
    private val onlineDevicesCache = OnlineDevicesCache(context)

    /**
     * 设备状态的唯一真源消费端：消费 core 快照并派生 [devices]。
     * **其他模块只能通过它读取或触发刷新，不得自行维护设备副本。**
     */
    private val snapshotStore =
        DeviceSnapshotStore(
            scope = coroutineScope,
            localUuidProvider = { uuid },
            defaultPort = listenPort,
            onlineDevicesCache = onlineDevicesCache,
            onSnapshotRefreshed = { syncAuthenticatedDeviceMetadata(it) },
        )

    /** 同步查询入口（只读快照投影）。 */
    private val directory =
        DeviceDirectory(
            store = snapshotStore,
            localUuidProvider = { uuid },
            localDisplayNameProvider = { getLocalDisplayName() },
            localIpProvider = { getLocalIpAddress() },
            defaultPort = listenPort,
        )

    /** 平台端唯一向 core 登记连接目标的出口（重连状态机 / 已知设备扫描器）。 */
    private val targetRegistry = DeviceTargetRegistry(localUuidProvider = { uuid })

    /** 设备查询门面（只读；展示元数据来自 core 快照）。 */
    private val deviceQuery =
        DeviceQuery(
            directory = directory,
            store = snapshotStore,
            authenticatedDeviceTable = { synchronized(authenticatedDevices) { authenticatedDevices.toMap() } },
            rejectedDeviceIds = { synchronized(rejectedDevices) { rejectedDevices.toSet() } },
        )

    /** Rust core 上下文的一次性初始化与启动。 */
    private val rustSession = RustCoreSession(context)

    /** 配对流程的平台侧状态与收尾。 */
    private val pairingCoordinator =
        PairingCoordinator(
            registry = targetRegistry,
            snapshotStore = snapshotStore,
            authenticatedDeviceTable = authenticatedDevices,
            onAuthChanged = { saveAuthedDevices() },
        )

    /** Rust 心跳「状态查询」响应器（超级岛/媒体会话）。 */
    override val stateQueryResponder = StateQueryResponder(context)

    /**
     * 音频中继控制器：与设备连接解耦，仅注入「按 uuid 反查设备」与「发送 DATA_MEDIA_CONTROL」两个能力。
     */
    override val audioRelay =
        AudioRelayController(
            context = context,
            resolveDeviceInfo = { uuid -> resolveDeviceInfo(uuid, "", 23333) },
            sendMediaControl = { target, plaintext ->
                ProtocolSender.sendEncrypted(this, target, AudioRelayController.HEADER_MEDIA_CONTROL, plaintext)
            },
        )

    // 协议不兼容设备集合（收到对端协议不兼容标记时登记）
    override val incompatibleDeviceIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /** 移除已配对设备（含 core 侧密钥删除与目标注销）。 */
    private val deviceRemover =
        PairedDeviceRemover(
            context = context,
            scope = coroutineScope,
            registry = targetRegistry,
            snapshotStore = snapshotStore,
            stateQueryResponder = stateQueryResponder,
            incompatibleDeviceIds = incompatibleDeviceIds,
            authenticatedDeviceTable = authenticatedDevices,
        )

    // ==================== DeviceCallbackHost 实现（供 Rust 回调处理器使用） ====================

    override val callbackContext: Context
        get() = context

    override val callbackScope: CoroutineScope
        get() = coroutineScope

    override val localUuid: String
        get() = uuid

    override val deviceManager: DeviceConnectionManager
        get() = this

    override fun localDisplayName(): String = getLocalDisplayName()

    override fun resolveDevice(uuid: String): DeviceInfo? = resolveDeviceInfo(uuid, "", listenPort)

    override fun deviceInfoOf(uuid: String): DeviceInfo? = directory.find(uuid)

    override fun sendReject() {
        val ctx = rustContext ?: return
        val local = uuid
        coroutineScope.launch { NativeCore.sendReject(ctx, local) }
    }

    override fun sendAccept(deviceType: String) {
        val ctx = rustContext ?: return
        val local = uuid
        val pubKey = localPublicKey
        val localIp = getLocalIpAddress()
        val battery = BatteryUtils.getBatteryLevel(context)
        coroutineScope.launch { NativeCore.sendAccept(ctx, local, pubKey, localIp, battery, deviceType) }
    }

    override fun sendDataMessage(
        target: DeviceInfo,
        header: String,
        plaintext: String,
    ) {
        ProtocolSender.sendEncrypted(this, target, header, plaintext)
    }

    // === 以下为提供给内部组件使用的访问器（保持字段本身 private） ===
    internal fun lookupDevice(uuid: String): DeviceInfo? = getDeviceInfo(uuid)

    // Rust 原生上下文（由 RustCoreSession 创建并持有）
    private var rustContext: Pointer? = null

    // UI全局开关：是否启用设备发现，使用内存缓存避免频繁数据库访问
    var discoveryEnabled: Boolean
        get() = AppConfig.getUdpDiscoveryEnabled(context)
        set(value) {
            AppConfig.setUdpDiscoveryEnabled(context, value)
        }

    init {
        // 本机 UUID：
        // - 升级用户：SP 旧 uuid 即输入种子（迁移期间保持一致，经 start_core 写入 Rust 库）
        // - 全新安装：空值，由 Rust 生成/持有（库落盘），平台端不生成不存储
        val legacyUuid = rustSession.readLegacyUuid()
        uuid = legacyUuid
        // 兼容旧用户：首次运行时如无保存则默认true
        if (!AppConfig.getUdpDiscoveryEnabled(context)) {
            AppConfig.setUdpDiscoveryEnabled(context, true)
        }

        // 初始化 Rust 上下文、密钥与回调（持久化由 Rust 私有库管理）
        val init = rustSession.initialize(this, legacyUuid)
        rustContext = init.rustContext
        uuid = init.localUuid
        localPublicKey = init.localPublicKey
        loadAuthedDevices()

        // 统一启动核心：TCP、心跳调度、离线检测、发送队列、已知设备扫描、重连状态机
        val battery = SystemStateMonitor.signedBatteryLevel(context)
        rustSession.startCore(rustContext, uuid, localPublicKey, getLocalDisplayName(), battery, listenPort)?.let {
            systemStateMonitor.seedSignedBattery(it)
        }

        // 旧设备表迁移与平台存储清理（需在本机 uuid 已进入 Rust 后才可保证落盘）
        coroutineScope.launch {
            LegacyDeviceMigrator.migrateAndCleanup(context, init.legacyStateEnc, uuid)?.let { uuid = it }
            loadAuthedDevices()
        }
        // 电池/锁屏监听：电量与充电状态变化同步到 Rust 心跳调度器，锁屏变化切换心跳模式
        systemStateMonitor.register()
        discoveryManager.registerNetworkCallback()
        // 初始同步心跳模式（锁屏/WLAN直连 → TCP 备用，否则广播主用）
        discoveryManager.syncHeartbeatMode()
        // 自动重连已移交 Rust 重连状态机（loadAuthedDevices 中已登记认证设备）
    }

    // ==================== 设备快照刷新（唯一入口 → DeviceSnapshotStore） ====================

    /**
     * 异步触发设备快照刷新（JNA 回调线程安全：禁止在回调内同步调用 core 接口造成重入）。
     */
    override fun triggerDeviceListRefresh() {
        snapshotStore.requestRefresh()
    }

    /** 同步刷新设备快照（沿用既有回调语义，供超时/连接/断开回调使用）。 */
    override fun updateDeviceListNow() {
        snapshotStore.refresh()
    }

    /** 同步刷新设备快照（供内部组件使用）。 */
    internal fun refreshDevices() = snapshotStore.refresh()

    private fun getDeviceInfo(uuid: String): DeviceInfo? = directory.find(uuid)

    /**
     * 获取已配对设备列表（展示元数据来自 core 快照）。
     */
    fun getAuthenticatedDevices(): Map<String, AuthInfo> = deviceQuery.authenticated()

    /**
     * 获取已拒绝设备列表
     */
    fun getRejectedDevices(): Set<String> = deviceQuery.rejected()

    /** 待处理的配对请求（由 [PairingCoordinator] 持有）。 */
    override var pendingPairing: PendingPairing?
        get() = pairingCoordinator.pendingPairing
        set(value) {
            pairingCoordinator.pendingPairing = value
        }

    /**
     * 取消当前待处理的配对请求。
     */
    fun cancelPendingPairing() = pairingCoordinator.cancelPendingPairing()

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
    override fun completePairingWithLongTermKeys(
        uuid: String,
        remoteLtPubKey: String,
        displayName: String,
        lastIp: String?,
    ): Boolean = pairingCoordinator.completeWithLongTermKeys(uuid, remoteLtPubKey, displayName, lastIp)

    /**
     * 公开解析设备信息：优先使用快照，缺失IP时使用提供的回退IP。
     */
    fun resolveDeviceInfo(
        uuid: String,
        fallbackIp: String?,
        fallbackPort: Int = 23333,
    ): DeviceInfo? = deviceQuery.resolve(uuid, fallbackIp, fallbackPort)

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

    // 将设备登记到 Rust 重连状态机（先移除再添加，重置重试周期）
    override fun registerReconnectTarget(
        uuid: String,
        ip: String,
    ) = targetRegistry.registerReconnectTarget(uuid, ip)

    // 将设备登记到 Rust 已知设备扫描器（known_device_scanner）
    override fun registerKnownDevice(
        uuid: String,
        ip: String,
    ) = targetRegistry.registerKnownDevice(uuid, ip)

    // 重新登记所有已配对设备到 Rust 重连状态机（网络恢复后调用）
    internal fun refreshAllReconnectTargets() {
        targetRegistry.rehydrateFromCore(snapshotStore.pairedSnapshots())
    }

    // 获取本机 IP 地址
    private fun getLocalIpAddress(): String = NativeCore.getLocalIp() ?: "0.0.0.0"

    /**
     * 获取在线且已配对的设备数量（线程安全）。
     * 在线判定与配对状态均来自 core 快照。
     */
    fun getAuthenticatedOnlineCount(): Int = deviceQuery.onlineAuthenticatedCount()

    /**
     * 获取在线且已配对的设备列表（线程安全）。
     */
    fun getAuthenticatedOnlineDevices(): List<DeviceInfo> = deviceQuery.onlineAuthenticated()

    /**
     * 公开API：移除已认证设备（线程安全）。
     * - 从 Rust 上下文移除设备密钥（registry 状态随之清理，删除结果落库失败则中止）
     * - 从 Rust 重连状态机与已知设备扫描器移除（心跳调度随之停止）
     * - 断开会话（TCP 状态清理，避免已删设备经活跃连接重新出现在列表）
     * - 从内存中移除设备信息并刷新快照
     * 返回 true 表示存在并已移除，false 表示没有该uuid 或 Rust 持久化删除未完成
     */
    fun removeAuthenticatedDevice(
        uuid: String,
        deleteHistory: Boolean = false,
    ): Boolean {
        return deviceRemover.remove(uuid, deleteHistory)
    }

}
