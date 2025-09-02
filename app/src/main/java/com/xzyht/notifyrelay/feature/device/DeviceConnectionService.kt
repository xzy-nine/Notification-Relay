package com.xzyht.notifyrelay.feature.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xzyht.notifyrelay.feature.device.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.DeviceInfo
import com.xzyht.notifyrelay.feature.device.NotificationRepository

class DeviceConnectionService : Service() {
    private lateinit var connectionManager: DeviceConnectionManager
    private val NOTIFY_ID = 1001 // 与 NotifyRelayNotificationListenerService 保持一致
    // 可选：暴露静态方法方便外部启动服务
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DeviceConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 重新启动服务，防止被系统杀死
        val restartIntent = Intent(applicationContext, DeviceConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }
    override fun onCreate() {
        super.onCreate()
        // 保证全局唯一实例，UI和Service同步
        connectionManager = com.xzyht.notifyrelay.feature.device.DeviceForwardFragment.getDeviceManager(applicationContext)
        connectionManager.startDiscovery()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "notifyrelay_foreground"
        val channelName = "通知转发后台服务"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("通知转发运行中")
            .setContentText("保证本设备的在线和通知的获取与转发")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        startForeground(NOTIFY_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.xzyht.notifyrelay.ACTION_REISSUE_FOREGROUND") {
            // 延迟补发前台通知
            val delay = intent.getLongExtra("delay", 0L)
            if (delay > 0) {
                android.os.Handler(mainLooper).postDelayed({
                    startForegroundServiceNotification()
                }, delay)
            } else {
                startForegroundServiceNotification()
            }
        } else {
            startForegroundServiceNotification() // 确保每次重启都恢复前台通知
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 释放 DeviceConnectionManager 资源，防止内存泄漏和端口占用
        try {
            // 关闭 socket、线程等（需在 DeviceConnectionManager 内部实现对应方法）
            if (this::connectionManager.isInitialized) {
                connectionManager.stopAll()
            }
        } catch (_: Exception) {}
    }
}
