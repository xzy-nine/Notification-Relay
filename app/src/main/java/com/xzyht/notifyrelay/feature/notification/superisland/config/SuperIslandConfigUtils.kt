package com.xzyht.notifyrelay.feature.notification.superisland.config

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.xzyht.notifyrelay.feature.notification.superisland.receiver.NotificationBroadcastReceiver
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import notifyrelay.base.util.DeviceUtils
import notifyrelay.base.util.Logger
import notifyrelay.data.StorageManager

/**
 * 超级岛配置工具类，提供浮窗和规范信息注入相关的公共方法
 */
object SuperIslandConfigUtils {
    private const val TAG = "SuperIslandConfigUtils"
    private const val SUPER_ISLAND_FLOATING_WINDOW_KEY = "super_island_floating_window"
    private const val SUPER_ISLAND_NOTIFICATION_LIST_KEY = "super_island_notification_list"
    private const val SPEC_INJECTION_MODE_KEY = "spec_injection_mode"

    // 注入方式枚举
    enum class SpecInjectionMode {
        SUPER_ISLAND, // 仅超级岛规范信息注入（需要 XMSF 鉴权，未注册 scope 的构建不生效）
        LIVE_UPDATES, // 仅Live Updates规范信息注入
        NONE, // 都不注入（不应该使用，但为了完整性保留）
    }

    // 旧版本默认值（两者都注入），用于迁移判断；旧默认视为"未显式修改"，跟随新默认
    private const val LEGACY_BOTH_ORDINAL = 2

    /**
     * 检查浮窗功能是否开启
     */
    fun isFloatingWindowEnabled(context: Context): Boolean = StorageManager.getBoolean(context, SUPER_ISLAND_FLOATING_WINDOW_KEY, FloatingReplicaManager.getDefaultFloatingWindowEnabled())

    /**
     * 设置浮窗开关（与通知列表模式互斥）。
     * 开启浮窗时自动关闭通知列表模式。
     */
    fun setFloatingWindowEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (enabled && isNotificationListMode(context)) {
            setNotificationListMode(context, false)
            Logger.i(TAG, "浮窗开启，自动关闭通知列表模式（互斥）")
        }
        StorageManager.putBoolean(context, SUPER_ISLAND_FLOATING_WINDOW_KEY, enabled)
    }

    /**
     * 检查通知列表模式是否开启。
     * 该模式仅在浮窗关闭时有效，与浮窗互斥。
     * 默认值：平板开启，手机关闭。
     */
    fun isNotificationListMode(context: Context): Boolean {
        val defaultListMode = DeviceUtils.isTablet(context) && !isFloatingWindowEnabled(context)
        return StorageManager.getBoolean(
            context,
            SUPER_ISLAND_NOTIFICATION_LIST_KEY,
            defaultListMode,
        )
    }

    /**
     * 设置通知列表模式（与浮窗互斥）。
     * 开启通知列表模式时自动关闭浮窗。
     */
    fun setNotificationListMode(
        context: Context,
        enabled: Boolean,
    ) {
        if (enabled && isFloatingWindowEnabled(context)) {
            setFloatingWindowEnabled(context, false)
            Logger.i(TAG, "通知列表模式开启，自动关闭浮窗（互斥）")
        }
        StorageManager.putBoolean(context, SUPER_ISLAND_NOTIFICATION_LIST_KEY, enabled)
    }

    /**
     * 获取规范信息注入模式。
     * 迁移逻辑：
     * - 无存储值 / 旧默认 BOTH（视为"仍为默认"，用户未显式修改过）→ 固定默认 LIVE_UPDATES
     * - 存储为显式 SUPER_ISLAND / LIVE_UPDATES → 保持用户修改
     */
    fun getSpecInjectionMode(context: Context): SpecInjectionMode {
        val modeOrdinal = StorageManager.getInt(context, SPEC_INJECTION_MODE_KEY, -1)
        if (modeOrdinal == -1 || modeOrdinal == LEGACY_BOTH_ORDINAL) {
            // 无存储或旧默认 BOTH：固定默认 Live Updates
            return SpecInjectionMode.LIVE_UPDATES
        }
        return SpecInjectionMode.entries.toTypedArray().getOrElse(modeOrdinal) { SpecInjectionMode.LIVE_UPDATES }
    }

    /**
     * 检查超级岛规范信息注入是否开启
     */
    fun isSuperIslandSpecInjectionEnabled(context: Context): Boolean =
        getSpecInjectionMode(context) == SpecInjectionMode.SUPER_ISLAND

    /**
     * 检查Live Updates规范信息注入是否开启
     */
    fun isLiveUpdatesSpecInjectionEnabled(context: Context): Boolean =
        getSpecInjectionMode(context) == SpecInjectionMode.LIVE_UPDATES

    /**
     * 检查是否至少有一种规范信息注入开启
     * @return true 如果至少有一种注入开启，false 如果都关闭
     */
    fun isAnySpecInjectionEnabled(context: Context): Boolean =
        getSpecInjectionMode(context) != SpecInjectionMode.NONE

    /**
     * 创建通知移除时的删除 PendingIntent
     */
    fun createDeletePendingIntent(
        context: Context,
        notificationId: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NotificationBroadcastReceiver::class.java)
                .putExtra("notificationId", notificationId)
                .setAction("com.xzyht.notifyrelay.ACTION_CLOSE_NOTIFICATION"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 验证规范信息注入开关状态，确保至少有一种开启
     * 如果都关闭，则重置为固定默认 Live Updates
     */
    fun validateSpecInjectionSwitches(context: Context) {
        if (!isAnySpecInjectionEnabled(context)) {
            StorageManager.putInt(context, SPEC_INJECTION_MODE_KEY, SpecInjectionMode.LIVE_UPDATES.ordinal)
            Logger.w(TAG, "规范信息注入模式无效，已重置为 Live Updates")
        }
    }
}
