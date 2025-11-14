package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.text.Html
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

// 图片信息模板：识别图形组件，显示应用图标或自定义图片
data class PicInfo(
    val type: Int, // 组件类型：1 appIcon，2 middle，3 large，5 倒计时带图
    val pic: String? = null, // 图片资源key（type=2/3时必传）
    val picDark: String? = null, // 深色模式图片资源key
    val actionInfo: ActionInfo? = null, // 操作信息
    val title: String? = null, // 组件文字（type=5时使用）
    val colorTitle: String? = null // 文字颜色（type=5时使用）
)

// 解析图片信息组件（识别图形组件）
fun parsePicInfo(json: JSONObject): PicInfo {
    return PicInfo(
        type = json.optInt("type", 1),
        pic = json.optString("pic", "").takeIf { it.isNotEmpty() },
        picDark = json.optString("picDark", "").takeIf { it.isNotEmpty() },
        actionInfo = json.optJSONObject("actionInfo")?.let { parseActionInfo(it) },
        title = json.optString("title", "").takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", "").takeIf { it.isNotEmpty() }
    )
}

// 构建PicInfo视图
fun buildPicInfoView(context: Context, picInfo: PicInfo, picMap: Map<String, String>?): LinearLayout {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    picInfo.pic?.let { pic ->
        picMap?.get(pic)?.let { url ->
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = downloadBitmap(context, url, 5000)
                if (bitmap != null) {
                    val iv = ImageView(context).apply {
                        setImageBitmap(bitmap)
                        val size = (48 * context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    container.addView(iv, 0) // 添加到开头
                }
            }
        }
    }

    picInfo.title?.let {
        val tv = TextView(context).apply {
            text = Html.fromHtml(unescapeHtml(it), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(parseColor(picInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
            layoutParams = lp
        }
        container.addView(tv)
    }

    return container
}