package com.xzyht.notifyrelay.core.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.cache.IconCacheManager
import com.xzyht.notifyrelay.core.util.AppListHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用数据仓库
 * 封装应用列表的缓存、搜索过滤、数据更新逻辑
 */
object AppRepository {
    private const val TAG = "AppRepository"

    // 缓存的应用列表
    private var cachedApps: List<ApplicationInfo>? = null
    private var isLoaded = false

    // 缓存的应用图标 (包名 -> Bitmap)
    private var cachedIcons: MutableMap<String, Bitmap?> = mutableMapOf()
    private var iconsLoaded = false

    // 状态流
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _apps = MutableStateFlow<List<ApplicationInfo>>(emptyList())
    val apps: StateFlow<List<ApplicationInfo>> = _apps.asStateFlow()

    /**
     * 加载应用列表
     */
    suspend fun loadApps(context: Context) {
        if (isLoaded && cachedApps != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "使用缓存的应用列表")
            _apps.value = cachedApps!!
            return
        }

        _isLoading.value = true
        try {
            // 初始化图标缓存管理器
            IconCacheManager.init(context)

            if (BuildConfig.DEBUG) Log.d(TAG, "开始加载应用列表")
            val apps = AppListHelper.getInstalledApplications(context).sortedBy { appInfo ->
                try {
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "获取应用标签失败，使用包名: ${appInfo.packageName}", e)
                    appInfo.packageName
                }
            }

            cachedApps = apps
            isLoaded = true
            _apps.value = apps

            // 同时加载应用图标
            loadAppIcons(context, apps)

