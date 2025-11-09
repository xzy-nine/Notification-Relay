package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject

// 进度信息：定义进度条的样式和状态
data class ProgressInfo(
    val progress: Int, // 当前进度百分比
    val colorProgress: String? = null, // 进度条起始颜色
    val colorProgressEnd: String? = null, // 进度条结束颜色
    val picForward: String? = null, // 前进图形资源key
    val picMiddle: String? = null, // 中间节点选中状态资源key
    val picMiddleUnselected: String? = null, // 中间节点未选中状态资源key
    val picEnd: String? = null, // 目标点选中状态资源key
    val picEndUnselected: String? = null, // 目标点未选中状态资源key
    val isCCW: Boolean? = null, // 是否逆时针旋转
    val isAutoProgress: Boolean? = null // 是否自动更新进度
)

// 解析进度信息组件
fun parseProgressInfo(json: JSONObject): ProgressInfo {
    return ProgressInfo(
        progress = json.optInt("progress", 0),
        colorProgress = json.optString("colorProgress", "").takeIf { it.isNotEmpty() },
        colorProgressEnd = json.optString("colorProgressEnd", "").takeIf { it.isNotEmpty() },
        picForward = json.optString("picForward", "").takeIf { it.isNotEmpty() },
        picMiddle = json.optString("picMiddle", "").takeIf { it.isNotEmpty() },
        picMiddleUnselected = json.optString("picMiddleUnselected", "").takeIf { it.isNotEmpty() },
        picEnd = json.optString("picEnd", "").takeIf { it.isNotEmpty() },
        picEndUnselected = json.optString("picEndUnselected", "").takeIf { it.isNotEmpty() },
        isCCW = json.optBoolean("isCCW", false),
        isAutoProgress = json.optBoolean("isAutoProgress", false)
    )
}

// 构建ProgressInfo视图
fun buildProgressInfoView(context: Context, progressInfo: ProgressInfo, picMap: Map<String, String>?): ProgressBar {
    val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = progressInfo.progress
        progressInfo.colorProgress?.let { color -> progressTintList = android.content.res.ColorStateList.valueOf(parseColor(color) ?: 0xFF00FF00.toInt()) }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, (4 * context.resources.displayMetrics.density).toInt(), 0, 0)
        layoutParams = lp
    }
    return progressBar
}