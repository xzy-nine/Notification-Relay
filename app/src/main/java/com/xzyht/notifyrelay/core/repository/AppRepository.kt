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
 * 应用数据仓库。
 *
 * 封装了应用列表和应用图标的内存缓存与持久化缓存操作，提供加载、过滤、查询、缓存管理等功能。
 * 所有对外提供的方法均设计为在主进程/UI 线程或协程中安全使用（按方法注释中的说明）。
 *
 * 功能概览：
 * - 加载已安装的应用列表并按应用名称排序
 * - 加载并缓存应用图标（内存 + 持久化）
 * - 提供同步/异步的图标与包名查询方法
 * - 清理和统计缓存
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
     * 加载应用列表并缓存。
     *
     * 说明：该方法为挂起函数，会从 PackageManager 读取已安装应用信息并按应用标签排序，
     *       同时初始化图标缓存管理器并异步触发图标加载（内部会调用 [loadAppIcons]）。
     *
     * @param context Android 上下文，用于访问 PackageManager 和持久化缓存（非空）。
     * @return 无（在成功或失败后会更新内部状态流 `_apps` 与 `_isLoading`）。
     * @throws Exception 当 PackageManager 访问或缓存初始化发生严重错误时向上抛出（调用方可选择捕获）。
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
     * 获取过滤后的应用列表。
     *
     * @param query 搜索关键字；若为空或仅空白字符则返回所有满足条件的应用。
     * @param showSystemApps 是否展示系统应用（true 包含系统应用，false 仅显示用户安装的应用）。
     * @param context Android 上下文，用于获取应用标签进行匹配（非空）。
     * @return 符合查询与系统/用户筛选条件的应用列表（不可为 null，可能为空）。
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
     * 清除所有缓存（内存缓存与持久化缓存）。
     *
     * 说明：该方法会清空内存中的应用与图标缓存，并同步清理持久化的图标缓存（通过 [IconCacheManager.clearAllCache]）。
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
     * 检查应用数据（应用列表）是否已加载。
     *
     * @return 如果已加载返回 true，否则返回 false。
     */
    fun isDataLoaded(): Boolean = isLoaded

    /**
     * 获取指定包名的应用标签（显示名）。
     *
     * @param context Android 上下文，用于访问 PackageManager（非空）。
     * @param packageName 目标应用的包名（非空）。
     * @return 应用的标签字符串；若无法获取则返回包名或空字符串，具体由 [AppListHelper.getApplicationLabel] 决定。
     */
    fun getAppLabel(context: Context, packageName: String): String {
        return AppListHelper.getApplicationLabel(context, packageName)
    }

    /**
     * 获取已缓存的已安装应用包名集合（同步返回）。
     *
     * @param context Android 上下文（未使用，仅为 API 对称性保留）。
     * @return 当前缓存的已安装应用包名集合，若尚未加载返回空集合。
     */
    fun getInstalledPackageNames(context: Context): Set<String> {
        return cachedApps?.map { it.packageName }?.toSet() ?: emptySet()
    }

    /**
     * 异步获取已安装应用包名集合（确保在返回前数据已加载）。
     *
     * @param context Android 上下文，用于在必要时调用 [loadApps] 加载数据。
     * @return 已安装应用的包名集合（非空）。
     */
    suspend fun getInstalledPackageNamesAsync(context: Context): Set<String> {
        if (!isLoaded) {
            loadApps(context)
        }
        return getInstalledPackageNames(context)
    }

    /**
     * 同步获取已安装应用包名集合。如果尚未加载，则会在当前线程同步加载数据（阻塞）。
     *
     * 注意：该方法会在必要时使用 runBlocking 在当前线程执行加载，请谨慎在 UI 线程中使用以避免卡顿。
     *
     * @param context Android 上下文，用于调用 [loadApps]。
     * @return 已安装应用的包名集合（非空）。
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
     * 加载并缓存应用图标（内存 + 持久化）。
     *
     * 说明：该方法为挂起函数，会尝试优先从持久化缓存加载图标，若不存在则从 PackageManager 获取并转换为 Bitmap，
     *       最后保存到持久化缓存以便下次读取加速。
     *
     * @param context Android 上下文，用于访问 PackageManager 与持久化缓存（非空）。
     * @param apps 需要加载图标的应用列表（非空，可为空列表）。
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
                    var iconBitmap = IconCacheManager.loadIcon(packageName)

                    if (iconBitmap == null) {
                        // 如果持久化缓存中没有，从PackageManager获取
                        val drawable = pm.getApplicationIcon(appInfo)
                        iconBitmap = when (drawable) {
                            is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                            else -> {
                                // 将其他类型的drawable转换为bitmap
                                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                                val createdBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(createdBitmap)
                                drawable.setBounds(0, 0, width, height)
                                drawable.draw(canvas)
                                createdBitmap
                            }
                        }

                        // 保存到持久化缓存
                        if (iconBitmap != null) {
                            IconCacheManager.saveIcon(packageName, iconBitmap)
                        }
                    }

                    newIcons[packageName] = iconBitmap
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
     * 获取应用图标（非阻塞版本）。
     *
     * 说明：该方法为非阻塞的同步接口，优先返回内存缓存中的图标；若内存缓存和持久化缓存都不存在，则返回 null，
     *       调用方可使用 [getAppIconAsync] 或 [getAppIconSync] 获取图标的异步/同步版本。
     *
     * @param packageName 目标应用的包名（非空）。
     * @return 若内存缓存中存在则返回 Bitmap，否则返回 null（不进行阻塞加载）。
     */
    fun getAppIcon(packageName: String): android.graphics.Bitmap? {
        // 首先检查内存缓存
        var iconBitmap = cachedIcons[packageName]
        if (iconBitmap != null) {
            return iconBitmap
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
     * 异步获取应用图标（确保在返回前数据已加载）。
     *
     * @param context Android 上下文，用于在必要时加载应用列表与访问持久化缓存。
     * @param packageName 目标应用的包名（非空）。
     * @return 应用图标的 Bitmap；若不存在则返回 null。
     */
    suspend fun getAppIconAsync(context: Context, packageName: String): android.graphics.Bitmap? {
        if (!isLoaded) {
            loadApps(context)
        }

        // 首先检查内存缓存
        var iconBitmap = cachedIcons[packageName]
        if (iconBitmap != null) {
            return iconBitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        iconBitmap = IconCacheManager.loadIcon(packageName)
        if (iconBitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = iconBitmap
        }

        return iconBitmap
    }

    /**
     * 同步获取应用图标（若未加载应用列表则会同步加载，可能阻塞当前线程）。
     *
     * 注意：该方法在必要时会使用 runBlocking 加载数据，请谨慎在 UI 线程中直接调用以避免卡顿。
     *
     * @param context Android 上下文，用于加载应用列表与访问持久化缓存。
     * @param packageName 目标应用的包名（非空）。
     * @return 应用图标的 Bitmap；若不存在则返回 null。
     */
    fun getAppIconSync(context: Context, packageName: String): android.graphics.Bitmap? {
        if (!isLoaded || cachedApps == null) {
            // 同步加载，使用runBlocking
            kotlinx.coroutines.runBlocking {
                loadApps(context)
            }
        }

        // 首先检查内存缓存
        var iconBitmap = cachedIcons[packageName]
        if (iconBitmap != null) {
            return iconBitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        iconBitmap = kotlinx.coroutines.runBlocking {
            IconCacheManager.loadIcon(packageName)
        }
        if (iconBitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = iconBitmap
        }

        return iconBitmap
    }

    /**
     * 检查图标是否已加载完成。
     *
     * @return 若图标加载过程已完成返回 true，否则返回 false。
     */
    fun isIconsLoaded(): Boolean = iconsLoaded

    /**
     * 获取所有已缓存的图标（只读拷贝）。
     *
     * @return 包名 -> Bitmap 的映射拷贝，Bitmap 可能为 null 表示缓存占位或失败。
     */
    fun getAllCachedIcons(): Map<String, android.graphics.Bitmap?> {
        return cachedIcons.toMap()
    }

    /**
     * 缓存外部应用的图标（内存 + 持久化），用于保存未安装应用或来自远端的图标数据。
     *
     * @param packageName 外部应用的包名（用于作为键）。
     * @param icon 要缓存的 Bitmap，若为 null 则只在内存中移除对应条目。
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
     * 移除外部应用图标的缓存（内存 + 持久化）。
     *
     * @param packageName 目标包名，会从内存缓存移除并从持久化缓存删除对应文件/记录。
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
     * 获取外部应用图标（优先从内存缓存获取，如果没有则同步从持久化缓存加载）。
     *
     * @param packageName 目标应用包名。
     * @return 若存在则返回 Bitmap，否则返回 null。
     */
    fun getExternalAppIcon(packageName: String): android.graphics.Bitmap? {
        // 首先检查内存缓存
        var iconBitmap = cachedIcons[packageName]
        if (iconBitmap != null) {
            return iconBitmap
        }

        // 如果内存缓存中没有，从持久化缓存加载
        iconBitmap = kotlinx.coroutines.runBlocking {
            IconCacheManager.loadIcon(packageName)
        }
        if (iconBitmap != null) {
            // 更新内存缓存
            cachedIcons[packageName] = iconBitmap
        }

        return iconBitmap
    }

    /**
     * 获取持久化图标缓存的统计信息。
     *
     * @return [IconCacheManager.CacheStats] 包含缓存大小、条目数、命中率等统计信息。
     */
    fun getCacheStats(): com.xzyht.notifyrelay.core.cache.IconCacheManager.CacheStats {
        return IconCacheManager.getCacheStats()
    }

    /**
     * 清理过期的持久化图标缓存（挂起函数）。
     *
     * 说明：会删除满足过期策略的缓存文件/记录以释放磁盘空间。
     */
    suspend fun cleanupExpiredCache() {
        IconCacheManager.cleanupExpiredCache()
    }
}
