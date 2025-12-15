package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigIslandArea.A

/**
 * A区（左侧 imageTextInfoLeft）组件模型。
 * 类型由 JSON 中的 type 字段区分：
 * - type = 1 -> 图文组件1
 * - type = 5 -> 图文组件5
 */
sealed interface AComponent { val type: Int }

/**
 * 图文组件1（imageTextInfoLeft.type = 1）
 * - picInfo.type 必须为 1
 * - pic 为图片键（如 "miui.focus.pic_xxx"）
 */
data class AImageText1(
    val title: String? = null,
    val content: String? = null,
    val narrowFont: Boolean = false,
    val showHighlightColor: Boolean = false,
    val picKey: String? = null
) : AComponent { override val type: Int = 1 }

data class AImageText5(
    val title: String,
    val content: String? = null,
    val showHighlightColor: Boolean = false,
    val picKey: String
) : AComponent { override val type: Int = 5 }
