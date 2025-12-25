package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.xzyht.notifyrelay.core.util.ImageLoader
import com.xzyht.notifyrelay.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

fun parseColor(colorString: String?): Int? {
    return try {
        colorString?.let { Color.parseColor(it) }
    } catch (e: Exception) {
        null
    }
}

suspend fun downloadBitmap(context: Context, url: String, timeoutMs: Int): Bitmap? {
    return try {
        val resolved = SuperIslandImageStore.resolve(context, url) ?: url
        ImageLoader.loadBitmapSuspend(context, resolved, timeoutMs)
    } catch (e: Exception) {
        Logger.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
        null
    }
}

fun unescapeHtml(input: String): String {
    return input
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
        .replace("\\u0027", "'")
        .replace("\\u0022", "\"")
        .replace("\\u0026", "&")
}