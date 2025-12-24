package com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer

import org.json.JSONObject

// 图片信息模板：识别图形组件，显示应用图标或自定义图片
data class PicInfo(
    val type: Int, // 组件类型：1 appIcon，2 middle，3 large，5 倒计时带图
    val pic: String? = null, // 图片资源key（type=2/3时必传）
    val picDark: String? = null, // 深色模式图片资源key
    val actionInfo: ActionInfo? = null, // 操作信息
    val title: String? = null, // 组件文字（type=5时使用）
    val colorTitle: String? = null // 文字颜色（type=5时使用）
)

// 解析图片信息组件（识别图形组件）
fun parsePicInfo(json: JSONObject): PicInfo {
    return PicInfo(
        type = json.optInt("type", 1),
        pic = json.optString("pic", "").takeIf { it.isNotEmpty() },
        picDark = json.optString("picDark", "").takeIf { it.isNotEmpty() },
        actionInfo = json.optJSONObject("actionInfo")?.let { parseActionInfo(it) },
        title = json.optString("title", "").takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotEmpty() }
    )
}

