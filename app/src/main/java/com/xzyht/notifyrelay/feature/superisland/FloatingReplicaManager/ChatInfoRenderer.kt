package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.TimerInfo
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.parseTimerInfo
import kotlin.math.roundToInt

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
fun buildChatInfoView(
    context: Context,
    paramV2: ParamV2,
    picMap: Map<String, String>?
): ChatInfoViewResult {
    val chatInfo = paramV2.chatInfo
        ?: return ChatInfoViewResult(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }, null)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val density = context.resources.displayMetrics.density
    val avatarSize = (48f * density).roundToInt()
    val statusSize = (48f * density).roundToInt()
    val completionSize = (28f * density).roundToInt()

    val avatarView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = CircularOutlineProvider
    }

    val statusFrame = FrameLayout(context).apply {
        val lp = LinearLayout.LayoutParams(statusSize, statusSize)
        lp.setMargins((8 * density).roundToInt(), 0, 0, 0)
        layoutParams = lp
        visibility = View.GONE
    }

    val progressView = CircularProgressView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setStrokeWidthDp(3.5f)
        visibility = View.GONE
    }

    val completionView = ImageView(context).apply {
        val size = completionSize
        val lp = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        layoutParams = lp
        visibility = View.GONE
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    statusFrame.addView(progressView)
    statusFrame.addView(completionView)

    container.addView(avatarView)
    container.addView(statusFrame)

    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins((8 * density).roundToInt(), 0, 0, 0)
        layoutParams = lp
    }

    chatInfo.title?.let {
        val tv = TextView(context).apply {
            text = Html.fromHtml(unescapeHtml(it), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(parseColor(chatInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
        }
        textContainer.addView(tv)
    }

    chatInfo.content?.let {
        val tv = TextView(context).apply {
            text = Html.fromHtml(unescapeHtml(it), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(parseColor(chatInfo.colorContent) ?: 0xFFDDDDDD.toInt())
            textSize = 12f
        }
        textContainer.addView(tv)
    }

    container.addView(textContainer)

    loadProfileAsync(context, chatInfo, picMap, avatarView)

    val actionWithProgress = paramV2.actions?.firstOrNull { it.progressInfo != null }
    val progressInfo = actionWithProgress?.progressInfo ?: paramV2.progressInfo
    val iconSource = actionWithProgress ?: paramV2.actions?.firstOrNull { !it.actionIcon.isNullOrBlank() || !it.actionIconDark.isNullOrBlank() }

    val binding = if (
        progressInfo != null ||
        !iconSource?.actionIcon.isNullOrBlank() ||
        !iconSource?.actionIconDark.isNullOrBlank()
    ) {
        statusFrame.visibility = View.VISIBLE
        CircularProgressBinding(
            context = context,
            progressView = progressView,
            completionView = completionView,
            picMap = picMap,
            progressInfo = progressInfo,
            completionIcon = iconSource?.actionIcon,
            completionIconDark = iconSource?.actionIconDark
        )
    } else {
        progressView.visibility = View.GONE
        completionView.visibility = View.GONE
        statusFrame.visibility = View.GONE
        null
    }

    return ChatInfoViewResult(container, binding)
}

private fun loadProfileAsync(
    context: Context,
    chatInfo: ChatInfo,
    picMap: Map<String, String>?,
    avatarView: ImageView
) {
    val key = selectProfileKey(context, chatInfo)
    val url = key?.let { picMap?.get(it) } ?: chatInfo.picProfile?.let { picMap?.get(it) }
    if (url.isNullOrEmpty()) return
    CoroutineScope(Dispatchers.Main).launch {
        val bitmap = downloadBitmap(url, 5000)
        if (bitmap != null) {
            avatarView.setImageBitmap(bitmap)
        }
    }
}

private fun selectProfileKey(context: Context, chatInfo: ChatInfo): String? {
    val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val preferDark = nightMask == Configuration.UI_MODE_NIGHT_YES
    return if (preferDark) chatInfo.picProfileDark ?: chatInfo.picProfile else chatInfo.picProfile ?: chatInfo.picProfileDark
}

data class ChatInfoViewResult(
    val view: LinearLayout,
    val progressBinding: CircularProgressBinding?
)

class CircularProgressBinding(
    private val context: Context,
    private val progressView: CircularProgressView,
    private val completionView: ImageView?,
    private val picMap: Map<String, String>?,
    private val progressInfo: ProgressInfo?,
    private val completionIcon: String?,
    private val completionIconDark: String?
) {
    private val strokeColor = parseColor(progressInfo?.colorProgress) ?: DEFAULT_PROGRESS_COLOR
    private val trackColor = parseColor(progressInfo?.colorProgressEnd)
        ?: ((strokeColor and 0x00FFFFFF) or (0x33 shl 24))
    private val animationDuration = if (progressInfo?.isAutoProgress == true) 280L else 420L
    private var completionIconLoaded = false

    var currentProgress: Int? = progressInfo?.progress?.coerceIn(0, 100)
        private set

    fun apply(previousProgress: Int?) {
        val target = currentProgress
        if (target != null) {
            completionView?.visibility = View.GONE
            progressView.visibility = View.VISIBLE
            progressView.setDirection(true)
            progressView.setColors(strokeColor, trackColor)
            if (previousProgress != null) {
                progressView.setProgressAnimated(previousProgress, target, animationDuration)
            } else {
                progressView.setProgressInstant(target)
            }
        } else {
            progressView.visibility = View.GONE
            if (completionView != null) {
                completionView.visibility = View.VISIBLE
                loadCompletionIconIfNeeded()
            }
        }
    }

    private fun loadCompletionIconIfNeeded() {
        if (completionView == null || completionIconLoaded) return
        val key = selectCompletionIconKey()
        val url = key?.let { picMap?.get(it) }
        if (url.isNullOrEmpty()) {
            completionIconLoaded = true
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = downloadBitmap(url, 5000)
            if (bitmap != null) {
                completionView.setImageBitmap(bitmap)
            }
            completionIconLoaded = true
        }
    }

    private fun selectCompletionIconKey(): String? {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val preferDark = nightMask == Configuration.UI_MODE_NIGHT_YES
        return if (preferDark) completionIconDark ?: completionIcon else completionIcon ?: completionIconDark
    }
}

private object CircularOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

private const val DEFAULT_PROGRESS_COLOR = 0xFF3482FF.toInt()