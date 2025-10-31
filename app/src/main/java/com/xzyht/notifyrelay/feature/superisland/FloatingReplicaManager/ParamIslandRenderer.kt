package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 param_island 节点，提取小岛/大岛摘要所需的基础文本与图标信息。
 */
data class ParamIsland(
    val smallIslandArea: SmallIslandArea? = null,
    val bigIslandArea: JSONObject? = null
)

data class SmallIslandArea(
    val primaryText: String? = null,
    val secondaryText: String? = null,
    val iconKey: String? = null
)

fun parseParamIsland(json: JSONObject): ParamIsland {
    val small = json.optJSONObject("smallIslandArea")?.let { parseSmallIslandArea(it) }
        ?: json.optJSONObject("smallIsland")?.let { parseSmallIslandArea(it) }
    val big = json.optJSONObject("bigIslandArea") ?: json.optJSONObject("bigIsland")
    return ParamIsland(smallIslandArea = small, bigIslandArea = big)
}

private fun parseSmallIslandArea(obj: JSONObject): SmallIslandArea {
    val primary = extractPrimaryText(obj)
    val secondary = extractSecondaryText(obj)
    val icon = extractIconKey(obj)
    return SmallIslandArea(primaryText = primary, secondaryText = secondary, iconKey = icon)
}

private fun extractPrimaryText(obj: JSONObject): String? {
    val direct = firstString(obj, PRIMARY_TEXT_KEYS)
    if (direct != null) return direct
    for (nestedKey in NESTED_TEXT_KEYS) {
        obj.optJSONObject(nestedKey)?.let { nested ->
            val value = extractPrimaryText(nested)
            if (!value.isNullOrBlank()) return value
        }
    }
    val fromArray = firstFromArrays(obj, ARRAY_KEYS) { extractPrimaryText(it) }
    return fromArray?.takeIf { it.isNotBlank() }
}

private fun extractSecondaryText(obj: JSONObject): String? {
    val direct = firstString(obj, SECONDARY_TEXT_KEYS)
    if (direct != null) return direct
    for (nestedKey in NESTED_TEXT_KEYS) {
        obj.optJSONObject(nestedKey)?.let { nested ->
            val value = extractSecondaryText(nested)
            if (!value.isNullOrBlank()) return value
        }
    }
    val fromArray = firstFromArrays(obj, ARRAY_KEYS) { extractSecondaryText(it) }
    return fromArray?.takeIf { it.isNotBlank() }
}

private fun extractIconKey(obj: JSONObject): String? {
    val direct = firstString(obj, ICON_TEXT_KEYS)
    if (!direct.isNullOrBlank()) return direct
    for (nestedKey in ICON_OBJECT_KEYS) {
        obj.optJSONObject(nestedKey)?.let { nested ->
            val nestedValue = extractIconKey(nested)
            if (!nestedValue.isNullOrBlank()) return nestedValue
        }
    }
    val fromArray = firstFromArrays(obj, ARRAY_KEYS) { extractIconKey(it) }
    return fromArray?.takeIf { it.isNotBlank() }
}

private fun firstString(obj: JSONObject, keys: Array<String>): String? {
    for (key in keys) {
        val value = runCatching { obj.getString(key) }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun <T> firstFromArrays(
    obj: JSONObject,
    arrayKeys: Array<String>,
    extractor: (JSONObject) -> T?
): T? {
    for (arrayKey in arrayKeys) {
        val arr = obj.optJSONArray(arrayKey) ?: continue
        val result = firstFromArray(arr, extractor)
        if (result != null) return result
    }
    return null
}

private fun <T> firstFromArray(array: JSONArray, extractor: (JSONObject) -> T?): T? {
    for (index in 0 until array.length()) {
        val child = array.optJSONObject(index) ?: continue
        val value = extractor(child)
        if (value != null) return value
    }
    return null
}

private val PRIMARY_TEXT_KEYS = arrayOf(
    "title",
    "primaryText",
    "frontTitle",
    "mainText",
    "mainTitle",
    "largeText",
    "bigText",
    "text"
)

private val SECONDARY_TEXT_KEYS = arrayOf(
    "content",
    "secondaryText",
    "subTitle",
    "subContent",
    "afterText",
    "tailText"
)

private val ICON_TEXT_KEYS = arrayOf(
    "icon",
    "iconKey",
    "pic",
    "picContent",
    "picFunction",
    "picIcon",
    "picUrl",
    "src"
)

private val ICON_OBJECT_KEYS = arrayOf(
    "iconInfo",
    "picInfo",
    "icon",
    "animIconInfo",
    "imageInfo",
    "imageIcon"
)

private val NESTED_TEXT_KEYS = arrayOf(
    "textInfo",
    "leftTextInfo",
    "iconTextInfo",
    "imageTextInfoLeft",
    "imageTextInfoRight",
    "imageTextInfo",
    "smallTextInfo"
)

private val ARRAY_KEYS = arrayOf(
    "components",
    "componentList",
    "items",
    "subItems"
)
