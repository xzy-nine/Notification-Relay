package github.xzynine.superislandui.parser

import github.xzynine.superislandui.floating.smallisland.left.AComponent
import github.xzynine.superislandui.floating.smallisland.left.AImageText1
import github.xzynine.superislandui.floating.smallisland.left.AImageText5
import org.json.JSONObject

/**
 * 解析 A区（imageTextInfoLeft）。
 * @param bigIsland 大岛区域 JSON
 * @param picFunction 功能图标键（来自 highlightInfo.picFunction）
 * @param aodPic AOD 图片键
 */
fun parseAComponent(
    bigIsland: JSONObject?,
    picFunction: String? = null,
    aodPic: String? = null,
): AComponent? {
    val left = bigIsland?.optJSONObject("imageTextInfoLeft") ?: return null
    val type = left.optInt("type", 0)

    return when (type) {
        1 -> {
            val textInfo = left.optJSONObject("textInfo")
            val title =
                left.optString("title", "").takeIf { it.isNotBlank() }
                    ?: textInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
            val content =
                left.optString("content", "").takeIf { it.isNotBlank() }
                    ?: textInfo?.optString("content", "")?.takeIf { it.isNotBlank() }
            val narrowFont = textInfo?.optBoolean("narrowFont", false) ?: false
            val showHighlightColor = textInfo?.optBoolean("showHighlightColor", false) ?: false

            val picInfo = left.optJSONObject("picInfo")
            val t = picInfo?.optInt("type", 0) ?: 0
            // picInfo.type 含义：
            // - type=1: appIcon - 应用图标
            // - type=2: middle - 中等尺寸图片
            // - type=3: large - 大尺寸图片
            // - type=4: 静态图标
            val picRaw = picInfo?.optString("pic", "")?.takeIf { it.isNotBlank() }
            val picKey = resolvePicKey(picRaw, picFunction, aodPic)
            // 对于 type=4（静态图资源键），pic 字段必须有效
            val mustHavePicKey = (t == 4)
            if (mustHavePicKey && picKey == null) return null

            AImageText1(
                title = title,
                content = content,
                narrowFont = narrowFont,
                showHighlightColor = showHighlightColor,
                picKey = picKey,
            )
        }
        5 -> {
            val textInfo = left.optJSONObject("textInfo")
            val title =
                textInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
                    ?: left.optString("title", "").takeIf { it.isNotBlank() }
            val content =
                textInfo?.optString("content", "")?.takeIf { it.isNotBlank() }
                    ?: left.optString("content", "").takeIf { it.isNotBlank() }
            val showHighlightColor = textInfo?.optBoolean("showHighlightColor", false) ?: false

            val picInfo = left.optJSONObject("picInfo")
            val picTypeOk = (picInfo?.optInt("type", 0) == 4)
            val picRaw = picInfo?.optString("pic", "")?.takeIf { it.isNotBlank() }
            val picKey = resolvePicKey(picRaw, picFunction, aodPic)

            // 必填：title、picInfo.type==4、picKey
            if (title == null || !picTypeOk || picKey == null) return null

            AImageText5(
                title = title,
                content = content,
                showHighlightColor = showHighlightColor,
                picKey = picKey,
            )
        }
        else -> null
    }
}

/**
 * 解析 picKey 的辅助函数
 * 图标选择优先级：
 * 1. picRaw 字段（如果是 miui.focus.pic_ 前缀）
 * 2. picFunction（来自 highlightInfo.picFunction）
 * 3. aodPic
 */
private fun resolvePicKey(
    picRaw: String?,
    picFunction: String?,
    aodPic: String?,
): String? =
    when {
        picRaw != null && picRaw.startsWith("miui.focus.pic_") -> picRaw
        picFunction != null && picFunction.startsWith("miui.focus.pic_") -> picFunction
        aodPic != null && aodPic.startsWith("miui.focus.pic_") -> aodPic
        else -> null
    }
