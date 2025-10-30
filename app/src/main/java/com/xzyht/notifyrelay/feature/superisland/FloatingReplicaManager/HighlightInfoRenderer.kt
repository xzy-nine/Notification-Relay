package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

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
    val colorSubContentDark: String? = null // 深色模式辅助文本2颜色
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
        colorSubContentDark = json.optString("colorSubContentDark", "").takeIf { it.isNotEmpty() }
    )
}

// 构建HighlightInfo视图
fun buildHighlightInfoView(context: Context, highlightInfo: HighlightInfo, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val tv = TextView(context).apply {
        text = highlightInfo.title ?: "高亮信息"
        setTextColor(parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
        textSize = 14f
    }
    container.addView(tv)

    return container
}