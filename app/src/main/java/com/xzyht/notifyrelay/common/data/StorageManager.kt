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

    // 便捷方法：获取字符串（带数据验证）
    fun getString(context: Context, key: String, default: String = "", prefsType: PrefsType = PrefsType.GENERAL): String {
        return try {
            getPrefs(context, prefsType).getString(key, default) ?: default
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to get string for key: $key", e)
            default
        }
    }

    // 便捷方法：设置字符串
    fun putString(context: Context, key: String, value: String, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putString(key, value).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to put string for key: $key", e)
        }
    }

    // 便捷方法：获取布尔值（带数据验证）
    fun getBoolean(context: Context, key: String, default: Boolean = false, prefsType: PrefsType = PrefsType.GENERAL): Boolean {
        return try {
            getPrefs(context, prefsType).getBoolean(key, default)
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to get boolean for key: $key", e)
            default
        }
    }

    // 便捷方法：设置布尔值（支持批量操作）
    fun putBoolean(context: Context, key: String, value: Boolean, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to put boolean for key: $key", e)
        }
    }

    // 批量设置布尔值
    fun putBooleans(context: Context, values: Map<String, Boolean>, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().apply {
            values.forEach { (key, value) -> putBoolean(key, value) }
        }.apply()
    }

    // 便捷方法：获取整数
    fun getInt(context: Context, key: String, default: Int = 0, prefsType: PrefsType = PrefsType.GENERAL): Int {
        return try {
            getPrefs(context, prefsType).getInt(key, default)
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to get int for key: $key", e)
            default
        }
    }

    // 便捷方法：设置整数
    fun putInt(context: Context, key: String, value: Int, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putInt(key, value).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to put int for key: $key", e)
        }
    }

    // 便捷方法：获取长整数
    fun getLong(context: Context, key: String, default: Long = 0L, prefsType: PrefsType = PrefsType.GENERAL): Long {
        return try {
            getPrefs(context, prefsType).getLong(key, default)
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to get long for key: $key", e)
            default
        }
    }

    // 便捷方法：设置长整数
    fun putLong(context: Context, key: String, value: Long, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putLong(key, value).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to put long for key: $key", e)
        }
    }

    // 批量设置字符串
    fun putStrings(context: Context, values: Map<String, String>, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    // 批量设置整数
    fun putInts(context: Context, values: Map<String, Int>, prefsType: PrefsType = PrefsType.GENERAL) {
        getPrefs(context, prefsType).edit().apply {
            values.forEach { (key, value) -> putInt(key, value) }
        }.apply()
    }

    // 便捷方法：获取字符串集合
    fun getStringSet(context: Context, key: String, default: Set<String> = emptySet(), prefsType: PrefsType = PrefsType.GENERAL): Set<String> {
        return try {
            getPrefs(context, prefsType).getStringSet(key, default) ?: default
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to get string set for key: $key", e)
            default
        }
    }

    // 便捷方法：设置字符串集合
    fun putStringSet(context: Context, key: String, value: Set<String>, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putStringSet(key, value).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to put string set for key: $key", e)
        }
    }

    // 移除键
    fun remove(context: Context, key: String, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().remove(key).apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to remove key: $key", e)
        }
    }

    // 清空
    fun clear(context: Context, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.w("StorageManager", "Failed to clear preferences", e)
        }
    }

    private fun getPrefs(context: Context, prefsType: PrefsType): SharedPreferences {
        return when (prefsType) {
            PrefsType.GENERAL -> getGeneralPrefs(context)
            PrefsType.DEVICE -> getDevicePrefs(context)
            PrefsType.FILTER -> getFilterPrefs(context)
        }
    }

    // 数据迁移支持
    fun migrateData(context: Context, fromVersion: Int, toVersion: Int) {
        try {
            val prefs = getGeneralPrefs(context)
            val currentVersion = prefs.getInt("data_version", 0)

            if (currentVersion < toVersion) {
                // 执行数据迁移逻辑
                android.util.Log.i("StorageManager", "Migrating data from v$currentVersion to v$toVersion")

                // 示例迁移：重命名键
                if (currentVersion < 1) {
                    // 迁移逻辑示例
                    val oldValue = getString(context, "old_key")
                    if (oldValue.isNotEmpty()) {
                        putString(context, "new_key", oldValue)
                        remove(context, "old_key")
                    }
                }

                // 更新版本号
                prefs.edit().putInt("data_version", toVersion).apply()
                android.util.Log.i("StorageManager", "Data migration completed")
            }
        } catch (e: Exception) {
            android.util.Log.e("StorageManager", "Data migration failed", e)
        }
    }

    enum class PrefsType {
        GENERAL,
        DEVICE,
        FILTER
    }
}
