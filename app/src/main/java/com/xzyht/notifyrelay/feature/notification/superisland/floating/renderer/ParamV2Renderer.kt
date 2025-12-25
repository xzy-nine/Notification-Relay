package com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import com.xzyht.notifyrelay.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseBigIslandArea
import org.json.JSONObject

// 摘要态组件解析

// 参数 V2 总容器，使用分支选择不同模板
data class ParamV2(
    val baseInfo: BaseInfo? = null, // 文本组件
    val chatInfo: ChatInfo? = null, // IM图文组件
    val highlightInfo: HighlightInfo? = null, // 强调图文组件
    val animTextInfo: AnimTextInfo? = null, // 动画文本组件
    val picInfo: PicInfo? = null, // 识别图形组件
    val progressInfo: ProgressInfo? = null, // 进度组件
    val multiProgressInfo: MultiProgressInfo? = null, // 多进度组件
    val actions: List<ActionInfo>? = null, // 按钮组件
    val hintInfo: HintInfo? = null, // 提示组件（按钮组件2/3）
    val textButton: TextButton? = null, // 文本按钮组件
    val paramIsland: ParamIsland? = null, // 摘要态组件
    val business: String? = null // 可选的业务标识（例如 miui_flashlight）
)



// 构建Compose UI视图的函数
suspend fun buildComposeViewFromTemplate(context: Context, paramV2: ParamV2, picMap: Map<String, String>?, business: String? = null, lifecycleOwner: LifecycleOwner? = null): ComposeTemplateViewResult {
    return ComposeTemplateViewResult(
        view = com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.buildComposeViewFromParam(context, paramV2, picMap, business, lifecycleOwner),
        progressBinding = null
    )
}

// 解析param_v2总容器，根据不同字段选择对应的子组件解析
fun parseParamV2(jsonString: String): ParamV2? {
    return try {
        Logger.d("超级岛", "开始解析param_v2: ${jsonString.take(200)}...")
        val json = JSONObject(jsonString)
        val business = json.optString("business", "").takeIf { it.isNotBlank() }
        
        // 逐个字段解析，确保每个字段解析失败不会影响整体解析
        var anim: AnimTextInfo? = null
        var highlight: HighlightInfo? = null
        var baseInfo: BaseInfo? = null
        var chatInfo: ChatInfo? = null
        var picInfo: PicInfo? = null
        var progressInfo: ProgressInfo? = null
        var multiProgressInfo: MultiProgressInfo? = null
        var actions: List<ActionInfo>? = null
        var hintInfo: HintInfo? = null
        var textButton: TextButton? = null
        var paramIsland: ParamIsland? = null
        
        // 解析各个字段，每个字段单独try-catch，避免一个字段解析失败导致整体失败
        try {
            anim = json.optJSONObject("animTextInfo")?.let { parseAnimTextInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析animTextInfo失败: ${e.message}")
        }
        
        try {
            highlight = json.optJSONObject("highlightInfo")?.let { parseHighlightInfo(it) }
                ?: parseHighlightFromIconText(json)
        } catch (e: Exception) {
            Logger.w("超级岛", "解析highlightInfo失败: ${e.message}")
        }
        
        try {
            baseInfo = json.optJSONObject("baseInfo")?.let { parseBaseInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析baseInfo失败: ${e.message}")
        }
        
        try {
            chatInfo = json.optJSONObject("chatInfo")?.let { parseChatInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析chatInfo失败: ${e.message}")
        }
        
        try {
            picInfo = json.optJSONObject("picInfo")?.let { parsePicInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析picInfo失败: ${e.message}")
        }
        
        try {
            progressInfo = json.optJSONObject("progressInfo")?.let { parseProgressInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析progressInfo失败: ${e.message}")
        }
        
        try {
            multiProgressInfo = json.optJSONObject("multiProgressInfo")?.let { parseMultiProgressInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析multiProgressInfo失败: ${e.message}")
        }
        
        try {
            actions = json.optJSONArray("actions")?.let { parseActions(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析actions失败: ${e.message}")
        }
        
        try {
            hintInfo = json.optJSONObject("hintInfo")?.let { parseHintInfo(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析hintInfo失败: ${e.message}")
        }
        
        try {
            textButton = json.optJSONObject("textButton")?.let { parseTextButton(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析textButton失败: ${e.message}")
        }
        
        try {
            paramIsland = (json.optJSONObject("param_island")
                ?: json.optJSONObject("paramIsland")
                ?: json.optJSONObject("islandParam"))?.let { parseParamIsland(it) }
        } catch (e: Exception) {
            Logger.w("超级岛", "解析paramIsland失败: ${e.message}")
        }
        
        val paramV2 = ParamV2(
            business = business,
            baseInfo = baseInfo,
            chatInfo = chatInfo,
            highlightInfo = highlight,
            animTextInfo = anim,
            picInfo = picInfo,
            progressInfo = progressInfo,
            multiProgressInfo = multiProgressInfo,
            actions = actions,
            hintInfo = hintInfo,
            textButton = textButton,
            paramIsland = paramIsland
        )
        
        Logger.d("超级岛", "解析param_v2成功: business=$business, baseInfo=${paramV2.baseInfo != null}")
        paramV2
    } catch (e: Exception) {
        {
            Logger.w("超级岛", "解析param_v2失败: ${e.message}")
            Logger.w("超级岛", "失败的JSON: ${jsonString.take(300)}...")
            e.printStackTrace()
        }
        null
    }
}

private fun parseHighlightFromIconText(root: JSONObject): HighlightInfo? {
    val iconText = root.optJSONObject("iconTextInfo") ?: return null
    val title = iconText.optString("title", "").takeIf { it.isNotBlank() }
    val content = iconText.optString("content", "").takeIf { it.isNotBlank() }
    val sub = sequenceOf("subTitle", "tip", "desc", "description")
        .map { key -> iconText.optString(key, "") }
        .firstOrNull { it.isNotBlank() }
    if (title == null && content == null && sub == null) return null

    val animIcon = iconText.optJSONObject("animIconInfo")
    val iconKey = animIcon?.optString("src", "")?.takeIf { it.isNotBlank() }
    val iconKeyDark = animIcon?.optString("srcDark", "")?.takeIf { it.isNotBlank() }

    val paramIsland = (root.optJSONObject("param_island")
        ?: root.optJSONObject("paramIsland")
        ?: root.optJSONObject("islandParam"))
    val big = parseBigIslandArea(
        paramIsland?.optJSONObject("bigIslandArea") ?: paramIsland?.optJSONObject("bigIsland")
    )
    val leftPic = big?.leftImage
    val rightPic = big?.rightImage

    return HighlightInfo(
        title = title,
        content = content,
        subContent = sub,
        picFunction = iconKey,
        picFunctionDark = iconKeyDark,
        colorTitle = iconText.optString("titleColor", "").takeIf { it.isNotBlank() },
        colorContent = iconText.optString("contentColor", "").takeIf { it.isNotBlank() },
        colorSubContent = iconText.optString("subtitleColor", "").takeIf { it.isNotBlank() },
        bigImageLeft = leftPic,
        bigImageRight = rightPic,
        iconOnly = true
    )
}


// 支持ComposeView的视图结果类
data class ComposeTemplateViewResult(
    val view: ComposeView,
    val progressBinding: CircularProgressBinding? = null
)