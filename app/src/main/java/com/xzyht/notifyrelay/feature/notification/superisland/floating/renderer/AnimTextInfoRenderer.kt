package com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer

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






