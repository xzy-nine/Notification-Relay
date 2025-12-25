package com.xzyht.notifyrelay.feature.notification.ui.dialog

import android.content.pm.ApplicationInfo

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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xzyht.notifyrelay.core.repository.AppRepository

/**
 * 应用选择的弹窗
 */
@Composable
fun AppPickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit,
    title: String = "选择应用"
) {
    if (!visible) return

    val context = LocalContext.current
    val pm = context.packageManager
    val coroutineScope = rememberCoroutineScope()

    var showSystemApps by rememberSaveable(key = "showSystemApps") { mutableStateOf(true) }
    var appSearchQuery by rememberSaveable(key = "appSearchQuery") { mutableStateOf("") }

    val showDialog = rememberSaveable(key = "showDialog") { mutableStateOf(visible) }

    // 监听 AppRepository 的状态
    val isLoading by AppRepository.isLoading.collectAsState()
    val allApps by AppRepository.apps.collectAsState()

    // 计算过滤后的应用列表
    val filteredApps by remember(allApps, appSearchQuery, showSystemApps) {
        derivedStateOf {
            AppRepository.getFilteredApps(appSearchQuery, showSystemApps, context)
        }
    }

    LaunchedEffect(visible) {
        showDialog.value = visible
        if (visible && !AppRepository.isDataLoaded()) {
            coroutineScope.launch {
                AppRepository.loadApps(context)
            }
        }
    }

    // 处理关闭时的状态重置
    LaunchedEffect(showDialog.value) {
        if (!showDialog.value) {
            appSearchQuery = ""
        }
    }

    val defaultAppIconBitmap = remember {
        val drawable = try { pm.getDefaultActivityIcon() } catch (_: Exception) { null }
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            drawable.bitmap.asImageBitmap()
        } else {
            androidx.compose.ui.graphics.ImageBitmap(22, 22, androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888)
        }
    }

    MiuixTheme {
        SuperBottomSheet(
            show = showDialog,
            title = title,
            onDismissRequest = { 
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
                        onCheckedChange = { showSystemApps = it },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("显示系统应用", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                }

                var expanded by remember { mutableStateOf(false) }
                
                SearchBar(
                    inputField = {
                        InputField(
                            query = appSearchQuery,
                            onQueryChange = { appSearchQuery = it },
                            onSearch = { /* 搜索操作已通过 onQueryChange 实时处理 */ },
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            label = "搜索应用/包名"
                        )
                    },
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    // SearchBar 内容区域
                    if (isLoading || allApps.isEmpty()) {
                        // 显示加载提示在应用列表区域
                        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp), contentAlignment = Alignment.Center) {
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
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            if (filteredApps.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                        Text("没有匹配的应用", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                    }
                                }
                            } else {
                                items(filteredApps) { appInfo: ApplicationInfo ->
                                    val pkg = appInfo.packageName
                                    val label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
                                    // 使用缓存的图标（同步版本）
                                    val iconBitmap = AppRepository.getAppIconSync(context, pkg)?.asImageBitmap()
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
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

                                if (appSearchQuery.isNotBlank() && filteredApps.none { app: ApplicationInfo -> app.packageName == appSearchQuery } && appSearchQuery.matches(Regex("[a-zA-Z0-9_.]"))) {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
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
            }
        }
    }
}