            if (BuildConfig.DEBUG) Log.d(TAG, "应用列表加载成功，共 ${apps.size} 个应用")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "应用列表加载失败", e)
            cachedApps = emptyList()
            isLoaded = true
            _apps.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 获取过滤后的应用列表
     */
    fun getFilteredApps(
        query: String,
        showSystemApps: Boolean,
        context: Context
    ): List<ApplicationInfo> {
        val allApps = _apps.value
        if (allApps.isEmpty()) return emptyList()

        // 区分用户应用和系统应用
        val userApps = allApps.filter { app ->
            (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }

        val displayApps = if (showSystemApps) allApps else userApps

        if (query.isBlank()) {
            return displayApps
        }

        // 搜索过滤
        return displayApps.filter { app ->
            try {
                val label = context.packageManager.getApplicationLabel(app).toString()
                val matchesLabel = label.contains(query, ignoreCase = true)
                val matchesPackage = app.packageName.contains(query, ignoreCase = true)
                matchesLabel || matchesPackage
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "搜索时获取应用标签失败: ${app.packageName}", e)
                app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedApps = null
        isLoaded = false
        _apps.value = emptyList()

        // 清除图标内存缓存
        cachedIcons.clear()
        iconsLoaded = false

        // 清除持久化缓存
        kotlinx.coroutines.runBlocking {
            IconCacheManager.clearAllCache()
        }
    }

    /**
     * 检查是否已加载
     */
    fun isDataLoaded(): Boolean = isLoaded

    /**
     * 获取应用标签
     */
    fun getAppLabel(context: Context, packageName: String): String {
        return AppListHelper.getApplicationLabel(context, packageName)
    }

    /**
     * 获取已安装应用包名集合
     */
    fun getInstalledPackageNames(context: Context): Set<String> {
        return cachedApps?.map { it.packageName }?.toSet() ?: emptySet()
    }

    /**
     * 异步获取已安装应用包名集合（确保数据已加载）
     */
    suspend fun getInstalledPackageNamesAsync(context: Context): Set<String> {
        if (!isLoaded) {
            loadApps(context)
        }
        return getInstalledPackageNames(context)
    }

    /**
     * 同步获取已安装应用包名集合（如果未加载则同步加载）
     */
    fun getInstalledPackageNamesSync(context: Context): Set<String> {
        if (!isLoaded || cachedApps == null) {
            // 同步加载，使用runBlocking
            kotlinx.coroutines.runBlocking {
                loadApps(context)
            }
        }
        return getInstalledPackageNames(context)
    }

    /**
     * 加载应用图标
     */
    private suspend fun loadAppIcons(context: Context, apps: List<ApplicationInfo>) {
        if (iconsLoaded) {
            if (BuildConfig.DEBUG) Log.d(TAG, "使用缓存的应用图标")
            return
        }

        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "开始加载应用图标")
            val pm = context.packageManager
            val newIcons = mutableMapOf<String, android.graphics.Bitmap?>()

            apps.forEach { appInfo ->
                try {
                    val packageName = appInfo.packageName

                    // 首先尝试从持久化缓存加载
                    var bitmap = IconCacheManager.loadIcon(packageName)

                    if (bitmap == null) {
                        // 如果持久化缓存中没有，从PackageManager获取
                        val drawable = pm.getApplicationIcon(appInfo)
                        bitmap = when (drawable) {
                            is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                            else -> {
                                // 将其他类型的drawable转换为bitmap
                                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, width, height)
                                drawable.draw(canvas)
                                bitmap
                            }
                        }

                        // 保存到持久化缓存
                        if (bitmap != null) {
                            IconCacheManager.saveIcon(packageName, bitmap)
                        }
                    }

                    newIcons[packageName] = bitmap
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "获取应用图标失败: ${appInfo.packageName}", e)
                    newIcons[appInfo.packageName] = null
                }
            }

            cachedIcons.clear()
            cachedIcons.putAll(newIcons)
            iconsLoaded = true

            if (BuildConfig.DEBUG) Log.d(TAG, "应用图标加载成功，共 ${newIcons.size} 个图标")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "应用图标加载失败", e)
        }
    }

    /**
     * 获取应用图标
     */
    fun getAppIcon(packageName: String): android.graphics.Bitmap? {
        // 首先检查内存缓存
        var bitmap = cachedIcons[packageName]
        if (bitmap != null) {
            return bitmap
        }

        // 如果内存缓存中没有，尝试从持久化缓存加载
        if (IconCacheManager.hasIcon(packageName)) {
            // 这里是同步调用，需要在协程中调用
            // 对于UI线程调用，我们提供同步版本
            return null // 让调用方使用异步版本
        }

        return null
    }

    /**
     * 获取应用图标（异步，确保数据已加载）
     */
    suspend fun getAppIconAsync(context: Context, packageName: String): android.graphics.Bitmap? {
        if (!isLoaded) {
            loadApps(context)
        }

        // 首先检查内存缓存
        var bitmap = cachedIcons[packageName]
        if (bitmap != null) {
            return bitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        bitmap = IconCacheManager.loadIcon(packageName)
        if (bitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = bitmap
        }

        return bitmap
    }

    /**
     * 获取应用图标（同步，如果未加载则同步加载）
     */
    fun getAppIconSync(context: Context, packageName: String): android.graphics.Bitmap? {
        if (!isLoaded || cachedApps == null) {
            // 同步加载，使用runBlocking
            kotlinx.coroutines.runBlocking {
                loadApps(context)
            }
        }

        // 首先检查内存缓存
        var bitmap = cachedIcons[packageName]
        if (bitmap != null) {
            return bitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        bitmap = kotlinx.coroutines.runBlocking {
            IconCacheManager.loadIcon(packageName)
        }
        if (bitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = bitmap
        }

        return bitmap
    }

    /**
     * 检查图标是否已加载
     */
    fun isIconsLoaded(): Boolean = iconsLoaded

    /**
     * 获取所有缓存的图标
     */
    fun getAllCachedIcons(): Map<String, android.graphics.Bitmap?> {
        return cachedIcons.toMap()
    }

    /**
     * 缓存外部应用图标（为后续扩展存储本机未安装的应用图标信息）
     */
    fun cacheExternalAppIcon(packageName: String, icon: android.graphics.Bitmap?) {
        cachedIcons[packageName] = icon

        // 同时保存到持久化缓存
        if (icon != null) {
            kotlinx.coroutines.runBlocking {
                IconCacheManager.saveIcon(packageName, icon)
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "缓存外部应用图标: $packageName")
    }

    /**
     * 移除外部应用图标缓存
     */
    fun removeExternalAppIcon(packageName: String) {
        cachedIcons.remove(packageName)

        // 同时从持久化缓存移除
        kotlinx.coroutines.runBlocking {
            IconCacheManager.removeIcon(packageName)
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "移除外部应用图标缓存: $packageName")
    }

    /**
     * 获取外部应用图标（优先从缓存获取，如果没有则返回null）
     */
    fun getExternalAppIcon(packageName: String): android.graphics.Bitmap? {
        // 首先检查内存缓存
        var bitmap = cachedIcons[packageName]
        if (bitmap != null) {
            return bitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        bitmap = kotlinx.coroutines.runBlocking {
            IconCacheManager.loadIcon(packageName)
        }
        if (bitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = bitmap
        }

        return bitmap
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): com.xzyht.notifyrelay.core.cache.IconCacheManager.CacheStats {
        return IconCacheManager.getCacheStats()
    }

    /**
     * 清理过期缓存
     */
    suspend fun cleanupExpiredCache() {
        IconCacheManager.cleanupExpiredCache()
    }
}
