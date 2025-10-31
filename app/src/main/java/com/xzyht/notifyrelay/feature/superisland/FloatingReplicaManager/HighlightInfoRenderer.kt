package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import java.util.Locale
import kotlin.math.max
import org.json.JSONObject
import com.xzyht.notifyrelay.core.util.DataUrlUtils

// 高亮信息模板：强调图文组件，强调显示数据或内容
data class HighlightInfo(
    val title: String? = null, // 强调文本：需要强调的数据或内容
    val timerInfo: TimerInfo? = null, // 时间信息
    val content: String? = null, // 辅助文本1：补充信息
    val picFunction: String? = null, // 功能图标资源key
    val picFunctionDark: String? = null, // 深色模式功能图标
    val subContent: String? = null, // 辅助文本2：状态信息
    val type: Int? = null, // 是否隐藏辅助文本1：1隐藏
    val colorTitle: String? = null, // 强调文本颜色
    val colorTitleDark: String? = null, // 深色模式强调文本颜色
    val colorContent: String? = null, // 辅助文本1颜色
    val colorContentDark: String? = null, // 深色模式辅助文本1颜色
    val colorSubContent: String? = null, // 辅助文本2颜色
    val colorSubContentDark: String? = null, // 深色模式辅助文本2颜色
    val bigImageLeft: String? = null, // 大岛区域左侧图片
    val bigImageRight: String? = null // 大岛区域右侧图片
)

// 解析高亮信息组件（强调图文组件）
fun parseHighlightInfo(json: JSONObject): HighlightInfo {
    return HighlightInfo(
        title = json.optString("title", "").takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        content = json.optString("content", "").takeIf { it.isNotEmpty() },
        picFunction = json.optString("picFunction", "").takeIf { it.isNotEmpty() },
        picFunctionDark = json.optString("picFunctionDark", "").takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", "").takeIf { it.isNotEmpty() },
        type = json.optInt("type", -1).takeIf { it != -1 },
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", "").takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", "").takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", "").takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", "").takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", "").takeIf { it.isNotEmpty() },
        bigImageLeft = json.optString("bigImageLeft", "").takeIf { it.isNotEmpty() },
        bigImageRight = json.optString("bigImageRight", "").takeIf { it.isNotEmpty() }
    )
}

// 构建HighlightInfo视图
fun buildHighlightInfoView(context: Context, highlightInfo: HighlightInfo, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val density = context.resources.displayMetrics.density

    decodeBitmap(picMap, highlightInfo.picFunction)?.let { bitmap ->
        val iconSize = (40 * density).toInt()
        val iconView = ImageView(context).apply {
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(iconView)
    }

    val hasLeadingIcon = container.childCount > 0

    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val marginStart = if (hasLeadingIcon) (8 * density).toInt() else 0
        lp.setMargins(marginStart, 0, 0, 0)
        layoutParams = lp
    }

    val primaryText = listOfNotNull(
        highlightInfo.title,
        highlightInfo.content,
        highlightInfo.subContent
    ).firstOrNull { it.isNotBlank() } ?: "高亮信息"

    val primaryView = TextView(context).apply {
        text = primaryText
        val primaryColor = parseColor(highlightInfo.colorTitle)
            ?: parseColor(highlightInfo.colorContent)
            ?: 0xFFFFFFFF.toInt()
        setTextColor(primaryColor)
        textSize = 15f
    }
    textContainer.addView(primaryView)

    highlightInfo.content
        ?.takeIf { it.isNotBlank() && it != primaryText }
        ?.let { content ->
            val contentView = TextView(context).apply {
                text = content
                setTextColor(parseColor(highlightInfo.colorContent) ?: 0xFFDDDDDD.toInt())
                textSize = 12f
            }
            textContainer.addView(contentView)
        }

    highlightInfo.subContent
        ?.takeIf { it.isNotBlank() && it != primaryText }
        ?.let { subText ->
            val subView = TextView(context).apply {
                text = subText
                setTextColor(parseColor(highlightInfo.colorSubContent) ?: 0xFF9EA3FF.toInt())
                textSize = 12f
            }
            textContainer.addView(subView)
        }

    highlightInfo.timerInfo?.let { timerInfo ->
        val display = formatTimerInfo(timerInfo)
        if (display.isNotBlank()) {
            val timerView = TextView(context).apply {
                text = display
                setTextColor(parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
                textSize = 16f
            }
            bindTimerUpdater(timerView, timerInfo)
            textContainer.addView(timerView)
        }
    }

    container.addView(textContainer)

    val bigArea = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins((8 * density).toInt(), 0, 0, 0)
        layoutParams = lp
        gravity = Gravity.CENTER_VERTICAL
    }

    addBigAreaImage(context, bigArea, highlightInfo.bigImageLeft, picMap)
    addBigAreaImage(context, bigArea, highlightInfo.bigImageRight, picMap)

    if (bigArea.childCount > 0) {
        container.addView(bigArea)
    }

    return container
}

