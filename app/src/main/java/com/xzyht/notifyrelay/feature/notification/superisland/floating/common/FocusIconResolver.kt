package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

/**
 * 实现与View版本相同的图标加载逻辑
 * 1) 若传入了主键（例如 miui.focus.pic_app_icon），应优先使用它
 * 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
 * 3) 按"优先第二位"的规则重排（针对 type=2 无主键场景）：若有第二项，将其放到首位，其余保持原始相对顺序
 * 4) 遍历所有候选图标，尝试加载
 * 5) 所有候选图标都加载失败时，尝试加载 miui.focus.pic_app_icon 作为兜底
 */
fun getFocusIconUrl(picMap: Map<String, String>?, primaryKey: String?): String? {
    // 1) 若传入了主键，应优先使用它
    if (!primaryKey.isNullOrBlank()) {
        picMap?.get(primaryKey)?.let { return it }
    }

    // 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
    val ordered = picMap?.keys?.asSequence()
        ?.filter { it.startsWith("miui.focus.pic_", ignoreCase = true) && !it.equals("miui.focus.pics", true) }
        ?.toList()
        ?: emptyList()

    // 3) 按"优先第二位"的规则重排：若有第二项，将其放到首位，其余保持原始相对顺序
    val candidates = if (ordered.size >= 2) {
        listOf(ordered[1]) + ordered.filterIndexed { idx, _ -> idx != 1 }
    } else ordered

    // 4) 避免重复尝试主键
    val finalList = if (!primaryKey.isNullOrBlank()) candidates.filterNot { it.equals(primaryKey, true) } else candidates

    // 5) 遍历所有候选图标，尝试加载
    for (k in finalList) {
        picMap?.get(k)?.let { return it }
    }

    // 6) 所有候选图标都加载失败时，尝试加载 miui.focus.pic_app_icon 作为兜底
    if (!primaryKey.equals("miui.focus.pic_app_icon", true)) {
        picMap?.get("miui.focus.pic_app_icon")?.let { return it }
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
    primaryKey: String?
): String? {
    val url = getFocusIconUrl(picMap, primaryKey)
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