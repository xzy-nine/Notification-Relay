package com.xzyht.notifyrelay.feature.device.service.pairing

import com.xzyht.notifyrelay.feature.device.model.AuthInfo
import com.xzyht.notifyrelay.feature.device.model.PendingPairing
import com.xzyht.notifyrelay.feature.device.service.state.DeviceSnapshotStore
import com.xzyht.notifyrelay.feature.device.service.state.DeviceTargetRegistry
import com.xzyht.notifyrelay.nativecore.NativeCore
import notifyrelay.base.util.Logger

/**
 * 配对流程的平台侧状态与收尾。
 *
 * 职责：
 * - 暂存待确认的配对请求（[pendingPairing]，由 UI 读取并展示配对码输入框）；
 * - 取消待处理配对（同时清理 core 侧配对码）；
 * - 配对码校验通过后，用**长期** ECDH 密钥完成密钥交换并登记设备目标。
 *
 * 说明：密钥派生与持有全部在 core（`nrc_ecdh_derive_shared_secret` + Keystore），
 * 平台端只在 [authenticatedDeviceTable] 中记录 `isAccepted` / `publicKey` 用于「公钥变更需重新配对」判定。
 */
class PairingCoordinator(
    private val registry: DeviceTargetRegistry,
    private val snapshotStore: DeviceSnapshotStore,
    private val authenticatedDeviceTable: MutableMap<String, AuthInfo>,
    private val onAuthChanged: () -> Unit,
) {
    companion object {
        private const val TAG = "死神-NotifyRelay"
    }

    private val pendingLock = Any()
    private var pending: PendingPairing? = null

    /** 待处理的配对请求。 */
    var pendingPairing: PendingPairing?
        get() = synchronized(pendingLock) { pending }
        set(value) {
            synchronized(pendingLock) { pending = value }
        }

    /**
     * 取消当前待处理的配对请求，并清理 core 侧配对码。
     */
    fun cancelPendingPairing() {
        val current = pendingPairing
        if (current != null) {
            Logger.d(TAG, "取消配对: ${current.remoteUuid}")
        }
        pendingPairing = null
        NativeCore.getContext()?.let { NativeCore.clearPairingCode(it) }
    }

    /**
     * 使用长期 ECDH 密钥完成标准密钥交换（配对码校验通过后调用）。
     *
     * @param uuid 远端设备 UUID
     * @param remoteLtPubKey 远端长期 ECDH 公钥 Base64
     * @param displayName 设备显示名称
     * @param lastIp 设备 IP（用于登记重连/扫描目标）
     * @return 是否成功
     */
    fun completeWithLongTermKeys(
        uuid: String,
        remoteLtPubKey: String,
        displayName: String,
        lastIp: String?,
    ): Boolean {
        return try {
            val ctx = NativeCore.getContext()
            if (ctx != null && !NativeCore.deriveSharedSecret(ctx, uuid, remoteLtPubKey)) {
                Logger.e(TAG, "长期密钥派生失败: $uuid")
                return false
            }
            synchronized(authenticatedDeviceTable) {
                authenticatedDeviceTable[uuid] =
                    AuthInfo(
                        publicKey = remoteLtPubKey,
                        sharedSecret = "",
                        isAccepted = true,
                        displayName = displayName,
                        lastIp = lastIp,
                    )
            }
            onAuthChanged()
            registry.registerReconnectTarget(uuid, lastIp ?: "")
            // 不能同步刷新——本方法可能从 Rust nrc_connect_device 的回调中触发，
            // 同步调用 nrc_get_device_list 会导致 Rust 核心重入崩溃
            snapshotStore.requestRefresh()
            Logger.d(TAG, "长期密钥配对完成: $uuid")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "长期密钥配对失败: $uuid", e)
            false
        }
    }
}
