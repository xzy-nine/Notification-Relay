package com.xzyht.notifyrelay.feature.device.service.callback

import android.content.Context
import com.xzyht.notifyrelay.feature.device.model.AuthInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.PendingPairing
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.audio.AudioRelayController
import com.xzyht.notifyrelay.feature.device.service.pairing.HandshakeWaiterRegistry
import com.xzyht.notifyrelay.feature.device.service.statequery.StateQueryResponder
import kotlinx.coroutines.CoroutineScope

/**
 * Rust/JNA 回调处理器所需的宿主能力。
 *
 * 由 [DeviceConnectionManager] 实现。回调处理器只通过本接口访问宿主状态，
 * 不直接依赖宿主的具体实现细节。
 *
 * 注意：所有 `on_*` 回调都运行在 **JNA 附加线程**（非主线程、非协程），因此：
 * - 回调首行必须调用 `Native.detach(false)`；
 * - 回调内同步调用 `nrc_get_device_list` 会与 core 重入，优先走 [triggerDeviceListRefresh]（协程异步）；
 *   [updateDeviceListNow] 为同步版本，仅用于沿用既有语义的超时/连接/断开回调。
 */
interface DeviceCallbackHost {
    /** 应用上下文（供各 Processor 与系统服务使用）。 */
    val callbackContext: Context

    /** IO 协程作用域：回调线程内需要异步执行的操作都投递到这里。 */
    val callbackScope: CoroutineScope

    /** 本机 uuid。 */
    val localUuid: String

    /** 本机长期 ECDH 公钥（Base64 编码的 65 字节未压缩点）。 */
    val localPublicKey: String

    /** 已认证设备表（key=uuid，可直接读写）。 */
    val authenticatedDeviceTable: MutableMap<String, AuthInfo>

    /** 被用户拒绝的设备集合。 */
    val rejectedDeviceIds: MutableSet<String>

    /** 协议不兼容设备集合。 */
    val incompatibleDeviceIds: MutableSet<String>

    /** 待处理的配对请求。 */
    var pendingPairing: PendingPairing?

    /** 配对请求 UI 处理器（收到 PAIRING_INIT 时通过它弹出配对码框）。 */
    var handshakeRequestHandler: HandshakeRequestHandler?

    /** 握手结果等待器登记表。 */
    val handshakeWaiters: HandshakeWaiterRegistry

    /** 通知数据接收回调集合。 */
    val notificationDataReceivedCallbacks: Collection<(String) -> Unit>

    /** 音频中继控制器。 */
    val audioRelay: AudioRelayController

    /** 状态查询响应器。 */
    val stateQueryResponder: StateQueryResponder

    /** 设备连接管理器本体（各 Processor 以它作为回调宿主）。 */
    val deviceManager: DeviceConnectionManager

    fun localDisplayName(): String

    /** 按 uuid 反查设备信息。 */
    fun resolveDevice(uuid: String): DeviceInfo?

    /**
     * 按 uuid 反查当前设备信息（来自 core 快照投影；未知返回 null）。
     *
     * 用于构造 UI 展示对象，**不要**用它回写设备状态——状态一律由 core 快照决定。
     */
    fun deviceInfoOf(uuid: String): DeviceInfo?

    /** 持久化/刷新已认证设备状态。 */
    fun saveAuthedDevices()

    /** 异步触发设备快照刷新（切到协程，回调线程内安全）。 */
    fun triggerDeviceListRefresh()

    /**
     * 同步刷新设备快照（沿用既有语义，供超时/连接/断开回调使用）。
     *
     * ⚠ 会同步调用 `nrc_get_device_list`；仅在与既有行为一致处使用，
     * 新代码优先使用 [triggerDeviceListRefresh]。
     */
    fun updateDeviceListNow()

    /** 登记 Rust 重连状态机目标。 */
    fun registerReconnectTarget(
        uuid: String,
        ip: String,
    )

    /** 登记 Rust 已知设备扫描器目标。 */
    fun registerKnownDevice(
        uuid: String,
        ip: String,
    )

    /** 用长期密钥完成配对。 */
    fun completePairingWithLongTermKeys(
        uuid: String,
        remoteLtPubKey: String,
        displayName: String = "未知设备",
        lastIp: String? = null,
    ): Boolean

    /** 回 ACCEPT（宿主补齐本机 IP / 电量 / 公钥；目标为当前握手会话对端）。 */
    fun sendAccept(deviceType: String)

    /** 回 REJECT（目标为当前握手会话对端）。 */
    fun sendReject()

    /** 向指定设备发送一条加密数据报文。 */
    fun sendDataMessage(
        target: DeviceInfo,
        header: String,
        plaintext: String,
    )
}
