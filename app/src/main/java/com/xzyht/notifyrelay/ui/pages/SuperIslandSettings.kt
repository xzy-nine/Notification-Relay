package com.xzyht.notifyrelay.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.config.SuperIslandConfigUtils
import com.xzyht.notifyrelay.feature.notification.superisland.config.SuperIslandConfigUtils.SpecInjectionMode
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import com.xzyht.notifyrelay.ui.activity.DeveloperModeActivity
import com.xzyht.notifyrelay.ui.dialog.AppPickerDialog
import com.xzyht.notifyrelay.ui.dialog.SuperIslandTestDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.ToastUtils
import notifyrelay.data.FilterConfigDefaults
import notifyrelay.data.StorageManager
import notifyrelay.data.database.entity.SuperIslandMirrorFilterEntity
import notifyrelay.data.database.repository.DatabaseRepository
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SUPER_ISLAND_KEY = "superisland_enabled"
private const val SUPER_ISLAND_SHOW_KEY = "superisland_show"
private const val SUPER_ISLAND_FLOATING_WINDOW_KEY = "super_island_floating_window"
private const val SPEC_INJECTION_MODE_KEY = "spec_injection_mode"
private const val MIRROR_FILTER_ENABLED_KEY = "super_island_mirror_filter_enabled"

private val DEFAULT_MIRROR_PACKAGES: List<String>
    get() = FilterConfigDefaults.defaultMirrorPackages

// 注入方式选项
val specInjectionOptions =
    listOf(
        "仅超级岛规范信息注入" to SpecInjectionMode.SUPER_ISLAND,
        "仅Live Updates规范信息注入" to SpecInjectionMode.LIVE_UPDATES,
    )

