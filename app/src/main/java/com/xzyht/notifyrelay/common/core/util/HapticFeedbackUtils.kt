package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticFeedbackUtils {
    /**
     * 是否支持手机震动的判断。
     *
     * @param context 上下文
     * @return true 表示当前设备/用户设置允许振动
     */
    fun isVibrationSupported(context: Context): Boolean {
        return try {
            val vibrator = obtainVibrator(context) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported() // 基本保证存在可用模式
            } else {
                vibrator.hasVibrator()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 为轻量触觉反馈提供统一入口，当前用于超级岛关闭指示器等场景。
     *
     * @param context 上下文
     * @param durationMs 振动时长，单位毫秒
     */
    fun performLightHaptic(context: Context, durationMs: Long = 30L) {
        try {
            val vibrator = obtainVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 使用指定模式进行震动。
     *
     * @param context 上下文
     * @param pattern 振动模式：交替的静止/振动时长（毫秒），例如 [0, 1000, 500, 2000]
     * @param repeat 重复索引：-1 表示不重复，0 表示从第一个元素开始重复
     */
    fun performPatternHaptic(context: Context, pattern: LongArray, repeat: Int = -1) {
        if (pattern.isEmpty()) return
        try {
            val vibrator = obtainVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // VibrationEffect 不直接支持 repeat 索引，但可通过外层逻辑控制；
                // 这里优先保证一次性模式，保持简单安全。
                val effect = VibrationEffect.createWaveform(pattern, repeat)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, repeat)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 统一获取 Vibrator，兼容 Android 12+ 的 VibratorManager。
     */
    private fun obtainVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }
}