private fun addBigAreaImage(
    context: Context,
    bigArea: LinearLayout,
    key: String?,
    picMap: Map<String, String>?
) {
    val bitmap = decodeBitmap(picMap, key) ?: return
    val density = context.resources.displayMetrics.density
    val size = (44 * density).toInt()
    val imageView = ImageView(context).apply {
        val lp = LinearLayout.LayoutParams(size, size)
        if (bigArea.childCount > 0) {
            lp.setMargins((6 * density).toInt(), 0, 0, 0)
        }
        layoutParams = lp
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(bitmap)
        clipToOutline = false
    }
    bigArea.addView(imageView)
}

private fun decodeBitmap(picMap: Map<String, String>?, key: String?): Bitmap? {
    if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
    val raw = picMap[key] ?: return null
    return try {
        when {
            raw.startsWith("data:", ignoreCase = true) -> DataUrlUtils.decodeDataUrlToBitmap(raw)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun formatTimerInfo(timerInfo: TimerInfo, nowMillis: Long = System.currentTimeMillis()): String {
    val millis = calculateTimerMillis(timerInfo, nowMillis)
    val totalSeconds = max(0L, millis / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun calculateTimerMillis(timerInfo: TimerInfo, nowMillis: Long): Long {
    val baseElapsed = max(0L, timerInfo.timerSystemCurrent - timerInfo.timerWhen)
    val delta = max(0L, nowMillis - timerInfo.timerSystemCurrent)
    return when (timerInfo.timerType) {
        -2 -> resolveRemaining(timerInfo, includeDelta = false, baseElapsed = baseElapsed, nowMillis = nowMillis)
        -1, 0 -> resolveRemaining(timerInfo, includeDelta = true, baseElapsed = baseElapsed, nowMillis = nowMillis)
        2 -> max(0L, if (timerInfo.timerTotal > 0L) timerInfo.timerTotal else baseElapsed)
        else -> baseElapsed + delta
    }
}

private fun resolveRemaining(
    timerInfo: TimerInfo,
    includeDelta: Boolean,
    baseElapsed: Long,
    nowMillis: Long
): Long {
    val anchor = if (includeDelta) nowMillis else timerInfo.timerSystemCurrent
    val total = timerInfo.timerTotal
    val delta = max(0L, nowMillis - timerInfo.timerSystemCurrent)
    val elapsed = baseElapsed + if (includeDelta) delta else 0L

    return when {
        total > ABSOLUTE_TIME_THRESHOLD -> max(0L, total - anchor)
        total > 0L -> max(0L, total - elapsed)
        else -> 0L
    }
}

private fun bindTimerUpdater(view: TextView, timerInfo: TimerInfo) {
    val updater = TimerTextUpdater(view, timerInfo)
    view.addOnAttachStateChangeListener(updater)
    if (view.isAttachedToWindow) {
        updater.start()
    }
}

private class TimerTextUpdater(
    private val view: TextView,
    private val timerInfo: TimerInfo
) : Runnable, View.OnAttachStateChangeListener {

    override fun run() {
        if (!view.isAttachedToWindow) return
        view.text = formatTimerInfo(timerInfo, System.currentTimeMillis())
        view.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
    }

    fun start() {
        view.removeCallbacks(this)
        view.text = formatTimerInfo(timerInfo, System.currentTimeMillis())
        view.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
    }

    override fun onViewAttachedToWindow(v: View) {
        start()
    }

    override fun onViewDetachedFromWindow(v: View) {
        view.removeCallbacks(this)
        v.removeOnAttachStateChangeListener(this)
    }
}

private const val ABSOLUTE_TIME_THRESHOLD = 1_000_000_000_000L
private const val TIMER_UPDATE_INTERVAL_MS = 1_000L