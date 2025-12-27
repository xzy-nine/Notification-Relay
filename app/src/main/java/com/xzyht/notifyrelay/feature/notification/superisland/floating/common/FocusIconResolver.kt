package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

/**
 * 实现与View版本相同的图标加载逻辑
 * 1) 若传入了主键（例如 miui.focus.pic_app_icon），应优先使用它
 * 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
 * 3) 按主题优先选择 expand_light/expand_dark，再按指定顺序尝试其他图标
 * 4) 遍历所有候选图标，尝试加载
 */
fun getFocusIconUrl(picMap: Map<String, String>?, primaryKey: String?, isDarkTheme: Boolean): String? {
    // 1) 若传入了主键且为miui.focus.*形式，应优先使用它（支持pic_和ic_两种前缀）
    if (!primaryKey.isNullOrBlank() && primaryKey.startsWith("miui.focus.", ignoreCase = true)) {
        picMap?.get(primaryKey)?.let { return it }
    }

    // 2) 根据主题选择优先加载的expand图标
    val expandPriority = if (isDarkTheme) {
        listOf("miui.focus.pic_expand_dark", "miui.focus.pic_expand_light")
    } else {
        listOf("miui.focus.pic_expand_light", "miui.focus.pic_expand_dark")
    }

    // 3) 先尝试加载主题对应的expand图标
    for (expandKey in expandPriority) {
        if (primaryKey != null && primaryKey.equals(expandKey, true)) continue
        picMap?.get(expandKey)?.let { return it }
    }

    // 4) 再按照指定优先级尝试加载其他图标
    val otherPriorityOrder = listOf(
        "miui.focus.pic_aod",
        "miui.focus.pic_ado_pic",
        "miui.focus.pic_app_icon"
    )

    for (priorityKey in otherPriorityOrder) {
        if (primaryKey != null && primaryKey.equals(priorityKey, true)) continue
        picMap?.get(priorityKey)?.let { return it }
    }

    // 5) 遍历所有miui.focus.ic_开头的图标，这些通常是具体功能图标
    val icKeys = picMap?.keys?.asSequence()
        ?.filter { 
            it.startsWith("miui.focus.ic_", ignoreCase = true) && 
            !it.equals("miui.focus.pics", true) && 
            (primaryKey == null || !primaryKey.equals(it, true))
        }
        ?.toList()
        ?: emptyList()

    for (k in icKeys) {
        picMap?.get(k)?.let { return it }
    }

    // 6) 遍历所有其他miui.focus.pic_开头的键
    val allTriedKeys = expandPriority + otherPriorityOrder
    val otherPicKeys = picMap?.keys?.asSequence()
        ?.filter { 
            it.startsWith("miui.focus.pic_", ignoreCase = true) && 
            !it.equals("miui.focus.pics", true) && 
            !allTriedKeys.any { triedKey -> triedKey.equals(it, true) } && 
            (primaryKey == null || !primaryKey.equals(it, true))
        }
        ?.toList()
        ?: emptyList()

    for (k in otherPicKeys) {
        picMap?.get(k)?.let { return it }
    }

    return null
}

/**
 * 解析图标URL，支持ref: URL和data: URL
 */
fun resolveIconUrl(
    picMap: Map<String, String>?,
    iconKey: String?,
    context: android.content.Context? = null
): String? {
    if (iconKey.isNullOrEmpty() || picMap == null) return null

    // 1. 从picMap获取原始URL
    var url = picMap[iconKey]
    if (url.isNullOrEmpty()) return null

    // 2. 处理ref: URL
    if (url.startsWith("ref:", ignoreCase = true)) {
        url = try {
            SuperIslandImageStore.resolve(context, url) ?: url
        } catch (_: Exception) {
            url
        }
    }

    return url
}

/**
 * 解析焦点图标URL，与View版本的逻辑完全一致
 */
fun resolveFocusIconUrl(
    picMap: Map<String, String>?, 
    primaryKey: String?,
    isDarkTheme: Boolean = false
): String? {
    val url = getFocusIconUrl(picMap, primaryKey, isDarkTheme)
    if (url.isNullOrEmpty()) return null

    // 处理ref: URL
    if (url.startsWith("ref:", ignoreCase = true)) {
        return try {
            SuperIslandImageStore.resolve(null, url) ?: url
        } catch (_: Exception) {
            url
        }
    }

    return url
}