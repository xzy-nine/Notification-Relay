package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig

object AppListHelper {

    /**
     * 获取已安装的应用列表
     */
    fun getInstalledApplications(context: Context): List<android.content.pm.ApplicationInfo> {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            // 过滤掉系统应用和自己
            apps.filter { appInfo ->
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isSelf = appInfo.packageName == context.packageName
                !isSystemApp && !isUpdatedSystemApp && !isSelf
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("AppListHelper", "Failed to get installed applications", e)
            emptyList()
        }
    }

    /**
     * 检查是否可以查询应用列表
     */
    fun canQueryApps(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            apps.size > 2 // 简单的检查，至少有几个应用
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取应用标签（名称）
     */
    fun getApplicationLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName // 如果获取失败，返回包名
        }
    }

    /**
     * 根据包名过滤应用列表
     */
    fun filterAppsByPackageNames(
        context: Context,
        packageNames: List<String>
    ): List<android.content.pm.ApplicationInfo> {
        val allApps = getInstalledApplications(context)
        return allApps.filter { appInfo ->
            packageNames.contains(appInfo.packageName)
        }
    }

    /**
     * 搜索应用（按名称或包名）
     */
    fun searchApps(
        context: Context,
        query: String,
        installedApps: List<android.content.pm.ApplicationInfo>? = null
    ): List<android.content.pm.ApplicationInfo> {
        val apps = installedApps ?: getInstalledApplications(context)
        if (query.isBlank()) return apps

        return apps.filter { appInfo ->
            val appName = getApplicationLabel(context, appInfo.packageName)
            appName.contains(query, ignoreCase = true) ||
            appInfo.packageName.contains(query, ignoreCase = true)
        }
    }
}
