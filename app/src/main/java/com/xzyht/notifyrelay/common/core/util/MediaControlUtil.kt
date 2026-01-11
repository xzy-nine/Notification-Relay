package com.xzyht.notifyrelay.common.core.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import com.xzyht.notifyrelay.common.core.notification.servers.NotifyRelayNotificationListenerService

object MediaControlUtil {

    /**
     * 旧的基于 `Context` 的接口已不再尝试获取系统级媒体控制权限（MEDIA_CONTENT_CONTROL），
     * 因为普通 App 无法稳定获取该权限。建议从通知监听服务中提取按钮的 `PendingIntent` 并调用对应重载方法。
     */
    fun playPause(context: Context) {
        try {
            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
            if (sbn != null) {
                triggerPlayPauseFromNotification(sbn)
                return
            }
        } catch (_: Exception) {}
        Logger.w("MediaControlUtil", "playPause(context): 未找到媒体通知，无法触发；请使用 PendingIntent 触发媒体操作或确保 NotificationListenerService 已启用")
    }

    /**
     * 通过 Notification 的 `PendingIntent` 触发播放/暂停
     */
    fun playPause(pendingIntent: PendingIntent) {
        sendPendingIntentSafe(pendingIntent)
    }

    fun next(context: Context) {
        try {
            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
            if (sbn != null) {
                triggerNextFromNotification(sbn)
                return
            }
        } catch (_: Exception) {}
        Logger.w("MediaControlUtil", "next(context): 未找到媒体通知，无法触发；请使用 PendingIntent 触发媒体操作或确保 NotificationListenerService 已启用")
    }

    /**
     * 通过 Notification 的 `PendingIntent` 触发下一首
     */
    fun next(pendingIntent: PendingIntent) {
        sendPendingIntentSafe(pendingIntent)
    }

    fun previous(context: Context) {
        try {
            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
            if (sbn != null) {
                triggerPreviousFromNotification(sbn)
                return
            }
        } catch (_: Exception) {}
        Logger.w("MediaControlUtil", "previous(context): 未找到媒体通知，无法触发；请使用 PendingIntent 触发媒体操作或确保 NotificationListenerService 已启用")
    }

    /**
     * 通过 Notification 的 `PendingIntent` 触发上一首
     */
    fun previous(pendingIntent: PendingIntent) {
        sendPendingIntentSafe(pendingIntent)
    }

    /**
     * 安全发送 `PendingIntent`，用于模拟通知按钮点击以实现媒体控制
     */
    private fun sendPendingIntentSafe(pendingIntent: PendingIntent) {
        try {
            pendingIntent.send()
        } catch (e: PendingIntent.CanceledException) {
            Logger.e("MediaControlUtil", "PendingIntent 已被取消", e)
        } catch (e: Exception) {
            Logger.e("MediaControlUtil", "发送 PendingIntent 失败", e)
        }
    }

    /**
     * 从 `Notification` 中提取所有 action 的 `PendingIntent`，key 为 action 的 title
     */
    fun extractActionPendingIntents(notification: Notification): Map<String, PendingIntent> {
        val map = mutableMapOf<String, PendingIntent>()
        try {
            val actions = notification.actions
            if (actions != null) {
                for (action in actions) {
                    try {
                        val title = action.title?.toString() ?: ""
                        val pi = action.actionIntent
                        if (pi != null) map[title] = pi
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Logger.e("MediaControlUtil", "解析 Notification.actions 失败", e)
        }
        return map
    }

    /**
     * StatusBarNotification 版本
     */
    fun extractActionPendingIntents(sbn: StatusBarNotification): Map<String, PendingIntent> {
        return extractActionPendingIntents(sbn.notification)
    }

    /**
     * 根据关键词（如 "play"/"pause"/"next"/"previous"/中文）在 Notification.action.title 中查找对应的 PendingIntent
     */
    fun findMediaActionPendingIntent(notification: Notification, vararg keywords: String): PendingIntent? {
        try {
            val actions = notification.actions ?: return null
            for (action in actions) {
                val title = action.title?.toString()?.lowercase() ?: ""
                for (kw in keywords) {
                    if (kw.isEmpty()) continue
                    if (title.contains(kw.lowercase())) return action.actionIntent
                }
            }
        } catch (e: Exception) {
            Logger.e("MediaControlUtil", "查找媒体动作 PendingIntent 失败", e)
        }
        return null
    }

    fun findMediaActionPendingIntent(sbn: StatusBarNotification, vararg keywords: String): PendingIntent? {
        return findMediaActionPendingIntent(sbn.notification, *keywords)
    }

    /**
     * 便捷方法：从 Notification 中触发常见媒体操作（尝试多种关键词匹配）
     */
    fun triggerPlayPauseFromNotification(notification: Notification) {
        val pi = findMediaActionPendingIntent(notification, "play", "pause", "播放", "暂停", "toggle", "resume")
        if (pi != null) sendPendingIntentSafe(pi) else Logger.w("MediaControlUtil", "未找到播放/暂停 PendingIntent")
    }

    fun triggerNextFromNotification(notification: Notification) {
        val pi = findMediaActionPendingIntent(notification, "next", "下一", "下一首")
        if (pi != null) sendPendingIntentSafe(pi) else Logger.w("MediaControlUtil", "未找到下一首 PendingIntent")
    }

    fun triggerPreviousFromNotification(notification: Notification) {
        val pi = findMediaActionPendingIntent(notification, "prev", "previous", "上", "上一首")
        if (pi != null) sendPendingIntentSafe(pi) else Logger.w("MediaControlUtil", "未找到上一首 PendingIntent")
    }

    fun triggerPlayPauseFromNotification(sbn: StatusBarNotification) = triggerPlayPauseFromNotification(sbn.notification)
    fun triggerNextFromNotification(sbn: StatusBarNotification) = triggerNextFromNotification(sbn.notification)
    fun triggerPreviousFromNotification(sbn: StatusBarNotification) = triggerPreviousFromNotification(sbn.notification)
}