package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import coil.ImageLoader as CoilImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore

/**
 * 统一图片加载器：同时支持 data: URL 与 http(s) URL。
 * - 无外部依赖，使用轻量线程池与主线程切回。
 * - 用于展开态与摘要态中组件级图标加载，避免在渲染器中写网络细节。
 */
object ImageLoader {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 根据 key 从 picMap 取 URL 并加载到 ImageView。返回是否已启动加载。
     */
    fun loadKeyInto(view: ImageView, picMap: Map<String, String>?, key: String?, timeoutMs: Int = 5000): Boolean {
        val url = if (!key.isNullOrBlank()) picMap?.get(key) else null
        if (url.isNullOrBlank()) return false
        // 若为引用则先解析为真实值
        val resolved = try {
            if (url.startsWith("ref:", ignoreCase = true)) SuperIslandImageStore.resolve(view.context, url) ?: url else url
        } catch (_: Exception) { url }
        loadInto(view, resolved, timeoutMs)
        return true
    }

    /**
     * 将 urlOrData（data: 或 http(s)）加载到 ImageView。
     */
    fun loadInto(view: ImageView, urlOrData: String, timeoutMs: Int = 5000) {
        val targetRef = WeakReference(view)
        io.execute {
            // 若为引用则先解析为真实值
            val finalUrl = try {
                if (urlOrData.startsWith("ref:", ignoreCase = true)) SuperIslandImageStore.resolve(view.context.applicationContext, urlOrData) ?: urlOrData else urlOrData
            } catch (_: Exception) { urlOrData }
            val bmp: Bitmap? = try { loadBitmap(finalUrl, timeoutMs) } catch (_: Exception) { null }

            val target = targetRef.get() ?: return@execute
            if (bmp != null) {
                main.post { target.setImageBitmap(bmp) }
            }
        }
    }

    /**
     * 同步加载并返回 Bitmap（后台线程使用）。
     */
    fun loadBitmap(urlOrData: String, timeoutMs: Int = 5000): Bitmap? {
        // 已弃用的同步路径 —— 建议使用带上下文的 suspend 加载器
        return if (urlOrData.startsWith("data:", ignoreCase = true)) {
            DataUrlUtils.decodeDataUrlToBitmap(urlOrData)
        } else {
            loadHttpBitmap(urlOrData, timeoutMs)
        }
    }

    private fun loadHttpBitmap(url: String, timeoutMs: Int): Bitmap? {
        // 保留原有实现，作为同步调用方的兜底方案
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            if (conn.responseCode != 200) {
                conn.disconnect()
                null
            } else {
                val stream = conn.inputStream
                val bmp = BitmapFactory.decodeStream(stream)
                try { stream.close() } catch (_: Exception) {}
                conn.disconnect()
                bmp
            }
        } catch (_: Exception) { null }
    }

    /**
    *暂停使用 Coil 进行 HTTP/URI 加载的加载器，并将 data: 协议 URI 委托给 DataUrlUtils 处理。
     */
    suspend fun loadBitmapSuspend(context: Context, urlOrData: String, timeoutMs: Int = 5000): Bitmap? {
        return try {
            if (urlOrData.startsWith("data:", ignoreCase = true)) {
                DataUrlUtils.decodeDataUrlToBitmap(urlOrData)
            } else {
                val loader = CoilImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(urlOrData)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is android.graphics.drawable.BitmapDrawable) return drawable.bitmap
                    // 将 drawable 转换为 bitmap
                    val bmp = DataUrlUtils.drawableToBitmap(drawable)
                    bmp
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
