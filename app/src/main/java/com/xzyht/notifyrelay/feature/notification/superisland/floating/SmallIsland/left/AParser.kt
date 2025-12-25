package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left

import org.json.JSONObject

/**
 * 解析 A区（imageTextInfoLeft）。
 */
fun parseAComponent(bigIsland: JSONObject?): AComponent? {
    val left = bigIsland?.optJSONObject("imageTextInfoLeft") ?: return null
    val type = left.optInt("type", 0)

    return when (type) {
        1 -> {
            val textInfo = left.optJSONObject("textInfo")
            val title = left.optString("title", "").takeIf { it.isNotBlank() }
                ?: textInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
            val content = left.optString("content", "").takeIf { it.isNotBlank() }
                ?: textInfo?.optString("content", "")?.takeIf { it.isNotBlank() }
            val narrowFont = textInfo?.optBoolean("narrowFont", false) ?: false
            val showHighlightColor = textInfo?.optBoolean("showHighlightColor", false) ?: false

            val picInfo = left.optJSONObject("picInfo")
            val t = picInfo?.optInt("type", 0) ?: 0
            // 兼容多类：
    // - type=1/4：按 pic 字段作为 picKey 使用（4 为静态图资源键）
    // - type=2（系统内置资源占位）：本应用不读取系统资源，此处不设主键，渲染时仅从 picMap 的 miui.focus.pic_* 集合中挑选（“第二位优先”）
    // - type=3：系统内置图标，pic字段可能不是有效的miui.focus键，允许picKey为空，交由渲染期处理
    val picKey: String? = when (t) {
        1, 4 -> picInfo?.optString("pic", "")?.takeIf { it.isNotBlank() }
        2, 3 -> null
        else -> null
    }
    // 对于 type=4（静态图资源键），pic 字段必须有效；对于 type=1/2/3，允许 picKey 为空，交由渲染期从 pic_* 集合中挑选
    // 注意：type=1 不再强制要求picKey，因为有些系统通知可能没有有效的pic字段，但有picMap中的图标
    val mustHavePicKey = (t == 4)
    if (mustHavePicKey && picKey == null) return null

            AImageText1(
                title = title,
                content = content,
                narrowFont = narrowFont,
                showHighlightColor = showHighlightColor,
                picKey = picKey
            )
        }
        5 -> {
            val textInfo = left.optJSONObject("textInfo")
            val title = textInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
                ?: left.optString("title", "").takeIf { it.isNotBlank() }
            val content = textInfo?.optString("content", "")?.takeIf { it.isNotBlank() }
                ?: left.optString("content", "").takeIf { it.isNotBlank() }
            val showHighlightColor = textInfo?.optBoolean("showHighlightColor", false) ?: false

            val picInfo = left.optJSONObject("picInfo")
            val picTypeOk = (picInfo?.optInt("type", 0) == 4)
            val picKey = picInfo?.optString("pic", "")?.takeIf { it.isNotBlank() }

            // 必填：title、picInfo.type==4、picKey
            if (title == null || !picTypeOk || picKey == null) return null

            AImageText5(
                title = title,
                content = content,
                showHighlightColor = showHighlightColor,
                picKey = picKey
            )
        }
        else -> null
    }
}
