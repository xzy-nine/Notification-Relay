package com.xzyht.notifyrelay.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限辅助工具类
 *
 * 提供一组用于检查与请求应用在运行时常用权限的静态方法，包含通知监听、应用列表访问、通知发送、使用情况访问、蓝牙连接、悬浮窗与电池优化等权限的检查与请求逻辑。
 */
object PermissionHelper {

    /**
     * 检查所有必要权限是否已授权。
     *
     * 本方法会检查：
     * 1. 通知监听权限（应用是否列在系统已启用通知监听器中）；
     * 2. 应用列表访问权限（或使用情况访问权限，针对 MIUI/澎湃会优先检查特定权限）；
     * 3. 通知发送权限（Android 13 / API 33 及以上需要 `POST_NOTIFICATIONS`）。
     *
     * @param context 用于访问 PackageManager、Settings 及系统服务的上下文（通常传入 Activity 或 Application 的 Context）。
     * @return 如果所有必要权限均已授予则返回 true，否则返回 false。
     */
    fun checkAllPermissions(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val hasNotification = enabledListeners.contains(context.packageName)

        // 判断是否为 MIUI/澎湃系统（厂商或系统包识别）
        val isMiui = detectMiuiOrPengpai(context)

    // 检查应用列表权限
    var canQueryApps: Boolean
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            canQueryApps = apps.size > 2
                if (isMiui) {
                canQueryApps = canQueryApps && (ContextCompat.checkSelfPermission(context, "com.android.permission.GET_INSTALLED_APPS") == PackageManager.PERMISSION_GRANTED)
            }
        } catch (e: Exception) {
            canQueryApps = false
        }

