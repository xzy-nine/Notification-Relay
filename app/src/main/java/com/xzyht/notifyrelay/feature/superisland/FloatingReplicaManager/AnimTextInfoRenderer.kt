package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.core.util.ImageLoader
import org.json.JSONObject

// 动画文本组件：animTextInfo（独立于 HighlightInfo）
data class AnimIconInfo(
    val src: String,
    val srcDark: String? = null
)

data class AnimTextInfo(
    val icon: AnimIconInfo,
    val title: String? = null,        // 主要文本（与 timerInfo 至少二选一）
    val content: String? = null,      // 次要文本
    val timerInfo: TimerInfo? = null, // 计时信息
    val colorTitle: String? = null,
    val colorTitleDark: String? = null,
    val colorContent: String? = null,
    val colorContentDark: String? = null
)

fun parseAnimTextInfo(json: JSONObject): AnimTextInfo? {
    val iconObj = json.optJSONObject("animIconInfo") ?: return null
    val src = iconObj.optString("src", "").takeIf { it.isNotBlank() } ?: return null
    val srcDark = iconObj.optString("srcDark", "").takeIf { it.isNotBlank() }

    val title = json.optString("title", "").takeIf { it.isNotBlank() }
    val tInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) }
    if (title.isNullOrBlank() && tInfo == null) return null // 至少二选一

    return AnimTextInfo(
        icon = AnimIconInfo(src = src, srcDark = srcDark),
        title = title,
        content = json.optString("content", "").takeIf { it.isNotBlank() },
        timerInfo = tInfo,
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotBlank() },
        colorTitleDark = json.optString("colorTitleDark", "").takeIf { it.isNotBlank() },
        colorContent = json.optString("colorContent", "").takeIf { it.isNotBlank() },
        colorContentDark = json.optString("colorContentDark", "").takeIf { it.isNotBlank() }
    )
}

fun buildAnimTextInfoView(context: Context, info: AnimTextInfo, picMap: Map<String, String>?): LinearLayout {
    val density = context.resources.displayMetrics.density
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // 左侧 动画/图片
    val iconSize = (40 * density).toInt()
    val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val preferDark = nightMask == Configuration.UI_MODE_NIGHT_YES
    val iconKey = if (preferDark) (info.icon.srcDark ?: info.icon.src) else info.icon.src
    // 优先加载 animIconInfo 指定的资源；若 picMap 中没有该键，则仅回退到系统传入的 focus 图集合（miui.focus.pic_*）中的“第二个图”。
    // 第二个图的选择规则：按 picMap 的原始插入顺序筛选所有以 miui.focus.pic_ 开头的键，取第 2 个（index=1）。
    var iconVisible = ImageLoader.loadKeyInto(iconView, picMap, iconKey)
    if (!iconVisible) {
        // 仅尝试“第二个图”：按插入顺序筛选 miui.focus.pic_* 后取 index=1（若存在）
        val prefix = "miui.focus.pic_"
        val focusKeys = picMap?.keys
            ?.filter { it.startsWith(prefix) }
            ?.toList()
            ?: emptyList()

        val secondKey = focusKeys.getOrNull(1)
        if (secondKey != null) {
            iconVisible = ImageLoader.loadKeyInto(iconView, picMap, secondKey)
        }
    }
    if (!iconVisible) {
        iconView.visibility = View.GONE
    }
    container.addView(iconView)

    // 右侧 文本区
    val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        // 若左侧无图标则取消起始间距
        lp.setMargins(if (iconView.visibility == View.VISIBLE) (8 * density).toInt() else 0, 0, 0, 0)
        layoutParams = lp
    }

    val formattedTimer = info.timerInfo?.let { formatTimerInfo(it) }
    val major = info.title ?: formattedTimer
    major?.let { text ->
        val color = safeParseColor(if (preferDark) info.colorTitleDark else info.colorTitle) ?: 0xFFFFFFFF.toInt()
        val tv = TextView(context).apply {
            this.text = Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT)
            setTextColor(color)
            textSize = 15f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            if (text.matches(Regex("[0-9: ]{2,}"))) typeface = Typeface.MONOSPACE
        }
        if (info.title.isNullOrBlank() && info.timerInfo != null) bindTimerUpdater(tv, info.timerInfo)
        textColumn.addView(tv)
    }

    val secondary = info.content ?: run {
        if (!info.title.isNullOrBlank() && formattedTimer != null) formattedTimer else null
    }
    secondary?.let { text ->
        val color = safeParseColor(if (preferDark) info.colorContentDark else info.colorContent) ?: 0xFFDDDDDD.toInt()
        val tv = TextView(context).apply {
            this.text = Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT)
            setTextColor(color)
            textSize = 12f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        if (info.content == null && !info.title.isNullOrBlank() && info.timerInfo != null) bindTimerUpdater(tv, info.timerInfo)
        textColumn.addView(tv)
    }

    if (textColumn.childCount > 0) container.addView(textColumn)
    return container
}

private fun safeParseColor(s: String?): Int? = try {
    s?.let { Color.parseColor(it) }
} catch (_: Exception) { null }




