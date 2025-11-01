package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import kotlin.math.max
import org.json.JSONObject

// 多进度信息：多段进度条组件
data class MultiProgressInfo(
    val title: String, // 进度描述文本
    val progress: Int, // 当前进度百分比
    val color: String? = null, // 进度条颜色
    val points: Int? = null, // 节点数量（0-4）
    val picForward: String? = null, // 进度指示点
    val picForwardWait: String? = null, // 目标指示点
    val picForwardBox: String? = null, // 进度条背景块
    val picMiddle: String? = null, // 激活的中间节点
    val picMiddleUnselected: String? = null, // 未激活的中间节点
    val picEnd: String? = null, // 激活的末尾节点
    val picEndUnselected: String? = null // 未激活的末尾节点
)

// 解析多进度信息组件
fun parseMultiProgressInfo(json: JSONObject): MultiProgressInfo {
    val middleUnselected = sequenceOf(
        json.optString("picMiddleUnselected", ""),
        json.optString("picMiddelUnselected", "")
    ).firstOrNull { it.isNotEmpty() }
    return MultiProgressInfo(
        title = json.optString("title", ""),
        progress = json.optInt("progress", 0),
        color = json.optString("color", "").takeIf { it.isNotEmpty() },
        points = json.optInt("points", -1).takeIf { it != -1 },
        picForward = json.optString("picForward", "").takeIf { it.isNotEmpty() },
        picForwardWait = json.optString("picForwardWait", "").takeIf { it.isNotEmpty() },
        picForwardBox = json.optString("picForwardBox", "").takeIf { it.isNotEmpty() },
        picMiddle = json.optString("picMiddle", "").takeIf { it.isNotEmpty() },
        picMiddleUnselected = middleUnselected,
        picEnd = json.optString("picEnd", "").takeIf { it.isNotEmpty() },
        picEndUnselected = json.optString("picEndUnselected", "").takeIf { it.isNotEmpty() }
    )
}

// 构建MultiProgressInfo视图
fun buildMultiProgressInfoView(
    context: Context,
    multiProgressInfo: MultiProgressInfo,
    picMap: Map<String, String>?
): LinearLayout {
    val density = context.resources.displayMetrics.density
    val primaryColor = parseColor(multiProgressInfo.color) ?: DEFAULT_PRIMARY_COLOR
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    multiProgressInfo.title.takeIf { it.isNotBlank() }?.let { title ->
        val titleView = TextView(context).apply {
            text = title
            setTextColor(primaryColor)
            textSize = 13f
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, (4 * density).toInt())
        titleView.layoutParams = lp
        container.addView(titleView)
    }

    val requestedPoints = multiProgressInfo.points ?: DEFAULT_NODE_COUNT
    val nodeCount = max(2, requestedPoints)
    val segmentCount = max(1, nodeCount - 1)
    val progressValue = multiProgressInfo.progress.coerceIn(0, 100)
    val stageFloat = progressValue / 100f * segmentCount
    val pointerIndex = stageFloat.toInt().coerceIn(0, nodeCount - 1)
    val reachedEnd = progressValue >= 100

    val track = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val nodeSize = (40 * density).toInt()
    val pointerKey = multiProgressInfo.picForward
    val pointerWaitKey = multiProgressInfo.picForwardWait ?: pointerKey

    for (index in 0 until nodeCount) {
        val isLast = index == nodeCount - 1
        val isCompleted = index < pointerIndex || (isLast && reachedEnd)
        val isPointer = index == pointerIndex
        val baseIconKey = when {
            isLast && isCompleted -> multiProgressInfo.picEnd ?: multiProgressInfo.picMiddle
            isLast -> multiProgressInfo.picEndUnselected ?: multiProgressInfo.picMiddleUnselected
            isCompleted || isPointer -> multiProgressInfo.picMiddle ?: multiProgressInfo.picForwardBox
            else -> multiProgressInfo.picMiddleUnselected ?: multiProgressInfo.picForwardBox
        }
        val nodeView = createNodeView(
            context = context,
            size = nodeSize,
            iconKey = baseIconKey,
            picMap = picMap,
            tintColor = primaryColor,
            showPointer = isPointer,
            pointerKey = if (isLast && reachedEnd) pointerWaitKey else pointerKey
        )
        track.addView(nodeView)
        if (index < nodeCount - 1) {
            val connectorProgress = (stageFloat - index).coerceIn(0f, 1f)
            track.addView(
                createConnectorView(
                    context = context,
                    info = multiProgressInfo,
                    density = density,
                    tintColor = primaryColor,
                    picMap = picMap,
                    segmentProgress = connectorProgress
                )
            )
        }
    }

    container.addView(track)
    return container
}

