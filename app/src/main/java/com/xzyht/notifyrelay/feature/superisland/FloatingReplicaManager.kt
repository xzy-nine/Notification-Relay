package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Button
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.MessageSender
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import org.json.JSONObject
import org.json.JSONArray

// 数据类定义模板组件

data class TimerInfo(
    val timerType: Int,
    val timerWhen: Long,
    val timerTotal: Long,
    val timerSystemCurrent: Long
)

data class ActionInfo(
    val action: String? = null,
    val actionIcon: String? = null,
    val actionIconDark: String? = null,
    val actionTitle: String? = null,
    val actionTitleColor: String? = null,
    val actionTitleColorDark: String? = null,
    val actionBgColor: String? = null,
    val actionBgColorDark: String? = null,
    val actionIntentType: Int? = null,
    val actionIntent: String? = null,
    val clickWithCollapse: Boolean? = null,
    val type: Int? = null,
    val progressInfo: ProgressInfo? = null
)

data class ProgressInfo(
    val progress: Int,
    val colorProgress: String? = null,
    val colorProgressEnd: String? = null,
    val picForward: String? = null,
    val picMiddle: String? = null,
    val picMiddleUnselected: String? = null,
    val picEnd: String? = null,
    val picEndUnselected: String? = null,
    val isCCW: Boolean? = null,
    val isAutoProgress: Boolean? = null
)

data class MultiProgressInfo(
    val title: String,
    val progress: Int,
    val color: String? = null,
    val points: Int? = null
)

data class BaseInfo(
    val type: Int,
    val title: String? = null,
    val subTitle: String? = null,
    val extraTitle: String? = null,
    val specialTitle: String? = null,
    val content: String? = null,
    val subContent: String? = null,
    val picFunction: String? = null,
    val picFunctionDark: String? = null,
    val colorTitle: String? = null,
    val colorTitleDark: String? = null,
    val colorSubTitle: String? = null,
    val colorSubTitleDark: String? = null,
    val colorExtraTitle: String? = null,
    val colorExtraTitleDark: String? = null,
    val colorSpecialTitle: String? = null,
    val colorSpecialTitleDark: String? = null,
    val colorSpecialBg: String? = null,
    val colorContent: String? = null,
    val colorContentDark: String? = null,
    val colorSubContent: String? = null,
    val colorSubContentDark: String? = null,
    val showDivider: Boolean? = null,
    val showContentDivider: Boolean? = null
)

data class ChatInfo(
    val picProfile: String? = null,
    val picProfileDark: String? = null,
    val appIconPkg: String? = null,
    val title: String? = null,
    val content: String? = null,
    val timerInfo: TimerInfo? = null,
    val colorTitle: String? = null,
    val colorTitleDark: String? = null,
    val colorContent: String? = null,
    val colorContentDark: String? = null
)

data class HighlightInfo(
    val title: String? = null,
    val timerInfo: TimerInfo? = null,
    val content: String? = null,
    val picFunction: String? = null,
    val picFunctionDark: String? = null,
    val subContent: String? = null,
    val type: Int? = null,
    val colorTitle: String? = null,
    val colorTitleDark: String? = null,
    val colorContent: String? = null,
    val colorContentDark: String? = null,
    val colorSubContent: String? = null,
    val colorSubContentDark: String? = null
)

data class PicInfo(
    val type: Int,
    val pic: String? = null,
    val picDark: String? = null,
    val actionInfo: ActionInfo? = null,
    val title: String? = null,
    val colorTitle: String? = null
)

data class HintInfo(
    val type: Int,
    val title: String? = null,
    val timerInfo: TimerInfo? = null,
    val subTitle: String? = null,
    val content: String? = null,
    val subContent: String? = null,
    val picContent: String? = null,
    val colorTitle: String? = null,
    val colorTitleDark: String? = null,
    val colorSubTitle: String? = null,
    val colorSubTitleDark: String? = null,
    val colorContent: String? = null,
    val colorContentDark: String? = null,
    val colorSubContent: String? = null,
    val colorSubContentDark: String? = null,
    val colorContentBg: String? = null,
    val actionInfo: ActionInfo? = null
)

