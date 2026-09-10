package com.xzyht.notifyrelay.feature.device.service.state

import android.content.Context
import com.xzyht.notifyrelay.feature.device.model.AuthInfo
import com.xzyht.notifyrelay.feature.device.service.statequery.StateQueryResponder
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import notifyrelay.data.database.repository.DatabaseRepository

/**
 * 移除已配对设备。
 *
 * 顺序（顺序即一致性保证）：
 * 1. 清除协议不兼容标记与状态查询缓存；
 * 2. `nrc_remove_device` 删除 core 侧密钥与 registry 状态 —— **落库失败则中止**，
 *    否则内存已删但库未同步，重启后设备会经旧 state/行复活，造成两端不一致；
 * 3. 断开会话、移除重连与扫描目标（心跳调度随之停止）；
 * 4. 移除平台侧内存记录与快照投影，异步清理关联数据。
 */
class PairedDeviceRemover(
    private val context: Context,
    private val scope: CoroutineScope,
    private val registry: DeviceTargetRegistry,
    private val snapshotStore: DeviceSnapshotStore,
    private val stateQueryResponder: StateQueryResponder,
    private val incompatibleDeviceIds: MutableSet<String>,
    private val authenticatedDeviceTable: MutableMap<String, AuthInfo>,
) {
    companion object {
        private const val TAG = "死神-NotifyRelay"
    }

    /**
     * @return true 表示该设备存在并已移除；
     * false 表示没有该 uuid，或 core 持久化删除未完成（已中止平台侧清理）
     */
    fun remove(
        uuid: String,
        deleteHistory: Boolean = false,
    ): Boolean {
        try {
            var existed = false

            try {
                incompatibleDeviceIds.remove(uuid)
            } catch (_: Exception) {
            }

            val ctx = NativeCore.getContext()
            if (ctx != null && !NativeCore.removeDevice(ctx, uuid)) {
                Logger.w(TAG, "removeAuthenticatedDevice: Rust 删除未完成，中止平台侧清理: $uuid")
                return false
            }

            registry.removeAllTargets(uuid)

            synchronized(authenticatedDeviceTable) {
                if (authenticatedDeviceTable.containsKey(uuid)) {
                    // 删除设备迁移残留行（若旧表数据尚未迁移）及关联数据
                    scope.launch {
                        val repository = DatabaseRepository.getInstance(context)
                        repository.deleteDeviceMigrationByUuid(uuid)
                        if (deleteHistory) {
                            repository.deleteNotificationsByDevice(uuid)
                            repository.deleteAppDeviceAssociationsByDeviceUuid(uuid)
                        }
                    }
                    authenticatedDeviceTable.remove(uuid)
                    existed = true
                }
            }

            // 清理快照投影与状态查询缓存
            snapshotStore.forget(uuid)
            stateQueryResponder.removeKeysForDevice(uuid)
            snapshotStore.requestRefresh()
            return existed
        } catch (e: Exception) {
            Logger.w(TAG, "removeAuthenticatedDevice failed: ${e.message}")
            return false
        }
    }
}
