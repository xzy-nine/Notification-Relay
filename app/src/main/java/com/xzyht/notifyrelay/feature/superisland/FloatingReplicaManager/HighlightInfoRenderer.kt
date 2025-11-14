package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import java.util.Locale
import kotlin.math.max
import org.json.JSONObject
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.superisland.SuperIslandImageStore

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
    val bigImageRight: String? = null, // 大岛区域右侧图片
    val iconOnly: Boolean = false // 是否仅展示图标
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
        bigImageRight = json.optString("bigImageRight", "").takeIf { it.isNotEmpty() },
        iconOnly = json.optBoolean("iconOnly", false)
    )
}

// 构建HighlightInfo视图
fun buildHighlightInfoView(context: Context, highlightInfo: HighlightInfo, picMap: Map<String, String>?): LinearLayout {
    return buildHighlightLayout(context, highlightInfo, picMap)
}

private fun buildHighlightLayout(
    context: Context,
    highlightInfo: HighlightInfo,
    picMap: Map<String, String>?
): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (highlightInfo.iconOnly) Gravity.CENTER else Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val density = context.resources.displayMetrics.density
    val iconKey = selectIconKey(context, highlightInfo)
    val bitmap = decodeBitmap(context, picMap, iconKey)
    val hasLeadingIcon = bitmap != null
    if (bitmap != null) {
        val iconSize = if (highlightInfo.iconOnly) (48 * density).toInt() else (40 * density).toInt()
        val width = if (highlightInfo.iconOnly) LinearLayout.LayoutParams.WRAP_CONTENT else iconSize
        val iconView = ImageView(context).apply {
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(width, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(iconView)
    }
    val textLayoutParams = if (highlightInfo.iconOnly) {
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (hasLeadingIcon) setMargins((12 * density).toInt(), 0, 0, 0)
        }
    } else {
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (hasLeadingIcon) setMargins((8 * density).toInt(), 0, 0, 0)
        }
    }

    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = textLayoutParams
    }

    val primaryText = listOfNotNull(
        highlightInfo.title,
        highlightInfo.content,
        highlightInfo.subContent
    ).firstOrNull { it.isNotBlank() } ?: if (highlightInfo.iconOnly) null else "高亮信息"

    primaryText?.let { text ->
        val primaryColor = parseColor(highlightInfo.colorTitle)
            ?: parseColor(highlightInfo.colorContent)
            ?: 0xFFFFFFFF.toInt()
        val tv = TextView(context).apply {
            this.text = Html.fromHtml(unescapeHtml(text), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(primaryColor)
            textSize = if (highlightInfo.iconOnly) 15f else 15f
        }
        textContainer.addView(tv)
    }

    highlightInfo.content
        ?.takeIf { it.isNotBlank() && it != primaryText }
        ?.let { content ->
            val tv = TextView(context).apply {
                text = Html.fromHtml(unescapeHtml(content), Html.FROM_HTML_MODE_COMPACT)
                setTextColor(parseColor(highlightInfo.colorContent) ?: 0xFFDDDDDD.toInt())
                textSize = 12f
            }
            textContainer.addView(tv)
        }

    highlightInfo.subContent
        ?.takeIf { it.isNotBlank() && it != primaryText }
        ?.let { sub ->
            val tv = TextView(context).apply {
                text = Html.fromHtml(unescapeHtml(sub), Html.FROM_HTML_MODE_COMPACT)
                setTextColor(parseColor(highlightInfo.colorSubContent) ?: 0xFF9EA3FF.toInt())
                textSize = 12f
            }
            textContainer.addView(tv)
        }

    val statusText = highlightInfo.timerInfo
        ?.let { resolveStatusText(highlightInfo) }
        ?.takeIf { it.isNotBlank() && it != primaryText }

    statusText?.let { status ->
        val tv = TextView(context).apply {
            text = Html.fromHtml(unescapeHtml(status), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(parseColor(highlightInfo.colorSubContent) ?: parseColor(highlightInfo.colorContent) ?: 0xFFDDDDDD.toInt())
            textSize = 12f
        }
        textContainer.addView(tv)
    }

    highlightInfo.timerInfo
        ?.takeIf { !highlightInfo.iconOnly }
        ?.let { timerInfo ->
            val display = formatTimerInfo(timerInfo)
            if (display.isNotBlank()) {
                val timerView = TextView(context).apply {
                    text = Html.fromHtml(unescapeHtml(display), Html.FROM_HTML_MODE_COMPACT)
                    setTextColor(parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
                    textSize = 16f
                }
                bindTimerUpdater(timerView, timerInfo)
                textContainer.addView(timerView)
            }
        }

    if (textContainer.childCount > 0) {
        container.addView(textContainer)
    }

    if (!highlightInfo.iconOnly) {
        val bigArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((8 * density).toInt(), 0, 0, 0)
            layoutParams = lp
            gravity = Gravity.CENTER_VERTICAL
        }

        val leftAdded = addBigAreaImage(
            context = context,
            bigArea = bigArea,
            highlightInfo = highlightInfo,
            key = highlightInfo.bigImageLeft,
            picMap = picMap,
            allowFallback = true
        )
        addBigAreaImage(
            context = context,
            bigArea = bigArea,
            highlightInfo = highlightInfo,
            key = highlightInfo.bigImageRight,
            picMap = picMap,
            allowFallback = !leftAdded
        )

        if (bigArea.childCount > 0) {
            container.addView(bigArea)
        }
    }

    return container
}

fun resolveHighlightIconBitmap(
    context: Context,
    highlightInfo: HighlightInfo,
    picMap: Map<String, String>?
): Bitmap? {
    val key = selectIconKey(context, highlightInfo)
    return decodeBitmap(context, picMap, key)
}

private fun selectIconKey(context: Context, highlightInfo: HighlightInfo): String? {
    val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val preferDark = nightMask == Configuration.UI_MODE_NIGHT_YES
    val candidates = mutableListOf<String>()
    if (preferDark) {
        highlightInfo.picFunctionDark?.let(candidates::add)
        highlightInfo.picFunction?.let(candidates::add)
    } else {
        highlightInfo.picFunction?.let(candidates::add)
        highlightInfo.picFunctionDark?.let(candidates::add)
    }
    highlightInfo.bigImageLeft?.let { if (!candidates.contains(it)) candidates.add(it) }
    highlightInfo.bigImageRight?.let { if (!candidates.contains(it)) candidates.add(it) }
    return candidates.firstOrNull()
}

private fun addBigAreaImage(
    context: Context,
    bigArea: LinearLayout,
    highlightInfo: HighlightInfo,
    key: String?,
    picMap: Map<String, String>?,
    allowFallback: Boolean
): Boolean {
    val bitmap = decodeBitmap(context, picMap, key)
        ?: if (allowFallback) resolveHighlightIconBitmap(context, highlightInfo, picMap) else null
        ?: return false
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
    return true
}

private fun decodeBitmap(context: Context, picMap: Map<String, String>?, key: String?): Bitmap? {
    if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
    val raw = picMap[key] ?: return null
    val resolved = SuperIslandImageStore.resolve(context, raw) ?: raw
    return try {
        when {
            resolved.startsWith("data:", ignoreCase = true) -> DataUrlUtils.decodeDataUrlToBitmap(resolved)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

fun formatTimerInfo(timerInfo: TimerInfo, nowMillis: Long = System.currentTimeMillis()): String {
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

fun bindTimerUpdater(view: TextView, timerInfo: TimerInfo) {
    val updater = TimerTextUpdater(view, timerInfo)
    view.addOnAttachStateChangeListener(updater)
    if (view.isAttachedToWindow) {
        updater.start()
    }
}

private fun resolveStatusText(highlightInfo: HighlightInfo): String? {
    val preferred = listOfNotNull(
        highlightInfo.title,
        highlightInfo.content,
        highlightInfo.subContent
    ).firstOrNull { it.contains("进行") }
    if (!preferred.isNullOrBlank()) {
        return preferred
    }
    val base = listOfNotNull(
        highlightInfo.subContent,
        highlightInfo.title,
        highlightInfo.content
    ).firstOrNull { it.isNotBlank() } ?: return null
    return if (base.contains("进行")) base else base + "进行中"
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