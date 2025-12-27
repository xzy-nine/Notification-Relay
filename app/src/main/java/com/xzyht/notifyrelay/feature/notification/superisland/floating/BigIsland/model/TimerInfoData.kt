package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model

import org.json.JSONObject

// 定时器信息：用于计时功能，支持倒计时、正计时等
data class TimerInfo(
    val timerType: Int, // 计时类型：-2倒计时暂停，-1倒计时开始，0默认，1正计时开始，2正计时暂停
    val timerWhen: Long, // 计时起点时间戳（毫秒）
    val timerTotal: Long, // 计时总进度
    val timerSystemCurrent: Long // 计时系统当前时间戳（毫秒）
)

// 解析定时器信息组件
fun parseTimerInfo(json: JSONObject): TimerInfo {
    return TimerInfo(
        timerType = json.optInt("timerType", 0),
        timerWhen = json.optLong("timerWhen", 0),
        timerTotal = json.optLong("timerTotal", 0),
        timerSystemCurrent = json.optLong("timerSystemCurrent", 0)
    )
}

// 格式化计时器信息为显示文本
// 使用CommonCompose.kt中的公共实现
fun formatTimerInfo(timerInfo: TimerInfo): String {
    return com.xzyht.notifyrelay.feature.notification.superisland.floating.common.formatTimerInfo(timerInfo)
}