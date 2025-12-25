package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.app.ActivityManager
import com.xzyht.notifyrelay.BuildConfig

/**
 * 服务管理工具类
 *
 * 负责启动/检测与通知转发相关的后台服务，并在无法启动时返回明确的错误信息，
 * 便于上层进行提示或引导用户到系统设置放行。
 */
object ServiceManager {
    /**
     * 当自动启动或后台运行被系统限制时，给出的友好提示信息。
     */
    private const val AUTO_START_ERROR_MESSAGE = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"

    /**
     * 启动通知监听服务。
     *
     * @param context 应用或组件上下文，用于调用 startService。
     * @return 启动请求是否成功（不代表系统已实际在前台运行，仅表示调用未抛出异常）
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
            Logger.e("ServiceManager", "启动通知监听服务失败", e)
            false
        }
    }

    /**
     * 检查指定服务是否正在运行。
     *
     * @param context 上下文，用于获取 ActivityManager 服务。
     * @param serviceClassName 要检查的服务类全名（例如：com.xxx.MyService）。
     * @return 若当前系统进程列表中存在匹配的服务则返回 true，否则返回 false。
     */
    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context, serviceClassName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val running = am.getRunningServices(Int.MAX_VALUE)
            running.any { it.service.className == serviceClassName }
        } catch (e: Exception) {
            Logger.e("ServiceManager", "检查服务运行状态时发生异常", e)
            false
        }
    }

    /**
     * 检查自启动权限（实现方式：尝试启动通知监听服务，若能成功发起启动请求则认为拥有自启动/后台运行权限）。
     *
     * @param context 用于启动服务的上下文。
     * @return 若能成功发起服务启动请求则返回 true，否则返回 false。
     */
    fun checkAutoStartPermission(context: Context): Boolean {
        return startNotificationListenerService(context)
    }

    /**
     * 启动所有必要的后台服务（当前仅包括通知监听服务）。
     *
     * @param context 用于启动服务的上下文。
     * @return Pair 第一个元素表示是否至少有一个服务成功发起启动请求；
     *         第二个元素为可选的错误提示字符串，当存在启动失败且需要提示用户时返回该字符串，否则为 null。
     */
    fun startAllServices(context: Context): Pair<Boolean, String?> {
        var serviceStarted = false
        var errorMessage: String? = null

        // 启动通知监听服务
        try {
            val notificationStarted = startNotificationListenerService(context)
            if (!notificationStarted) {
                if (errorMessage == null) {
                    errorMessage = AUTO_START_ERROR_MESSAGE
                }
            } else {
                serviceStarted = true
            }
        } catch (e: Exception) {
            Logger.e("ServiceManager", "启动所有服务时发生异常", e)
            if (errorMessage == null) {
                errorMessage = AUTO_START_ERROR_MESSAGE
            }
        }

        return Pair(serviceStarted, errorMessage)
    }
}
