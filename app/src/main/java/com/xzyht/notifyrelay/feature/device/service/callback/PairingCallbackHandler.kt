package com.xzyht.notifyrelay.feature.device.service.callback

import android.os.Handler
import android.os.Looper
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.PendingPairing
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.nativecore.NotifyRelayCore
import com.xzyht.notifyrelay.sync.HeartbeatProcessor
import notifyrelay.base.util.Logger
import org.json.JSONObject

/**
 * `nrc_set_on_pairing_cb` 回调处理器。
 *
 * 负责配对/握手相关的全部消息类型：
 * - `HANDSHAKE`     对端发起握手（已配对设备由 Rust 自动 ACCEPT，平台只登记元数据）
 * - `PAIRING_INIT`  对端发起配对，转交 UI 弹出配对码框
 * - `PAIRING_RESP`  意外消息（平台上不应收到）
 * - `ACCEPT`        对端接受并回传长期公钥 → 完成配对
 * - `REJECT`        对端拒绝
 * - `RESULT`        本机发起的配对结果
 * - `HEARTBEAT_TCP` TCP 心跳（携带名称/电量/类型/IP）
 *
 * 时序（对端主动配对）：
 * ```mermaid
 * sequenceDiagram
 *     participant Remote as 远端设备
 *     participant Core as Rust Core
 *     participant H as PairingCallbackHandler
 *     participant UI as PairingCodeDialog
 *     participant W as HandshakeWaiterRegistry
 *
 *     Remote->>Core: PAIRING_INIT(spake2_pub, ip)
 *     Core->>H: on_pairing(uuid, "PAIRING_INIT", data)
 *     H->>H: pendingPairing = PendingPairing(...)
 *     H->>UI: handshakeRequestHandler.onPairingInitRequest()
 *     UI->>W: register(uuid)
 *     Remote->>Core: RESULT(ok)
 *     Core->>H: on_pairing(uuid, "RESULT", intValue)
 *     H->>Core: nrc_export_device_key(uuid)
 *     H->>H: completePairingWithLongTermKeys(uuid, ltPub)
 *     H->>W: resolve(uuid, true)
 * ```
 */
