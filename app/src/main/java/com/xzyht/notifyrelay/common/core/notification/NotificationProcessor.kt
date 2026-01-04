package com.xzyht.notifyrelay.common.core.notification

import android.content.Context
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 远程通知处理管线（单条通知级别，不负责网络收发）。
 */
object NotificationProcessor {

    private const val TAG = "NotificationProcessor"

    data class NotificationInput(
        val header: String?,
        val rawData: String,
        val sharedSecret: String?,
        val remoteUuid: String?,
    )

    fun process(
        context: Context,
        manager: DeviceConnectionManager,
        scope: CoroutineScope,
        input: NotificationInput,
        notificationCallbacks: Collection<(String) -> Unit>
    ) {
        val (header, data, sharedSecret, remoteUuid) = input

        val decrypted = if (sharedSecret != null) {
            try {
                manager.decryptDataInternal(data, sharedSecret)
            } catch (e: Exception) {
                Logger.e(TAG, "解密失败: ${e.message}")
                data
            }
        } else data

        handleJsonLevel(context, manager, header, decrypted, sharedSecret, remoteUuid)

        handleFilterAndReplicate(context, manager, scope, decrypted, remoteUuid)

        notificationCallbacks.forEach { callback ->
            try { callback.invoke(decrypted) } catch (e: Exception) { Logger.e(TAG, "调用UI层回调失败: ${e.message}") }
        }
    }

    private fun handleJsonLevel(
        context: Context,
        manager: DeviceConnectionManager,
        header: String?,
        decrypted: String,
        sharedSecret: String?,
        remoteUuid: String?
    ) {
        try {
            if (remoteUuid != null) {
                val json = org.json.JSONObject(decrypted)
                val pkg = json.optString("packageName")
                val appName = json.optString("appName")
                val title = json.optString("title")
                val text = json.optString("text")
                val time = json.optLong("time", System.currentTimeMillis())

                val installedPkgs = com.xzyht.notifyrelay.common.core.repository.AppRepository.getInstalledPackageNamesSync(context)
                val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(pkg.orEmpty(), installedPkgs)

                try {
                    NotificationRepository.addRemoteNotification(mappedPkg, appName, title, text, time, remoteUuid, context)
                    NotificationRepository.scanDeviceList(context)
                } catch (e: Exception) {
                    Logger.e(TAG, "存储远程通知失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleJsonLevel异常: ${e.message}")
        }
    }

    private fun handleFilterAndReplicate(
        context: Context,
        manager: DeviceConnectionManager,
        scope: CoroutineScope,
        decrypted: String,
        remoteUuid: String?
    ) {
        val result = com.xzyht.notifyrelay.feature.device.repository.remoteNotificationFilter(decrypted, context)

        if (result.shouldShow) {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val localIsLocked = keyguardManager.isKeyguardLocked

            if (result.needsDelay && localIsLocked) {
                handleLockedScreenDelayed(context, scope, result)
                ChatMemory.append(context, "收到: ${result.rawData}")
            } else {
                scope.launch {
                    com.xzyht.notifyrelay.feature.device.repository.replicateNotification(context, result, null, startMonitoring = true)
                }

                if (remoteUuid != null) {
                    try {
                        val sourceDevice = manager.getDeviceInfoInternal(remoteUuid)
                        if (sourceDevice != null) {
                            com.xzyht.notifyrelay.common.core.sync.IconSyncManager.checkAndSyncIcon(
                                context,
                                result.mappedPkg,
                                manager,
                                sourceDevice
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "图标同步检查失败", e)
                    }
                }
            }
        } else {
            ChatMemory.append(context, "收到: ${result.rawData}")
        }
    }

    private fun handleLockedScreenDelayed(
        context: Context,
        scope: CoroutineScope,
        result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult
    ) {
        try {
            if (com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.enableDeduplication) {
                com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addPlaceholder(result.title, result.text, result.mappedPkg, 15_000L)
            }
        } catch (_: Exception) {}

        scope.launch {
            try {
                val waitMs = 15_000L
                delay(waitMs)

                val localList = NotificationRepository.getNotificationsByDevice("本机")
                fun normalizeTitleLocal(t: String?): String {
                    if (t == null) return ""
                    val prefixPattern = Regex("^\\([^)]+\\)")
                    return t.replace(prefixPattern, "").trim()
                }

                val normalizedPendingTitle = normalizeTitleLocal(result.title)
                val pendingText = result.text
                val duplicateFound = localList.any { nr ->
                    try {
                        nr.device == "本机" && normalizeTitleLocal(nr.title) == normalizedPendingTitle && (nr.text ?: "") == (pendingText ?: "")
                    } catch (_: Exception) { false }
                }

                if (!duplicateFound) {
                    val placeholderStillExists = try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.isPlaceholderPresent(result.title, result.text, result.mappedPkg) } catch (e: Exception) { true }

                    if (!placeholderStillExists) {
                        // 占位被移除，跳过复刻
                    } else {
                        try {
                            com.xzyht.notifyrelay.feature.device.repository.replicateNotification(context, result, null, startMonitoring = false)
                        } catch (e: Exception) {
                            Logger.e("智能去重", "锁屏延迟复刻执行复刻时发生错误", e)
                        } finally {
                            try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
                        }
                    }
                } else {
                    try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Logger.e("智能去重", "锁屏延迟复刻异常", e)
                try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
            }
        }
    }
}
