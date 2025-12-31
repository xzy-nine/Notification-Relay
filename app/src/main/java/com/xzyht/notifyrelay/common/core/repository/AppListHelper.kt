package com.xzyht.notifyrelay.common.core.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import com.xzyht.notifyrelay.common.core.util.Logger

object AppListHelper {
    /**
     * 获取已安装的应用列表（非系统应用且排除当前应用）
     *
     * @param context 用于访问 PackageManager 的 Context
     * @return 已安装应用的列表，若发生异常则返回空列表
     */
    fun getInstalledApplications(context: Context): List<ApplicationInfo> {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            // 过滤掉系统应用和自己
            apps.filter { appInfo ->
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isSelf = appInfo.packageName == context.packageName
                !isSystemApp && !isUpdatedSystemApp && !isSelf
            }
        } catch (e: Exception) {
            {
                Logger.e("AppListHelper", "获取已安装应用列表失败: ${e.message}", e)
            }
            emptyList()
        }
    }

    /**
     * 检查是否可以查询应用列表
     *
     * @param context 用于访问 PackageManager 的 Context
     * @return 如果能够成功查询并且至少检测到多个应用返回 true，否则返回 false
     */
    fun canQueryApps(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            val result = apps.size > 2 // 简单的检查，至少有几个应用
            {
                //Logger.d("AppListHelper", "canQueryApps 检查结果: 应用数量=${apps.size}, 可查询=$result")
            }
            result
        } catch (e: Exception) {
            {
                Logger.e("AppListHelper", "检查是否可查询应用列表失败: ${e.message}", e)
            }
            false
        }
    }

    /**
     * 获取应用标签（名称）
     *
     * @param context 用于访问 PackageManager 的 Context
     * @param packageName 要查询的应用包名
     * @return 应用的显示名称，若查询失败则返回 packageName
     */
    fun getApplicationLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            {
                Logger.w("AppListHelper", "获取应用名失败, 包名=$packageName, 错误=${e.message}", e)
            }
            packageName // 如果获取失败，返回包名
        }
    }

    /**
     * 根据包名过滤应用列表
     *
     * @param context 用于访问 PackageManager 的 Context（仅用于内部调用的兼容性）
     * @param packageNames 要保留的包名列表
     * @return 匹配的应用信息列表
     */
    fun filterAppsByPackageNames(
        context: Context,
        packageNames: List<String>
    ): List<ApplicationInfo> {
        val allApps = getInstalledApplications(context)
        return allApps.filter { appInfo ->
            packageNames.contains(appInfo.packageName)
        }
    }

    /**
     * 搜索应用（按名称或包名）
     *
     * @param context 用于访问 PackageManager 的 Context
     * @param query 要搜索的关键字，支持部分匹配；为空字符串将返回传入的 installedApps 或所有已安装应用
     * @param installedApps 可选的已安装应用列表，传入时会在该列表中搜索以减少重复 IO
     * @return 匹配关键字的应用信息列表
     */
    fun searchApps(
        context: Context,
        query: String,
        installedApps: List<ApplicationInfo>? = null
    ): List<ApplicationInfo> {
        val apps = installedApps ?: getInstalledApplications(context)
        if (query.isBlank()) return apps

        return apps.filter { appInfo ->
            val appName = getApplicationLabel(context, appInfo.packageName)
            appName.contains(query, ignoreCase = true) ||
            appInfo.packageName.contains(query, ignoreCase = true)
        }
    }
}