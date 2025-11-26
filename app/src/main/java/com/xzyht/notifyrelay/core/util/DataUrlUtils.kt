package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream

object DataUrlUtils {
    private const val TAG = "DataUrlUtils"
    // Older regex-based approach could miss data URIs that are JSON-escaped (e.g. "data:image\/png;base64,....")
    // or that have been wrapped/line-broken. Use a tolerant scanner that finds "data:" and extracts a likely
    // candidate until a reasonable terminator (quote, whitespace, brace) — then sanitize before decoding.
    private const val DATA_PREFIX = "data:"

    /**
     * Find all data URLs in the given text (tolerant to JSON-escaped slashes and line breaks).
     */
    fun findDataUrls(text: String): List<String> {
        val results = mutableListOf<String>()
        var start = text.indexOf(DATA_PREFIX, 0, ignoreCase = true)
        while (start >= 0) {
            val end = findDataEndCandidate(text, start)
            results.add(text.substring(start, end))
            start = text.indexOf(DATA_PREFIX, end, ignoreCase = true)
        }
        return results
    }

    /**
     * Split the text into parts where each part is either a plain text or a data URL.
     * Returns a list of pairs (textPart, dataUrl) where only one of the two is non-null.
     */
    fun splitByDataUrls(text: String): List<Pair<String?, String?>> {
        val parts = mutableListOf<Pair<String?, String?>>()
        var lastIndex = 0
        var start = text.indexOf(DATA_PREFIX, 0, ignoreCase = true)
        while (start >= 0) {
            if (start > lastIndex) parts.add(Pair(text.substring(lastIndex, start), null))
            val end = findDataEndCandidate(text, start)
            parts.add(Pair(null, text.substring(start, end)))
            lastIndex = end
            start = text.indexOf(DATA_PREFIX, lastIndex, ignoreCase = true)
        }
        if (lastIndex < text.length) parts.add(Pair(text.substring(lastIndex), null))
        return parts
    }

