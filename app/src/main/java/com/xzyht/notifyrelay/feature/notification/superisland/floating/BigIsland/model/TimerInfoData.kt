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
fun formatTimerInfo(timerInfo: TimerInfo): String {
    val now = System.currentTimeMillis()
    val timerWhen = timerInfo.timerWhen // 倒计时结束时间
    val timerSystemCurrent = timerInfo.timerSystemCurrent // 服务器发送时的时间戳
    val timerTotal = timerInfo.timerTotal // 总时长（毫秒）
    
    val displayValue: Long = when (timerInfo.timerType) {
        -2 -> { // 倒计时暂停
            // 暂停状态：剩余时间 = 结束时间 - 发送时间，保持不变
            val remainingAtSend = timerWhen - timerSystemCurrent
            remainingAtSend.coerceAtLeast(0)
        }
        -1 -> { // 倒计时进行中
            // 进行中状态：剩余时间 = 结束时间 - 当前时间
            val remaining = timerWhen - now
            remaining.coerceAtLeast(0)
        }
        2 -> { // 正计时暂停
            // 暂停状态：已过时间 = 发送时间 - 开始时间，保持不变
            val elapsedAtSend = timerSystemCurrent - timerWhen
            elapsedAtSend.coerceAtLeast(0)
        }
        1 -> { // 正计时进行中
            // 进行中状态：已过时间 = 当前时间 - 开始时间
            val elapsed = now - timerWhen
            elapsed.coerceAtLeast(0)
        }
        else -> 0
    }
    
    val seconds = (displayValue / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format("%02d:%02d", minutes % 60, seconds % 60)
    }
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