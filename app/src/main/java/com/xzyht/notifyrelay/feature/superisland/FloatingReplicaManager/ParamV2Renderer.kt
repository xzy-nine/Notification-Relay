package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import com.xzyht.notifyrelay.BuildConfig
import android.util.Log
import org.json.JSONArray

// 摘要态组件解析
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.ParamIsland
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.parseParamIsland

// 参数 V2 总容器，使用分支选择不同模板
data class ParamV2(
    val baseInfo: BaseInfo? = null, // 文本组件
    val chatInfo: ChatInfo? = null, // IM图文组件
    val highlightInfo: HighlightInfo? = null, // 强调图文组件
    val picInfo: PicInfo? = null, // 识别图形组件
    val progressInfo: ProgressInfo? = null, // 进度组件
    val multiProgressInfo: MultiProgressInfo? = null, // 多进度组件
    val actions: List<ActionInfo>? = null, // 按钮组件
    val hintInfo: HintInfo? = null, // 提示组件（按钮组件2/3）
    val textButton: TextButton? = null, // 文本按钮组件
    val paramIsland: ParamIsland? = null // 摘要态组件
)

// 构建UI视图的函数
fun buildViewFromTemplate(context: Context, paramV2: ParamV2, picMap: Map<String, String>?): TemplateViewResult {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val padding = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(0xEE000000.toInt())
    }

    var progressBinding: CircularProgressBinding? = null

    // 根据模板类型构建不同的布局，使用分支处理
    when {
        paramV2.baseInfo != null -> {
            val view = buildBaseInfoView(context, paramV2.baseInfo, picMap)
            container.addView(view)
        }
        paramV2.chatInfo != null -> {
            val result = buildChatInfoView(context, paramV2, picMap)
            container.addView(result.view)
            progressBinding = result.progressBinding
        }
        paramV2.highlightInfo != null -> {
            val view = buildHighlightInfoView(context, paramV2.highlightInfo, picMap)
            container.addView(view)
        }
        paramV2.picInfo != null -> {
            val view = buildPicInfoView(context, paramV2.picInfo, picMap)
            container.addView(view)
        }
        paramV2.hintInfo != null -> {
            val view = buildHintInfoView(context, paramV2.hintInfo, picMap)
            container.addView(view)
        }
        paramV2.textButton != null -> {
            val view = buildTextButtonView(context, paramV2.textButton, picMap)
            container.addView(view)
        }
        else -> {
            // 默认模板：未支持的模板类型
            val tv = android.widget.TextView(context).apply {
                text = "未支持的模板"
                setTextColor(0xFFFFFFFF.toInt())
            }
            container.addView(tv)
        }
    }

    // 添加进度条如果有
    paramV2.progressInfo?.let {
        val progressBar = buildProgressInfoView(context, it, picMap)
        container.addView(progressBar)
    }

    paramV2.multiProgressInfo?.let {
        val tv = buildMultiProgressInfoView(context, it, picMap)
        container.addView(tv)
    }

    // 添加按钮如果有
    paramV2.actions?.let { actions ->
        actions.forEach { action ->
            val btn = buildActionInfoView(context, action, picMap)
            container.addView(btn)
        }
    }

    return TemplateViewResult(container, progressBinding)
}

// 解析param_v2总容器，根据不同字段选择对应的子组件解析
fun parseParamV2(jsonString: String): ParamV2? {
    return try {
        val json = JSONObject(jsonString)
        val highlight = json.optJSONObject("highlightInfo")?.let { parseHighlightInfo(it) }
            ?: parseHighlightFromIconText(json)
        ParamV2(
            baseInfo = json.optJSONObject("baseInfo")?.let { parseBaseInfo(it) },
            chatInfo = json.optJSONObject("chatInfo")?.let { parseChatInfo(it) },
            highlightInfo = highlight,
            picInfo = json.optJSONObject("picInfo")?.let { parsePicInfo(it) },
            progressInfo = json.optJSONObject("progressInfo")?.let { parseProgressInfo(it) },
            multiProgressInfo = json.optJSONObject("multiProgressInfo")?.let { parseMultiProgressInfo(it) },
            actions = json.optJSONArray("actions")?.let { parseActions(it) },
            hintInfo = json.optJSONObject("hintInfo")?.let { parseHintInfo(it) },
            textButton = json.optJSONObject("textButton")?.let { parseTextButton(it) },
            paramIsland = (json.optJSONObject("param_island")
                ?: json.optJSONObject("paramIsland")
                ?: json.optJSONObject("islandParam"))?.let { parseParamIsland(it) }
        )
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w("超级岛", "解析param_v2失败: ${e.message}")
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
    val bigIsland = paramIsland?.optJSONObject("bigIslandArea")
    val leftPic = bigIsland
        ?.optJSONObject("imageTextInfoLeft")
        ?.optJSONObject("picInfo")
        ?.optString("pic", "")
        ?.takeIf { it.isNotBlank() }
    val rightPic = bigIsland
        ?.optJSONObject("imageTextInfoRight")
        ?.optJSONObject("picInfo")
        ?.optString("pic", "")
        ?.takeIf { it.isNotBlank() }

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

data class TemplateViewResult(
    val view: View,
    val progressBinding: CircularProgressBinding? = null
)