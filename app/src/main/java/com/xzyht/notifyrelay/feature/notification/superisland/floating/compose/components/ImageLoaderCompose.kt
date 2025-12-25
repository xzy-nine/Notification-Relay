package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.rememberAsyncImagePainter
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

/**
 * 统一的Compose图片加载工具，封装了现有ImageLoader和DataUrlUtils的功能
 * 确保Compose渲染和View渲染使用完全相同的图片加载逻辑
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
        if (!resolvedUrl.isNullOrEmpty() && resolvedUrl.startsWith("ref:", ignoreCase = true)) {
            try {
                SuperIslandImageStore.resolve(null, resolvedUrl) ?: resolvedUrl
            } catch (_: Exception) {
                resolvedUrl
            }
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
 * 解析图标URL，与View渲染的ImageLoader.loadKeyInto逻辑完全一致
 */
fun resolveIconUrl(
    picMap: Map<String, String>?, 
    iconKey: String?,
    context: Context? = null
): String? {
    if (iconKey.isNullOrEmpty() || picMap == null) return null
    
    // 1. 从picMap获取原始URL
    var url = picMap[iconKey]
    if (url.isNullOrEmpty()) return null
    
    // 2. 处理ref: URL
    if (url.startsWith("ref:", ignoreCase = true)) {
        url = try {
            SuperIslandImageStore.resolve(context, url) ?: url
        } catch (_: Exception) {
            url
        }
    }
    
    return url
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
        if (!url.isNullOrEmpty() && url.startsWith("ref:", ignoreCase = true)) {
            url = try {
                SuperIslandImageStore.resolve(null, url) ?: url
            } catch (_: Exception) {
                url
            }
        }
        return url
    }
    
    return null
}