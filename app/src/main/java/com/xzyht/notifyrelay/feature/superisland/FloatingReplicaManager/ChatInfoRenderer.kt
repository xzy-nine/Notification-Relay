package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.TimerInfo
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.parseTimerInfo

// 聊天信息模板：IM图文组件，显示头像、主要文本、次要文本
data class ChatInfo(
    val picProfile: String? = null, // 头像图片资源key
    val picProfileDark: String? = null, // 深色模式头像图片
    val appIconPkg: String? = null, // 自定义应用图标包名
    val title: String? = null, // 主要文本：关键信息
    val content: String? = null, // 次要文本：补充描述
    val timerInfo: TimerInfo? = null, // 时间信息
    val colorTitle: String? = null, // 主要文本颜色
    val colorTitleDark: String? = null, // 深色模式主要文本颜色
    val colorContent: String? = null, // 次要文本颜色
    val colorContentDark: String? = null // 深色模式次要文本颜色
)

// 解析聊天信息组件（IM图文组件）
fun parseChatInfo(json: JSONObject): ChatInfo {
    return ChatInfo(
        picProfile = json.optString("picProfile", "").takeIf { it.isNotEmpty() },
        picProfileDark = json.optString("picProfileDark", "").takeIf { it.isNotEmpty() },
        appIconPkg = json.optString("appIconPkg", "").takeIf { it.isNotEmpty() },
        title = json.optString("title", "").takeIf { it.isNotEmpty() },
        content = json.optString("content", "").takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", "").takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", "").takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", "").takeIf { it.isNotEmpty() }
    )
}

// 构建ChatInfo视图
fun buildChatInfoView(context: Context, chatInfo: ChatInfo, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    chatInfo.picProfile?.let { pic ->
        picMap?.get(pic)?.let { url ->
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = downloadBitmap(url, 5000)
                if (bitmap != null) {
                    val iv = ImageView(context).apply {
                        setImageBitmap(bitmap)
                        val size = (48 * context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    container.addView(iv, 0) // 添加到开头
                }
            }
        }
    }

    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        layoutParams = lp
    }

    chatInfo.title?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(chatInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
        }
        textContainer.addView(tv)
    }

    chatInfo.content?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(chatInfo.colorContent) ?: 0xFFDDDDDD.toInt())
            textSize = 12f
        }
        textContainer.addView(tv)
    }

    container.addView(textContainer)
    return container
}