data class TextButton(
    val actions: List<ActionInfo>
)

data class ParamV2(
    val baseInfo: BaseInfo? = null,
    val chatInfo: ChatInfo? = null,
    val highlightInfo: HighlightInfo? = null,
    val picInfo: PicInfo? = null,
    val progressInfo: ProgressInfo? = null,
    val multiProgressInfo: MultiProgressInfo? = null,
    val actions: List<ActionInfo>? = null,
    val hintInfo: HintInfo? = null,
    val textButton: TextButton? = null
)

// JSON解析函数
private fun parseParamV2(jsonString: String): ParamV2? {
    return try {
        val json = JSONObject(jsonString)
        ParamV2(
            baseInfo = json.optJSONObject("baseInfo")?.let { parseBaseInfo(it) },
            chatInfo = json.optJSONObject("chatInfo")?.let { parseChatInfo(it) },
            highlightInfo = json.optJSONObject("highlightInfo")?.let { parseHighlightInfo(it) },
            picInfo = json.optJSONObject("picInfo")?.let { parsePicInfo(it) },
            progressInfo = json.optJSONObject("progressInfo")?.let { parseProgressInfo(it) },
            multiProgressInfo = json.optJSONObject("multiProgressInfo")?.let { parseMultiProgressInfo(it) },
            actions = json.optJSONArray("actions")?.let { parseActions(it) },
            hintInfo = json.optJSONObject("hintInfo")?.let { parseHintInfo(it) },
            textButton = json.optJSONObject("textButton")?.let { parseTextButton(it) }
        )
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w("超级岛", "解析param_v2失败: ${e.message}")
        null
    }
}

private fun parseTimerInfo(json: JSONObject): TimerInfo {
    return TimerInfo(
        timerType = json.optInt("timerType", 0),
        timerWhen = json.optLong("timerWhen", 0),
        timerTotal = json.optLong("timerTotal", 0),
        timerSystemCurrent = json.optLong("timerSystemCurrent", 0)
    )
}

private fun parseActionInfo(json: JSONObject): ActionInfo {
    return ActionInfo(
        action = json.optString("action", null).takeIf { it.isNotEmpty() },
        actionIcon = json.optString("actionIcon", null).takeIf { it.isNotEmpty() },
        actionIconDark = json.optString("actionIconDark", null).takeIf { it.isNotEmpty() },
        actionTitle = json.optString("actionTitle", null).takeIf { it.isNotEmpty() },
        actionTitleColor = json.optString("actionTitleColor", null).takeIf { it.isNotEmpty() },
        actionTitleColorDark = json.optString("actionTitleColorDark", null).takeIf { it.isNotEmpty() },
        actionBgColor = json.optString("actionBgColor", null).takeIf { it.isNotEmpty() },
        actionBgColorDark = json.optString("actionBgColorDark", null).takeIf { it.isNotEmpty() },
        actionIntentType = json.optInt("actionIntentType", -1).takeIf { it != -1 },
        actionIntent = json.optString("actionIntent", null).takeIf { it.isNotEmpty() },
        clickWithCollapse = json.optBoolean("clickWithCollapse", false),
        type = json.optInt("type", -1).takeIf { it != -1 },
        progressInfo = json.optJSONObject("progressInfo")?.let { parseProgressInfo(it) }
    )
}

private fun parseProgressInfo(json: JSONObject): ProgressInfo {
    return ProgressInfo(
        progress = json.optInt("progress", 0),
        colorProgress = json.optString("colorProgress", null).takeIf { it.isNotEmpty() },
        colorProgressEnd = json.optString("colorProgressEnd", null).takeIf { it.isNotEmpty() },
        picForward = json.optString("picForward", null).takeIf { it.isNotEmpty() },
        picMiddle = json.optString("picMiddle", null).takeIf { it.isNotEmpty() },
        picMiddleUnselected = json.optString("picMiddleUnselected", null).takeIf { it.isNotEmpty() },
        picEnd = json.optString("picEnd", null).takeIf { it.isNotEmpty() },
        picEndUnselected = json.optString("picEndUnselected", null).takeIf { it.isNotEmpty() },
        isCCW = json.optBoolean("isCCW", false),
        isAutoProgress = json.optBoolean("isAutoProgress", false)
    )
}

