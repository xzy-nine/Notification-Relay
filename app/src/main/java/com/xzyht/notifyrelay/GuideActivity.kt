package com.xzyht.notifyrelay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Switch

class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("notifyrelay_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)
        val fromInternal = intent.getBooleanExtra("fromInternal", false)
        // 仅冷启动且权限满足时自动跳主界面，应用内跳转（fromInternal=true）始终渲染引导页
        if (!fromInternal && checkAllPermissions(this) && !isFirstLaunch) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
            MiuixTheme(colors = colors) {
                GuideScreen(onContinue = {
                    // 首次启动后标记为已启动
                    prefs.edit().putBoolean("isFirstLaunch", false).apply()
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
    // Toast工具
    fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 权限检测方法（去除自动跳转，仅检测状态）
    fun refreshPermissions() {
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        hasNotification = enabledListeners?.contains(context.packageName) == true
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            appOps.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            val compatMode = androidx.core.app.AppOpsManagerCompat.noteOp(
                context,
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName
            )
            if (compatMode == androidx.core.app.AppOpsManagerCompat.MODE_ALLOWED) {
                android.app.AppOpsManager.MODE_ALLOWED
            } else {
                android.app.AppOpsManager.MODE_IGNORED
            }
        }
        hasUsage = mode == android.app.AppOpsManager.MODE_ALLOWED
        hasPost = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            canQueryApps = apps.size > 2
        } catch (e: Exception) {
            canQueryApps = false
        }
        // 检查悬浮通知权限（临时通知分组）
        hasFloatNotification = try {
            android.provider.Settings.canDrawOverlays(context)
        } catch (_: Exception) { false }
        // 检查开发者选项-停用屏幕共享保护
        hasDevScreenShareProtectOff = try {
            val value = android.provider.Settings.Global.getInt(context.contentResolver, "disable_screen_sharing_protection", 0)
            value == 1
        } catch (_: Exception) { false }
        permissionsGranted = hasNotification && canQueryApps && hasPost
        // 检查蓝牙连接权限（Android 12+）
        hasBluetoothConnect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("欢迎使用通知转发应用", fontSize = 24.sp)
                Spacer(modifier = Modifier.height(16.dp))
                // 权限状态列表
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ...existing code...
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知访问权限", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = hasNotification,
                            onCheckedChange = {
                                showToast("跳转通知访问授权页面")
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            },
                            enabled = true
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("应用列表权限", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = canQueryApps,
                            onCheckedChange = {
                                showToast("请在应用信息页面的权限管理-其他权限中允许<访问应用列表>")
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = android.net.Uri.parse("package:" + context.packageName)
                                context.startActivity(intent)
                            },
                            enabled = true
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知发送权限 (Android 13+)", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
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
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("蓝牙连接权限 (可选)", fontSize = 16.sp, color = Color(0xFF888888))
                        Spacer(modifier = Modifier.width(8.dp))
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
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("临时通知分组设置 (可选)", fontSize = 16.sp, color = Color(0xFF888888))
                        Spacer(modifier = Modifier.width(8.dp))
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
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开发者选项-停用屏幕共享保护 (可选)", fontSize = 16.sp, color = Color(0xFF888888))
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                showToast("请在开发者选项中启用'停用屏幕共享保护'以获得更好体验")
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 32.dp)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text("设置屏幕共享保护", fontSize = 14.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (permissionsGranted) {
                        onContinue()
                    } else {
                        val missing = buildList {
                            if (!hasNotification) add("获取通知访问权限")
                            if (!canQueryApps) add("获取应用列表权限")
                            if (!hasPost) add("获取通知发送权限")
                            if (!hasBluetoothConnect) add("获取蓝牙连接权限")
                        }.joinToString(", ")
                        if (missing.isNotEmpty()) {
                            showToast("请先授权: $missing")
                        }
                    }
                }) {
                    Text(if (permissionsGranted) "进入应用" else "请先完成必要权限授权")
                }
            }
        }
    }
}

fun requestAllPermissions(activity: Activity) {
    // 判断是否为 MIUI/澎湃系统
    var isMiuiOrPengpai = false
    try {
        val permissionInfo = activity.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
        if (permissionInfo.packageName == "com.lbe.security.miui") {
            isMiuiOrPengpai = true
        }
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {}

    // 通知访问权限
    val intentNotification = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    activity.startActivity(intentNotification)

    if (isMiuiOrPengpai) {
        // MIUI/澎湃优先动态申请应用列表权限
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, "com.android.permission.GET_INSTALLED_APPS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf("com.android.permission.GET_INSTALLED_APPS"), 999)
        }
    } else {
        // 非 MIUI/澎湃，使用原生应用使用情况访问权限
        val intentUsage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        activity.startActivity(intentUsage)
    }

    // 通知发送权限（Android 13+）
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
    }
}

fun checkAllPermissions(context: Context): Boolean {
    // 检查通知监听
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: ""
    val hasNotification = enabledListeners.contains(context.packageName)

    // 判断是否为 MIUI/澎湃系统
    var isMiuiOrPengpai = false
    try {
        val permissionInfo = context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
        if (permissionInfo.packageName == "com.lbe.security.miui") {
            isMiuiOrPengpai = true
        }
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {}

    // 检查应用列表权限
    var canQueryApps: Boolean
    try {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        canQueryApps = apps.size > 2
        if (isMiuiOrPengpai) {
            canQueryApps = canQueryApps && (androidx.core.content.ContextCompat.checkSelfPermission(context, "com.android.permission.GET_INSTALLED_APPS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    } catch (e: Exception) {
        canQueryApps = false
    }

    // 检查通知发送权限
    val hasPost = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
    return hasNotification && canQueryApps && hasPost
}
