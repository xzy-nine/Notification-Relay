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
import coil.ImageLoader as CoilImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

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