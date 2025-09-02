package com.xzyht.notifyrelay.common.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 存储管理器，负责 SharedPreferences 的统一管理
 */
object StorageManager {

    private const val PREFS_GENERAL = "notifyrelay_prefs"
    private const val PREFS_DEVICE = "notifyrelay_device_prefs"
    private const val PREFS_FILTER = "notifyrelay_filter_prefs"

    // 通用设置
    fun getGeneralPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
    }

    // 设备设置
    fun getDevicePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
    }

    // 过滤器设置
    fun getFilterPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILTER, Context.MODE_PRIVATE)
    }

    // 便捷方法：获取字符串
    fun getString(context: Context, key: String, default: String = "", prefsType: PrefsType = PrefsType.GENERAL): String {
        return getPrefs(context, prefsType).getString(key, default) ?: default
    }

    // 便捷方法：设置字符串
    fun putString(context: Context, key: String, value: String, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().putString(key, value).apply()
    }

    // 便捷方法：获取布尔值
    fun getBoolean(context: Context, key: String, default: Boolean = false, prefsType: PrefsType = PrefsType.GENERAL): Boolean {
        return getPrefs(context, prefsType).getBoolean(key, default)
    }

    // 便捷方法：设置布尔值
    fun putBoolean(context: Context, key: String, value: Boolean, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().putBoolean(key, value).apply()
    }

    // 便捷方法：获取整数
    fun getInt(context: Context, key: String, default: Int = 0, prefsType: PrefsType = PrefsType.GENERAL): Int {
        return getPrefs(context, prefsType).getInt(key, default)
    }

    // 便捷方法：设置整数
    fun putInt(context: Context, key: String, value: Int, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().putInt(key, value).apply()
    }

    // 便捷方法：获取长整数
    fun getLong(context: Context, key: String, default: Long = 0L, prefsType: PrefsType = PrefsType.GENERAL): Long {
        return getPrefs(context, prefsType).getLong(key, default)
    }

    // 便捷方法：设置长整数
    fun putLong(context: Context, key: String, value: Long, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().putLong(key, value).apply()
    }

    // 便捷方法：获取字符串集合
    fun getStringSet(context: Context, key: String, default: Set<String> = emptySet(), prefsType: PrefsType = PrefsType.GENERAL): Set<String> {
        return getPrefs(context, prefsType).getStringSet(key, default) ?: default
    }

    // 便捷方法：设置字符串集合
    fun putStringSet(context: Context, key: String, value: Set<String>, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().putStringSet(key, value).apply()
    }

    // 移除键
    fun remove(context: Context, key: String, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().remove(key).apply()
    }

    // 清空
    fun clear(context: Context, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().clear().apply()
    }

    private fun getPrefs(context: Context, prefsType: PrefsType): SharedPreferences {
        return when (prefsType) {
            PrefsType.GENERAL -> getGeneralPrefs(context)
            PrefsType.DEVICE -> getDevicePrefs(context)
            PrefsType.FILTER -> getFilterPrefs(context)
        }
    }

    enum class PrefsType {
        GENERAL, DEVICE, FILTER
    }
}
