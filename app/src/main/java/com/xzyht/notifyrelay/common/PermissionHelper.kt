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
}
