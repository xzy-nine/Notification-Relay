package com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer

import android.content.Context
import android.text.Html
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import org.json.JSONObject

// 提示信息模板：按钮组件2和3，包含文本和按钮
data class HintInfo(
    val type: Int, // 组件类型：1按钮组件3，2按钮组件2
    val title: String? = null, // 主要文本：关键信息
    val timerInfo: TimerInfo? = null, // 时间信息
    val subTitle: String? = null, // 主要小文本2
    val content: String? = null, // 前置文本1或图文特殊标签文本
    val subContent: String? = null, // 前置文本2
    val picContent: String? = null, // 图文特殊标签图标资源key
    val colorTitle: String? = null, // 主要文本颜色
    val colorTitleDark: String? = null, // 深色模式主要文本颜色
    val colorSubTitle: String? = null, // 主要小文本2颜色
    val colorSubTitleDark: String? = null, // 深色模式主要小文本2颜色
    val colorContent: String? = null, // 前置文本1颜色
    val colorContentDark: String? = null, // 深色模式前置文本1颜色
    val colorSubContent: String? = null, // 前置文本2颜色
    val colorSubContentDark: String? = null, // 深色模式前置文本2颜色
    val colorContentBg: String? = null, // 图文特殊标签背景色
    val actionInfo: ActionInfo? = null // 按钮操作信息
)

// 解析提示信息组件（按钮组件2和3）
fun parseHintInfo(json: JSONObject): HintInfo {
    return HintInfo(
        type = json.optInt("type", 1),
        title = json.optString("title", "").takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        subTitle = json.optString("subTitle", "").takeIf { it.isNotEmpty() },
        content = json.optString("content", "").takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", "").takeIf { it.isNotEmpty() },
        picContent = json.optString("picContent", "").takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", "").takeIf { it.isNotEmpty() },
        colorSubTitle = json.optString("colorSubTitle", "").takeIf { it.isNotEmpty() },
        colorSubTitleDark = json.optString("colorSubTitleDark", "").takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", "").takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", "").takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", "").takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", "").takeIf { it.isNotEmpty() },
        colorContentBg = json.optString("colorContentBg", "").takeIf { it.isNotEmpty() },
        actionInfo = json.optJSONObject("actionInfo")?.let { parseActionInfo(it) }
    )
}

// 构建HintInfo视图
fun buildHintInfoView(context: Context, hintInfo: HintInfo, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val tv = TextView(context).apply {
        text = Html.fromHtml(unescapeHtml(hintInfo.title ?: "提示信息"), Html.FROM_HTML_MODE_COMPACT)
        setTextColor(parseColor(hintInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
        textSize = 14f
    }
    container.addView(tv)

    return container
}