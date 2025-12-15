package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigIslandArea.A

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.core.util.ImageLoader

/**
 * 创建 A区 浮窗视图（细化：图文组件1 + 兼容图文组件5 基础）。
 * - 图文组件1：支持 narrowFont、showHighlightColor、picKey（data URL 直接显示，其它留空待上层下载）
 */
fun buildAView(context: Context, component: AComponent, picMap: Map<String, String>? = null): View {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val density = context.resources.displayMetrics.density

    fun tryLoadFocusIcon(iv: ImageView, picMapLocal: Map<String, String>?, primaryKey: String?): Boolean {
        // 1) 若传入了主键（例如 miui.focus.pic_app_icon），应优先使用它
        if (!primaryKey.isNullOrBlank()) {
            if (ImageLoader.loadKeyInto(iv, picMapLocal, primaryKey)) return true
        }

        // 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
        val ordered = picMapLocal?.keys?.asSequence()
            ?.filter { it.startsWith("miui.focus.pic_", ignoreCase = true) && !it.equals("miui.focus.pics", true) }
            ?.toList()
            ?: emptyList()

        // 按“优先第二位”的规则重排（针对 type=2 无主键场景）：若有第二项，将其放到首位，其余保持原始相对顺序
        val candidates = if (ordered.size >= 2) {
            listOf(ordered[1]) + ordered.filterIndexed { idx, _ -> idx != 1 }
        } else ordered

        // 避免重复尝试主键
        val finalList = if (!primaryKey.isNullOrBlank()) candidates.filterNot { it.equals(primaryKey, true) } else candidates

        for (k in finalList) {
            if (ImageLoader.loadKeyInto(iv, picMapLocal, k)) return true
        }
        return false
    }

    when (component) {
        is AImageText1 -> {
            // 图标
            val key = component.picKey
            val hasFocusCandidates = picMap?.keys?.any { it.startsWith("miui.focus.pic_", true) } == true
            if (hasFocusCandidates || !key.isNullOrBlank()) {
                val hasText = !component.title.isNullOrBlank() || !component.content.isNullOrBlank()
                val iconDp = if (hasText) 18 else 24 // 纯图标场景放大以填满胶囊视觉
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((iconDp * density).toInt(), (iconDp * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(iv)
                // 这里按优先级尝试 miui.focus.pic_2/1 等，再退回到传入 key
                tryLoadFocusIcon(iv, picMap, key)
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = (6 * density).toInt()
                layoutParams = lp
            }

            component.title?.let { t ->
                val tv = TextView(context).apply {
                    text = t
                    setTextColor(if (component.showHighlightColor) 0xFF40C4FF.toInt() else 0xFFFFFFFF.toInt())
                    textSize = 14f
                    // 单行与自动收缩，避免胶囊过长
                    isSingleLine = true
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                    maxWidth = (140 * density).toInt()
                    typeface = if (component.narrowFont) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.DEFAULT_BOLD
                }
                textContainer.addView(tv)
            }

            component.content?.let { c ->
                val tv = TextView(context).apply {
                    text = c
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 12f
                    isSingleLine = true
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                    maxWidth = (140 * density).toInt()
                    typeface = if (component.narrowFont) Typeface.create("sans-serif-condensed", Typeface.NORMAL) else Typeface.DEFAULT
                }
                textContainer.addView(tv)
            }

            container.addView(textContainer)
        }

        is AImageText5 -> {
            // 图标（仅在 data URL 可直接显示；其余交给上层图片加载）
            val key = component.picKey
            val hasFocusCandidates = picMap?.keys?.any { it.startsWith("miui.focus.pic_", true) } == true
            if (hasFocusCandidates || !key.isNullOrBlank()) {
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(iv)
                tryLoadFocusIcon(iv, picMap, key)
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = (6 * density).toInt()
                layoutParams = lp
            }
            val title = component.title
            val tvTitle = TextView(context).apply {
                text = title
                setTextColor(if (component.showHighlightColor) 0xFF40C4FF.toInt() else 0xFFFFFFFF.toInt())
                textSize = 14f
                isSingleLine = true
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                maxWidth = (140 * density).toInt()
                typeface = Typeface.DEFAULT_BOLD
            }
            textContainer.addView(tvTitle)

            component.content?.let { c ->
                val tv = TextView(context).apply {
                    text = c
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 12f
                    isSingleLine = true
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                    maxWidth = (140 * density).toInt()
                    typeface = Typeface.DEFAULT
                }
                textContainer.addView(tv)
            }
            container.addView(textContainer)
        }
    }

    return container
}
