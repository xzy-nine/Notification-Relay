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
import androidx.compose.material.icons.Icons // 可选，若用 Material Icons
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
        // 优化：仅首次启动或未完成必要权限时进入引导页，否则直接进入主界面（不渲染引导页）
        if (checkAllPermissions(this) && !isFirstLaunch) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            MiuixTheme {
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
    val prefs = context.getSharedPreferences("notifyrelay_prefs", Context.MODE_PRIVATE)
    val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)

    // Toast工具
    fun showToast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 权限检测方法
    fun refreshPermissions() {
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        hasNotification = enabledListeners.contains(context.packageName)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            "android:get_usage_stats",
            android.os.Process.myUid(),
            context.packageName
        )
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
        permissionsGranted = hasNotification && canQueryApps && hasPost
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知访问权限", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = hasNotification,
                            onCheckedChange = {
                                showToast("跳转通知访问授权页面")
                                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                context.startActivity(intent)
                                // 移除 refreshPermissions()，授权后由 onResume 刷新
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
                                try {
                                    val pm = context.packageManager
                                    val apps = pm.getInstalledApplications(0)
                                    canQueryApps = apps.size > 2
                                    showToast("已获取应用列表，数量：${apps.size}")
                                } catch (e: Exception) {
                                    canQueryApps = false
                                    showToast("获取应用列表失败：${e.message}")
                                }
                                // 保持原逻辑，应用列表权限本地可直接刷新
                                refreshPermissions()
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
                                }
                                // 移除 refreshPermissions()，授权后由 onResume 刷新
                            },
                            enabled = true
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (permissionsGranted) {
                        onContinue()
                    } else {
                        val missing = buildList {
                            if (!hasNotification) add("通知访问权限")
                            if (!canQueryApps) add("应用列表权限")
                            if (!hasPost) add("通知发送权限")
                        }.joinToString(", ")
                        if (missing.isNotEmpty()) {
                            showToast("请先授权: $missing")
                        }
                    }
                }) {
                    Text(if (permissionsGranted) "进入应用" else "请先完成必要权限的授权")
                }
            }
        }
    }
}

fun requestAllPermissions(activity: Activity) {
    // 通知访问权限
    val intentNotification = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    activity.startActivity(intentNotification)
    // 应用使用情况访问权限（应用列表权限）
    val intentUsage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    activity.startActivity(intentUsage)
    // 通知发送权限（Android 13+）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
    // 检查应用列表权限
    var canQueryApps = false
    try {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        canQueryApps = apps.size > 2
    } catch (e: Exception) {
        canQueryApps = false
    }
    // 检查通知发送权限
    val hasPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
    return hasNotification && canQueryApps && hasPost
}
