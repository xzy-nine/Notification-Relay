package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.core.util.MessageSender
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.SmallIslandArea
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.buildViewFromTemplate
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.parseParamV2

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private const val TAG = "超级岛"
    private const val EXPANDED_DURATION_MS = 3_000L
    private const val AUTO_DISMISS_DURATION_MS = 12_000L

    private var overlayView: View? = null
    private var stackContainer: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private data class EntryRecord(
        val key: String,
        val container: FrameLayout,
        var expandedView: View,
        var summaryView: View,
        var isExpanded: Boolean = true,
        var collapseRunnable: Runnable? = null,
        var removalRunnable: Runnable? = null
    )

    private val entries = mutableMapOf<String, EntryRecord>()

    /**
     * 显示超级岛复刻悬浮窗。
     * paramV2Raw: miui.focus.param 中 param_v2 的原始 JSON 字符串（可为 null）
     * picMap: 从 extras 中解析出的图片键->URL 映射（可为 null）
     */
    // sourceId: 用于区分不同来源的超级岛通知（通常传入 superPkg），用于刷新/去重同一来源的浮窗
    fun showFloating(
        context: Context,
        sourceId: String?,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null
    ) {
        try {
            if (!canShowOverlay(context)) {
                requestOverlayPermission(context)
                MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
                return
            }

            CoroutineScope(Dispatchers.Main).launch {
                val paramV2 = paramV2Raw?.let { parseParamV2(it) }
                val smallIsland = paramV2?.paramIsland?.smallIslandArea
                val summaryBitmap = smallIsland?.iconKey?.let { iconKey -> downloadBitmapByKey(picMap, iconKey) }
                val fallbackBitmap = summaryBitmap ?: downloadFirstAvailableImage(picMap)

                ensureOverlayExists(context)

                val entryKey = sourceId ?: "${title ?: ""}|${text ?: ""}"
                val expandedView = paramV2?.let { buildViewFromTemplate(context, it, picMap) }
                    ?: buildLegacyExpandedView(context, title, text, fallbackBitmap)
                val summaryView = buildSummaryView(
                    context,
                    smallIsland,
                    title,
                    text,
                    summaryBitmap ?: fallbackBitmap
                )

                addOrUpdateEntry(context, entryKey, expandedView, summaryView)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    // ---- 多条浮窗管理实现 ----

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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams.x = 0
            layoutParams.y = 100

            overlayView = container
            stackContainer = innerStack
            try { wm.addView(container, layoutParams) } catch (_: Exception) {}
            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 浮窗容器已创建，初始坐标 x=${layoutParams.x}, y=${layoutParams.y}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 创建浮窗容器失败: ${e.message}")
        }
    }

    private fun addOrUpdateEntry(context: Context, key: String, expandedView: View, summaryView: View) {
        try {
            val stack = stackContainer ?: return
            val existing = entries[key]
            if (existing != null) {
                updateRecordContent(existing, expandedView, summaryView)
                showExpanded(existing)
                scheduleCollapse(existing)
                scheduleRemoval(existing, key)
                if (BuildConfig.DEBUG) Log.d(TAG, "超级岛: 刷新浮窗条目 key=$key")
                return
            }

            val container = FrameLayout(context).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val margin = (4 * context.resources.displayMetrics.density).toInt()
                lp.setMargins(0, margin, 0, margin)
                layoutParams = lp
                isClickable = true
            }

            detachFromParent(summaryView)
            detachFromParent(expandedView)
            summaryView.visibility = View.GONE
            expandedView.visibility = View.VISIBLE
            container.addView(summaryView)
            container.addView(expandedView)
            container.setOnClickListener { onEntryClicked(key) }

            stack.addView(container, 0)

            val record = EntryRecord(key, container, expandedView, summaryView)
            entries[key] = record
            showExpanded(record)
            scheduleCollapse(record)
            scheduleRemoval(record, key)
            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 新增浮窗条目 key=$key")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: addOrUpdateEntry 出错: ${e.message}")
        }
    }

    private fun updateRecordContent(record: EntryRecord, expandedView: View, summaryView: View) {
        detachFromParent(summaryView)
        detachFromParent(expandedView)
        record.container.removeAllViews()
        summaryView.visibility = View.GONE
        expandedView.visibility = View.VISIBLE
        record.container.addView(summaryView)
        record.container.addView(expandedView)
        record.expandedView = expandedView
        record.summaryView = summaryView
        record.isExpanded = true
    }

    private fun onEntryClicked(key: String) {
        val record = entries[key] ?: return
        showExpanded(record)
        scheduleCollapse(record)
        scheduleRemoval(record, key)
    }

    private fun showExpanded(record: EntryRecord) {
        if (record.isExpanded) return
        record.summaryView.visibility = View.GONE
        record.expandedView.visibility = View.VISIBLE
        record.isExpanded = true
    }

    private fun showSummary(record: EntryRecord) {
        if (!record.isExpanded) return
        record.expandedView.visibility = View.GONE
        record.summaryView.visibility = View.VISIBLE
        record.isExpanded = false
    }

    private fun scheduleCollapse(record: EntryRecord, delayMs: Long = EXPANDED_DURATION_MS) {
        record.collapseRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!entries.containsKey(record.key)) return@Runnable
            showSummary(record)
        }
        record.collapseRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun scheduleRemoval(record: EntryRecord, key: String, delayMs: Long = AUTO_DISMISS_DURATION_MS) {
        record.removalRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { removeEntry(key) }
        record.removalRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun removeEntry(key: String) {
        val record = entries.remove(key) ?: return
        record.collapseRunnable?.let { handler.removeCallbacks(it) }
        record.removalRunnable?.let { handler.removeCallbacks(it) }
        try {
            stackContainer?.removeView(record.container)
        } catch (_: Exception) {}
        if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 自动移除浮窗条目 key=$key")
    }

    private fun detachFromParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.removeView(view)
    }

    private fun buildLegacyExpandedView(context: Context, title: String?, content: String?, image: Bitmap?): View {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xEE000000.toInt())

            if (image != null) {
                val size = (56 * density).toInt()
                val iconView = ImageView(context).apply {
                    id = android.R.id.icon
                    setImageBitmap(image)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(iconView)
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val marginStart = if (image != null) (8 * density).toInt() else 0
                lp.setMargins(marginStart, 0, 0, 0)
                layoutParams = lp
            }

            val titleView = TextView(context).apply {
                id = android.R.id.text1
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                text = title ?: "(无标题)"
            }

            val textView = TextView(context).apply {
                id = android.R.id.text2
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 12f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 2
                text = content ?: "(无内容)"
            }

            textColumn.addView(titleView)
            textColumn.addView(textView)
            addView(textColumn)
        }
    }

    private fun buildSummaryView(
        context: Context,
        smallIsland: SmallIslandArea?,
        fallbackTitle: String?,
        fallbackText: String?,
        bitmap: Bitmap?
    ): View {
        val density = context.resources.displayMetrics.density
        val primary = listOfNotNull(
            smallIsland?.primaryText,
            fallbackTitle,
            fallbackText
        ).firstOrNull { !it.isNullOrBlank() } ?: "(无内容)"

        val secondary = listOfNotNull(
            smallIsland?.secondaryText,
            fallbackText?.takeIf { !it.isNullOrBlank() && it != primary },
            fallbackTitle?.takeIf { !it.isNullOrBlank() && it != primary }
        ).firstOrNull { !it.isNullOrBlank() }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xEE000000.toInt())

            if (bitmap != null) {
                val size = (48 * density).toInt()
                val iconView = ImageView(context).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "focus_icon"
                }
                addView(iconView)
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val marginStart = if (bitmap != null) (8 * density).toInt() else 0
                lp.setMargins(marginStart, 0, 0, 0)
                layoutParams = lp
            }

            val primaryView = TextView(context).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                text = primary
            }

            textColumn.addView(primaryView)

            if (!secondary.isNullOrBlank()) {
                val secondaryView = TextView(context).apply {
                    setTextColor(0xFFDDDDDD.toInt())
                    textSize = 11f
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 1
                    text = secondary
                }
                textColumn.addView(secondaryView)
            }

            addView(textColumn)
        }
    }

    private fun canShowOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    private fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 请求悬浮窗权限失败: ${e.message}")
        }
    }

    private suspend fun downloadBitmapByKey(picMap: Map<String, String>?, key: String?): Bitmap? {
        if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
        val url = picMap[key] ?: return null
        return withContext(Dispatchers.IO) { downloadBitmap(url, 5000) }
    }

    private suspend fun downloadFirstAvailableImage(picMap: Map<String, String>?): Bitmap? {
        if (picMap.isNullOrEmpty()) return null
        for ((_, url) in picMap) {
            try {
                val bmp = withContext(Dispatchers.IO) { downloadBitmap(url, 5000) }
                if (bmp != null) return bmp
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            }
        }
        return null
    }

    private fun downloadBitmap(url: String, timeoutMs: Int): Bitmap? {
        return try {
            if (url.startsWith("data:", ignoreCase = true)) {
                DataUrlUtils.decodeDataUrlToBitmap(url)
            } else {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.doInput = true
                conn.connect()
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    null
                } else {
                    val stream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(stream)
                    try { stream.close() } catch (_: Exception) {}
                    conn.disconnect()
                    bmp
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            null
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "超级岛: 浮窗移动到 x=${params.x}, y=${params.y}")
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
