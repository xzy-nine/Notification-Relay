package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF.toInt()
private const val DEFAULT_NODE_COUNT = 3
private const val IMAGE_MAX_DIMENSION = 320
private const val DOWNLOAD_MAX_BYTES = 4 * 1024 * 1024

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
suspend fun buildMultiProgressInfoView(
    context: Context,
    multiProgressInfo: MultiProgressInfo,
    picMap: Map<String, String>?,
    business: String? = null
): FrameLayout {
    val density = context.resources.displayMetrics.density
    // 节点尺寸放到最前，供容器内边距与最小高度计算，避免被上层文本裁剪
    // 用户要求把节点扩大到 55dp
    val nodeSize = (55 * density).toInt()
    val primaryColor = parseColor(multiProgressInfo.color) ?: DEFAULT_PRIMARY_COLOR
    val container = FrameLayout(context).apply {
        // 父级是垂直 LinearLayout，这里使用其专用的 LayoutParams 并增加顶部间距，避免与上方文本发生视觉遮挡
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // 移除默认的顶部间距，按设计要求进度区与上方文本不再保留间隔
            topMargin = 0
        }
        // 不再需要容器顶部预留间距，移除 paddingTop 以实现紧贴文本的效果
        setPadding(0, 0, 0, 0)
        clipChildren = false
        clipToPadding = false
    }

    val requestedPoints = multiProgressInfo.points ?: DEFAULT_NODE_COUNT
    val nodeCount = max(2, requestedPoints)
    val segmentCount = max(1, nodeCount - 1)
    val progressValue = multiProgressInfo.progress.coerceIn(0, 100)
    val stageFloat = progressValue / 100f * segmentCount
    val pointerIndex = stageFloat.toInt().coerceIn(0, nodeCount - 1)
    val reachedEnd = progressValue >= 100

    // Track层：底条 + 覆盖条 + 指针（背景层），再叠加节点（前景层）
    val trackLayer = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        // 设置最小高度为节点高度，避免仅按进度条高度测量导致子节点顶部超出而被祖先容器裁剪
        minimumHeight = nodeSize
        clipChildren = false
        clipToPadding = false
    }

    val barHeight = (8 * density).toInt()
    val cornerRadius = barHeight / 2f

    val baseBar = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            barHeight,
            Gravity.BOTTOM
        )
        background = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(adjustAlpha(primaryColor, 0x33))
        }
    }
    trackLayer.addView(baseBar)

    val progressBar = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            1,
            barHeight,
            Gravity.BOTTOM or Gravity.START
        )
        background = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(primaryColor)
        }
    }
    trackLayer.addView(progressBar)

    val pointerBitmapGlobal = decodeBitmap(picMap, multiProgressInfo.picForward)
    // 额外放大指示点尺寸：比默认增加 15dp（用户要求）
    val pointerExtra = (15 * density).toInt()
    val pointerSize = (barHeight * 4).toInt() + pointerExtra
    val pointerViewGlobal = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(pointerSize, pointerSize, Gravity.BOTTOM)
        scaleType = ImageView.ScaleType.FIT_CENTER
        elevation = 6f
        pointerBitmapGlobal?.let { setImageBitmap(it) }
        visibility = if (progressValue in 1..99) View.VISIBLE else View.INVISIBLE
    }
    trackLayer.addView(pointerViewGlobal)

    // 节点容器（前景层）：使用等权重空白占位来保证节点等距
    val track = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        clipChildren = false
    }

    val pointerKey = multiProgressInfo.picForward
    val pointerWaitKey = multiProgressInfo.picForwardWait ?: pointerKey

    for (index in 0 until nodeCount) {
        val isLast = index == nodeCount - 1
        val isCompleted = index <= pointerIndex
        val isPointer = index == pointerIndex
        val baseIconKey = when {
            isLast && isCompleted -> multiProgressInfo.picEnd ?: multiProgressInfo.picMiddle
            isLast -> multiProgressInfo.picEndUnselected ?: multiProgressInfo.picMiddleUnselected
            isCompleted -> multiProgressInfo.picMiddle ?: multiProgressInfo.picForwardBox
            else -> multiProgressInfo.picMiddleUnselected ?: multiProgressInfo.picForwardBox
        }
        val nodeView = createNodeView(
            context = context,
            size = nodeSize,
            iconKey = baseIconKey,
            picMap = picMap,
            tintColor = primaryColor
        )
        if (index == 0 && business == "food_delivery") {
            nodeView.alpha = 0f
        }
        track.addView(nodeView)
        if (index < nodeCount - 1) {
            val spacer = View(context)
            spacer.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            track.addView(spacer)
        }
    }
    // 按整体百分比更新覆盖条与指针位置（基于 trackLayer 实际宽度）
    trackLayer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val w = v.width
        if (w <= 0) return@addOnLayoutChangeListener
        val ratio = (progressValue / 100f).coerceIn(0f, 1f)

        val lp = progressBar.layoutParams as FrameLayout.LayoutParams
        val newWidth = (w * ratio).roundToInt().coerceIn(0, w)
        lp.width = newWidth
        progressBar.layoutParams = lp

        val pvlp = pointerViewGlobal.layoutParams as FrameLayout.LayoutParams
        val half = pointerSize / 2
        pvlp.leftMargin = (w * ratio - half).roundToInt().coerceIn(0, max(0, w - pointerSize))
        pointerViewGlobal.layoutParams = pvlp
    }

    trackLayer.addView(track)
    container.addView(trackLayer)
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

