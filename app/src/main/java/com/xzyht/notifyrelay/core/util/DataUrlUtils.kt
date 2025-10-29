package com.xzyht.notifyrelay.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

object DataUrlUtils {
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
            if (comma <= 0) return null
            val meta = candidate.substring(5, comma)
            var rawData = candidate.substring(comma + 1)
            // Strip whitespace and any characters not valid in base64
            rawData = rawData.replace(Regex("[^A-Za-z0-9+/=]"), "")
            if (meta.contains("base64", ignoreCase = true)) {
                // 提供更多容错与调试信息：记录 base64 长度、尝试多种解码 flag，并在必要时补齐 padding
                try {
                    if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                        try { android.util.Log.d("NotifyRelay", "base64: decodeDataUrlToBitmap meta=$meta rawDataLen=${rawData.length}") } catch (_: Exception) {}
                    }


                    var cleaned = rawData
                    // 先尝试将常见的 \uXXXX unicode 转义还原（例如有人把 '=' 或其他字符以 \u003d 形式传输）
                    try { cleaned = unescapeUnicode(cleaned) } catch (_: Exception) {}

                    // remove any chars not valid in base64 (keep only base64 chars and '=' for now)
                    cleaned = cleaned.replace(Regex("[^A-Za-z0-9+/=]"), "")

                    // Normalize padding: remove all '=' then re-pad correctly (prevents too many '=' or '=' in the middle)
                    try {
                        val withoutEq = cleaned.replace("=", "")
                        val pad = (4 - withoutEq.length % 4) % 4
                        cleaned = withoutEq + "=".repeat(pad)
                    } catch (_: Exception) {}

                    // If length not multiple of 4, pad with '='
                    val pad = (4 - cleaned.length % 4) % 4
                    if (pad > 0) cleaned += "=".repeat(pad)

                    val decodeFlags = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.NO_PADDING, Base64.URL_SAFE)
                    var lastErr: Exception? = null
                    var bytes: ByteArray? = null
                    for (flag in decodeFlags) {
                        try {
                            bytes = Base64.decode(cleaned, flag)
                            if (bytes != null) {
                                if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                    try { android.util.Log.d("NotifyRelay", "base64: decode success flag=$flag bytes=${bytes.size}") } catch (_: Exception) {}
                                }
                                break
                            }
                        } catch (e: Exception) {
                            lastErr = e
                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                try { android.util.Log.d("NotifyRelay", "base64: decode failed flag=$flag", e) } catch (_: Exception) {}
                            }
                        }
                    }

                    if (bytes == null) {
                        // as ultimate fallback, try Java URLDecoder then re-decode
                        try {
                            val urlDecoded = java.net.URLDecoder.decode(rawData, "UTF-8").replace(Regex("[^A-Za-z0-9+/=]"), "")
                            val pad2 = (4 - urlDecoded.length % 4) % 4
                            val cleaned2 = if (pad2 > 0) urlDecoded + "=".repeat(pad2) else urlDecoded
                            bytes = Base64.decode(cleaned2, Base64.DEFAULT)
                        } catch (e: Exception) {
                            lastErr = e
                        }
                    }

                    if (bytes == null) {
                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                            try {
                                val head = if (cleaned.length > 200) cleaned.substring(0, 200) else cleaned
                                val tail = if (cleaned.length > 200) cleaned.substring(cleaned.length - 200) else ""
                                android.util.Log.d("NotifyRelay", "base64: all base64 decode attempts failed; lastErr=${lastErr?.message} cleanedLen=${cleaned.length} cleanedHead=$head cleanedTail=$tail")
                            } catch (_: Exception) {}
                        }
                        return null
                    }

                    // Quick header check for PNG/JPEG to help debugging
                    try {
                        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.d("NotifyRelay", "base64: bytes look like PNG header; len=${bytes.size}")
                        }
                    } catch (_: Exception) {}

                    // First try a straightforward decode
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) return bmp

                    // Extra fallback: try decode via InputStream (some platforms handle streams differently)
                    try {
                        val `is` = java.io.ByteArrayInputStream(bytes)
                        val optsStream = BitmapFactory.Options()
                        optsStream.inPreferredConfig = Bitmap.Config.ARGB_8888
                        bmp = BitmapFactory.decodeStream(`is`, null, optsStream)
                        if (bmp != null) return bmp
                    } catch (_: Exception) {}

                    // Another fallback: try a more memory-efficient config (RGB_565) which sometimes helps decode
                    try {
                        val optsRGB = BitmapFactory.Options()
                        optsRGB.inPreferredConfig = Bitmap.Config.RGB_565
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optsRGB)
                        if (bmp != null) return bmp
                    } catch (_: Exception) {}

                    // If that failed, attempt a sampled decode to avoid OOMs and improve chances
                    try {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                        // If bounds couldn't be read, still try a small sample decode as last resort
                        val reqMax = 256
                        var inSampleSize = 1
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            val halfW = opts.outWidth / 2
                            val halfH = opts.outHeight / 2
                            while (halfW / inSampleSize >= reqMax || halfH / inSampleSize >= reqMax) {
                                inSampleSize *= 2
                            }
                        } else {
                            // unknown bounds: try small sample to increase chance
                            inSampleSize = 4
                        }

                        val decodeOpts = BitmapFactory.Options()
                        decodeOpts.inSampleSize = inSampleSize
                        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                        return bmp
                    } catch (e: Exception) {
                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.d("NotifyRelay", "base64: sampled decode failed", e)
                        return null
                    }
                } catch (e: Exception) {
                    if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.d("NotifyRelay", "base64: decodeDataUrlToBitmap top-level failure", e)
                    return null
                }
            }
        } catch (_: Exception) {}
        return null
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