private fun parseMultiProgressInfo(json: JSONObject): MultiProgressInfo {
    return MultiProgressInfo(
        title = json.optString("title", ""),
        progress = json.optInt("progress", 0),
        color = json.optString("color", null).takeIf { it.isNotEmpty() },
        points = json.optInt("points", -1).takeIf { it != -1 }
    )
}

private fun parseBaseInfo(json: JSONObject): BaseInfo {
    return BaseInfo(
        type = json.optInt("type", 1),
        title = json.optString("title", null).takeIf { it.isNotEmpty() },
        subTitle = json.optString("subTitle", null).takeIf { it.isNotEmpty() },
        extraTitle = json.optString("extraTitle", null).takeIf { it.isNotEmpty() },
        specialTitle = json.optString("specialTitle", null).takeIf { it.isNotEmpty() },
        content = json.optString("content", null).takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", null).takeIf { it.isNotEmpty() },
        picFunction = json.optString("picFunction", null).takeIf { it.isNotEmpty() },
        picFunctionDark = json.optString("picFunctionDark", null).takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", null).takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", null).takeIf { it.isNotEmpty() },
        colorSubTitle = json.optString("colorSubTitle", null).takeIf { it.isNotEmpty() },
        colorSubTitleDark = json.optString("colorSubTitleDark", null).takeIf { it.isNotEmpty() },
        colorExtraTitle = json.optString("colorExtraTitle", null).takeIf { it.isNotEmpty() },
        colorExtraTitleDark = json.optString("colorExtraTitleDark", null).takeIf { it.isNotEmpty() },
        colorSpecialTitle = json.optString("colorSpecialTitle", null).takeIf { it.isNotEmpty() },
        colorSpecialTitleDark = json.optString("colorSpecialTitleDark", null).takeIf { it.isNotEmpty() },
        colorSpecialBg = json.optString("colorSpecialBg", null).takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", null).takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", null).takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", null).takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", null).takeIf { it.isNotEmpty() },
        showDivider = json.optBoolean("showDivider", false),
        showContentDivider = json.optBoolean("showContentDivider", false)
    )
}

private fun parseChatInfo(json: JSONObject): ChatInfo {
    return ChatInfo(
        picProfile = json.optString("picProfile", null).takeIf { it.isNotEmpty() },
        picProfileDark = json.optString("picProfileDark", null).takeIf { it.isNotEmpty() },
        appIconPkg = json.optString("appIconPkg", null).takeIf { it.isNotEmpty() },
        title = json.optString("title", null).takeIf { it.isNotEmpty() },
        content = json.optString("content", null).takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        colorTitle = json.optString("colorTitle", null).takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", null).takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", null).takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", null).takeIf { it.isNotEmpty() }
    )
}

private fun parseHighlightInfo(json: JSONObject): HighlightInfo {
    return HighlightInfo(
        title = json.optString("title", null).takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        content = json.optString("content", null).takeIf { it.isNotEmpty() },
        picFunction = json.optString("picFunction", null).takeIf { it.isNotEmpty() },
        picFunctionDark = json.optString("picFunctionDark", null).takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", null).takeIf { it.isNotEmpty() },
        type = json.optInt("type", -1).takeIf { it != -1 },
        colorTitle = json.optString("colorTitle", null).takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", null).takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", null).takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", null).takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", null).takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", null).takeIf { it.isNotEmpty() }
    )
}

