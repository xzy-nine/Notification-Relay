package com.xzyht.notifyrelay.feature.device.service.callback

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.xzyht.notifyrelay.nativecore.NotifyRelayCore
import notifyrelay.base.util.Logger

/**
 * 设备生命周期与状态查询类 Rust 回调处理器：
 * `on_device_discovered` / `on_device_timeout` / `on_device_connected` /
 * `on_device_disconnected` / `on_state_query`。
 *
 * 关键约束：
 * - 这些回调运行在 Rust 扫描/心跳/连接线程上，同步调用 `nrc_get_device_list` 会与 core 重入，
 *   因此 `on_device_discovered` 通过 [DeviceCallbackHost.triggerDeviceListRefresh] 切到协程异步刷新。
 * - `on_device_timeout` / `on_device_connected` / `on_device_disconnected` 同样通过
 *   [DeviceCallbackHost.triggerDeviceListRefresh] 切到协程**异步**刷新，避免在 Rust 回调线程同步调用 core 造成重入。
 * - `on_state_query` 是唯一必须**同步**返回 0/1/2 的回调，
 *   因此直接委托给 [DeviceCallbackHost.stateQueryResponder]。
 *
 * 时序：
 * ```mermaid
 * sequenceDiagram
 *     participant Core as Rust Core 线程
 *     participant H as DeviceEventCallbackHandler
 *     participant Host as DeviceCallbackHost
 *     participant Store as 设备快照刷新
 *
 *     Core->>H: on_device_discovered / timeout / connected / disconnected
 *     H->>Host: triggerDeviceListRefresh()（异步，禁止同步调 core）
 *     Host->>Store: 协程内 nrc_get_device_list
 *     Core->>H: on_state_query（必须同步返回）
 *     H->>Host: stateQueryResponder.handle()
 *     Host-->>Core: 0=不存在 / 1=无变更 / 2=有变更
 * ```
 */
class DeviceEventCallbackHandler(
    private val host: DeviceCallbackHost,
) {
    companion object {
        private const val TAG = "CoreCb"
    }

    /** TCP 扫描发现：core 已完成自身过滤/名称解码/状态登记，平台端仅触发一次快照刷新。 */
    fun buildDiscovered(): NotifyRelayCore.OnDeviceDiscoveredCb =
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
                val remoteUuid = NotifyRelayCore.ptrToString(uuid) ?: return
                try {
                    Logger.d(
                        "死神-NotifyRelay",
                        "[on_device_discovered] uuid=$remoteUuid, ip=${NotifyRelayCore.ptrToString(ip) ?: ""}, port=$port, battery=$battery",
                    )
                    // 运行在 Rust 扫描线程：不得同步调用 nrc_get_device_list（会与 core 重入），
                    // 交由协程异步刷新
                    host.triggerDeviceListRefresh()
                } catch (e: Exception) {
                    Logger.e(TAG, "on_device_discovered error", e)
                }
            }
        }

    /** 心跳超时：重新登记重连目标并刷新快照，让 UI 立即反映离线状态。 */
    fun buildTimeout(): NotifyRelayCore.OnDeviceTimeoutCb =
        object : NotifyRelayCore.OnDeviceTimeoutCb {
            override fun invoke(
                uuidPtr: Pointer?,
                userData: Pointer?,
            ) {
                Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                // 超时离线状态由 Rust DeviceRegistry 维护，此处仅重新登记重连目标
                val auth = synchronized(host.authenticatedDeviceTable) { host.authenticatedDeviceTable[uuid] }
                if (auth != null) host.registerReconnectTarget(uuid, auth.lastIp ?: "")
                // 触发快照刷新，UI 立即反映离线状态（替代原 1s 轮询的离线更新职责）
                host.triggerDeviceListRefresh()
            }
        }

    /** TCP 连接建立：回填连接来源 IP 并刷新快照。 */
    fun buildConnected(): NotifyRelayCore.OnDeviceConnectedCb =
        object : NotifyRelayCore.OnDeviceConnectedCb {
            override fun invoke(
                uuidPtr: Pointer?,
                ipPtr: Pointer?,
                userData: Pointer?,
            ) {
                Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                if (uuid == host.localUuid) return
                // 连接来源 IP 由 core registry 维护，此处只需触发快照刷新
                // （替代原 1s 轮询的在线更新职责）
                host.triggerDeviceListRefresh()
            }
        }

    /** TCP 断开：立即刷新快照，UI 无需等 12 秒离线超时。 */
    fun buildDisconnected(): NotifyRelayCore.OnDeviceDisconnectedCb =
        object : NotifyRelayCore.OnDeviceDisconnectedCb {
            override fun invoke(
                uuidPtr: Pointer?,
                userData: Pointer?,
            ) {
                Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                if (uuid == host.localUuid) return
                host.triggerDeviceListRefresh()
            }
        }

    /** 超级岛/媒体心跳查询：必须同步返回 0/1/2。 */
    fun buildStateQuery(): NotifyRelayCore.OnStateQueryCb =
        object : NotifyRelayCore.OnStateQueryCb {
            override fun invoke(
                uuidPtr: Pointer?,
                featureIdPtr: Pointer?,
                isMedia: Int,
                userData: Pointer?,
            ): Int {
                Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                val remoteUuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return 0
                val featureId = NotifyRelayCore.ptrToString(featureIdPtr) ?: return 0
                return try {
                    host.stateQueryResponder.handle(remoteUuid, featureId, isMedia != 0)
                } catch (e: Exception) {
                    Logger.e(TAG, "on_state_query error: ${e.message}")
                    1 // 异常保守保活，等待下一次查询
                }
            }
        }
}
