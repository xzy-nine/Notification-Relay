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
    // sourceId: 用于区分不同来源的超级岛通知（通常传入 superPkg），用于刷新/去重同一来源的浮窗
    fun showFloating(context: Context, sourceId: String?, title: String?, text: String?, paramV2Raw: String? = null, picMap: Map<String, String>? = null) {
        try {
            if (!canShowOverlay(context)) {
                // 没有权限时：弹出设置意图并回退成高优先级通知
                requestOverlayPermission(context)
                MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
                return
            }

            // 异步准备图片资源并显示浮窗（在主线程更新 UI）
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = downloadFirstAvailableImage(picMap)
                ensureOverlayExists(context)
                addOrUpdateEntry(context, sourceId ?: (title + "|" + text), title, text, bitmap)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    // ---- 多条浮窗管理实现 ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stackContainer: LinearLayout? = null
    // key -> Pair(view, Runnable removal)
    private val entries = mutableMapOf<String, Pair<View, Runnable>>()

    private fun ensureOverlayExists(context: Context) {
        if (overlayView != null && stackContainer != null) return
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // 移除已存在浮窗
            try { overlayView?.let { wm.removeView(it) } } catch (_: Exception) {}

            val container = FrameLayout(context)
            container.setBackgroundColor(0x00000000)

            val padding = (12 * (context.resources.displayMetrics.density)).toInt()
            val innerStack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

            container.addView(innerStack)

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
            stackContainer = innerStack
            try { wm.addView(container, layoutParams) } catch (_: Exception) {}
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 浮窗容器已创建，初始坐标 x=${layoutParams.x}, y=${layoutParams.y}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 创建浮窗容器失败: ${e.message}")
        }
    }

    private fun addOrUpdateEntry(context: Context, key: String, title: String?, text: String?, image: android.graphics.Bitmap?) {
        try {
            val stack = stackContainer ?: return

            // 如果已有相同key，更新内容并重置定时移除
            val existing = entries[key]
            if (existing != null) {
                val (view, oldRunnable) = existing
                // 更新文本和图片
                try {
                    val img = view.findViewById<ImageView>(android.R.id.icon)
                    val tvTitle = view.findViewById<TextView>(android.R.id.text1)
                    val tvText = view.findViewById<TextView>(android.R.id.text2)
                    if (image != null) img.setImageBitmap(image)
                    tvTitle.text = title ?: "(无标题)"
                    tvText.text = text ?: "(无内容)"
                } catch (_: Exception) {}

                // 取消旧的移除任务并重新排期
                handler.removeCallbacks(oldRunnable)
                val removal = Runnable {
                    try {
                        stack.removeView(view)
                        entries.remove(key)
                        if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                    } catch (_: Exception) {}
                }
                entries[key] = view to removal
                handler.postDelayed(removal, 5000)
                if (BuildConfig.DEBUG) Log.d("超级岛", "超级岛: 刷新浮窗条目 key=$key")
                return
            }

            // 创建新的条目视图（水平排列）
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val innerPadding = (8 * context.resources.displayMetrics.density).toInt()
                setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
                setBackgroundColor(0xEE000000.toInt())
            }
            val iv = ImageView(context).apply {
                id = android.R.id.icon
                if (image != null) setImageBitmap(image)
                val size = (56 * context.resources.displayMetrics.density).toInt()
                this.layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val tvs = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
                this.layoutParams = lp
            }
            val tt = TextView(context).apply { id = android.R.id.text1; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; this.text = title ?: "(无标题)" }
            val tx = TextView(context).apply { id = android.R.id.text2; setTextColor(0xFFDDDDDD.toInt()); textSize = 12f; this.text = text ?: "(无内容)" }
            tvs.addView(tt)
            tvs.addView(tx)
            item.addView(iv)
            item.addView(tvs)

            // 支持拖动整个stack（按条目拖动暂时不实现，仅支持整体）
            // 将新条目添加到顶部（最新在上）
            stack.addView(item, 0)

            val removal = Runnable {
                try {
                    stack.removeView(item)
                    entries.remove(key)
                    if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                } catch (_: Exception) {}
            }
            entries[key] = item to removal
            handler.postDelayed(removal, 5000)
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 新增浮窗条目 key=$key, title=${title}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: addOrUpdateEntry 出错: ${e.message}")
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
            // 支持 data URI（base64）、以及常规 http/https URL
            if (url.startsWith("data:", ignoreCase = true)) {
                val comma = url.indexOf(',')
                if (comma <= 0) return null
                val meta = url.substring(5, comma)
                val data = url.substring(comma + 1)
                // 仅处理 base64 编码的 data URI
                if (meta.contains("base64")) {
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                return null
            }

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
