package com.xzyht.notifyrelay.feature.notification.superisland.replica

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.net.toUri
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingComposeContainer
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowLifecycleOwner
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.formatter.SuperIslandDataFormatter
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore
import com.xzyht.notifyrelay.feature.notification.superisland.lifecycle.LifecycleManager
import com.xzyht.notifyrelay.feature.notification.superisland.notification.LiveUpdatesNotificationManager
import com.xzyht.notifyrelay.feature.notification.superisland.notification.NotificationGenerator
import com.xzyht.notifyrelay.feature.notification.superisland.config.SuperIslandConfigUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import notifyrelay.base.util.IntentUtils
import notifyrelay.base.util.Logger
import java.lang.ref.WeakReference

object FloatingReplicaWindowManager {
    private const val TAG = "超级岛浮窗管理"
    private const val FIXED_WIDTH_DP = 320

    private val floatingWindowManager =
        FloatingWindowManager().apply {
            onEntriesEmpty = {
                removeOverlayContainer()
                NotificationGenerator.clearAllReplicaNotifications(overlayView?.get()?.context)
            }
            onEntryRemoved = { key, reason ->
                if (reason != FloatingWindowManager.RemovalReason.HIDDEN) {
                    val context = overlayView?.get()?.context
                    if (context != null) {
                        NotificationGenerator.cancelReplicaNotification(context, key)
                    } else {
                        FloatingReplicaMappingManager.removeNotificationId(key)
                    }
                    hiddenEntries.remove(key)
                } else {
                    Logger.i(TAG, "超级岛: 条目被隐藏 (HIDDEN)，保留系统通知以便恢复, key=$key")
                }

                val sourceIdsToBlock = FloatingReplicaMappingManager.removeSourceIdMapping(key)
                sourceIdsToBlock?.forEach { sourceId ->
                    FloatingReplicaMappingManager.handleRemovalReason(sourceId, reason)
                }
            }
        }

    private val lifecycleManager = LifecycleManager()
    private var overlayLifecycleOwner: FloatingWindowLifecycleOwner? = null

    private var overlayView: WeakReference<View>? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var windowManager: WeakReference<WindowManager>? = null

    private val hiddenEntries = mutableMapOf<String, Any>()

    init {
        FloatingReplicaMappingManager.setOverlayView(null)
    }

    fun getFloatingWindowManager(): FloatingWindowManager = floatingWindowManager

    fun isFloatingWindowEnabled(context: Context): Boolean = SuperIslandConfigUtils.isFloatingWindowEnabled(context)

    fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        runWithErrorHandling("请求悬浮窗权限") {
            val intent = IntentUtils.createImplicitIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = "package:${context.packageName}".toUri()
            IntentUtils.startActivity(context, intent, true)
        }
    }

    fun showFloatingInternal(
        context: Context,
        sourceId: String,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null,
        appName: String? = null,
        isLocked: Boolean = false,
        isRestoring: Boolean = false,
    ) {
        runWithErrorHandling("显示浮窗") {
            if (!isFloatingWindowEnabled(context)) {
                Logger.i(TAG, "超级岛: 浮窗功能已关闭，不创建浮窗, sourceId=$sourceId")
                return@runWithErrorHandling
            }

            if (!isRestoring && sourceId.isNotBlank() && FloatingReplicaMappingManager.isInstanceBlocked(sourceId)) {
                Logger.i(TAG, "超级岛: instanceId=$sourceId 已在本轮会话中被屏蔽，忽略展示")
                return@runWithErrorHandling
            }

            if (!canShowOverlay(context)) {
                Logger.i(TAG, "超级岛: 无悬浮窗权限，尝试请求权限")
                requestOverlayPermission(context)
                return@runWithErrorHandling
            }

            CoroutineScope(Dispatchers.Main).launch {
                runWithErrorHandlingSuspend("显示浮窗(协程)") {
                    val taskVersion = FloatingReplicaMappingManager.nextVersion(sourceId)

                    if (overlayLifecycleOwner == null) {
                        overlayLifecycleOwner = FloatingWindowLifecycleOwner()
                    }
                    lifecycleManager.onShow()

                    val internedPicMap =
                        withContext(Dispatchers.IO) {
                            SuperIslandImageStore.internAll(context, sourceId, picMap)
                        }

                    if (!FloatingReplicaMappingManager.isLatestVersion(sourceId, taskVersion)) {
                        return@runWithErrorHandlingSuspend
                    }

                    val formattedData = SuperIslandDataFormatter.formatForDisplay(context, paramV2Raw, internedPicMap)
                    val paramV2 = formattedData.paramV2

                    val summaryOnly =
                        when {
                            paramV2?.business == "miui_flashlight" -> true
                            paramV2Raw?.contains("miui_flashlight") == true -> true
                            else -> false
                        }

                    val entryKey = sourceId

                    val displayTitle =
                        title?.takeIf { it.isNotBlank() }
                            ?: paramV2?.highlightInfo?.title?.takeIf { it.isNotBlank() }
                            ?: paramV2?.baseInfo?.title?.takeIf { it.isNotBlank() }

                    val displayText =
                        text?.takeIf { it.isNotBlank() }
                            ?: paramV2?.highlightInfo?.content?.takeIf { it.isNotBlank() }
                            ?: paramV2?.baseInfo?.content?.takeIf { it.isNotBlank() }

                    // 记录更新前浮窗条目是否已存在：存在说明系统通知已发出过，保活包无变更时可跳过通知刷新
                    val entryExistedBefore = floatingWindowManager.getEntry(entryKey) != null

                    floatingWindowManager.addOrUpdateEntry(
                        key = entryKey,
                        paramV2 = paramV2,
                        paramV2Raw = formattedData.paramV2Raw,
                        picMap = formattedData.resolvedPicMap,
                        isExpanded = if (isLocked) false else !summaryOnly,
                        summaryOnly = summaryOnly,
                        business = paramV2?.business,
                        title = displayTitle,
                        text = displayText,
                        appName = appName,
                    )

                    FloatingReplicaMappingManager.addSourceIdMapping(sourceId, entryKey)

                    addOrUpdateEntry(context, entryKey, summaryOnly)

                    val isProgressType = SuperIslandDataFormatter.isProgressType(paramV2)

                    if (!isRestoring) {
                        // 内容与上次成功发出的通知一致且通知仍在展示时，跳过系统通知刷新（不调用 notify），
                        // 仅保留上方 addOrUpdateEntry 对内部撤回计时器（autoDismiss）的重置
                        val fingerprint =
                            FloatingReplicaMappingManager.computeNotificationFingerprint(
                                displayTitle,
                                displayText,
                                formattedData.paramV2Raw,
                                formattedData.resolvedPicMap,
                            )
                        val canSkipRefresh =
                            entryExistedBefore &&
                                fingerprint == FloatingReplicaMappingManager.getNotificationFingerprint(sourceId)

                        if (canSkipRefresh) {
                            Logger.i(TAG, "超级岛: 内容无变更，跳过系统通知刷新，仅重置内部撤回计时器: sourceId=$sourceId")
                        } else if (isProgressType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                            runWithErrorHandlingSuspend("发送Live Updates复合通知") {
                                LiveUpdatesNotificationManager.initialize(context)
                                val success = LiveUpdatesNotificationManager.showLiveUpdate(
                                    sourceId,
                                    displayTitle,
                                    displayText,
                                    appName,
                                    formattedData,
                                )
                                val liveUpdateNotificationId = sourceId.hashCode().and(0xffff) + 10000
                                FloatingReplicaMappingManager.putNotificationId(entryKey, liveUpdateNotificationId)
                                FloatingReplicaMappingManager.addSourceIdMapping(sourceId, entryKey, liveUpdateNotificationId)
                                // 仅在确认发出成功后记录指纹，发送异常被吞时留空，避免后续保活包被误跳过
                                if (success) {
                                    FloatingReplicaMappingManager.setNotificationFingerprint(sourceId, fingerprint)
                                }
                                Logger.i(TAG, "浮窗创建时发送Live Updates复合通知作为生命周期管理: sourceId=$sourceId, notificationId=$liveUpdateNotificationId")
                            }
                        } else {
                            val notificationId = NotificationGenerator.sendReplicaNotification(context, entryKey, displayTitle, displayText, appName, formattedData.paramV2, formattedData.paramV2Raw, formattedData.resolvedPicMap, sourceId, floatingWindowManager)
                            FloatingReplicaMappingManager.addSourceIdMapping(sourceId, entryKey, notificationId)
                            if (notificationId != null) {
                                FloatingReplicaMappingManager.setNotificationFingerprint(sourceId, fingerprint)
                            }
                            Logger.i(TAG, "浮窗创建时发送传统复刻通知: sourceId=$sourceId, notificationId=$notificationId")
                        }
                    } else {
                        Logger.i(TAG, "浮窗从隐藏状态恢复，不重新发送通知，使用现有的通知: sourceId=$sourceId")
                    }
                }
            }
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
        if (!isFloatingWindowEnabled(context)) {
            if (SuperIslandConfigUtils.isNotificationListMode(context)) {
                Logger.i(TAG, "超级岛: 列表模式 - 切换到下一条通知, sourceId=$sourceId")
                FloatingReplicaListModeManager.switchNotificationInList(context.applicationContext)
            } else {
                Logger.i(TAG, "超级岛: 浮窗功能已关闭，不处理浮窗状态切换, sourceId=$sourceId")
            }
            return
        }

        runWithErrorHandling("切换浮窗状态") {
            val entryKeys = FloatingReplicaMappingManager.getSourceIdEntryKeys(sourceId)
            val isShowing = entryKeys?.any { floatingWindowManager.getEntry(it) != null } == true

            if (isShowing) {
                Logger.i(TAG, "超级岛: 点击通知切换 - 隐藏浮窗, sourceId=$sourceId")

                val entry = floatingWindowManager.getEntry(sourceId)
                if (entry != null) {
                    hiddenEntries[sourceId] = entry
                    FloatingReplicaMappingManager.saveHiddenEntry(sourceId, entry)
                    Logger.i(TAG, "超级岛: 保存被隐藏的条目到 hiddenEntries, key=$sourceId")
                }

                dismissBySourceInternal(sourceId, FloatingWindowManager.RemovalReason.HIDDEN)
            } else {
                Logger.i(TAG, "超级岛: 点击通知切换 - 恢复浮窗, sourceId=$sourceId")
                FloatingReplicaMappingManager.removeBlockedInstance(sourceId)

                val existingEntry = FloatingReplicaMappingManager.getHiddenEntry(sourceId)
                if (existingEntry != null) {
                    showFloatingInternal(
                        context,
                        sourceId,
                        existingEntry.title,
                        existingEntry.text,
                        existingEntry.paramV2Raw,
                        existingEntry.picMap,
                        existingEntry.appName,
                        isLocked = false,
                        isRestoring = true,
                    )
                    FloatingReplicaMappingManager.removeHiddenEntry(sourceId)
                    hiddenEntries.remove(sourceId)
                    Logger.i(TAG, "超级岛: 从 hiddenEntries 中移除已恢复的条目, key=$sourceId")
                } else {
                    showFloatingInternal(
                        context,
                        sourceId,
                        title,
                        text,
                        paramV2Raw,
                        picMap,
                        appName,
                        isLocked = false,
                        isRestoring = true,
                    )
                }
            }
        }
    }

    fun dismissBySourceInternal(
        sourceId: String,
        reason: FloatingWindowManager.RemovalReason = FloatingWindowManager.RemovalReason.REMOTE,
    ) {
        runWithErrorHandling("按来源关闭浮窗") {
            if (FloatingReplicaMappingManager.isSourceRecentlyClosedWithinMinute(sourceId)) {
                Logger.i(TAG, "dismissBySourceInternal: sourceId=$sourceId 最近已关闭过，跳过")
                return@runWithErrorHandling
            }

            if (reason != FloatingWindowManager.RemovalReason.HIDDEN) {
                FloatingReplicaMappingManager.markSourceClosed(sourceId)
            }

            FloatingReplicaMappingManager.cancelTimeoutJob(sourceId)
            Logger.i(TAG, "dismissBySourceInternal: 清理超时任务, sourceId=$sourceId")

            NotificationGenerator.stopScrollUpdate(sourceId)
            Logger.i(TAG, "dismissBySourceInternal: 已停止滚动更新, sourceId=$sourceId")

            val ctx = FloatingReplicaMappingManager.getAppContext()
            if (ctx != null && !isFloatingWindowEnabled(ctx) && SuperIslandConfigUtils.isNotificationListMode(ctx)) {
                FloatingReplicaListModeManager.dismissFromList(ctx, sourceId)
                if (reason == FloatingWindowManager.RemovalReason.REMOTE || reason == FloatingWindowManager.RemovalReason.TIMEOUT) {
                    FloatingReplicaMappingManager.removeBlockedInstance(sourceId)
                }
                Logger.i(TAG, "dismissBySourceInternal: 列表模式处理完成, sourceId=$sourceId")
                return@runWithErrorHandling
            }

            val floatingEnabled = if (ctx != null) isFloatingWindowEnabled(ctx) else true

            val notificationIdsBefore = FloatingReplicaMappingManager.getNotificationIdsBySourceId(sourceId)
            val entryKeys = FloatingReplicaMappingManager.getSourceIdEntryKeys(sourceId)
            Logger.i(TAG, "dismissBySourceInternal: sourceId=$sourceId, floatingEnabled=$floatingEnabled, notificationIdsBefore=$notificationIdsBefore, entryKeys=$entryKeys")

            if (floatingEnabled) {
                if (entryKeys != null) {
                    entryKeys.forEach { entryKey ->
                        floatingWindowManager.removeEntry(entryKey, reason)
                    }
                    FloatingReplicaMappingManager.removeSourceIdMappings(sourceId)
                } else {
                    floatingWindowManager.removeEntry(sourceId, reason)
                }
            }

            FloatingReplicaNotificationManager.closeNotificationsBySourceId(sourceId, reason, notificationIdsBefore, entryKeys, ctx)
        }
    }

    fun removeOverlayContainer() {
        runWithErrorHandling("移除浮窗容器") {
            val view = overlayView?.get()
            val wm = windowManager?.get()
            val lp = overlayLayoutParams

            if (view != null && wm != null && lp != null) {
                wm.removeView(view)
                Logger.i(TAG, "超级岛: 浮窗容器已移除")

                overlayView = null
                overlayLayoutParams = null
                windowManager = null

                lifecycleManager.onHide()

                overlayLifecycleOwner?.let {
                    try {
                        it.onHide()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        overlayView = null
        overlayLayoutParams = null
        windowManager = null
        FloatingReplicaMappingManager.setOverlayView(null)
    }

    private fun addOrUpdateEntry(
        context: Context,
        key: String,
        summaryOnly: Boolean,
    ) {
        runWithErrorHandling("addOrUpdateEntry") {
            if (overlayView?.get() == null || windowManager?.get() == null || overlayLayoutParams == null) {
                runWithErrorHandling("创建浮窗容器") {
                    val appCtx = context.applicationContext
                    val wm =
                        appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                            ?: return@runWithErrorHandling

                    val lifecycleOwner =
                        overlayLifecycleOwner ?: FloatingWindowLifecycleOwner().also {
                            overlayLifecycleOwner = it
                        }
                    try {
                        lifecycleOwner.onShow()
                    } catch (_: Exception) {
                    }
                    lifecycleManager.onShow()

                    val density = context.resources.displayMetrics.density
                    val layoutParams =
                        WindowManager
                            .LayoutParams(
                                (FIXED_WIDTH_DP * density).toInt(),
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                PixelFormat.TRANSLUCENT,
                            ).apply {
                                gravity = Gravity.START or Gravity.TOP
                                x = ((context.resources.displayMetrics.widthPixels - (FIXED_WIDTH_DP * density).toInt()) / 2).coerceAtLeast(0)
                                y = 100
                            }

                    val composeContainer =
                        FloatingComposeContainer(context).apply {
                            val padding = (12 * density).toInt()
                            setPadding(padding, padding, padding, padding)
                            this.floatingWindowManager = this@FloatingReplicaWindowManager.floatingWindowManager
                            this.lifecycleOwner = lifecycleOwner
                            this.windowManager = wm
                            this.windowLayoutParams = layoutParams
                            this.onEntryClick = { entryKey -> onEntryClicked(entryKey) }
                            this.onContainerDragStart = { onContainerDragStarted() }
                            this.onContainerDragging = { }
                            this.onContainerDragEnd = { onContainerDragEnded() }
                        }

                    var added = false
                    runWithErrorHandling("addView") {
                        wm.addView(composeContainer, layoutParams)
                        added = true
                    }
                    if (added) {
                        overlayView = WeakReference(composeContainer)
                        overlayLayoutParams = layoutParams
                        windowManager = WeakReference(wm)
                        FloatingReplicaMappingManager.setOverlayView(composeContainer)
                        Logger.i(TAG, "超级岛: 浮窗容器已创建(首条条目触发)，x=${layoutParams.x}, y=${layoutParams.y}")
                    }
                }
            }
        }
    }

    private fun onEntryClicked(key: String) {
        val entry = floatingWindowManager.getEntry(key)
        if (entry == null) {
            return
        }
        floatingWindowManager.toggleEntryExpanded(key)
    }

    private fun onContainerDragStarted() {
    }

    private fun onContainerDragEnded() {
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

    private suspend inline fun runWithErrorHandlingSuspend(
        actionName: String,
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: $actionName 失败: ${e.message}")
        }
    }
}