private fun parsePicInfo(json: JSONObject): PicInfo {
    return PicInfo(
        type = json.optInt("type", 1),
        pic = json.optString("pic", null).takeIf { it.isNotEmpty() },
        picDark = json.optString("picDark", null).takeIf { it.isNotEmpty() },
        actionInfo = json.optJSONObject("actionInfo")?.let { parseActionInfo(it) },
        title = json.optString("title", null).takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", null).takeIf { it.isNotEmpty() }
    )
}

private fun parseHintInfo(json: JSONObject): HintInfo {
    return HintInfo(
        type = json.optInt("type", 1),
        title = json.optString("title", null).takeIf { it.isNotEmpty() },
        timerInfo = json.optJSONObject("timerInfo")?.let { parseTimerInfo(it) },
        subTitle = json.optString("subTitle", null).takeIf { it.isNotEmpty() },
        content = json.optString("content", null).takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", null).takeIf { it.isNotEmpty() },
        picContent = json.optString("picContent", null).takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", null).takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", null).takeIf { it.isNotEmpty() },
        colorSubTitle = json.optString("colorSubTitle", null).takeIf { it.isNotEmpty() },
        colorSubTitleDark = json.optString("colorSubTitleDark", null).takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", null).takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", null).takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", null).takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", null).takeIf { it.isNotEmpty() },
        colorContentBg = json.optString("colorContentBg", null).takeIf { it.isNotEmpty() },
        actionInfo = json.optJSONObject("actionInfo")?.let { parseActionInfo(it) }
    )
}

private fun parseActions(jsonArray: JSONArray): List<ActionInfo> {
    val list = mutableListOf<ActionInfo>()
    for (i in 0 until jsonArray.length()) {
        jsonArray.optJSONObject(i)?.let { parseActionInfo(it) }?.let { list.add(it) }
    }
    return list
}

private fun parseTextButton(json: JSONObject): TextButton {
    return TextButton(actions = json.optJSONArray("actions")?.let { parseActions(it) } ?: emptyList())
}

// 构建UI视图的函数
private fun buildViewFromTemplate(context: Context, paramV2: ParamV2, picMap: Map<String, String>?): View {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val padding = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(0xEE000000.toInt())
    }

    // 根据模板类型构建不同的布局
    when {
        paramV2.baseInfo != null -> buildBaseInfoView(context, container, paramV2.baseInfo, picMap)
        paramV2.chatInfo != null -> buildChatInfoView(context, container, paramV2.chatInfo, picMap)
        paramV2.highlightInfo != null -> buildHighlightInfoView(context, container, paramV2.highlightInfo, picMap)
        paramV2.picInfo != null -> buildPicInfoView(context, container, paramV2.picInfo, picMap)
        paramV2.hintInfo != null -> buildHintInfoView(context, container, paramV2.hintInfo, picMap)
        paramV2.textButton != null -> buildTextButtonView(context, container, paramV2.textButton, picMap)
        else -> {
            // 默认简单布局
            val tv = TextView(context).apply {
                text = "未支持的模板"
                setTextColor(0xFFFFFFFF.toInt())
            }
            container.addView(tv)
        }
    }

    // 添加进度条如果有
    paramV2.progressInfo?.let { buildProgressView(context, container, it) }
    paramV2.multiProgressInfo?.let { buildMultiProgressView(context, container, it) }

    // 添加按钮如果有
    paramV2.actions?.let { buildActionsView(context, container, it) }

    return container
}

private fun buildBaseInfoView(context: Context, container: LinearLayout, baseInfo: BaseInfo, picMap: Map<String, String>?) {
    // 简单实现：显示主要文本和次要文本
    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    baseInfo.title?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(baseInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
        }
        textContainer.addView(tv)
    }

    baseInfo.content?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(baseInfo.colorContent) ?: 0xFFDDDDDD.toInt())
            textSize = 12f
        }
        textContainer.addView(tv)
    }

    container.addView(textContainer)
}

