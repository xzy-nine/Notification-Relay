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

object PermissionHelper {

    /**
     * 检查所有必要权限是否已授权
     */
    fun checkAllPermissions(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val hasNotification = enabledListeners.contains(context.packageName)

        // 判断是否为 MIUI/澎湃系统
        var isMiuiOrPengpai = false
        isMiuiOrPengpai = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        if (!isMiuiOrPengpai) {
            try {
                val permissionInfo = context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
                if (permissionInfo.packageName == "com.lbe.security.miui") {
                    isMiuiOrPengpai = true
                }
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        // 检查应用列表权限
        var canQueryApps: Boolean
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            canQueryApps = apps.size > 2
            if (isMiuiOrPengpai) {
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
     * 请求所有必要权限
     */
    fun requestAllPermissions(activity: Activity) {
        // 判断是否为 MIUI/澎湃系统
        var isMiuiOrPengpai = false
        isMiuiOrPengpai = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        if (!isMiuiOrPengpai) {
            try {
                val permissionInfo = activity.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
                if (permissionInfo.packageName == "com.lbe.security.miui") {
                    isMiuiOrPengpai = true
                }
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        // 通知访问权限
        val intentNotification = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        activity.startActivity(intentNotification)

        if (isMiuiOrPengpai) {
            // MIUI/澎湃优先动态申请应用列表权限
            if (ContextCompat.checkSelfPermission(activity, "com.android.permission.GET_INSTALLED_APPS") != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf("com.android.permission.GET_INSTALLED_APPS"), 999)
            }
        } else {
            // 非 MIUI/澎湃，使用原生应用使用情况访问权限
            val intentUsage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            activity.startActivity(intentUsage)
        }

        // 通知发送权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // 敏感通知权限现在为可选，不再强制请求
        // if (Build.VERSION.SDK_INT >= 35 && !checkSensitiveNotificationPermission(activity)) {
        //     requestSensitiveNotificationPermission(activity)
        // }
    }

    /**
     * 检查敏感通知权限（Android 15+）
     */
    fun checkSensitiveNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 35) {
            context.checkSelfPermission("android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else {
            true // 低版本默认有权限
        }
    }

    /**
     * 请求敏感通知权限
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
     * 检查应用使用情况权限（可选）
     */
    @Suppress("DEPRECATION")
    fun isUsageStatsEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检查蓝牙连接权限（Android 12+）
     */
    fun checkBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 请求蓝牙连接权限
     */
    fun requestBluetoothConnectPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
        }
    }

    /**
     * 检查悬浮通知权限
     */
    fun checkOverlayPermission(context: Context): Boolean {
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 请求悬浮通知权限
     */
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = android.net.Uri.parse("package:${activity.packageName}")
        activity.startActivity(intent)
    }

    /**
     * 检查后台无限制权限（电池优化）
     */
    fun checkBackgroundUnlimitedPermission(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 检查通知监听服务是否能正常启动（作为自启动权限的间接验证，通过检查通知监听器是否启用）
     */
    fun checkNotificationListenerServiceCanStart(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(context.packageName) == true
    }
}
