package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.A.AComponent
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.A.buildAView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.A.parseAComponent
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.B.BComponent
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.B.BEmpty
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.B.BTextInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.B.buildBView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.B.parseBComponent
import org.json.JSONObject

/**
 * 超级岛 摘要/收起态总装配渲染器：将 A区 与 B区 的组件解析并组装为一个横向容器。
 * 目前为基础骨架，样式与比例后续可调整；暂不依赖外部图片加载库。
 */
fun buildBigIslandCollapsedView(
    context: Context,
    bigIsland: JSONObject?,
    picMap: Map<String, String>? = null,
    fallbackTitle: String? = null,
    fallbackContent: String? = null
): View {
    val density = context.resources.displayMetrics.density
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // 胶囊内边距
        val paddingH = (10 * density).toInt()
        val paddingV = (6 * density).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)
        // 胶囊背景（半透明、圆角）
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(0xCC000000.toInt()) // 半透明黑
            setStroke(density.toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
        }
        background = bg
        elevation = 6f * density
        clipToPadding = false
    }

    val aComp: AComponent? = parseAComponent(bigIsland)
    var bComp: BComponent = parseBComponent(bigIsland)

    fun addHeadSpacer(widthDp: Int) {
        val w = (widthDp * density).toInt()
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(w, 1)
        }
        container.addView(spacer)
    }

    // 如果 B 为空且存在兜底文本，则用兜底文本填充 B
    val bIsEmptyInitial = (bComp is BEmpty)
    if (bIsEmptyInitial) {
        val titleOrNull = fallbackTitle?.takeIf { it.isNotBlank() }
        val contentOrNull = fallbackContent?.takeIf { it.isNotBlank() }
        if (titleOrNull != null || contentOrNull != null) {
            bComp = BTextInfo(
                title = titleOrNull ?: contentOrNull.orEmpty(),
                content = if (titleOrNull != null) contentOrNull else null
            )
        }
    }

    // 构建 A/B 视图
    val aView = aComp?.let { buildAView(context, it, picMap) }
    val bIsEmpty = bComp is BEmpty
    val bView = if (!bIsEmpty) buildBView(context, bComp, picMap) else null

    val aExists = (aView != null)
    val bExists = (bView != null)

    // 使用左右包裹 + 弹性占位，确保：A 左对齐、B 右对齐，且整体宽度由内容自然决定，不被权重拉长
    // 左侧胶囊头（若 A 不存在）
    if (!aExists) addHeadSpacer(12)

    // 左包裹（A 左对齐）
    if (aExists) {
        val leftWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        leftWrap.addView(aView)
        container.addView(leftWrap)
    }

    // 中间弹性占位（将右侧内容推向右边界；若父容器给定更大宽度，也能保持 B 靠右）
    val spacer = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }
    container.addView(spacer)

    // 右包裹（B 右对齐）或右侧胶囊头
    if (bExists) {
        val rightWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rightWrap.addView(bView)
        container.addView(rightWrap)
    } else {
        addHeadSpacer(12)
    }

    return container
}
