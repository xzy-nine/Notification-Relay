package com.xzyht.notifyrelay.feature.notification.ui.dialog

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Switch

// 全局应用列表缓存，避免重复加载
private object AppListCache {
    var allApps: List<ApplicationInfo>? = null
    var isLoaded = false
}

/**
 * 应用选择弹窗
 */
@Composable
fun AppPickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit,
    title: String = "选择应用"
) {
    val TAG = "AppPickerDialog"
    Log.d(TAG, "托比昂: AppPickerDialog Composable called, visible = $visible")
    if (!visible) return

    val context = LocalContext.current
    val pm = context.packageManager
    Log.d(TAG, "托比昂: 初始化状态变量, 缓存状态: loaded=${AppListCache.isLoaded}, cacheSize=${AppListCache.allApps?.size ?: 0}")
    var showSystemApps by rememberSaveable(key = "showSystemApps") { mutableStateOf(true) }
    var appSearchQuery by rememberSaveable(key = "appSearchQuery") { mutableStateOf("") }

    // 使用全局缓存的应用列表
    var allApps by remember { mutableStateOf(AppListCache.allApps ?: emptyList()) }
    var dataLoaded by remember { mutableStateOf(AppListCache.isLoaded) }
    var isLoading by remember { mutableStateOf(!AppListCache.isLoaded) }
    var loadId by rememberSaveable(key = "loadId") { mutableStateOf(0) }

    val showDialog = rememberSaveable(key = "showDialog") { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        Log.d(TAG, "托比昂: LaunchedEffect(visible) triggered, visible=$visible")
        showDialog.value = visible
        if (visible && !AppListCache.isLoaded) {
            Log.d(TAG, "托比昂: LaunchedEffect for loading triggered, visible=$visible, cacheLoaded=${AppListCache.isLoaded}")
            Log.d(TAG, "托比昂: 开始加载应用列表")
            loadId++
            Log.d(TAG, "托比昂: loadId 增加到 $loadId")
            try {
                val apps = pm.getInstalledApplications(0).sortedBy { appInfo: ApplicationInfo ->
                    try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "托比昂: 获取应用标签失败，使用包名: ${appInfo.packageName}", e)
                        appInfo.packageName
                    }
                }
                AppListCache.allApps = apps.toList()
                AppListCache.isLoaded = true
                allApps = apps.toList()
                dataLoaded = true
                Log.d(TAG, "托比昂: 应用列表加载成功，共 ${apps.size} 个应用, allApps.size=${allApps.size}, 缓存已更新")
            } catch (e: Exception) {
                AppListCache.allApps = emptyList()
                AppListCache.isLoaded = true
                allApps = emptyList()
                dataLoaded = true
                Log.e(TAG, "托比昂: 应用列表加载失败", e)
            }
            isLoading = false
        } else if (visible && AppListCache.isLoaded) {
            Log.d(TAG, "托比昂: 使用缓存的应用列表, cacheSize=${AppListCache.allApps?.size ?: 0}")
            allApps = AppListCache.allApps ?: emptyList()
            dataLoaded = true
            isLoading = false
        }
    }

    // 处理关闭时的状态重置
    LaunchedEffect(showDialog.value) {
        if (!showDialog.value) {
            Log.d(TAG, "托比昂: 弹窗关闭，重置搜索查询")
            appSearchQuery = ""
        }
    }

    val userApps = if (!dataLoaded || allApps.isEmpty()) emptyList<ApplicationInfo>()
        else {
            val result = allApps.filter { app: ApplicationInfo -> (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            Log.d(TAG, "托比昂: 用户应用数量: ${result.size}")
            result.toList()
        }

    val displayApps = if (showSystemApps) allApps else userApps
    Log.d(TAG, "托比昂: displayApps.size=${displayApps.size}, showSystemApps=$showSystemApps")

    Log.d(TAG, "托比昂: 开始计算filteredApps, dataLoaded=$dataLoaded, allApps.size=${allApps.size}, showSystemApps=$showSystemApps, appSearchQuery='$appSearchQuery', userApps.size=${userApps.size}")
    val filteredApps = if (!dataLoaded || (if (showSystemApps) allApps.isEmpty() else userApps.isEmpty())) {
        Log.d(TAG, "托比昂: filteredApps 返回空列表, dataLoaded=$dataLoaded, allApps.size=${allApps.size}, showSystemApps=$showSystemApps")
        emptyList<ApplicationInfo>()
    }
    else if (appSearchQuery.isBlank()) {
        val result = if (showSystemApps) allApps as List<ApplicationInfo> else userApps
        Log.d(TAG, "托比昂: 无搜索分支, result.size=${result.size}, showSystemApps=$showSystemApps")
        result.toList()
    }
    else {
        Log.d(TAG, "托比昂: 搜索分支, appSearchQuery='$appSearchQuery', showSystemApps=$showSystemApps")
        val displayApps = if (showSystemApps) allApps as List<ApplicationInfo> else userApps
        Log.d(TAG, "托比昂: displayApps.size=${displayApps.size}")
        val result = displayApps.filter { app: ApplicationInfo ->
            try {
                val label = pm.getApplicationLabel(app).toString()
                val matchesLabel = label.contains(appSearchQuery, true)
                val matchesPackage = appSearchQuery in app.packageName
                Log.d(TAG, "托比昂: 过滤应用 ${app.packageName}, label='$label', matchesLabel=$matchesLabel, matchesPackage=$matchesPackage")
                matchesLabel || matchesPackage
            } catch (e: Exception) {
                Log.w(TAG, "托比昂: 搜索时获取应用标签失败: ${app.packageName}", e)
                val matchesPackage = appSearchQuery in app.packageName
                Log.d(TAG, "托比昂: 仅包名匹配 ${app.packageName}, matchesPackage=$matchesPackage")
                matchesPackage
            }
        }
        Log.d(TAG, "托比昂: 搜索结果数量: ${result.size}")
        result.toList()
    }
    Log.d(TAG, "托比昂: filteredApps 计算完成, size=${filteredApps.size}")

    val defaultAppIconBitmap = remember {
        val drawable = try { pm.getDefaultActivityIcon() } catch (_: Exception) { null }
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            drawable.bitmap.asImageBitmap()
        } else {
            androidx.compose.ui.graphics.ImageBitmap(22, 22, androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888)
        }
    }

    MiuixTheme {
        SuperDialog(
            show = showDialog,
            title = title,
            onDismissRequest = { 
                Log.d(TAG, "托比昂: SuperDialog onDismissRequest triggered")
                showDialog.value = false; onDismiss(); appSearchQuery = "" 
            }
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) {
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { 
                            Log.d(TAG, "托比昂: 显示系统应用按钮触发, old=$showSystemApps, new=$it")
                            showSystemApps = it 
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("显示系统应用", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                }

                TextField(
                    value = appSearchQuery,
                    onValueChange = { 
                        Log.d(TAG, "托比昂: 搜索查询变化, old='$appSearchQuery', new='$it'")
                        appSearchQuery = it 
                    },
                    label = "搜索应用/包名",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )

                if (isLoading || !dataLoaded) {
                    // 显示加载提示在应用列表区域
                    Log.d(TAG, "托比昂: 显示加载中，isLoading = $isLoading, dataLoaded = $dataLoaded, allApps.size=${allApps.size}")
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            InfiniteProgressIndicator(
                                size = 48.dp,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在加载应用列表...", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        }
                    }
                } else {
                    Log.d(TAG, "托比昂: 显示应用列表，filteredApps.size = ${filteredApps.size}, dataLoaded=$dataLoaded, isLoading=$isLoading, loadId=$loadId")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        Log.d(TAG, "托比昂: LazyColumn 执行, filteredApps.size=${filteredApps.size}, displayApps.size=${displayApps.size}")
                        if (filteredApps.isEmpty()) {
                            Log.d(TAG, "托比昂: filteredApps为空，显示无匹配应用")
                            item {
                                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                    Text("没有匹配的应用", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                            }
                        } else {
                            Log.d(TAG, "托比昂: 开始渲染应用列表项, filteredApps.size=${filteredApps.size}")
                            items(filteredApps) { appInfo: ApplicationInfo ->
                                Log.d(TAG, "托比昂: 渲染应用项: ${appInfo.packageName}")
                                val pkg = appInfo.packageName
                                val label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { 
                                    Log.w(TAG, "托比昂: 显示时获取应用标签失败: $pkg")
                                    pkg 
                                }
                                val iconBitmap = try {
                                    val icon = pm.getApplicationIcon(appInfo)
                                    when (icon) {
                                        is android.graphics.drawable.BitmapDrawable -> icon.bitmap.asImageBitmap()
                                        else -> {
                                            val drawable = icon as android.graphics.drawable.Drawable
                                            val width: Int = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                                            val height: Int = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                                            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                            val canvas = android.graphics.Canvas(bitmap)
                                            drawable.setBounds(0, 0, width, height)
                                            drawable.draw(canvas)
                                            bitmap.asImageBitmap()
                                        }
                                    }
                                } catch (_: Exception) { 
                                    Log.w(TAG, "托比昂: 获取应用图标失败: $pkg")
                                    null 
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            Log.d(TAG, "托比昂: 选择了应用: $pkg")
                                            onAppSelected(pkg)
                                            showDialog.value = false
                                            onDismiss()
                                            appSearchQuery = ""
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (iconBitmap != null) {
                                            Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(22.dp))
                                        } else {
                                            Image(bitmap = defaultAppIconBitmap, contentDescription = null, modifier = Modifier.size(22.dp))
                                        }
                                        Column(modifier = Modifier.padding(start = 8.dp)) {
                                            Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                                            Text(pkg, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(top = 8.dp),
                                        color = MiuixTheme.colorScheme.dividerLine,
                                        thickness = 0.7.dp
                                    )
                                }
                            }

                            if (appSearchQuery.isNotBlank() && filteredApps.none { app: ApplicationInfo -> app.packageName == appSearchQuery } && appSearchQuery.matches(Regex("[a-zA-Z0-9_.]+"))) {
                                Log.d(TAG, "托比昂: 显示自定义包名选项, appSearchQuery='$appSearchQuery'")
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                Log.d(TAG, "托比昂: 选择了自定义包名: ${appSearchQuery}")
                                                onAppSelected(appSearchQuery)
                                                showDialog.value = false
                                                onDismiss()
                                                appSearchQuery = ""
                                            }
                                            .padding(horizontal = 4.dp, vertical = 10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Image(bitmap = defaultAppIconBitmap, contentDescription = null, modifier = Modifier.size(22.dp))
                                            Text(
                                                "添加自定义包名：${appSearchQuery}",
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "关闭",
                    onClick = { 
                        Log.d(TAG, "托比昂: 关闭按钮点击")
                        showDialog.value = false; onDismiss(); appSearchQuery = "" 
                    }
                )
            }
        }
    }
}
