package com.xzyht.notifyrelay.feature.guide

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import com.xzyht.notifyrelay.MainActivity
import com.xzyht.notifyrelay.common.PermissionHelper
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.core.util.AppListHelper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isFirstLaunch = StorageManager.getBoolean(this, "isFirstLaunch", true, StorageManager.PrefsType.GENERAL)
        val fromInternal = intent.getBooleanExtra("fromInternal", false)
        // 仅冷启动且权限满足时自动跳主界面，应用内跳转（fromInternal=true）始终渲染引导页
        if (!fromInternal && PermissionHelper.checkAllPermissions(this) && !isFirstLaunch) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
    // 沉浸式虚拟键，内容延伸到手势提示线区域
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    // 统一用 WindowCompat 控制系统栏外观，避免废弃API
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    // 颜色设置放到 Compose SideEffect 里统一管理

        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
            MiuixTheme(colors = colors) {
                val colorScheme = MiuixTheme.colorScheme
                // 统一在 Composable 作用域设置 window decor
                SideEffect {
                    val window = this@GuideActivity.window
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightNavigationBars = !isDarkTheme
                    controller.isAppearanceLightStatusBars = !isDarkTheme
                    window.statusBarColor = colorScheme.background.toArgb()
                    window.navigationBarColor = colorScheme.background.toArgb()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                }
                GuideScreen(onContinue = {
                    // 首次启动后标记为已启动
                    StorageManager.putBoolean(this@GuideActivity, "isFirstLaunch", false, StorageManager.PrefsType.GENERAL)
                    startActivity(Intent(this@GuideActivity, MainActivity::class.java))
                    finish()
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面回到前台时刷新权限状态
        // 通过 Compose 的全局事件通知 GuideScreen 刷新
        GuideScreen.refreshTrigger++
    }
}

object GuideScreen {
    // 用于触发刷新
    var refreshTrigger by mutableStateOf(0)
}

@Composable
fun GuideScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    var showCheck by remember { mutableStateOf(false) }
    var hasNotification by remember { mutableStateOf(false) }
    var hasUsage by remember { mutableStateOf(false) }
    var hasPost by remember { mutableStateOf(false) }
    var canQueryApps by remember { mutableStateOf(false) }
    // 可选权限状态
    var hasFloatNotification by remember { mutableStateOf(false) }
    var hasDevScreenShareProtectOff by remember { mutableStateOf(false) }

    var hasBluetoothConnect by remember { mutableStateOf(false) }
    // Android 15+ 敏感通知权限
    var hasSensitiveNotification by remember { mutableStateOf(true) }
    // 自启动权限状态
    var hasSelfStart by remember { mutableStateOf(false) }
    // 后台无限制权限状态 (可选)
    var hasBackgroundUnlimited by remember { mutableStateOf(false) }
    // Toast工具
    fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 权限检测方法（使用 PermissionHelper 和 AppListHelper）
    fun refreshPermissions() {
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        hasNotification = enabledListeners?.contains(context.packageName) == true

        // 使用 PermissionHelper 检查使用情况权限
        hasUsage = PermissionHelper.isUsageStatsEnabled(context)

        // 使用 PermissionHelper 检查通知发送权限
        hasPost = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        // 使用 AppListHelper 检查应用列表权限
        canQueryApps = AppListHelper.canQueryApps(context)

        // 检查悬浮通知权限
        hasFloatNotification = PermissionHelper.checkOverlayPermission(context)

        // 检查开发者选项-停用屏幕共享保护
        hasDevScreenShareProtectOff = try {
            val value = android.provider.Settings.Global.getInt(context.contentResolver, "disable_screen_sharing_protection", 0)
            value == 1
        } catch (_: Exception) { false }

        // 使用 PermissionHelper 检查敏感通知权限
        hasSensitiveNotification = PermissionHelper.checkSensitiveNotificationPermission(context)

        // 使用 PermissionHelper 检查蓝牙连接权限
        hasBluetoothConnect = PermissionHelper.checkBluetoothConnectPermission(context)

        // 使用 PermissionHelper 检查后台无限制权限
        hasBackgroundUnlimited = PermissionHelper.checkBackgroundUnlimitedPermission(context)

        // 使用 PermissionHelper 检查自启动权限（通过通知监听器启用状态间接验证）
        hasSelfStart = PermissionHelper.checkNotificationListenerServiceCanStart(context)

        permissionsGranted = hasNotification && canQueryApps && hasPost && hasSelfStart

        showCheck = permissionsGranted
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
    }
    // 监听 GuideActivity 的刷新事件
    val trigger = GuideScreen.refreshTrigger
    LaunchedEffect(trigger) {
        refreshPermissions()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(20.dp)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "欢迎使用通知转发应用",
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 权限状态列表（使用 Miuix BasicComponent 展示，紧凑分割线风格）
                Column(modifier = Modifier.fillMaxWidth()) {
                    val dividerColor = MiuixTheme.colorScheme.dividerLine
                    BasicComponent(
                        title = "通知访问权限",
                        summary = if (hasNotification) "已授权" else "用于读取通知内容，实现转发功能",
                        rightActions = {
                            Switch(
                                checked = hasNotification,
                                onCheckedChange = {
                                    showToast("跳转通知访问授权页面")
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    context.startActivity(intent)
                                },
                                enabled = true
                            )
                        },
                        onClick = {
                            showToast("跳转通知访问授权页面")
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(vertical = 0.dp)
                    )
                    top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "应用列表权限",
            summary = if (canQueryApps) "已授权" else "用于发现本机已安装应用，辅助通知跳转",
            rightActions = {
                Switch(
                    checked = canQueryApps,
                    onCheckedChange = {
                        try {
                            val isMiuiOrPengpai = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                                try {
                                    val permissionInfo = context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
                                    permissionInfo != null && permissionInfo.packageName == "com.lbe.security.miui"
                                } catch (_: Exception) { false }
                            if (isMiuiOrPengpai) {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, "com.android.permission.GET_INSTALLED_APPS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    (context as? Activity)?.let { act ->
                                        androidx.core.app.ActivityCompat.requestPermissions(
                                            act,
                                            arrayOf("com.android.permission.GET_INSTALLED_APPS"),
                                            999
                                        )
                                        showToast("已请求应用列表权限，请在弹窗中允许")
                                    } ?: run {
                                        showToast("请在应用信息页面的权限管理-其他权限中允许<访问应用列表>")
                                    }
                                } else {
                                    showToast("已获得应用列表权限")
                                }
                            } else {
                                showToast("请在应用信息页面的权限管理-其他权限中允许<访问应用列表>")
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = android.net.Uri.parse("package:" + context.packageName)
                                context.startActivity(intent)
                            }
                        } catch (_: Exception) {
                            showToast("请在应用信息页面的权限管理-其他权限中允许<访问应用列表>")
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = android.net.Uri.parse("package:" + context.packageName)
                            context.startActivity(intent)
                        }
                    },
                    enabled = true
                )
            },
            onClick = {
                showToast("请在应用信息页面的权限管理-其他权限中允许<访问应用列表>")
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:" + context.packageName)
                context.startActivity(intent)
            },
            modifier = Modifier.padding(vertical = 0.dp)
        )
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "通知发送权限 (Android 13+)",
            summary = if (hasPost) "已授权" else "用于发送本地通知，部分功能需开启",
            rightActions = {
                Switch(
                    checked = hasPost,
                    onCheckedChange = {
                        showToast("请求通知发送权限")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            (context as? Activity)?.requestPermissions(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                            )
                        } else {
                            showToast("请在系统设置中开启通知权限")
                        }
                    },
                    enabled = true
                )
            },
            onClick = {
                showToast("请求通知发送权限")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    (context as? Activity)?.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                    )
                } else {
                    showToast("请在系统设置中开启通知权限")
                }
            },
            modifier = Modifier.padding(vertical = 0.dp)
        )
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "蓝牙连接权限 (可选)",
            summary = if (hasBluetoothConnect) "已授权" else "用于优化设备发现速度，显示真实设备名",
            summaryColor = top.yukonga.miuix.kmp.basic.BasicComponentColors(
                color = Color(0xFF888888),
                disabledColor = Color(0xFFCCCCCC)
            ),
            rightActions = {
                Switch(
                    checked = hasBluetoothConnect,
                    onCheckedChange = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            (context as? Activity)?.requestPermissions(
                                arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1001
                            )
                            showToast("开启后可优化设备发现速度，并以设备实际名称而非型号作为设备名")
                        } else {
                            showToast("当前系统无需蓝牙连接权限")
                        }
                    },
                    enabled = true
                )
            },
            enabled = true,
            modifier = Modifier.padding(vertical = 0.dp)
        )
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "后台无限制权限 (可选)",
            summary = if (hasBackgroundUnlimited) "已设置" else "用于确保应用在后台正常运行，防止被系统杀死",
            summaryColor = top.yukonga.miuix.kmp.basic.BasicComponentColors(
                color = Color(0xFF888888),
                disabledColor = Color(0xFFCCCCCC)
            ),
            rightActions = {
                Switch(
                    checked = hasBackgroundUnlimited,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showToast("跳转到电池优化设置，请将应用设为无限制")
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = android.net.Uri.parse("package:" + context.packageName)
                            context.startActivity(intent)
                        } else {
                            hasBackgroundUnlimited = false
                        }
                    },
                    enabled = true
                )
            },
            onClick = {
                showToast("跳转到电池优化设置，请将应用设为无限制")
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:" + context.packageName)
                context.startActivity(intent)
            },
            enabled = true,
            modifier = Modifier.padding(vertical = 0.dp)
        )
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "悬浮通知权限 (可选)",
            summary = "请手动选择并打开具体的通知类别的悬浮通知权限，以提升通知体验",
            summaryColor = top.yukonga.miuix.kmp.basic.BasicComponentColors(
                color = Color(0xFF888888),
                disabledColor = Color(0xFFCCCCCC)
            ),
            rightActions = {
                Button(
                    onClick = {
                        showToast("请在系统设置-通知-通知分组中管理本应用的通知分组")
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 32.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    Text("管理通知分组", fontSize = 14.sp)
                }
            },
            enabled = true,
            modifier = Modifier.padding(vertical = 0.dp)
        )
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "敏感通知访问权限 (Android 15+，可选)",
            summary = "未授权时部分通知内容只能获取到'已隐藏敏感通知',因此应用予以隐藏，建议开启以完整接收通知。如无法跳转可复制下方 adb 命令授权。",
            // 不再用 rightActions，按钮放 summary 下方
            onClick = {
                if (!hasSensitiveNotification) {
                    PermissionHelper.requestSensitiveNotificationPermission(context as Activity)
                }
            },
            enabled = true,
            modifier = Modifier.padding(vertical = 0.dp)
        )
        if (!hasSensitiveNotification) {
            // 按钮放在描述下方，横向一行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        PermissionHelper.requestSensitiveNotificationPermission(context as Activity)
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 96.dp, minHeight = 32.dp)
                ) {
                    Text("去设置", fontSize = 14.sp)
                }
                Button(
                    onClick = {
                        val adbCmd = "adb shell appops set ${context.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("adb", adbCmd)
                        clipboard.setPrimaryClip(clip)
                        showToast("已复制adb命令到剪贴板")
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 96.dp, minHeight = 32.dp)
                ) {
                    Text("复制adb命令", fontSize = 14.sp)
                }
            }
        }
        top.yukonga.miuix.kmp.basic.HorizontalDivider(color = dividerColor, thickness = 1.dp)
        BasicComponent(
            title = "自启动权限",
            summary = if (hasSelfStart) "已启用" else "必须启用，否则监听服务无法启动",
            rightActions = {
                Switch(
                    checked = hasSelfStart,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showToast("请在应用详情页启用自启动权限")
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = android.net.Uri.parse("package:" + context.packageName)
                            context.startActivity(intent)
                        } else {
                            hasSelfStart = false
                        }
                    },
                    enabled = true
                )
            },
            onClick = {
                showToast("请在应用详情页启用自启动权限")
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:" + context.packageName)
                context.startActivity(intent)
            },
            modifier = Modifier.padding(vertical = 0.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        top.yukonga.miuix.kmp.basic.Button(onClick = {
            if (permissionsGranted) {
                onContinue()
            } else {
                val missing = buildList {
                    if (!hasNotification) add("获取通知访问权限")
                    if (!canQueryApps) add("获取应用列表权限")
                    if (!hasPost) add("获取通知发送权限")
                    if (!hasSelfStart) add("启用自启动权限")
                }.joinToString(", ")
                if (missing.isNotEmpty()) {
                    showToast("请先授权: $missing")
                }
            }
        }) {
            top.yukonga.miuix.kmp.basic.Text(
                if (permissionsGranted) "进入应用" else "请先完成必要权限授权",
                style = MiuixTheme.textStyles.button
            )
        }
            }
        }
    }
}
}

fun requestAllPermissions(activity: Activity) {
    PermissionHelper.requestAllPermissions(activity)
}