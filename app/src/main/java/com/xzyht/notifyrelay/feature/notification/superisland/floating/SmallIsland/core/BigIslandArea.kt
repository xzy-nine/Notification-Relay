package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.core

import org.json.JSONObject

/**
 * 集中解析和承载 "bigIslandArea"（超级岛的摘要 / 收起态）相关的数据结构与工具函数。
 * 其他模块请使用 parseBigIslandArea(...) 获取统一的 BigIslandArea 对象。
 */
data class BigIslandArea(
    val primaryText: String? = null,
    val secondaryText: String? = null,
    val leftImage: String? = null,
    val rightImage: String? = null
)

fun parseBigIslandArea(json: JSONObject?): BigIslandArea? {
    if (json == null) return null

    val leftPic = json.optJSONObject("imageTextInfoLeft")
        ?.optJSONObject("picInfo")
        ?.optString("pic", "")
        ?.takeIf { it.isNotBlank() }

    val rightPic = json.optJSONObject("imageTextInfoRight")
        ?.optJSONObject("picInfo")
        ?.optString("pic", "")
        ?.takeIf { it.isNotBlank() }

    val primary = firstString(json, arrayOf(
        "title",
        "primaryText",
        "frontTitle",
        "mainText",
        "mainTitle",
        "largeText",
        "bigText",
        "text"
    ))

    val secondary = firstString(json, arrayOf(
        "content",
        "secondaryText",
        "subTitle",
        "subContent",
        "afterText",
        "tailText"
    ))

    // 如果 primary/secondary 为 null，尝试在嵌套对象或数组里查找（有限的递归深度，避免复杂依赖）
    val finalPrimary = primary ?: nestedFirstString(json, arrayOf("textInfo", "iconTextInfo", "imageTextInfoLeft", "imageTextInfoRight"))
    val finalSecondary = secondary ?: nestedFirstString(json, arrayOf("textInfo", "iconTextInfo", "imageTextInfoLeft", "imageTextInfoRight"))

    return BigIslandArea(
        primaryText = finalPrimary,
        secondaryText = finalSecondary,
        leftImage = leftPic,
        rightImage = rightPic
    )
}

private fun firstString(obj: JSONObject, keys: Array<String>): String? {
    for (key in keys) {
        val value = runCatching { obj.getString(key) }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun nestedFirstString(obj: JSONObject, keys: Array<String>): String? {
    for (key in keys) {
        val nested = obj.optJSONObject(key) ?: continue
        val direct = firstString(nested, arrayOf("title", "text", "content", "primaryText", "secondaryText"))
        if (!direct.isNullOrBlank()) return direct
        // 支持数组中的第一项
        val arr = nested.optJSONArray("components") ?: nested.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val child = arr.optJSONObject(i) ?: continue
                val found = nestedFirstString(child, keys)
                if (!found.isNullOrBlank()) return found
            }
        }
    }
    return null
}