private fun buildChatInfoView(context: Context, container: LinearLayout, chatInfo: ChatInfo, picMap: Map<String, String>?) {
    // 头像
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
                    container.addView(iv)
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
}

private fun buildHighlightInfoView(context: Context, container: LinearLayout, highlightInfo: HighlightInfo, picMap: Map<String, String>?) {
    // 类似实现
    val tv = TextView(context).apply {
        text = highlightInfo.title ?: "高亮信息"
        setTextColor(parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
        textSize = 14f
    }
    container.addView(tv)
}

private fun buildPicInfoView(context: Context, container: LinearLayout, picInfo: PicInfo, picMap: Map<String, String>?) {
    picInfo.pic?.let { pic ->
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
                    container.addView(iv)
                }
            }
        }
    }

    picInfo.title?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(picInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
            layoutParams = lp
        }
        container.addView(tv)
    }
}

private fun buildHintInfoView(context: Context, container: LinearLayout, hintInfo: HintInfo, picMap: Map<String, String>?) {
    val tv = TextView(context).apply {
        text = hintInfo.title ?: "提示信息"
        setTextColor(parseColor(hintInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
        textSize = 14f
    }
    container.addView(tv)
}

private fun buildTextButtonView(context: Context, container: LinearLayout, textButton: TextButton, picMap: Map<String, String>?) {
    textButton.actions.forEach { action ->
        val btn = Button(context).apply {
            text = action.actionTitle ?: "按钮"
            setTextColor(parseColor(action.actionTitleColor) ?: 0xFFFFFFFF.toInt())
            action.actionBgColor?.let { setBackgroundColor(parseColor(it) ?: 0xFF333333.toInt()) }
        }
        container.addView(btn)
    }
}

private fun buildProgressView(context: Context, container: LinearLayout, progressInfo: ProgressInfo) {
    val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = progressInfo.progress
        progressInfo.colorProgress?.let { progressTintList = android.content.res.ColorStateList.valueOf(parseColor(it) ?: 0xFF00FF00.toInt()) }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, (4 * context.resources.displayMetrics.density).toInt(), 0, 0)
        layoutParams = lp
    }
    container.addView(progressBar)
}

private fun buildMultiProgressView(context: Context, container: LinearLayout, multiProgressInfo: MultiProgressInfo) {
    val tv = TextView(context).apply {
        text = "${multiProgressInfo.title}: ${multiProgressInfo.progress}%"
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
    }
    container.addView(tv)
}

