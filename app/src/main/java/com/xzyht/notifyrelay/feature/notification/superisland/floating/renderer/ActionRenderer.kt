package com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer

import org.json.JSONObject

// 操作信息：定义按钮或操作的行为和样式
data class ActionInfo(
    val action: String? = null, // 系统预定义action的key
    val actionIcon: String? = null, // 按钮图标资源key
    val actionIconDark: String? = null, // 深色模式按钮图标
    val actionTitle: String? = null, // 按钮文本
    val actionTitleColor: String? = null, // 按钮文本颜色
    val actionTitleColorDark: String? = null, // 深色模式按钮文本颜色
    val actionBgColor: String? = null, // 按钮背景色
    val actionBgColorDark: String? = null, // 深色模式按钮背景色
    val actionIntentType: Int? = null, // 跳转类型：1 activity, 2 broadcast, 3 service
    val actionIntent: String? = null, // 跳转URI
    val clickWithCollapse: Boolean? = null, // 点击是否收起面板
    val type: Int? = null, // 按钮类型：0普通，1进度，2文字
    val progressInfo: ProgressInfo? = null // 进度按钮的进度信息
)

// 解析操作信息组件（按钮等）
fun parseActionInfo(json: JSONObject): ActionInfo {
    return ActionInfo(
        action = json.optString("action", "").takeIf { it.isNotEmpty() },
        actionIcon = json.optString("actionIcon", "").takeIf { it.isNotEmpty() },
        actionIconDark = json.optString("actionIconDark", "").takeIf { it.isNotEmpty() },
        actionTitle = json.optString("actionTitle", "").takeIf { it.isNotEmpty() },
        actionTitleColor = json.optString("actionTitleColor", "").takeIf { it.isNotEmpty() },
        actionTitleColorDark = json.optString("actionTitleColorDark", "").takeIf { it.isNotEmpty() },
        actionBgColor = json.optString("actionBgColor", "").takeIf { it.isNotEmpty() },
        actionBgColorDark = json.optString("actionBgColorDark", "").takeIf { it.isNotEmpty() },
        actionIntentType = json.optInt("actionIntentType", -1).takeIf { it != -1 },
        actionIntent = json.optString("actionIntent", "").takeIf { it.isNotEmpty() },
        clickWithCollapse = json.optBoolean("clickWithCollapse", false),
        type = json.optInt("type", -1).takeIf { it != -1 },
        progressInfo = json.optJSONObject("progressInfo")?.let { parseProgressInfo(it) }
    )
}

// 按钮UI构建已移除，仅保留数据解析逻辑