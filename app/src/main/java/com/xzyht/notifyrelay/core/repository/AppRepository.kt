package com.xzyht.notifyrelay.core.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
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
            Log.d(TAG, "使用缓存的应用列表")
            _apps.value = cachedApps!!
            return
        }

        _isLoading.value = true
        try {
            Log.d(TAG, "开始加载应用列表")
            val apps = AppListHelper.getInstalledApplications(context).sortedBy { appInfo ->
                try {
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    Log.w(TAG, "获取应用标签失败，使用包名: ${appInfo.packageName}", e)
                    appInfo.packageName
                }
            }

            cachedApps = apps
            isLoaded = true
            _apps.value = apps
            Log.d(TAG, "应用列表加载成功，共 ${apps.size} 个应用")
        } catch (e: Exception) {
            Log.e(TAG, "应用列表加载失败", e)
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
                Log.w(TAG, "搜索时获取应用标签失败: ${app.packageName}", e)
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
}