@Composable
fun UISuperIslandSettings() {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_KEY, true)) }
    var showSuperIsland by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_SHOW_KEY, true)) }
    var floatingWindowEnabled by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_FLOATING_WINDOW_KEY, FloatingReplicaManager.getDefaultFloatingWindowEnabled())) }
    var notificationListEnabled by remember { mutableStateOf(SuperIslandConfigUtils.isNotificationListMode(context)) }

    val savedInjectionMode = SuperIslandConfigUtils.getSpecInjectionMode(context)
    var specInjectionMode by remember { mutableStateOf(savedInjectionMode) }

    val hasFloatingWindowSetting = StorageManager.getString(context, SUPER_ISLAND_FLOATING_WINDOW_KEY, "") != ""

    val defaultFloatingWindowEnabled = FloatingReplicaManager.getDefaultFloatingWindowEnabled()

    val showTestDialog = remember { mutableStateOf(false) }

    var mirrorFilterEnabled by remember { mutableStateOf(StorageManager.getBoolean(context, MIRROR_FILTER_ENABLED_KEY, true)) }
    var customPackages by remember { mutableStateOf<List<SuperIslandMirrorFilterEntity>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showCustomPkgInput by remember { mutableStateOf(false) }
    var customPkgText by remember { mutableStateOf("") }

    val floatingWindowSummary =
        run {
            val baseSummary = "用于a16的livedata通知api被系统支持前使用浮窗展示超级岛"
            val currentOsVersion = PermissionHelper.getDetailedOsVersion() ?: "未知"
            val versionComparisonResult = PermissionHelper.isVersionGreaterThan(currentOsVersion, "OS3.0.300")
            if (DeveloperModeActivity.DEBUG_UI_ENABLED.value) {
                "$baseSummary (当前版本: $currentOsVersion, 版本比较: ${if (versionComparisonResult) "高于" else "低于或等于"} OS3.0.300, 有用户设置: ${if (hasFloatingWindowSetting) "是" else "否"}, 预设默认值: ${if (defaultFloatingWindowEnabled) "开启" else "关闭"})"
            } else {
                baseSummary
            }
        }

    suspend fun loadCustomPackages() {
        val repo = DatabaseRepository.getInstance(context)
        customPackages =
            withContext(Dispatchers.IO) {
                repo.getAllMirrorFilterPackages()
            }
    }

    LaunchedEffect(Unit) {
        loadCustomPackages()
    }

    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    Scaffold {
        Surface(color = colorScheme.background) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .verticalScroll(remember { androidx.compose.foundation.ScrollState(0) }),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SwitchPreference(
                    title = "超级岛读取",
                    summary = "控制是否尝试从本机通知中读取小米超级岛数据并转发",
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        StorageManager.putBoolean(context, SUPER_ISLAND_KEY, it)
                    },
                )

                SwitchPreference(
                    title = "超级岛显示",
                    summary = "控制是否显示来自远端的超级岛",
                    checked = showSuperIsland,
                    onCheckedChange = {
                        showSuperIsland = it
                        StorageManager.putBoolean(context, SUPER_ISLAND_SHOW_KEY, it)
                        ToastUtils.showShortToast(context, "功能开发中")
                    },
                )

                SwitchPreference(
                    title = "浮窗兼容",
                    summary = floatingWindowSummary,
                    checked = floatingWindowEnabled,
                    onCheckedChange = {
                        floatingWindowEnabled = it
                        if (it) notificationListEnabled = false
                        SuperIslandConfigUtils.setFloatingWindowEnabled(context, it)
                    },
                )

                SwitchPreference(
                    title = "超级岛列表模式",
                    summary = "平板默认开启，开启后系统外显仅保留一条超级岛，点击切换",
                    checked = notificationListEnabled,
                    onCheckedChange = {
                        notificationListEnabled = it
                        if (it) {
                            floatingWindowEnabled = false
                            SuperIslandConfigUtils.setFloatingWindowEnabled(context, false)
                        }
                        SuperIslandConfigUtils.setNotificationListMode(context, it)
                    },
                )

                WindowDropdownPreference(
                    title = "规范信息注入方式",
                    summary = "控制通知中注入的规范信息类型",
                    items = specInjectionOptions.map { it.first },
                    selectedIndex = specInjectionOptions.indexOfFirst { it.second == specInjectionMode },
                    onSelectedIndexChange = { index ->
                        if (index in specInjectionOptions.indices) {
                            specInjectionMode = specInjectionOptions[index].second
                            StorageManager.putInt(context, SPEC_INJECTION_MODE_KEY, specInjectionMode.ordinal)
                            if (specInjectionMode == SpecInjectionMode.SUPER_ISLAND) {
                                ToastUtils.showLongToast(context, "请通过lsp模块关闭超级岛白名单否则大概率无法展示")
                            }
                        }
                    },
                )

                ArrowPreference(
                    title = "测试超级岛分支",
                    onClick = {
                        showTestDialog.value = true
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SwitchPreference(
                    title = "镜像应用过滤",
                    summary = "过滤双向对称应用的远程超级岛复刻（仅本地也存在同包名超级岛时触发）",
                    checked = mirrorFilterEnabled,
                    onCheckedChange = {
                        mirrorFilterEnabled = it
                        StorageManager.putBoolean(context, MIRROR_FILTER_ENABLED_KEY, it)
                    },
                )

                Text(
                    "过滤包名列表",
                    style = textStyles.main,
                    color = colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                val installedPkgs = remember { AppRepository.getInstalledPackageNamesSync(context) }

                DEFAULT_MIRROR_PACKAGES.forEach { pkg ->
                    val isInstalled = installedPkgs.contains(pkg)
                    var iconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    val isEnabled = customPackages.find { it.packageName == pkg }?.enabled ?: true

                    LaunchedEffect(pkg) {
                        if (iconBitmap == null) {
                            iconBitmap = AppRepository.getAppIconAsync(context, pkg)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        iconBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            pkg,
                            style = textStyles.body2,
                            color = if (isInstalled) colorScheme.primary else colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { v ->
                                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                    DatabaseRepository.getInstance(context).upsertMirrorFilterPackage(
                                        SuperIslandMirrorFilterEntity(pkg, enabled = v),
                                    )
                                    withContext(Dispatchers.Main) {
                                        loadCustomPackages()
                                    }
                                }
                            },
                        )
                    }
                }

                customPackages.filter { it.packageName !in DEFAULT_MIRROR_PACKAGES }.forEach { pkgEntity ->
                    key(pkgEntity.packageName) {
                        val pkg = pkgEntity.packageName
                        val isInstalled = installedPkgs.contains(pkg)
                        var iconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                        var pkgEnabled by remember { mutableStateOf(pkgEntity.enabled) }

                        LaunchedEffect(pkg) {
                            if (iconBitmap == null) {
                                iconBitmap = AppRepository.getAppIconAsync(context, pkg)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            iconBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                pkg,
                                style = textStyles.body2,
                                color = if (isInstalled) colorScheme.primary else colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = pkgEnabled,
                                onCheckedChange = { v ->
                                    pkgEnabled = v
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                        DatabaseRepository.getInstance(context).setMirrorFilterEnabled(pkg, v)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                        DatabaseRepository.getInstance(context).deleteMirrorFilterPackage(pkg)
                                        withContext(Dispatchers.Main) {
                                            loadCustomPackages()
                                        }
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showAppPicker = true },
                    ) {
                        Text("选择应用")
                    }
                }
            }
        }
    }

    SuperIslandTestDialog(showTestDialog, context)

    if (showAppPicker) {
        AppPickerDialog(
            visible = true,
            onDismiss = { showAppPicker = false },
            onAppSelected = { pkg ->
                showAppPicker = false
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    DatabaseRepository.getInstance(context).upsertMirrorFilterPackage(
                        SuperIslandMirrorFilterEntity(pkg, enabled = true),
                    )
                    withContext(Dispatchers.Main) {
                        loadCustomPackages()
                    }
                }
            },
            title = "选择镜像过滤应用",
        )
    }
}
