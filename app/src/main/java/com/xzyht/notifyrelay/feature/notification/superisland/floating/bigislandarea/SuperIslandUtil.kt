package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.ImageLoader
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandImageStore

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
        if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
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