// 根据 ProgressInfo 构造多节点进度信息
fun ProgressInfo.toMultiProgressInfo(title: String? = null, pointsOverride: Int? = null): MultiProgressInfo? {
    val hasNodeAssets = listOf(picMiddle, picMiddleUnselected, picEnd, picEndUnselected, picForward)
        .any { !it.isNullOrBlank() }
    if (!hasNodeAssets) return null

    val resolvedColor = colorProgress ?: colorProgressEnd
    return MultiProgressInfo(
        title = title?.trim().orEmpty(),
        progress = progress,
        color = resolvedColor,
        points = pointsOverride,
        picForward = picForward,
        picForwardWait = null,
        picForwardBox = null,
        picMiddle = picMiddle,
        picMiddleUnselected = picMiddleUnselected,
        picEnd = picEnd,
        picEndUnselected = picEndUnselected
    )
}

private fun createNodeView(
    context: Context,
    size: Int,
    iconKey: String?,
    picMap: Map<String, String>?,
    tintColor: Int,
    showPointer: Boolean,
    pointerKey: String?
): View {
    val frame = FrameLayout(context)
    val params = LinearLayout.LayoutParams(size, size)
    frame.layoutParams = params

    val baseBitmap = decodeBitmap(picMap, iconKey)
    if (baseBitmap != null) {
        val baseView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(baseBitmap)
        }
        frame.addView(baseView)
    } else {
        val fallback = View(context).apply {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tintColor)
            }
            background = drawable
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(fallback)
    }

    if (showPointer) {
        val pointerBitmap = decodeBitmap(picMap, pointerKey)
        pointerBitmap?.let {
            val pointerSize = (size * 0.55f).toInt()
            val pointerView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(pointerSize, pointerSize, Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(it)
            }
            frame.addView(pointerView)
        }
    }

    return frame
}

private fun createConnectorView(
    context: Context,
    info: MultiProgressInfo,
    density: Float,
    tintColor: Int,
    picMap: Map<String, String>?,
    segmentProgress: Float
): View {
    val completion = segmentProgress.coerceIn(0f, 1f)
    val completed = completion >= 0.999f
    val inProgress = completion > 0f && !completed
    val iconKey = when {
        completed -> info.picForward ?: info.picForwardBox
        inProgress -> info.picForward ?: info.picForwardBox
        else -> info.picForwardWait ?: info.picForwardBox
    }
    val bitmap = decodeBitmap(picMap, iconKey)
    val width = (24 * density).toInt()
    val height = if (bitmap != null) (8 * density).toInt() else (3 * density).toInt()
    val params = LinearLayout.LayoutParams(width, height)
    params.setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)

    return if (bitmap != null) {
        ImageView(context).apply {
            layoutParams = params
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
    } else {
        val container = FrameLayout(context).apply {
            layoutParams = params
        }

        val baseColorAlpha = when {
            completed -> 0xFF
            inProgress -> 0x66
            else -> 0x33
        }
        val baseColor = adjustAlpha(tintColor, baseColorAlpha)
        val cornerRadius = height / 2f
        val baseView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                this.cornerRadius = cornerRadius
                setColor(baseColor)
            }
        }
        container.addView(baseView)

        if (inProgress) {
            val overlayWidth = (width * completion).toInt().coerceIn(1, width)
            val overlayView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    overlayWidth,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.START
                )
                background = GradientDrawable().apply {
                    this.cornerRadius = cornerRadius
                    setColor(tintColor)
                }
            }
            container.addView(overlayView)
        }

        container
    }
}

private fun decodeBitmap(picMap: Map<String, String>?, key: String?): Bitmap? {
    if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
    val raw = picMap[key] ?: return null
    return try {
        if (raw.startsWith("data:", ignoreCase = true)) {
            DataUrlUtils.decodeDataUrlToBitmap(raw)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun adjustAlpha(color: Int, alpha: Int): Int {
    val clamped = alpha.coerceIn(0, 255)
    val rgb = color and 0x00FFFFFF
    return (clamped shl 24) or rgb
}

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF.toInt()
private const val DEFAULT_NODE_COUNT = 3