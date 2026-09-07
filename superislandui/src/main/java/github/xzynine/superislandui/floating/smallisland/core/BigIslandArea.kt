package github.xzynine.superislandui.floating.smallisland.core

import github.xzynine.superislandui.floating.smallisland.left.AComponent
import github.xzynine.superislandui.floating.smallisland.right.BComponent
import github.xzynine.superislandui.parser.parseAComponent
import github.xzynine.superislandui.parser.parseBComponent
import notifyrelay.base.util.Logger
import org.json.JSONObject

/**
 * 集中解析和承载 "bigIslandArea"（超级岛的摘要 / 收起态）相关的数据结构与工具函数。
 * 其他模块请使用 parseBigIslandArea(...) 获取统一的 BigIslandArea 对象。
 */
data class BigIslandArea(
    val primaryText: String? = null,
    val secondaryText: String? = null,
    val leftImage: String? = null,
    val rightImage: String? = null,
    val verificationCode: String? = null,
    val isVerificationCode: Boolean = false,
    val aComponent: AComponent? = null,
    val bComponent: BComponent? = null,
)

fun parseBigIslandArea(
    json: JSONObject?,
    picFunction: String?,
    aodPic: String?,
): BigIslandArea? {
    if (json == null) return null

    val leftPic =
        json
            .optJSONObject("imageTextInfoLeft")
            ?.optJSONObject("picInfo")
            ?.optString("pic", "")
            ?.takeIf { it.isNotBlank() }

    val rightPic =
        json
            .optJSONObject("imageTextInfoRight")
            ?.optJSONObject("picInfo")
            ?.optString("pic", "")
            ?.takeIf { it.isNotBlank() }

    // 解析验证码逻辑
    var isVerCode = false
    var verCode: String? = null
    val leftTextInfo = json.optJSONObject("imageTextInfoLeft")?.optJSONObject("textInfo")
    if (leftTextInfo != null) {
        val title = leftTextInfo.optString("title")
        val highlight = leftTextInfo.optBoolean("showHighlightColor", false)
        Logger.d("BigIslandArea", "解析验证码检查: highlight=$highlight")
        if (highlight && (title == "验证码" || title.contains("验证码"))) {
            isVerCode = true
            // 尝试从 textInfo 中提取验证码
            verCode = json.optJSONObject("textInfo")?.optString("title")
            val codeLength = verCode?.length ?: 0
            Logger.d("BigIslandArea", "识别到验证码: ${codeLength}位")
        }
    } else {
        Logger.d("BigIslandArea", "imageTextInfoLeft 或 textInfo 为空")
    }

    val primary =
        firstString(
            json,
            arrayOf(
                "title",
                "primaryText",
                "frontTitle",
                "mainText",
                "mainTitle",
                "largeText",
                "bigText",
                "text",
            ),
        )

    val secondary =
        firstString(
            json,
            arrayOf(
                "content",
                "secondaryText",
                "subTitle",
                "subContent",
                "afterText",
                "tailText",
            ),
        )

    // 如果 primary/secondary 为 null，尝试在嵌套对象或数组里查找（有限的递归深度，避免复杂依赖）
    val finalPrimary = primary ?: nestedFirstString(json, arrayOf("textInfo", "iconTextInfo", "imageTextInfoLeft", "imageTextInfoRight"))
    val finalSecondary = secondary ?: nestedFirstString(json, arrayOf("textInfo", "iconTextInfo", "imageTextInfoLeft", "imageTextInfoRight"))

    // 如果是验证码但没有提取到独立字段，尝试使用 primaryText
    if (isVerCode && verCode.isNullOrBlank()) {
        verCode = finalPrimary
    }

    // 解析 A/B 区组件
    val aComponent = parseAComponent(json, picFunction, aodPic)
    val bComponent = parseBComponent(json, picFunction, aodPic)

    return BigIslandArea(
        primaryText = finalPrimary,
        secondaryText = finalSecondary,
        leftImage = leftPic,
        rightImage = rightPic,
        verificationCode = verCode,
        isVerificationCode = isVerCode,
        aComponent = aComponent,
        bComponent = bComponent,
    )
}

private fun firstString(
    obj: JSONObject,
    keys: Array<String>,
): String? {
    for (key in keys) {
        val value = runCatching { obj.getString(key) }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun nestedFirstString(
    obj: JSONObject,
    keys: Array<String>,
): String? {
    for (key in keys) {
        val nested = obj.optJSONObject(key) ?: continue
        val direct = firstString(nested, arrayOf("title", "text", "content", "primaryText", "secondaryText"))
        if (!direct.isNullOrBlank()) return direct
        // 支持数组中的第一项
        val arr = nested.optJSONArray("components") ?: nested.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val child = arr.optJSONObject(i) ?: continue
                val found = nestedFirstString(child, keys)
                if (!found.isNullOrBlank()) return found
            }
        }
    }
    return null
}
