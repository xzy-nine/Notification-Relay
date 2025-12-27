package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model

import org.json.JSONObject

// 多进度信息：多段进度条组件
data class MultiProgressInfo(
    val title: String, // 进度描述文本
    val progress: Int, // 当前进度百分比
    val color: String? = null, // 进度条颜色
    val points: Int? = null, // 节点数量（0-4）
    val picForward: String? = null, // 进度指示点
    val picForwardWait: String? = null, // 目标指示点
    val picForwardBox: String? = null, // 进度条背景块
    val picMiddle: String? = null, // 激活的中间节点
    val picMiddleUnselected: String? = null, // 未激活的中间节点
    val picEnd: String? = null, // 激活的末尾节点
    val picEndUnselected: String? = null // 未激活的末尾节点
)

// 解析多进度信息组件
fun parseMultiProgressInfo(json: JSONObject): MultiProgressInfo {
    val middleUnselected = sequenceOf(
        json.optString("picMiddleUnselected", ""),
        json.optString("picMiddelUnselected", "")
    ).firstOrNull { it.isNotEmpty() }
    return MultiProgressInfo(
        title = json.optString("title", ""),
        progress = json.optInt("progress", 0),
        color = json.optString("color", "").takeIf { it.isNotEmpty() },
        points = json.optInt("points", -1).takeIf { it != -1 },
        picForward = json.optString("picForward", "").takeIf { it.isNotEmpty() },
        picForwardWait = json.optString("picForwardWait", "").takeIf { it.isNotEmpty() },
        picForwardBox = json.optString("picForwardBox", "").takeIf { it.isNotEmpty() },
        picMiddle = json.optString("picMiddle", "").takeIf { it.isNotEmpty() },
        picMiddleUnselected = middleUnselected,
        picEnd = json.optString("picEnd", "").takeIf { it.isNotEmpty() },
        picEndUnselected = json.optString("picEndUnselected", "").takeIf { it.isNotEmpty() }
    )
}

// 根据 ProgressInfo 构造多节点进度信息
fun ProgressInfo.toMultiProgressInfo(title: String? = null, pointsOverride: Int? = null): MultiProgressInfo? {
    val hasNodeAssets = listOf(picMiddle, picMiddleUnselected, picEnd, picEndUnselected, picForward)
        .any { !it.isNullOrBlank() }
    if (!hasNodeAssets) return null

    val resolvedColor = colorProgress ?: colorProgressEnd
    return MultiProgressInfo(
        title = title?.trim().orEmpty(),
        progress = progress,
        color = resolvedColor,
        points = pointsOverride,
        picForward = picForward,
        picForwardWait = null,
        picForwardBox = null,
        picMiddle = picMiddle,
        picMiddleUnselected = picMiddleUnselected,
        picEnd = picEnd,
        picEndUnselected = picEndUnselected
    )
}