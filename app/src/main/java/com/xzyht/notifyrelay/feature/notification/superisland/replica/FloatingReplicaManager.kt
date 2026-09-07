package com.xzyht.notifyrelay.feature.notification.superisland.replica

import android.content.Context
import com.xzyht.notifyrelay.feature.notification.superisland.notification.SuperIslandListManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.config.SuperIslandConfigUtils
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper

object FloatingReplicaManager {
    private const val TAG = "超级岛复刻实现骨架"

    private var appContext: Context? = null

    fun getDefaultFloatingWindowEnabled(): Boolean {
        val detailedOsVersion = PermissionHelper.getDetailedOsVersion()
        val isGreater = PermissionHelper.isVersionGreaterThan(detailedOsVersion, "OS3.0.300")
        return !isGreater
    }

    private fun isFloatingWindowEnabled(context: Context): Boolean = SuperIslandConfigUtils.isFloatingWindowEnabled(context)

    fun getAppContext(): Context? = appContext

    fun isSourceRecentlyClosed(sourceId: String): Boolean = FloatingReplicaMappingManager.isSourceRecentlyClosed(sourceId)

    fun showFloating(
        context: Context,
        sourceId: String,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null,
        appName: String? = null,
        isLocked: Boolean = false,
    ) {
        appContext = context.applicationContext
        FloatingReplicaMappingManager.setAppContext(appContext)

        val isRecentlyClosed = FloatingReplicaMappingManager.isSourceRecentlyClosed(sourceId)

        if (isFloatingWindowEnabled(context)) {
            if (isRecentlyClosed) {
                Logger.i(TAG, "超级岛: sourceId=$sourceId 在30秒内被关闭过，跳过浮窗展示")
                return
            }
            FloatingReplicaWindowManager.showFloatingInternal(context, sourceId, title, text, paramV2Raw, picMap, appName, isLocked, false)
        } else if (isRecentlyClosed && SuperIslandListManager.containsSourceId(sourceId)) {
            Logger.i(TAG, "超级岛: sourceId=$sourceId 在30秒内被关闭过且条目仍在列表中，跳过展示")
            return
        } else if (SuperIslandConfigUtils.isNotificationListMode(context)) {
            FloatingReplicaMappingManager.removeClosedSource(sourceId)
            Logger.i(TAG, "超级岛: 列表模式, sourceId=$sourceId")
            FloatingReplicaListModeManager.showFloatingListMode(context, sourceId, title, text, paramV2Raw, picMap, appName, isLocked)
        } else {
            FloatingReplicaMappingManager.removeClosedSource(sourceId)
            Logger.i(TAG, "超级岛: 浮窗功能已关闭，仅创建通知, sourceId=$sourceId")
            FloatingReplicaNotificationManager.sendNotification(context, sourceId, title, text, paramV2Raw, picMap, appName, isLocked)
        }
    }

    fun toggleFloating(
        context: Context,
        sourceId: String,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null,
        appName: String? = null,
    ) {
        FloatingReplicaWindowManager.toggleFloating(context, sourceId, title, text, paramV2Raw, picMap, appName)
    }

    fun closeByNotificationId(notificationId: Int) {
        runWithErrorHandling("根据通知ID关闭浮窗条目") {
            val ctx = appContext ?: return@runWithErrorHandling

            if (!isFloatingWindowEnabled(ctx) && SuperIslandConfigUtils.isNotificationListMode(ctx)) {
                FloatingReplicaListModeManager.closeListModeNotification(ctx, notificationId)
                return@runWithErrorHandling
            }

            FloatingReplicaNotificationManager.closeNotificationByNotificationId(ctx, notificationId)
        }
    }

    fun dismissBySource(sourceId: String) {
        FloatingReplicaWindowManager.dismissBySourceInternal(sourceId, FloatingWindowManager.RemovalReason.REMOTE)
    }

    private inline fun runWithErrorHandling(
        actionName: String,
        crossinline block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: $actionName 失败: ${e.message}")
        }
    }
}
