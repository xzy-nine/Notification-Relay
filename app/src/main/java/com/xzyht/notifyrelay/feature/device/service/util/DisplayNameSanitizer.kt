package com.xzyht.notifyrelay.feature.device.service.util

import android.util.Base64

/**
 * 设备显示名称的清洗与解码。
 *
 * 网络传输的名称为 Base64 编码的 UTF-8 字节，解码后需清洗不可见字符并按字节裁剪，
 * 避免超长或含控制字符的名称进入 UI 与持久化。
 */
object DisplayNameSanitizer {
    private const val MAX_UTF8_BYTES = 64
    private const val EMPTY_FALLBACK = "错误空" // 解码结果为空时的兜底值，便于排查故障点

    /** 将显示名称清洗为不可见字符替换、并裁剪（口径较宽） */
    fun sanitize(raw: String): String {
        try {
            var s = raw.replace(Regex("[\\r\\n]"), " ")
            s = s.trim()
            if (s.isEmpty()) return s
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_UTF8_BYTES) return s
            var cut = MAX_UTF8_BYTES
            while (cut > 0 && (bytes[cut - 1].toInt() and 0xC0) == 0x80) cut--
            return String(bytes.copyOfRange(0, cut), Charsets.UTF_8)
        } catch (_: Exception) {
            return raw
        }
    }

    /** 解码并清洗从网络接收到的名称 */
    fun decodeFromTransport(encoded: String): String {
        try {
            if (encoded.isEmpty()) {
                // 处理空字符串情况，返回默认设备名称"错误空"以便排除故障点
                return EMPTY_FALLBACK
            }
            val decoded =
                try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (_: Exception) {
                    null
                }
            if (decoded != null) {
                // 确保解码后的名称不为空，使用默认值"错误空"兜底以便排除故障点
                return sanitize(String(decoded, Charsets.UTF_8)).ifEmpty { EMPTY_FALLBACK }
            }
        } catch (_: Exception) {
        }
        // 如果解码失败，尝试直接使用原字符串，确保不为空
        return sanitize(encoded).ifEmpty { EMPTY_FALLBACK }
    }
}
