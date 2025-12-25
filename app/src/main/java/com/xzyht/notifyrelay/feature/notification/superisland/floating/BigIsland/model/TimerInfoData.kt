package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model

import android.os.Handler
import android.os.Looper
import android.widget.TextView
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

// 简化的CircularProgressBinding类，用于兼容现有代码
data class CircularProgressBinding(
    val updateProgress: (Float) -> Unit,
    val setMax: (Int) -> Unit
)

// 绑定计时器更新器，实现传统View的计时器更新
fun bindTimerUpdater(view: Any, timerInfo: TimerInfo?) {
    if (view !is TextView || timerInfo == null) {
        return
    }
    
    // 立即更新一次时间
    view.text = formatTimerInfo(timerInfo)
    
    // 检查是否需要持续更新
    val isPaused = timerInfo.timerType == -2 || timerInfo.timerType == 2
    if (isPaused) {
        // 暂停状态：只更新一次，不持续
        return
    }
    
    // 进行中状态：每秒更新一次
    val handler = Handler(Looper.getMainLooper())
    val updateRunnable = object : Runnable {
        override fun run() {
            view.text = formatTimerInfo(timerInfo)
            // 每秒更新一次
            handler.postDelayed(this, 1000)
        }
    }
    
    // 每秒更新一次
    handler.postDelayed(updateRunnable, 1000)
    
    // 将Handler和Runnable存储到View的tag中，以便后续可以取消
    view.tag = Pair(handler, updateRunnable)
}