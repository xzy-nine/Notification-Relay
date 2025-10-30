package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

// 多进度信息：多段进度条组件
data class MultiProgressInfo(
    val title: String, // 进度描述文本
    val progress: Int, // 当前进度百分比
    val color: String? = null, // 进度条颜色
    val points: Int? = null // 节点数量（0-4）
)

// 解析多进度信息组件
fun parseMultiProgressInfo(json: JSONObject): MultiProgressInfo {
    return MultiProgressInfo(
        title = json.optString("title", ""),
        progress = json.optInt("progress", 0),
        color = json.optString("color", "").takeIf { it.isNotEmpty() },
        points = json.optInt("points", -1).takeIf { it != -1 }
    )
}

// 构建MultiProgressInfo视图
fun buildMultiProgressInfoView(context: Context, multiProgressInfo: MultiProgressInfo, picMap: Map<String, String>?): TextView {
    val tv = TextView(context).apply {
        text = "${multiProgressInfo.title}: ${multiProgressInfo.progress}%"
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
    }
    return tv
}