package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// 复用工具函数

fun parseColor(colorString: String?): Int? {
    return try {
        colorString?.let { android.graphics.Color.parseColor(it) }
    } catch (e: Exception) {
        null
    }
}

suspend fun downloadBitmap(url: String, timeoutMs: Int): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            // 支持 data URI（base64）、以及常规 http/https URL
            if (url.startsWith("data:", ignoreCase = true)) {
                // delegate data URI decoding to DataUrlUtils (handles whitespace/newlines)
                return@withContext DataUrlUtils.decodeDataUrlToBitmap(url)
            }

            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            if (conn.responseCode != 200) return@withContext null
            val stream = conn.inputStream
            val bmp = BitmapFactory.decodeStream(stream)
            try { stream.close() } catch (_: Exception) {}
            conn.disconnect()
            return@withContext bmp
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("超级岛", "超级岛: 下载图片失败: ${e.message}")
            return@withContext null
        }
    }
}