package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.app.ActivityManager
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig

object ServiceManager {
    private const val AUTO_START_ERROR_MESSAGE = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"

    /**
     * 启动设备连接服务
     */
    fun startDeviceConnectionService(context: Context) {
        try {
            val serviceClass = Class.forName("com.xzyht.notifyrelay.feature.device.service.DeviceConnectionService")
            val startMethod = serviceClass.getMethod("start", Context::class.java)
            startMethod.invoke(null, context)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("ServiceManager", "Failed to start DeviceConnectionService", e)
        }
    }

    /**
     * 启动通知监听服务
     */
    fun startNotificationListenerService(context: Context): Boolean {
        return try {
            val cn = ComponentName(context, "com.xzyht.notifyrelay.feature.notification.service.NotifyRelayNotificationListenerService")
            val restartIntent = Intent()
            restartIntent.component = cn
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startService(restartIntent)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("ServiceManager", "Failed to start NotificationListenerService", e)
            false
        }
    }

    /**
     * 检查服务是否正在运行
     */
    fun isServiceRunning(context: Context, serviceClassName: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == serviceClassName }
    }

    /**
     * 检查自启动权限（通过尝试启动服务来检测）
     */
    fun checkAutoStartPermission(context: Context): Boolean {
        return startNotificationListenerService(context)
    }

    /**
     * 启动所有必要服务
     */
    fun startAllServices(context: Context): Pair<Boolean, String?> {
        var serviceStarted = false
        var errorMessage: String? = null

        // 启动设备连接服务
        try {
            if (!isServiceRunning(context, "com.xzyht.notifyrelay.feature.device.service.DeviceConnectionService")) {
                startDeviceConnectionService(context)
            }
            serviceStarted = true
        } catch (e: Exception) {
            errorMessage = "设备连接服务启动失败: ${e.message}"
        }

        // 启动通知监听服务
        try {
            val notificationStarted = startNotificationListenerService(context)
            if (!notificationStarted) {
                if (errorMessage == null) {
                    errorMessage = AUTO_START_ERROR_MESSAGE
                }
            }
        } catch (e: Exception) {
            if (errorMessage == null) {
                errorMessage = AUTO_START_ERROR_MESSAGE
            }
        }

        return Pair(serviceStarted, errorMessage)
    }
}
