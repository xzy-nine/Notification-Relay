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
import android.widget.LinearLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.MessageSender

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private var overlayView: View? = null

    /**
     * 显示超级岛复刻悬浮窗。
     * paramV2Raw: miui.focus.param 中 param_v2 的原始 JSON 字符串（可为 null）
     * picMap: 从 extras 中解析出的图片键->URL 映射（可为 null）
     */
    fun showFloating(context: Context, title: String?, text: String?, paramV2Raw: String? = null, picMap: Map<String, String>? = null) {
        try {
            if (!canShowOverlay(context)) {
                // 没有权限时：弹出设置意图并回退成高优先级通知
                requestOverlayPermission(context)
                MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
                return
            }

            // 异步准备图片资源并显示浮窗
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = downloadFirstAvailableImage(picMap)
                tryShowOverlay(context, title, text, bitmap)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    private fun canShowOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
    }

    private fun requestOverlayPermission(context: Context) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:" + context.packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 请求悬浮窗权限失败: ${e.message}")
        }
    }

    private suspend fun downloadFirstAvailableImage(picMap: Map<String, String>?): android.graphics.Bitmap? {
        if (picMap.isNullOrEmpty()) return null
        for ((_, url) in picMap) {
            try {
                val bmp = withContext(Dispatchers.IO) { downloadBitmap(url, 5000) }
                if (bmp != null) return bmp
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            }
        }
        return null
    }

    private fun tryShowOverlay(context: Context, title: String?, text: String?, image: android.graphics.Bitmap?) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // 移除已存在浮窗
            try { overlayView?.let { wm.removeView(it) } } catch (_: Exception) {}

            val container = FrameLayout(context)
            container.setBackgroundColor(0x00000000)

            val padding = (12 * (context.resources.displayMetrics.density)).toInt()
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(0xEE000000.toInt())
                val radius = (8 * context.resources.displayMetrics.density)
                // 为简单起见不使用 shape，直接留黑色背景
            }

            if (image != null) {
                val iv = ImageView(context)
                iv.setImageBitmap(image)
                val size = (56 * context.resources.displayMetrics.density).toInt()
                val lp = FrameLayout.LayoutParams(size, size)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                inner.addView(iv)
            }

            val tvs = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, 0, 0, 0)
            }
            val tt = TextView(context)
            tt.setTextColor(0xFFFFFFFF.toInt())
            tt.textSize = 14f
            tt.text = title ?: "(无标题)"
            val tx = TextView(context)
            tx.setTextColor(0xFFDDDDDD.toInt())
            tx.textSize = 12f
            tx.text = text ?: "(无内容)"
            tvs.addView(tt)
            tvs.addView(tx)
            inner.addView(tvs)

            container.addView(inner)

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

            // 支持拖动
            inner.setOnTouchListener(FloatingTouchListener(layoutParams, wm))

            overlayView = container
            wm.addView(container, layoutParams)
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 浮窗已显示，初始坐标 x=${layoutParams.x}, y=${layoutParams.y}")

            // 自动在5秒后移除
            container.postDelayed({
                try {
                    if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 定时移除浮窗，当前坐标 x=${layoutParams.x}, y=${layoutParams.y}")
                    wm.removeView(container); overlayView = null
                } catch (_: Exception) {}
            }, 5000)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 显示悬浮窗出错: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    private fun downloadBitmap(url: String, timeoutMs: Int): android.graphics.Bitmap? {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            if (conn.responseCode != 200) return null
            val stream = conn.inputStream
            val bmp = android.graphics.BitmapFactory.decodeStream(stream)
            try { stream.close() } catch (_: Exception) {}
            conn.disconnect()
            return bmp
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            return null
        }
    }

    // 简单的触摸拖动实现
    private class FloatingTouchListener(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager
    ) : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    params.x += dx
                    params.y += dy
                    try {
                        wm.updateViewLayout(v.rootView, params)
                        if (BuildConfig.DEBUG) Log.d("超级岛", "超级岛: 浮窗移动到 x=${params.x}, y=${params.y}")
                    } catch (_: Exception) {}
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
            }
            return false
        }
    }
}
