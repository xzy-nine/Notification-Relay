package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.bigIsandArea.B

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.superisland.SuperIslandImageStore
import com.xzyht.notifyrelay.core.util.ImageLoader
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.CircularProgressView
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.bindTimerUpdater
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.formatTimerInfo
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.TimerInfo as FMTimerInfo

/**
 * 创建 B区 浮窗视图。
 * - 图文组件2：支持 frontTitle、title(必填)、content、narrowFont、showHighlightColor、picKey。
 */
fun buildBView(context: Context, component: BComponent, picMap: Map<String, String>? = null): View {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val density = context.resources.displayMetrics.density

    fun parseColorSafeLocal(s: String?, default: Int): Int = try {
        if (s.isNullOrBlank()) default else Color.parseColor(s)
    } catch (_: IllegalArgumentException) { default }

    fun buildProgressBarLocal(progress: Int, colorReach: Int, colorUnReach: Int): View {
        val totalPx = (56 * density).toInt()
        val heightPx = (4 * density).toInt()
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(totalPx, heightPx)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val bg = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(totalPx, heightPx)
            setBackgroundColor(colorUnReach)
        }
        val fgWidth = (totalPx * (progress.coerceIn(0, 100) / 100f)).toInt()
        val fg = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(fgWidth, heightPx)
            setBackgroundColor(colorReach)
        }
        frame.addView(bg)
        frame.addView(fg)
        return frame
    }

    fun buildTextBlockView(
        frontTitle: String? = null,
        title: String? = null,
        content: String? = null,
        narrow: Boolean = false,
        highlight: Boolean = false,
        monospace: Boolean = false,
        onTitleView: ((TextView) -> Unit)? = null
    ): View {
        val textContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frontTitle?.let {
            val tv = TextView(context).apply {
                text = it
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                isSingleLine = true
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                maxWidth = (120 * density).toInt()
                typeface = when {
                    monospace -> Typeface.MONOSPACE
                    narrow -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                    else -> Typeface.DEFAULT
                }
            }
            textContainer.addView(tv)
        }
        title?.let {
            val tv = TextView(context).apply {
                text = it
                setTextColor(if (highlight) 0xFF40C4FF.toInt() else 0xFFFFFFFF.toInt())
                textSize = 14f
                isSingleLine = true
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                maxWidth = (140 * density).toInt()
                typeface = when {
                    monospace -> Typeface.MONOSPACE
                    narrow -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    else -> Typeface.DEFAULT_BOLD
                }
            }
            onTitleView?.let { cb -> cb(tv) }
            textContainer.addView(tv)
        }
        content?.let {
            val tv = TextView(context).apply {
                text = it
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                isSingleLine = true
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                maxWidth = (140 * density).toInt()
                typeface = when {
                    monospace -> Typeface.MONOSPACE
                    narrow -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                    else -> Typeface.DEFAULT
                }
            }
            textContainer.addView(tv)
        }
        return textContainer
    }

    fun addTextBlock(
        frontTitle: String? = null,
        title: String? = null,
        content: String? = null,
        narrow: Boolean = false,
        highlight: Boolean = false,
        monospace: Boolean = false,
        onTitleView: ((TextView) -> Unit)? = null
    ) {
        container.addView(buildTextBlockView(frontTitle, title, content, narrow, highlight, monospace, onTitleView))
    }

    when (component) {
        is BImageText2 -> {
            // 图标
            val key = component.picKey
            if (!key.isNullOrBlank()) {
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(iv)
                ImageLoader.loadKeyInto(iv, picMap, key)
            }
            addTextBlock(
                frontTitle = component.frontTitle,
                title = component.title,
                content = component.content,
                narrow = component.narrowFont,
                highlight = component.showHighlightColor
            )
        }
        is BImageText3 -> {
            val key = component.picKey
            if (!key.isNullOrBlank()) {
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(iv)
                ImageLoader.loadKeyInto(iv, picMap, key)
            }
            addTextBlock(
                title = component.title,
                narrow = component.narrowFont,
                highlight = component.showHighlightColor
            )
        }
        is BImageText4 -> {
            val (title, content, pic) = Triple(component.title, component.content, component.pic)
            if (!pic.isNullOrBlank()) {
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                }
                container.addView(iv)
            }
            addTextBlock(title = title, content = content)
        }
        is BImageText6 -> {
            val key = component.picKey
            val url = picMap?.get(key)
            val resolved = SuperIslandImageStore.resolve(context, url) ?: url
            val bmp: Bitmap? = when {
                resolved.isNullOrBlank() -> null
                resolved.startsWith("data:", true) -> runCatching { DataUrlUtils.decodeDataUrlToBitmap(resolved) }.getOrNull()
                else -> null
            }
            if (bmp != null) {
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }
                container.addView(iv)
            }
            addTextBlock(
                title = component.title,
                narrow = component.narrowFont,
                highlight = component.showHighlightColor
            )
        }
        is BTextInfo -> addTextBlock(
            frontTitle = component.frontTitle,
            title = component.title,
            content = component.content,
            narrow = component.narrowFont,
            highlight = component.showHighlightColor
        )
        is BFixedWidthDigitInfo -> addTextBlock(
            title = component.digit,
            content = component.content,
            highlight = component.showHighlightColor,
            monospace = true
        )
        is BSameWidthDigitInfo -> {
            val fmTimer = component.timer?.let { t ->
                FMTimerInfo(
                    timerType = t.timerType,
                    timerWhen = t.timerWhen ?: 0L,
                    timerTotal = t.timerTotal ?: 0L,
                    timerSystemCurrent = t.timerSystemCurrent ?: 0L
                )
            }
            val titleText = component.digit ?: fmTimer?.let { tf -> formatTimerInfo(tf) }
            addTextBlock(
                title = titleText,
                content = component.content,
                highlight = component.showHighlightColor,
                monospace = true,
                onTitleView = { tv ->
                    if (fmTimer != null) {
                        bindTimerUpdater(tv, fmTimer)
                    }
                }
            )
        }
        is BProgressTextInfo -> {
            // 摘要态进度样式与展开态一致：使用圆形进度环包裹小图标
            val sizePx = (20 * density).toInt()
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    // 与左侧其它元素保持微距
                }
            }
            val reach = parseColorSafeLocal(component.colorReach, 0xFF3482FF.toInt())
            val unreach = parseColorSafeLocal(component.colorUnReach, 0x33333333)
            val ring = CircularProgressView(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setStrokeWidthDp(2.5f)
                // 方向遵循 isCCW：逆时针则 clockwise=false
                setDirection(!component.isCCW)
                setColors(reach, unreach)
                setProgressInstant(component.progress.coerceIn(0, 100))
                visibility = View.VISIBLE
                // 打上可发现的标记，便于上层创建进度绑定动画
                tag = "collapsed_b_progress_ring"
            }
            frame.addView(ring)
            component.picKey?.let { key ->
                val iv = ImageView(context).apply {
                    val inset = (3 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(sizePx - inset * 2, sizePx - inset * 2, Gravity.CENTER)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                frame.addView(iv)
                ImageLoader.loadKeyInto(iv, picMap, key)
            }
            container.addView(frame)

            // 文本（若有）
            val textBlock = buildTextBlockView(
                frontTitle = component.frontTitle,
                title = component.title,
                content = component.content,
                narrow = component.narrowFont,
                highlight = component.showHighlightColor
            )
            container.addView(textBlock)
        }
        is BPicInfo -> {
            val key = component.picKey
            if (!key.isNullOrBlank()) {
                val size = 24
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((size * density).toInt(), (size * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(iv)
                ImageLoader.loadKeyInto(iv, picMap, key)
            }
        }
        is BEmpty -> {
            // 不添加任何子视图
        }
    }
    return container
}

private fun formatTimerText(timer: TimerInfo): String {
    val now = System.currentTimeMillis()
    val baseNow = timer.timerSystemCurrent ?: now
    val delta = now - baseNow
    val start = (timer.timerWhen ?: now) + delta
    val elapsed = (now - start).coerceAtLeast(0)
    val duration = if (timer.timerType == 1 && timer.timerTotal != null) {
        // 假定 type=1 为倒计时：剩余时间 = 总时长 - 已用时
        (timer.timerTotal - elapsed).coerceAtLeast(0)
    } else {
        // 其它视为正计时：显示已用时
        elapsed
    }
    return formatDuration(duration)
}

private fun formatDuration(ms: Long): String {
    var totalSec = (ms / 1000).toInt()
    val hours = totalSec / 3600
    totalSec %= 3600
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
