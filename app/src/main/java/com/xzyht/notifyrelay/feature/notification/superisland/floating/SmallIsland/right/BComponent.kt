package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right

/**
 * B区（右侧 imageTextInfoRight + 其它文本/图片/进度组件）模型。
 * imageTextInfoRight 的 type 映射：2、3、4、6。
 * 其他：textInfo / fixedWidthDigitInfo / sameWidthDigitInfo / progressTextInfo / picInfo。
 */
sealed interface BComponent { val kind: String }

// imageTextInfoRight 家族

/**
 * 图文组件2（imageTextInfoRight.type = 2）
 * - textInfo.title 必传（若缺失则解析应失败）
 * - picInfo.type 必须为 1，pic 为图片键（miui.focus.pic_xxx 或内置 key）
 */
data class BImageText2(
    val frontTitle: String? = null,
    val title: String,
    val content: String? = null,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false,
    val picKey: String
) : BComponent { override val kind: String = "imageText2" }

data class BImageText3(
    // 图文组件3：仅要求 title 与图标，支持强调与窄体
    val title: String,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false,
    val picKey: String
) : BComponent { override val kind: String = "imageText3" }

data class BImageText4(
    val title: String? = null,
    val content: String? = null,
    val pic: String? = null
) : BComponent { override val kind: String = "imageText4" }

/**
 * 图文组件6（imageTextInfoRight.type = 6）
 * - textInfo.title 必传
 * - picInfo.type 必须为 4，picKey 必传（miui.focus.pic_xxx）
 */
data class BImageText6(
    val title: String,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false,
    val picKey: String
) : BComponent { override val kind: String = "imageText6" }

// 文本、数字与进度

data class BTextInfo(
    val frontTitle: String? = null,
    val title: String,
    val content: String? = null,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false
) : BComponent { override val kind: String = "textInfo" }

/**
 * 定宽数字文本组件（fixedWidthDigitInfo）
 * - digit 必传
 * - content 可选
 * - showHighlightColor 可选
 */
data class BFixedWidthDigitInfo(
    val digit: String,
    val content: String? = null,
    val showHighlightColor: Boolean = false
) : BComponent { override val kind: String = "fixedWidthDigitInfo" }

/**
 * 计时信息（sameWidthDigitInfo.timerInfo）
 * - timerType 必传
 * - timerWhen / timerTotal / timerSystemCurrent 可选
 */
/**
 * 等宽数字文本组件（sameWidthDigitInfo）
 * - digit 与 timer 二选一，至少一项存在（digit 兼容旧字段名 text）
 * - content 可选
 * - showHighlightColor 可选
 */
data class BSameWidthDigitInfo(
    val digit: String? = null,
    val timer: com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.TimerInfo? = null,
    val content: String? = null,
    val showHighlightColor: Boolean = false
) : BComponent { override val kind: String = "sameWidthDigitInfo" }

/**
 * 进度文本组件（progressTextInfo）
 * - title(text) 必传
 * - content 可选（用于展示“xx%”等进度描述）
 * - showHighlightColor / narrowFont 可选
 */
data class BProgressTextInfo(
    // textInfo
    val frontTitle: String? = null,
    val title: String? = null,
    val content: String? = null,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false,
    // progressInfo (progress 必传)
    val progress: Int,
    val colorReach: String? = null,
    val colorUnReach: String? = null,
    val isCCW: Boolean = false,
    // picInfo（若存在则 type=1 且 picKey 必传）
    val picKey: String? = null
) : BComponent { override val kind: String = "progressTextInfo" }

// 大图

/**
 * 图片组件（picInfo）
 * - type 必传且目前仅支持 1（静态图片）
 * - picKey 必传（静态图标时为 pics Bundle 中 key）
 */
data class BPicInfo(
    val picKey: String,
    val type: Int = 1
) : BComponent { override val kind: String = "picInfo" }

// 空
object BEmpty : BComponent { override val kind: String = "empty" }
