package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.xzyht.notifyrelay.common.core.util.DataUrlUtils
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore
import coil.ImageLoader as CoilImageLoader

/**
 * 超级岛图片加载和处理工具类
 * 整合了图片加载、Compose 图片加载和辅助工具函数
 */
object SuperIslandImageUtil {
    /**
     * 使用 Coil 进行 HTTP/URI 加载，并将 data: 协议 URI 委托给 DataUrlUtils 处理。
     * 用于展开态与摘要态中组件级图标加载，避免在渲染器中写网络细节。
     */
    suspend fun loadBitmapSuspend(context: Context, urlOrData: String, timeoutMs: Int = 5000): Bitmap? {
        return try {
            // 若为引用则先解析为真实值
            val finalUrl = resolveReferenceUrl(context, urlOrData)
            
            if (finalUrl.startsWith("data:", ignoreCase = true)) {
                DataUrlUtils.decodeDataUrlToBitmap(finalUrl)
            } else {
                val loader = CoilImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(finalUrl)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable) return drawable.bitmap
                    // 将 drawable 转换为 bitmap
                    DataUrlUtils.drawableToBitmap(drawable)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 统一的Compose图片加载工具，封装了现有ImageLoader和DataUrlUtils的功能
     */
    @Composable
    fun rememberSuperIslandImagePainter(
        url: String?, 
        picMap: Map<String, String>? = null,
        iconKey: String? = null
    ): Painter? {
        // 状态管理
        val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
        val isLoading = remember { mutableStateOf(false) }

        // 如果提供了iconKey，先从picMap中获取url
        val resolvedUrl = remember(url, picMap, iconKey) {
            if (!iconKey.isNullOrEmpty() && picMap != null) {
                picMap[iconKey]
            } else {
                url
            }
        }

        // 处理ref: URL
        val processedUrl = remember(resolvedUrl) {
            if (!resolvedUrl.isNullOrEmpty()) {
                resolveReferenceUrl(null, resolvedUrl)
            } else {
                resolvedUrl
            }
        }

        // 对于data: URL，使用DataUrlUtils解码
        if (!processedUrl.isNullOrEmpty() && processedUrl.startsWith("data:", ignoreCase = true)) {
            // 同步解码data: URL
            val bitmap = DataUrlUtils.decodeDataUrlToBitmap(processedUrl)
            if (bitmap != null) {
                return BitmapPainter(bitmap.asImageBitmap())
            }
        }

        // 对于其他URL，使用Coil
        return if (!processedUrl.isNullOrEmpty()) {
            rememberAsyncImagePainter(model = processedUrl)
        } else {
            null
        }
    }

    /**
     * 解析备选图标URL，与View渲染的逻辑完全一致
     */
    fun resolveFallbackIconUrl(picMap: Map<String, String>?): String? {
        if (picMap == null) return null

        val prefix = "miui.focus.pic_"
        val focusKeys = picMap.keys
            .filter { it.startsWith(prefix) }
            .toList()

        val secondKey = focusKeys.getOrNull(1)
        if (secondKey != null) {
            var url = picMap[secondKey]
            if (!url.isNullOrEmpty()) {
                url = resolveReferenceUrl(null, url)
            }
            return url
        }

        return null
    }

    /**
     * 下载图片，内部调用loadBitmapSuspend
     */
    suspend fun downloadBitmap(context: Context, url: String, timeoutMs: Int): Bitmap? {
        return try {
            val resolved = resolveReferenceUrl(context, url)
            loadBitmapSuspend(context, resolved, timeoutMs)
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            null
        }
    }

    /**
     * 解析颜色字符串为颜色值
     */
    fun parseColor(colorString: String?): Int? {
        return try {
            colorString?.let { Color.parseColor(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解码HTML转义字符
     */
    fun unescapeHtml(input: String): String {
        return input
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
            .replace("\\u0027", "'")
            .replace("\\u0022", "\"")
            .replace("\\u0026", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 解析简单HTML标签，将其转换为AnnotatedString
     */
    fun parseSimpleHtmlToAnnotatedString(html: String): androidx.compose.ui.text.AnnotatedString {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        val unescapedHtml = unescapeHtml(html)
        
        // 简单的HTML解析，只处理font标签的color属性
        var index = 0
        while (index < unescapedHtml.length) {
            val tagStart = unescapedHtml.indexOf('<', index)
            if (tagStart == -1) {
                // 没有更多标签，添加剩余文本
                builder.append(unescapedHtml.substring(index))
                break
            }
            
            // 添加标签前的文本
            if (tagStart > index) {
                builder.append(unescapedHtml.substring(index, tagStart))
            }
            
            // 查找标签结束位置
            val tagEnd = unescapedHtml.indexOf('>', tagStart)
            if (tagEnd == -1) {
                // 标签未结束，将整个标签作为文本处理
                builder.append(unescapedHtml.substring(tagStart))
                break
            }
            
            val tag = unescapedHtml.substring(tagStart + 1, tagEnd)
            
            if (tag.startsWith("/")) {
                // 结束标签，移除当前样式
                val endTagName = tag.substring(1).trim()
                if (endTagName.equals("font", ignoreCase = true)) {
                    // 结束font标签，重置颜色
                    builder.pop()
                }
                // 移动到标签结束位置之后
                index = tagEnd + 1
            } else {
                // 开始标签，处理样式
                if (tag.startsWith("font", ignoreCase = true)) {
                    // 处理font标签的color属性
                    val colorMatch = Regex("color=['\"](#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})['\"]").find(tag)
                    colorMatch?.let { matchResult ->
                        val colorValue = matchResult.groupValues[1]
                        val colorInt = parseColor(colorValue) ?: 0xFFFFFFFF.toInt()
                        builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(colorInt)))
                    }
                }
                
                // 移动到标签结束位置之后
                index = tagEnd + 1
                
                // 查找下一个标签（可能是结束标签）
                val nextTagStart = unescapedHtml.indexOf('<', index)
                if (nextTagStart != -1) {
                    // 添加开始标签和结束标签之间的文本
                    builder.append(unescapedHtml.substring(index, nextTagStart))
                    // 移动到下一个标签开始位置
                    index = nextTagStart
                } else {
                    // 没有更多标签，添加剩余文本
                    builder.append(unescapedHtml.substring(index))
                    break
                }
            }
        }
        
        return builder.toAnnotatedString()
    }

    /**
     * 安全截断文本
     */
    fun truncateText(text: String, maxLength: Int, suffix: String = "..."): String {
        return if (text.length <= maxLength) {
            text
        } else {
            text.substring(0, maxLength - suffix.length) + suffix
        }
    }

    /**
     * 格式化数字，添加千分位分隔符
     */
    fun formatNumber(number: Long): String {
        return "%,d".format(number)
    }

    /**
     * 安全解析字符串为整数
     */
    fun safeParseInt(value: String?, default: Int = 0): Int {
        return value?.toIntOrNull() ?: default
    }

    /**
     * 安全解析字符串为长整数
     */
    fun safeParseLong(value: String?, default: Long = 0L): Long {
        return value?.toLongOrNull() ?: default
    }

    /**
     * 安全解析字符串为布尔值
     */
    fun safeParseBoolean(value: String?, default: Boolean = false): Boolean {
        return value?.toBooleanStrictOrNull() ?: default
    }

    /**
     * 解析引用URL，如果是ref:开头则解析为真实URL
     */
    private fun resolveReferenceUrl(context: Context?, urlOrRef: String): String {
        return if (urlOrRef.startsWith("ref:", ignoreCase = true)) {
            try {
                SuperIslandImageStore.resolve(context, urlOrRef) ?: urlOrRef
            } catch (_: Exception) {
                urlOrRef
            }
        } else {
            urlOrRef
        }
    }
}