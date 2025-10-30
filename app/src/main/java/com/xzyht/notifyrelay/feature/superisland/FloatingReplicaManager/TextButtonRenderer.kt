package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

// 文本按钮模板：按钮组件4，纯文字按钮
data class TextButton(
    val actions: List<ActionInfo> // 文字按钮列表（1-2个）
)

// 解析文本按钮组件（按钮组件4）
fun parseTextButton(json: JSONObject): TextButton {
    return TextButton(actions = json.optJSONArray("actions")?.let { parseActions(it) } ?: emptyList())
}

// 解析操作列表（按钮组件）
fun parseActions(jsonArray: JSONArray): List<ActionInfo> {
    val list = mutableListOf<ActionInfo>()
    for (i in 0 until jsonArray.length()) {
        jsonArray.optJSONObject(i)?.let { parseActionInfo(it) }?.let { list.add(it) }
    }
    return list
}

// 构建TextButton视图
fun buildTextButtonView(context: Context, textButton: TextButton, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    textButton.actions.forEach { action ->
        val btn = Button(context).apply {
            text = action.actionTitle ?: "按钮"
            setTextColor(parseColor(action.actionTitleColor) ?: 0xFFFFFFFF.toInt())
            action.actionBgColor?.let { setBackgroundColor(parseColor(it) ?: 0xFF333333.toInt()) }
        }
        container.addView(btn)
    }

    return container
}