class PairingCallbackHandler(
    private val host: DeviceCallbackHost,
) {
    companion object {
        private const val TAG = "CoreCb"
    }

    fun build(): NotifyRelayCore.OnPairingCb =
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
                val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                val msgType = NotifyRelayCore.ptrToString(msgTypePtr) ?: return
                val data = NotifyRelayCore.ptrToString(dataPtr)
                val extra = NotifyRelayCore.ptrToString(extraPtr)

                try {
                    when (msgType) {
                        "HANDSHAKE" -> handleHandshake(uuid, data)
                        "PAIRING_INIT" -> handlePairingInit(uuid, data)
                        "PAIRING_RESP" -> Logger.w(TAG, "收到意外的 PAIRING_RESP: $uuid")
                        "ACCEPT" -> handleAccept(uuid, data)
                        "REJECT" -> handleReject(uuid)
                        "RESULT" -> handleResult(uuid, intValue, extra)
                        "HEARTBEAT_TCP" -> handleHeartbeatTcp(uuid, data, extra, intValue)
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "on_pairing error: ${e.message}")
                }
            }
        }

    /** 对端发起握手：登记元数据、重连目标与已知设备；按需回 ACCEPT/REJECT。 */
    private fun handleHandshake(
        uuid: String,
        data: String?,
    ) {
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

        synchronized(host.authenticatedDeviceTable) {
            val auth = host.authenticatedDeviceTable[uuid]
            if (auth != null) {
                host.authenticatedDeviceTable[uuid] = auth.copy(lastIp = ip)
                host.saveAuthedDevices()
            }
        }
        host.registerReconnectTarget(uuid, ip)
        host.registerKnownDevice(uuid, ip)

        // Rust 侧已对已配对设备自动发送 ACCEPT（auto_accept=true），
        // 平台侧仅做 UI 持久化/登记，无需重复发送 ACCEPT 或 REJECT
        if (autoAccept) {
            // 自动闭环分支同样清理不兼容标记并持久化 deviceType，避免标记残留
            synchronized(host.incompatibleDeviceIds) { host.incompatibleDeviceIds.remove(uuid) }
            synchronized(host.authenticatedDeviceTable) {
                val auth = host.authenticatedDeviceTable[uuid]
                if (auth != null && auth.deviceType != deviceType && deviceType != "unknown") {
                    host.authenticatedDeviceTable[uuid] = auth.copy(deviceType = deviceType)
                    host.saveAuthedDevices()
                }
            }
            Logger.d(TAG, "HANDSHAKE 已自动闭环(Rust auto_accept): $uuid")
            return
        }

        val alreadyAuthed =
            synchronized(host.authenticatedDeviceTable) {
                host.authenticatedDeviceTable[uuid]?.isAccepted == true
            }
        if (!alreadyAuthed) {
            host.sendReject()
            return
        }

        synchronized(host.authenticatedDeviceTable) {
            val existingAuth = host.authenticatedDeviceTable[uuid]
            if (existingAuth != null && existingAuth.publicKey != pubKey) {
                host.sendReject()
                Logger.w(TAG, "已认证设备公钥变化，要求重新配对: $uuid")
                return
            }
        }
        synchronized(host.incompatibleDeviceIds) { host.incompatibleDeviceIds.remove(uuid) }
        host.sendAccept(deviceType)
        synchronized(host.authenticatedDeviceTable) {
            val auth = host.authenticatedDeviceTable[uuid]
            if (auth != null) {
                host.authenticatedDeviceTable[uuid] = auth.copy(deviceType = deviceType, lastIp = ip)
                host.saveAuthedDevices()
            }
        }
    }

    /** 对端发起配对：暂存请求并转交 UI 展示配对码输入框。 */
    private fun handlePairingInit(
        uuid: String,
        data: String?,
    ) {
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
        synchronized(host.rejectedDeviceIds) {
            if (host.rejectedDeviceIds.contains(uuid)) {
                host.sendReject()
                return
            }
        }
        val existing = host.deviceInfoOf(uuid)
        val displayName = existing?.displayName?.takeIf { it.isNotBlank() } ?: "未知设备"
        host.pendingPairing = PendingPairing(remoteUuid = uuid, remotePubKey = spake2Pub, remoteIp = ip)
        val remoteDevice = DeviceInfo(uuid, displayName, ip, existing?.port ?: 23333)
        Handler(Looper.getMainLooper()).post {
            host.handshakeRequestHandler?.onPairingInitRequest(remoteDevice, spake2Pub)
        }
        Logger.d(TAG, "PAIRING_INIT 已处理: $uuid")
    }

    /** 对端接受：用长期公钥完成配对并唤醒等待者。 */
    private fun handleAccept(
        uuid: String,
        data: String?,
    ) {
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
        val ok = host.completePairingWithLongTermKeys(uuid, ltPubKey, lastIp = ip)
        host.handshakeWaiters.resolve(uuid, ok)
        if (ok) {
            // 登记已知设备（uuid+ip），心跳由 Rust 调度器自动启动
            host.registerKnownDevice(uuid, ip)
        }
        Logger.d(TAG, "ACCEPT 已处理: $uuid")
    }

    /** 对端拒绝：唤醒等待者并记入拒绝集合。 */
    private fun handleReject(uuid: String) {
        host.handshakeWaiters.resolve(uuid, false)
        synchronized(host.rejectedDeviceIds) {
            host.rejectedDeviceIds.add(uuid)
        }
        Logger.w(TAG, "REJECT 已处理: $uuid")
    }

    /** 本机发起的配对结果。 */
    private fun handleResult(
        uuid: String,
        intValue: Int,
        extra: String?,
    ) {
        if (intValue == 0) {
            Logger.w(TAG, "配对失败: $uuid, error=${extra ?: ""}")
            host.handshakeWaiters.resolve(uuid, false)
            return
        }
        Logger.d(TAG, "配对成功: $uuid")
        val keyJson = NativeCore.getContext()?.let { NativeCore.exportDeviceKey(it, uuid) }
        if (keyJson != null) {
            val ltPub = JSONObject(keyJson).optString("remote_pub_key", "")
            if (ltPub.isNotEmpty()) {
                host.completePairingWithLongTermKeys(uuid, ltPub)
                // 登记已知设备（uuid+ip），心跳由 Rust 调度器自动启动
                host.registerKnownDevice(uuid, host.deviceInfoOf(uuid)?.ip ?: "")
            }
        }
        host.handshakeWaiters.resolve(uuid, true)
    }

    /** TCP 心跳：把对端名称/电量/类型/IP 交给心跳处理器。 */
    private fun handleHeartbeatTcp(
        uuid: String,
        data: String?,
        extra: String?,
        intValue: Int,
    ) {
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
        if (info.uuid != host.localUuid) {
            HeartbeatProcessor.processHeartbeat(info, host.deviceManager)
        }
    }
}