private fun buildActionsView(context: Context, container: LinearLayout, actions: List<ActionInfo>) {
    actions.forEach { action ->
        val btn = Button(context).apply {
            text = action.actionTitle ?: "操作"
            setTextColor(parseColor(action.actionTitleColor) ?: 0xFFFFFFFF.toInt())
            action.actionBgColor?.let { setBackgroundColor(parseColor(it) ?: 0xFF333333.toInt()) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((4 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
            layoutParams = lp
        }
        container.addView(btn)
    }
}

private fun parseColor(colorString: String?): Int? {
    return try {
        colorString?.let { android.graphics.Color.parseColor(it) }
    } catch (e: Exception) {
        null
    }
}

private fun downloadBitmap(url: String, timeoutMs: Int): android.graphics.Bitmap? {
    try {
        // 支持 data URI（base64）、以及常规 http/https URL
        if (url.startsWith("data:", ignoreCase = true)) {
            // delegate data URI decoding to DataUrlUtils (handles whitespace/newlines)
            return DataUrlUtils.decodeDataUrlToBitmap(url)
        }

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.doInput = true
        conn.connect()
        if (conn.responseCode != 200) return null
        val stream = conn.inputStream
        val bmp = android.graphics.BitmapFactory.decodeStream(stream)
        try { stream.close() } catch (_: Exception) {}
        conn.disconnect()
        return bmp
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
        return null
    }
}

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private var overlayView: View? = null

    /**
     * 显示超级岛复刻悬浮窗。
     * paramV2Raw: miui.focus.param 中 param_v2 的原始 JSON 字符串（可为 null）
     * picMap: 从 extras 中解析出的图片键->URL 映射（可为 null）
     */
    // sourceId: 用于区分不同来源的超级岛通知（通常传入 superPkg），用于刷新/去重同一来源的浮窗
    fun showFloating(context: Context, sourceId: String?, title: String?, text: String?, paramV2Raw: String? = null, picMap: Map<String, String>? = null) {
        try {
            if (!canShowOverlay(context)) {
                // 没有权限时：弹出设置意图并回退成高优先级通知
                requestOverlayPermission(context)
                MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
                return
            }

            // 异步准备图片资源并显示浮窗（在主线程更新 UI）
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = downloadFirstAvailableImage(picMap)
                ensureOverlayExists(context)

                // 如果有paramV2，使用模板构建视图，否则使用默认
                val paramV2 = paramV2Raw?.let { parseParamV2(it) }
                if (paramV2 != null) {
                    val templateView = buildViewFromTemplate(context, paramV2, picMap)
                    addOrUpdateEntryWithView(context, sourceId ?: (title + "|" + text), templateView)
                } else {
                    addOrUpdateEntry(context, sourceId ?: (title + "|" + text), title, text, bitmap)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
            MessageSender.sendHighPriorityNotification(context, title ?: "(无标题)", text ?: "(无内容)")
        }
    }

    // ---- 多条浮窗管理实现 ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stackContainer: LinearLayout? = null
    // key -> Pair(view, Runnable removal)
    private val entries = mutableMapOf<String, Pair<View, Runnable>>()

    private fun ensureOverlayExists(context: Context) {
        if (overlayView != null && stackContainer != null) return
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // 移除已存在浮窗
            try { overlayView?.let { wm.removeView(it) } } catch (_: Exception) {}

            val container = FrameLayout(context)
            container.setBackgroundColor(0x00000000)

            val padding = (12 * (context.resources.displayMetrics.density)).toInt()
            val innerStack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

            container.addView(innerStack)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams.x = 0
            layoutParams.y = 100

            overlayView = container
            stackContainer = innerStack
            try { wm.addView(container, layoutParams) } catch (_: Exception) {}
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 浮窗容器已创建，初始坐标 x=${layoutParams.x}, y=${layoutParams.y}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 创建浮窗容器失败: ${e.message}")
        }
    }

    private fun addOrUpdateEntryWithView(context: Context, key: String, view: View) {
        try {
            val stack = stackContainer ?: return

            // 如果已有相同key，更新视图
            val existing = entries[key]
            if (existing != null) {
                val (oldView, oldRunnable) = existing
                // 移除旧视图，添加新视图
                stack.removeView(oldView)
                stack.addView(view, 0)

                // 取消旧的移除任务并重新排期
                handler.removeCallbacks(oldRunnable)
                val removal = Runnable {
                    try {
                        stack.removeView(view)
                        entries.remove(key)
                        if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                    } catch (_: Exception) {}
                }
                entries[key] = view to removal
                handler.postDelayed(removal, 5000)
                if (BuildConfig.DEBUG) Log.d("超级岛", "超级岛: 刷新浮窗条目 key=$key")
                return
            }

            // 添加新视图
            stack.addView(view, 0)

            val removal = Runnable {
                try {
                    stack.removeView(view)
                    entries.remove(key)
                    if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                } catch (_: Exception) {}
            }
            entries[key] = view to removal
            handler.postDelayed(removal, 5000)
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 新增浮窗条目 key=$key")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: addOrUpdateEntryWithView 出错: ${e.message}")
        }
    }

    private fun addOrUpdateEntry(context: Context, key: String, title: String?, text: String?, image: android.graphics.Bitmap?) {
        try {
            val stack = stackContainer ?: return

            // 如果已有相同key，更新内容并重置定时移除
            val existing = entries[key]
            if (existing != null) {
                val (view, oldRunnable) = existing
                // 更新文本和图片
                try {
                    val img = view.findViewById<ImageView>(android.R.id.icon)
                    val tvTitle = view.findViewById<TextView>(android.R.id.text1)
                    val tvText = view.findViewById<TextView>(android.R.id.text2)
                    if (image != null) img.setImageBitmap(image)
                    tvTitle.text = title ?: "(无标题)"
                    tvText.text = text ?: "(无内容)"
                } catch (_: Exception) {}

                // 取消旧的移除任务并重新排期
                handler.removeCallbacks(oldRunnable)
                val removal = Runnable {
                    try {
                        stack.removeView(view)
                        entries.remove(key)
                        if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                    } catch (_: Exception) {}
                }
                entries[key] = view to removal
                handler.postDelayed(removal, 5000)
                if (BuildConfig.DEBUG) Log.d("超级岛", "超级岛: 刷新浮窗条目 key=$key")
                return
            }

            // 创建新的条目视图（水平排列）
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val innerPadding = (8 * context.resources.displayMetrics.density).toInt()
                setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
                setBackgroundColor(0xEE000000.toInt())
            }
            val iv = ImageView(context).apply {
                id = android.R.id.icon
                if (image != null) setImageBitmap(image)
                val size = (56 * context.resources.displayMetrics.density).toInt()
                this.layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val tvs = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
                this.layoutParams = lp
            }
            val tt = TextView(context).apply { id = android.R.id.text1; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; this.text = title ?: "(无标题)" }
            val tx = TextView(context).apply { id = android.R.id.text2; setTextColor(0xFFDDDDDD.toInt()); textSize = 12f; this.text = text ?: "(无内容)" }
            tvs.addView(tt)
            tvs.addView(tx)
            item.addView(iv)
            item.addView(tvs)

            // 支持拖动整个stack（按条目拖动暂时不实现，仅支持整体）
            // 将新条目添加到顶部（最新在上）
            stack.addView(item, 0)

            val removal = Runnable {
                try {
                    stack.removeView(item)
                    entries.remove(key)
                    if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 自动移除浮窗条目 key=$key")
                } catch (_: Exception) {}
            }
            entries[key] = item to removal
            handler.postDelayed(removal, 5000)
            if (BuildConfig.DEBUG) Log.i("超级岛", "超级岛: 新增浮窗条目 key=$key, title=${title}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: addOrUpdateEntry 出错: ${e.message}")
        }
    }

    private fun canShowOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
    }

    private fun requestOverlayPermission(context: Context) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:" + context.packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 请求悬浮窗权限失败: ${e.message}")
        }
    }

    private suspend fun downloadFirstAvailableImage(picMap: Map<String, String>?): android.graphics.Bitmap? {
        if (picMap.isNullOrEmpty()) return null
        for ((_, url) in picMap) {
            try {
                val bmp = withContext(Dispatchers.IO) { downloadBitmap(url, 5000) }
                if (bmp != null) return bmp
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            }
        }
        return null
    }

    private fun downloadBitmap(url: String, timeoutMs: Int): android.graphics.Bitmap? {
        try {
            // 支持 data URI（base64）、以及常规 http/https URL
            if (url.startsWith("data:", ignoreCase = true)) {
                // delegate data URI decoding to DataUrlUtils (handles whitespace/newlines)
                return DataUrlUtils.decodeDataUrlToBitmap(url)
            }

            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            if (conn.responseCode != 200) return null
            val stream = conn.inputStream
            val bmp = android.graphics.BitmapFactory.decodeStream(stream)
            try { stream.close() } catch (_: Exception) {}
            conn.disconnect()
            return bmp
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            return null
        }
    }

    // 简单的触摸拖动实现
    private class FloatingTouchListener(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager
    ) : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    params.x += dx
                    params.y += dy
                    try {
                        wm.updateViewLayout(v.rootView, params)
                        if (BuildConfig.DEBUG) Log.d("超级岛", "超级岛: 浮窗移动到 x=${params.x}, y=${params.y}")
                    } catch (_: Exception) {}
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
            }
            return false
        }
    }
}