        // 检查通知发送权限
        val hasPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return hasNotification && canQueryApps && hasPost
    }

    /**
     * 请求所有必要权限并引导用户到相应的系统设置或触发运行时权限弹窗。
     *
     * 行为说明：
     * - 会打开通知监听设置页面以引导用户开启通知监听权限；
     * - 在 MIUI/澎湃系统上会尝试动态请求 `com.android.permission.GET_INSTALLED_APPS`；
     * - 在非 MIUI/澎湃系统上会打开「使用情况访问」设置页面；
     * - 在 Android 13+（API 33）会请求 `POST_NOTIFICATIONS` 运行时权限。
     *
     * @param activity 用于启动设置页面和请求运行时权限的 Activity 实例。
     */
    fun requestAllPermissions(activity: Activity) {
        // 判断是否为 MIUI/澎湃系统（厂商或系统包识别）
        val isMiui = detectMiuiOrPengpai(activity)

    // 通知访问权限：引导用户打开通知监听器设置
    val intentNotification = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    activity.startActivity(intentNotification)

            if (isMiui) {
            // MIUI/澎湃优先动态申请应用列表权限
            if (ContextCompat.checkSelfPermission(activity, "com.android.permission.GET_INSTALLED_APPS") != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf("com.android.permission.GET_INSTALLED_APPS"), 999)
            }
        } else {
            // 非 MIUI/澎湃，使用原生应用使用情况访问权限
            val intentUsage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            activity.startActivity(intentUsage)
        }

        // 通知发送权限（Android 13+）：运行时请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // 敏感通知权限现在为可选，不再强制请求
        // if (Build.VERSION.SDK_INT >= 35 && !checkSensitiveNotificationPermission(activity)) {
        //     requestSensitiveNotificationPermission(activity)
        // }
    }

    /**
     * 检查敏感通知权限（Android 15+）。
     *
     * @param context 用于执行权限检查的上下文。
     * @return 在 API 35 及以上，返回是否拥有 `RECEIVE_SENSITIVE_NOTIFICATIONS` 权限；在较低版本返回 true（视为不需要该权限）。
     */
    fun checkSensitiveNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 35) {
            context.checkSelfPermission("android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else {
            true // 低版本默认有权限
        }
    }

    /**
     * 请求敏感通知权限（Android 15+）。
     *
     * 说明：Android 15 引入了对敏感通知的更细粒度控制。该方法不会直接弹出系统权限对话框，
     * 而是根据设备厂商引导用户到不同的位置或给出提示：
     * - MIUI：尝试跳转到设置页面以关闭增强型通知（Notification Assistant）以达到目标；
     * - 其他厂商：提示用户使用 ADB 命令授权（因为系统可能未提供 UI 入口）。
     *
     * @param activity 用于启动设置页面或显示提示的 Activity。
     */
    fun requestSensitiveNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 35) {
            val isMiui = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
            if (isMiui) {
                // MIUI 系统跳转关闭增强型通知
                try {
                    val intent = Intent()
                    intent.setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAssistantSettingsActivity")
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    // 跳转失败，提示手动设置
                    android.widget.Toast.makeText(activity, "请手动在设置-通知-增强型通知关闭", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                // 其他系统提示使用 ADB
                android.widget.Toast.makeText(activity, "请用adb授权: adb shell appops set ${activity.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 检查应用使用情况访问权限（可选）。
     *
     * 该权限用于获取设备上应用的使用情况统计（Usage Stats），某些功能需要此权限来判断应用是否处于前台等。
     *
     * @param context 用于获取 AppOpsManager 服务的上下文。
     * @return 如果 AppOps 管理器允许 `android:get_usage_stats` 则返回 true，否则返回 false。
     */
    @Suppress("DEPRECATION")
    fun isUsageStatsEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检查蓝牙连接权限（Android 12+）。
     *
     * @param context 用于检查权限的上下文。
     * @return 在 API 31+（Android 12）时检查 `BLUETOOTH_CONNECT` 是否已授予，低版本始终返回 true。
     */
    fun checkBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 请求蓝牙连接权限（Android 12+）。
     *
     * @param activity 用于触发运行时权限请求的 Activity。
     */
    fun requestBluetoothConnectPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
        }
    }

    /**
     * 检查应用是否具有悬浮窗（覆盖层）权限。
     *
     * @param context 用于调用 Settings.canDrawOverlays 的上下文。
     * @return 如果系统允许应用在其他应用上层显示窗口则返回 true，异常时返回 false。
     */
    fun checkOverlayPermission(context: Context): Boolean {
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 请求悬浮窗（覆盖层）权限。
     *
     * 会打开系统悬浮窗权限设置页面，用户需手动在系统设置中允许应用在其他应用之上显示。
     *
     * @param activity 用于启动设置页面的 Activity。
     */
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = android.net.Uri.parse("package:${activity.packageName}")
        activity.startActivity(intent)
    }

    /**
     * 检查应用是否被系统电池优化排除（即具有后台无限制运行权限）。
     *
     * @param context 用于获取 PowerManager 的上下文。
     * @return 如果应用被设置为忽略电池优化（可在后台长期运行）则返回 true，否则返回 false。
     */
    fun checkBackgroundUnlimitedPermission(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 检查通知监听服务是否已被启用（用于间接验证应用是否具有自启动/监听通知的能力）。
     *
     * @param context 用于读取 Settings.Secure 的上下文。
     * @return 当系统已启用通知监听器并包含当前应用包名时返回 true，否则返回 false。
     */
    fun checkNotificationListenerServiceCanStart(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(context.packageName) == true
    }

    /**
     * 私有工具：检测设备是否为 MIUI/澎湃（基于厂商名或系统权限信息判断）。
     *
     * @param context 用于访问 PackageManager 的上下文。
     * @return 如果判断为 MIUI/澎湃返回 true，否则返回 false。
     */
    private fun detectMiuiOrPengpai(context: Context): Boolean {
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return true
        return kotlin.runCatching {
            context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0).packageName == "com.lbe.security.miui"
        }.getOrElse { false }
    }
}
