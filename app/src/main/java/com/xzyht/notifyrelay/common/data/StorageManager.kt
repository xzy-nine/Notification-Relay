package com.xzyht.notifyrelay.common.data

import android.content.Context
import android.content.SharedPreferences
import com.xzyht.notifyrelay.BuildConfig
import android.util.Log

/**
 * StorageManager 是一个用于统一管理应用 SharedPreferences 的工具单例。
 *
 * 它将偏好设置按用途分为三类：通用（GENERAL）、设备（DEVICE）和过滤器（FILTER），
 * 并提供了一系列便捷的读写方法（支持字符串、布尔、整数、长整数、字符串集合以及批量写入）。
 * 所有方法内部均包含异常保护，避免因异常导致应用崩溃。
 */
object StorageManager {

    /**
     * 通用偏好设置名称，存储应用范围内的通用配置。
     */
    private const val PREFS_GENERAL = "notifyrelay_prefs"

    /**
     * 设备相关的偏好设置名称，存储与设备有关的配置或状态。
     */
    private const val PREFS_DEVICE = "notifyrelay_device_prefs"

    /**
     * 过滤器相关的偏好设置名称，存储通知/消息过滤器相关配置。
     */
    private const val PREFS_FILTER = "notifyrelay_filter_prefs"

    /**
     * 获取通用偏好设置对象。
     *
     * @param context 任意 Context 实例（通常传入 ApplicationContext 更安全）。
     * @return 对应名称的 [SharedPreferences] 实例，模式为 [Context.MODE_PRIVATE]。
     */
    fun getGeneralPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
    }

    /**
     * 获取设备偏好设置对象。
     *
     * @param context 任意 Context 实例（通常传入 ApplicationContext 更安全）。
     * @return 对应名称的 [SharedPreferences] 实例，模式为 [Context.MODE_PRIVATE]。
     */
    fun getDevicePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
    }

    /**
     * 获取过滤器偏好设置对象。
     *
     * @param context 任意 Context 实例（通常传入 ApplicationContext 更安全）。
     * @return 对应名称的 [SharedPreferences] 实例，模式为 [Context.MODE_PRIVATE]。
     */
    fun getFilterPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILTER, Context.MODE_PRIVATE)
    }

    /**
     * 从偏好设置中读取字符串值，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param default 值不存在时返回的默认字符串，默认空字符串。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     * @return 存储的字符串，若出现异常或值为 null 则返回 [default]。
     */
    fun getString(context: Context, key: String, default: String = "", prefsType: PrefsType = PrefsType.GENERAL): String {
        return try {
            getPrefs(context, prefsType).getString(key, default) ?: default
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "获取字符串失败，键: $key", e)
            default
        }
    }

    /**
     * 将字符串写入偏好设置，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param value 要写入的字符串值。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putString(context: Context, key: String, value: String, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putString(key, value).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "写入字符串失败，键: $key", e)
        }
    }

    /**
     * 从偏好设置中读取布尔值，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param default 键不存在时返回的默认值，默认为 false。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     * @return 存储的布尔值，若出现异常则返回 [default]。
     */
    fun getBoolean(context: Context, key: String, default: Boolean = false, prefsType: PrefsType = PrefsType.GENERAL): Boolean {
        return try {
            getPrefs(context, prefsType).getBoolean(key, default)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "获取布尔值失败，键: $key", e)
            default
        }
    }

    /**
     * 将布尔值写入偏好设置，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param value 要写入的布尔值。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putBoolean(context: Context, key: String, value: Boolean, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "写入布尔值失败，键: $key", e)
        }
    }

    /**
     * 批量写入布尔值（原子性由 SharedPreferences.Editor 管理，使用 apply 异步提交）。
     *
     * @param context 任意 Context 实例。
     * @param values 要写入的键值对集合。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putBooleans(context: Context, values: Map<String, Boolean>, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().apply {
                values.forEach { (key, value) -> putBoolean(key, value) }
            }.apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "批量写入布尔值失败", e)
        }
    }

    /**
     * 从偏好设置中读取整数，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param default 键不存在时返回的默认值，默认为 0。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     * @return 存储的整数，若出现异常则返回 [default]。
     */
    fun getInt(context: Context, key: String, default: Int = 0, prefsType: PrefsType = PrefsType.GENERAL): Int {
        return try {
            getPrefs(context, prefsType).getInt(key, default)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "获取整数失败，键: $key", e)
            default
        }
    }

    /**
     * 将整数写入偏好设置，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param value 要写入的整数值。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putInt(context: Context, key: String, value: Int, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putInt(key, value).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "写入整数失败，键: $key", e)
        }
    }

    /**
     * 从偏好设置中读取长整数（Long），带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param default 键不存在时返回的默认值，默认为 0L。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     * @return 存储的长整数，若出现异常则返回 [default]。
     */
    fun getLong(context: Context, key: String, default: Long = 0L, prefsType: PrefsType = PrefsType.GENERAL): Long {
        return try {
            getPrefs(context, prefsType).getLong(key, default)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "获取长整数失败，键: $key", e)
            default
        }
    }

    /**
     * 将长整数（Long）写入偏好设置，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param value 要写入的长整数值。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putLong(context: Context, key: String, value: Long, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putLong(key, value).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "写入长整数失败，键: $key", e)
        }
    }

    /**
     * 批量写入字符串键值对。
     *
     * @param context 任意 Context 实例。
     * @param values 要写入的字符串键值对集合。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putStrings(context: Context, values: Map<String, String>, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().apply {
                values.forEach { (key, value) -> putString(key, value) }
            }.apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "批量写入字符串失败", e)
        }
    }

    /**
     * 批量写入整数键值对。
     *
     * @param context 任意 Context 实例。
     * @param values 要写入的整数键值对集合。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putInts(context: Context, values: Map<String, Int>, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().apply {
                values.forEach { (key, value) -> putInt(key, value) }
            }.apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "批量写入整数失败", e)
        }
    }

    /**
     * 从偏好设置中读取字符串集合，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param default 键不存在时返回的默认集合，默认为空集合。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     * @return 存储的字符串集合，若出现异常或为 null 则返回 [default]。
     */
    fun getStringSet(context: Context, key: String, default: Set<String> = emptySet(), prefsType: PrefsType = PrefsType.GENERAL): Set<String> {
        return try {
            getPrefs(context, prefsType).getStringSet(key, default) ?: default
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "获取字符串集合失败，键: $key", e)
            default
        }
    }

    /**
     * 将字符串集合写入偏好设置，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 键名。
     * @param value 要写入的字符串集合。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun putStringSet(context: Context, key: String, value: Set<String>, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().putStringSet(key, value).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "写入字符串集合失败，键: $key", e)
        }
    }

    /**
     * 从偏好设置中移除指定键，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param key 要移除的键名。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun remove(context: Context, key: String, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().remove(key).apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "移除键失败: $key", e)
        }
    }

    /**
     * 清空指定偏好集合中的所有键值对，带异常保护。
     *
     * @param context 任意 Context 实例。
     * @param prefsType 指定使用哪个偏好集合，默认 [PrefsType.GENERAL]。
     */
    fun clear(context: Context, prefsType: PrefsType = PrefsType.GENERAL) {
        try {
            getPrefs(context, prefsType).edit().clear().apply()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("StorageManager", "清空偏好失败", e)
        }
    }

    /**
     * 根据 [PrefsType] 返回对应的 [SharedPreferences] 实例。
     *
     * @param context 任意 Context 实例。
     * @param prefsType 偏好集合类型，决定返回哪一个 [SharedPreferences]。
     * @return 对应的 [SharedPreferences] 实例。
     */
    private fun getPrefs(context: Context, prefsType: PrefsType): SharedPreferences {
        return when (prefsType) {
            PrefsType.GENERAL -> getGeneralPrefs(context)
            PrefsType.DEVICE -> getDevicePrefs(context)
            PrefsType.FILTER -> getFilterPrefs(context)
        }
    }

    /**
     * 执行偏好数据的迁移操作。此方法会读取当前存储的版本号，若低于目标版本则按需执行迁移逻辑并更新版本号。
     * 该方法内部包含异常保护，迁移失败会写入错误日志，但不抛出异常。
     *
     * 示例：当当前版本 < 1 时，会把键 "old_key" 的值迁移到 "new_key" 并移除旧键。
     *
     * @param context 任意 Context 实例。
     * @param fromVersion 当前代码的起始版本（通常用于决定执行哪些迁移步骤）。
     * @param toVersion 目标版本号，迁移完成后会写入到偏好中作为当前版本。
     */
    @Suppress("UNUSED_PARAMETER")
    fun migrateData(context: Context, fromVersion: Int, toVersion: Int) {
        try {
            // 使用 fromVersion 参数以避免编译时的未使用参数警告，并记录调用意图
            val _from = fromVersion // 显式读取参数，确保不会被诊断为未使用
            if (BuildConfig.DEBUG) Log.d("StorageManager", "migrateData 被调用: fromVersion=${_from}, toVersion=$toVersion")
            val prefs = getGeneralPrefs(context)
            val currentVersion = prefs.getInt("data_version", 0)

            if (currentVersion < toVersion) {
                // 执行数据迁移逻辑
                if (BuildConfig.DEBUG) Log.i("StorageManager", "正在迁移数据: 从 v$currentVersion 到 v$toVersion")

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
                if (BuildConfig.DEBUG) Log.i("StorageManager", "数据迁移完成")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("StorageManager", "数据迁移失败", e)
        }
    }

    /**
     * 偏好集合类型枚举：用于区分不同用途的 SharedPreferences。
     */
    enum class PrefsType {
        /** 通用偏好（应用范围） */
        GENERAL,

        /** 设备相关偏好 */
        DEVICE,

        /** 过滤器相关偏好 */
        FILTER
    }
}
