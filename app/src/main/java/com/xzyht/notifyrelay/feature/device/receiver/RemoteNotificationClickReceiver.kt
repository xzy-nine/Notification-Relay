package com.xzyht.notifyrelay.feature.device.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig

class RemoteNotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "RemoteNotificationClickReceiver onReceive called")
        val notifyId = intent.getIntExtra("notifyId", 0)
        val pkg = intent.getStringExtra("pkg") ?: run {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "pkg is null in broadcast")
            return
        }
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val key = intent.getStringExtra("key") ?: (System.currentTimeMillis().toString() + pkg)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(notifyId)
        // 跳转应用
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            if (BuildConfig.DEBUG) Log.w("NotifyRelay(狂鼠)", "No launch intent for package: $pkg")
        }
    }
}
