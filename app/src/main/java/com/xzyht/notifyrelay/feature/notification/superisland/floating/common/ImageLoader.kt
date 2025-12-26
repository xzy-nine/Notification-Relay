package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader as CoilImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

/**
 * 统一图片加载器：同时支持 data: URL 与 http(s) URL。
 * 用于展开态与摘要态中组件级图标加载，避免在渲染器中写网络细节。
 */
object ImageLoader {
    /**
     * 使用 Coil 进行 HTTP/URI 加载，并将 data: 协议 URI 委托给 DataUrlUtils 处理。
     */
    suspend fun loadBitmapSuspend(context: Context, urlOrData: String, timeoutMs: Int = 5000): Bitmap? {
        return try {
            // 若为引用则先解析为真实值
            val finalUrl = if (urlOrData.startsWith("ref:", ignoreCase = true)) {
                SuperIslandImageStore.resolve(context.applicationContext, urlOrData) ?: urlOrData
            } else {
                urlOrData
            }
            
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
}