private suspend fun createNodeView(
    context: Context,
    size: Int,
    iconKey: String?,
    picMap: Map<String, String>?,
    tintColor: Int
): View {
    val frame = FrameLayout(context)
    val params = LinearLayout.LayoutParams(size, size)
    frame.layoutParams = params
    frame.elevation = 10f

    val baseBitmap = decodeBitmap(picMap, iconKey)
    if (baseBitmap != null) {
        val baseView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 使用 FIT_CENTER 保证整张图完整显示，避免被裁剪造成“上部被遮挡”的视觉问题
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

    return frame
}

private suspend fun createConnectorView(
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
    val height = (8 * density).toInt()
    val params = LinearLayout.LayoutParams(0, height, 1f)
    params.gravity = Gravity.BOTTOM
    params.setMargins(0, 0, 0, 0)
    val container = FrameLayout(context).apply {
        layoutParams = params
        clipChildren = false
    }

    // 背景条：根据完成状态调整基色透明度
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

    // 填充覆盖条与指针：使用实际容器宽度计算
    val overlayView = View(context).apply {
        visibility = if (completed || inProgress) View.VISIBLE else View.INVISIBLE
        layoutParams = FrameLayout.LayoutParams(
            1, // 初始占位，待布局后按真实宽度更新
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START
        )
        background = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(tintColor)
        }
    }
    container.addView(overlayView)

    var pointerView: ImageView? = null
    val pointerBitmap = if (inProgress) decodeBitmap(picMap, info.picForward) else null
    if (pointerBitmap != null) {
        // 同样为 segment 内的指针增加 15dp
        val pointerExtraInner = (15 * density).toInt()
        val pointerSizeInner = (height * 4).toInt() + pointerExtraInner
        pointerView = ImageView(context).apply {
            val lp = FrameLayout.LayoutParams(pointerSizeInner, pointerSizeInner, Gravity.BOTTOM)
            layoutParams = lp
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(pointerBitmap)
            elevation = 5f
        }
        container.addView(pointerView)
    }

    // 根据容器实际宽度更新填充与指针位置
    container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val w = v.width
        if (w <= 0) return@addOnLayoutChangeListener
        val filled = if (completed) 1f else completion
        val overlayLp = overlayView.layoutParams as FrameLayout.LayoutParams
        val newWidth = (w * filled).roundToInt().coerceIn(1, w)
        overlayLp.width = newWidth
        overlayView.layoutParams = overlayLp

        pointerView?.let { pv ->
            val lp = pv.layoutParams as FrameLayout.LayoutParams
            val pointerHalf = pv.layoutParams.width / 2
            lp.leftMargin = (w * filled - pointerHalf).roundToInt().coerceIn(0, max(0, w - pv.layoutParams.width))
            pv.layoutParams = lp
        }
    }

    return container
}

private suspend fun decodeBitmap(picMap: Map<String, String>?, key: String?): Bitmap? {
    if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
    val raw = picMap[key] ?: return null
    return try {
        if (raw.startsWith("data:", ignoreCase = true)) {
            DataUrlUtils.decodeDataUrlToBitmap(raw)
        } else if (raw.startsWith("http", ignoreCase = true)) {
            val cleanedUrl = raw.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            downloadBitmap(cleanedUrl, 10000)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("MultiProgressRenderer", "Failed to decode bitmap for key $key: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val sampleSize = computeInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, maxDimension)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
}

private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    var largestSide = max(width, height)
    while (largestSide / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun adjustAlpha(color: Int, alpha: Int): Int {
    return (color and 0x00FFFFFF) or (alpha shl 24)
}