    // Helper: from an index pointing at a 'd' of "data:", find a reasonable end index for the data URI
    private fun findDataEndCandidate(text: String, startIndex: Int): Int {
        // Prefer to stop at the next unescaped double-quote or single-quote (common when data URI is inside JSON)
        var i = startIndex
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                // if the quote occurs before startIndex (shouldn't) skip; otherwise stop at quote
                if (i > startIndex) return i
            }
            // if we hit a closing brace/comma/whitespace and we've already passed the comma after the media type,
            // we assume the data URI has ended
            if ((c == '}' || c == ']' || c == ',' || c.isWhitespace()) && i > startIndex) {
                // ensure we passed a comma that separates meta from payload
                val commaAfter = text.indexOf(',', startIndex)
                if (commaAfter in (startIndex + 1) until i) return i
            }
            i++
        }
        return len
    }

    /**
     * Decode a base64 data URI (image) to a Bitmap. Returns null on failure.
     */
    fun decodeDataUrlToBitmap(dataUrl: String): Bitmap? {
        try {
            // Be tolerant: dataUrl may be JSON-escaped ("data:image\/png;base64,...")
            // or contain line breaks/extra characters. Clean up before decoding.
            var candidate = dataUrl.trim().removeSurrounding("\"")
            try { candidate = candidate.replace("\\/", "/") } catch (_: Exception) {}
            try { candidate = candidate.replace("\\\\", "") } catch (_: Exception) {}

            val comma = candidate.indexOf(',')
            if (comma <= 0) {
                if (BuildConfig.DEBUG) Log.w(TAG, "不是有效的 data URL：未找到分隔符 ','，候选长度=${candidate.length}")
                return null
            }
            val meta = candidate.substring(5, comma)
            var rawData = candidate.substring(comma + 1)
            // Strip whitespace
            rawData = rawData.replace(Regex("\\s+"), "")

            if (meta.contains("base64", ignoreCase = true)) {
                // Use the existing robust base64 cleaning/decoding, then decode bytes to Bitmap
                try {
                    var cleaned = rawData
                    try { cleaned = unescapeUnicode(cleaned) } catch (_: Exception) {}
                    cleaned = cleaned.replace(Regex("[^A-Za-z0-9+/=]"), "")

                    // Normalize padding
                    try {
                        val withoutEq = cleaned.replace("=", "")
                        val pad = (4 - withoutEq.length % 4) % 4
                        cleaned = withoutEq + "=".repeat(pad)
                    } catch (_: Exception) {}

                    if (BuildConfig.DEBUG) {
                        val preview = if (cleaned.length > 64) cleaned.substring(0, 64) + "..." else cleaned
                        Log.d(TAG, "尝试解码 base64，meta=$meta, cleanedLen=${cleaned.length}, preview=$preview")
                    }
                    val bytes = tryDecodeBase64Variants(cleaned)
                    if (bytes == null) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "base64 解码失败：无法解出有效字节数组 (meta=$meta)")
                        return null
                    }

                    // Prefer BitmapFactory for bytes, but let Coil handle advanced config elsewhere
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) return ensureCpuBitmap(bmp)

                    // Fallbacks similar to previous implementation
                    try {
                        val `is` = java.io.ByteArrayInputStream(bytes)
                        val optsStream = BitmapFactory.Options()
                        optsStream.inPreferredConfig = Bitmap.Config.ARGB_8888
                        bmp = BitmapFactory.decodeStream(`is`, null, optsStream)
                        if (bmp != null) return ensureCpuBitmap(bmp)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "通过 InputStream 解码 Bitmap 失败：${e.message}", e)
                    }

                    try {
                        val optsRGB = BitmapFactory.Options()
                        optsRGB.inPreferredConfig = Bitmap.Config.RGB_565
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optsRGB)
                        if (bmp != null) return ensureCpuBitmap(bmp)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "使用 RGB_565 解码失败：${e.message}", e)
                    }

                    // Last resort: sampled decode
                    try {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        val reqMax = 256
                        var inSampleSize = 1
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            val halfW = opts.outWidth / 2
                            val halfH = opts.outHeight / 2
                            while (halfW / inSampleSize >= reqMax || halfH / inSampleSize >= reqMax) {
                                inSampleSize *= 2
                            }
                        } else {
                            inSampleSize = 4
                        }
                        val decodeOpts = BitmapFactory.Options()
                        decodeOpts.inSampleSize = inSampleSize
                        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                        return if (bmp != null) ensureCpuBitmap(bmp) else null
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "采样解码（最后兜底）失败：${e.message}", e)
                        return null
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "处理 base64 数据时发生异常：${e.message}", e)
                    return null
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // Try to decode base64 using multiple flags and fallbacks
    private fun tryDecodeBase64Variants(cleaned: String): ByteArray? {
        val flags = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.NO_PADDING, Base64.URL_SAFE)
        var lastErr: Exception? = null
        for (f in flags) {
            try {
                val b = Base64.decode(cleaned, f)
                if (b.isNotEmpty()) return b
            } catch (e: Exception) { lastErr = e }
        }
        // try URLDecoder fallback
        return try {
            val urlDecoded = java.net.URLDecoder.decode(cleaned, "UTF-8").replace(Regex("[^A-Za-z0-9+/=]"), "")
            val pad = (4 - urlDecoded.length % 4) % 4
            val s2 = if (pad > 0) urlDecoded + "=".repeat(pad) else urlDecoded
            Base64.decode(s2, Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    /**
     * Use Coil to load a bitmap from common URIs (http(s), content://, file://).
     * Returns null on failure.
     */
    suspend fun loadBitmapWithCoil(context: Context, uri: Any): Bitmap? {
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val drawable = result.drawable
                if (drawable is BitmapDrawable) return drawable.bitmap
                // convert to bitmap
                return drawableToBitmap(drawable)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Ensure returned bitmap is CPU-accessible (not hardware-backed). If it's HARDWARE, return an ARGB_8888 copy.
    private fun ensureCpuBitmap(bmp: Bitmap): Bitmap {
        return try {
            if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
            } else bmp
        } catch (_: Exception) { bmp }
    }

    /**
     * Convert a Drawable to a Bitmap. Mirrors the helper used by SuperIslandManager.
     */
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    /**
     * Encode a Bitmap into a data URI (PNG, base64, NO_WRAP) — compatible with
     * how SuperIslandManager previously produced data URIs.
     */
    fun bitmapToDataUri(bitmap: Bitmap): String {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val b = baos.toByteArray()
            val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Quick check whether string looks like a data URI.
     */
    fun isDataUrl(text: String): Boolean = text.trim().startsWith(DATA_PREFIX, ignoreCase = true)

    // Lightweight unescape for \uXXXX sequences (hex). Converts e.g. "\u003d" -> '='
    private fun unescapeUnicode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i]
            if (c == '\\' && i + 5 < len && (s[i + 1] == 'u' || s[i + 1] == 'U')) {
                // try read 4 hex digits
                val hex = s.substring(i + 2, i + 6)
                try {
                    val code = hex.toInt(16)
                    sb.append(code.toChar())
                    i += 6
                    continue
                } catch (_: Exception) {
                    // fallthrough to append literal backslash
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
