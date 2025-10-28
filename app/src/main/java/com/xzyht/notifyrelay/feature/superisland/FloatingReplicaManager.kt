package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.MessageSender

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private var overlayView: View? = null

    fun showFloating(context: Context, title: String?, text: String?) {
        try {
            if (canShowOverlay(context)) {
                showOverlayView(context, title, text)
            } else {
                // 回退：使用高优先级临时通知提示（短时展示）
                MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("FloatingReplica", "显示浮窗失败，退化为通知: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    private fun canShowOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
    }

    private fun showOverlayView(context: Context, title: String?, text: String?) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 如果已存在一个浮窗，先移除
            try { overlayView?.let { wm.removeView(it) } } catch (_: Exception) {}

            val inflater = LayoutInflater.from(context)
            val container = FrameLayout(context)
            val tv = TextView(context)
            tv.text = "${title ?: "(无标题)"}\n${text ?: "(无内容)"}"
            tv.setBackgroundColor(0xCC000000.toInt())
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.setPadding(20, 20, 20, 20)
            container.addView(tv)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams.x = 0
            layoutParams.y = 100

            overlayView = container
            wm.addView(container, layoutParams)

            // 自动在5秒后移除
            container.postDelayed({
                try { wm.removeView(container); overlayView = null } catch (_: Exception) {}
            }, 5000)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("FloatingReplica", "showOverlayView error: ${e.message}")
            throw e
        }
    }
}
