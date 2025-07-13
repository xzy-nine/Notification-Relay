package com.xzyht.notifyrelay

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.useful.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.ui.platform.LocalContext
import com.xzyht.notifyrelay.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 权限检查，未授权则跳转引导页
        if (!checkAllPermissions(this)) {
            android.widget.Toast.makeText(this, "请先授权所有必要权限！", android.widget.Toast.LENGTH_SHORT).show()
            // 检查通知监听权限
            if (!isNotificationListenerEnabled()) {
                android.widget.Toast.makeText(this, "请在系统设置中授权通知访问权限！", android.widget.Toast.LENGTH_LONG).show()
                val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            }
            // 检查通知发送权限（Android 13+）
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
                }
            }
            // 检查应用使用情况权限
            if (!isUsageStatsEnabled()) {
                android.widget.Toast.makeText(this, "请在系统设置中授权应用使用情况访问权限！", android.widget.Toast.LENGTH_LONG).show()
                val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
            val intent = android.content.Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            startActivity(intent)
            finish()
            return
        }
        // 启动时加载本地历史通知
        NotificationRepository.init(this)
        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
            MiuixTheme(colors = colors) {
                MainApp()
            }
        }
    }

    // 检查通知监听权限
    private fun isNotificationListenerEnabled(): Boolean {
        val cn = android.content.ComponentName(this, "com.xzyht.notifyrelay.NotifyRelayNotificationListenerService")
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    // 检查应用使用情况权限
    @Suppress("DEPRECATION")
    private fun isUsageStatsEnabled(): Boolean {
        val appOps = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}

@Composable
fun MainApp() {
    var selectedTab by remember { mutableStateOf(0) }
    val items = listOf(
        NavigationItem("设备与转发", MiuixIcons.Useful.Settings),
        NavigationItem("通知历史", MiuixIcons.Basic.Check)
    )
    Scaffold(
        bottomBar = {
            NavigationBar(
                items = items,
                selected = selectedTab,
                onClick = { selectedTab = it }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> DeviceForwardScreen()
                1 -> NotificationHistoryScreen()
            }
        }
    }
}

@Composable
fun DeviceForwardScreen() {
    val textStyles = MiuixTheme.textStyles
    top.yukonga.miuix.kmp.basic.Text(
        text = "设备与转发设置页",
        style = textStyles.body1
    )
}

@Composable
fun NotificationHistoryScreen() {
    val context = LocalContext.current
    // 每次进入页面时同步本地存储
    // LaunchedEffect(Unit) {
    //     NotificationRepository.init(context)
    // }
    var selectedDevice by remember { mutableStateOf(NotificationRepository.currentDevice) }
    val deviceList = NotificationRepository.deviceList
    var notifications by remember { mutableStateOf(NotificationRepository.getNotificationsByDevice(selectedDevice)) }
    // 移除媒体通知分组与置顶逻辑，所有通知统一处理
    val textStyles = MiuixTheme.textStyles
    val colorScheme = MiuixTheme.colorScheme
    val pm = context.packageManager
    // 用 BitmapFactory 强制加载 PNG 作为默认图标，避免 XML/VectorDrawable 问题
    val defaultIconBitmap = remember {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)?.asImageBitmap()
    }
    // 权限检测日志输出
    val notificationPermission = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    val listenerEnabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
    android.util.Log.i("NotifyRelay", "NotificationHistoryScreen 权限状态: POST_NOTIFICATIONS=$notificationPermission, ListenerEnabled=$listenerEnabled")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            top.yukonga.miuix.kmp.basic.Text(
                text = "通知历史",
                style = textStyles.title2.copy(color = colorScheme.onBackground)
            )
            Row {
                deviceList.forEach { device ->
                    Button(
                        onClick = {
                            selectedDevice = device
                            NotificationRepository.currentDevice = device
                        },
                        colors = if (selectedDevice == device) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors()
                    ) {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = device,
                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        try {
                            NotificationRepository.clearDeviceHistory(selectedDevice, context)
                            // 清除后刷新通知列表
                            notifications = NotificationRepository.getNotificationsByDevice(selectedDevice)
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay", "清除历史异常", e)
                            android.widget.Toast.makeText(context, "清除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors()
                ) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = "清除",
                        style = textStyles.body2.copy(color = colorScheme.onBackground)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (notifications.isEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(
                text = "暂无通知",
                style = textStyles.body1.copy(color = colorScheme.onBackground)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // 所有通知统一展示，无媒体置顶分组和专属UI
                items(notifications) { record ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val iconBitmap = remember(record.packageName) {
                                try {
                                    pm.getApplicationInfo(record.packageName, 0)
                                    val appIcon = pm.getApplicationIcon(record.packageName)
                                    when (appIcon) {
                                        is android.graphics.drawable.BitmapDrawable -> appIcon.bitmap.asImageBitmap()
                                        else -> {
                                            val bmp = android.graphics.Bitmap.createBitmap(
                                                appIcon.intrinsicWidth.takeIf { it > 0 } ?: 40,
                                                appIcon.intrinsicHeight.takeIf { it > 0 } ?: 40,
                                                android.graphics.Bitmap.Config.ARGB_8888
                                            )
                                            val canvas = android.graphics.Canvas(bmp)
                                            appIcon.setBounds(0, 0, canvas.width, canvas.height)
                                            appIcon.draw(canvas)
                                            bmp.asImageBitmap()
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("NotifyRelay", "iconerr: 图标加载异常, 包名=${record.packageName}", e)
                                    defaultIconBitmap
                                }
                            }
                            val finalBitmap = iconBitmap ?: defaultIconBitmap
                            Box(modifier = Modifier.size(40.dp).padding(end = 8.dp)) {
                                if (finalBitmap != null) {
                                    Image(
                                        bitmap = finalBitmap,
                                        contentDescription = record.packageName,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                // smallIcon实际绘制（跨包获取Drawable并转Bitmap）
                                val smallIconResId = record.smallIconResId
                                if (smallIconResId != null && smallIconResId != 0) {
                                    val smallIconBitmap = try {
                                        val res = context.packageManager.getResourcesForApplication(record.packageName)
                                        val drawable = res.getDrawable(smallIconResId, null)
                                        if (drawable != null) {
                                            val bmp = android.graphics.Bitmap.createBitmap(
                                                drawable.intrinsicWidth.takeIf { it > 0 } ?: 20,
                                                drawable.intrinsicHeight.takeIf { it > 0 } ?: 20,
                                                android.graphics.Bitmap.Config.ARGB_8888
                                            )
                                            val canvas = android.graphics.Canvas(bmp)
                                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                                            drawable.draw(canvas)
                                            bmp
                                        } else null
                                    } catch (e: Exception) { null }
                                    if (smallIconBitmap != null) {
                                        Image(
                                            bitmap = smallIconBitmap.asImageBitmap(),
                                            contentDescription = "smallIcon",
                                            modifier = Modifier
                                                .size(20.dp)
                                                .align(Alignment.BottomEnd)
                                        )
                                    }
                                }
                            }
                            Column(modifier = Modifier.padding(12.dp)) {
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = record.title ?: "(无标题)",
                                    style = textStyles.title4.copy(color = colorScheme.onBackground)
                                )
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = record.text ?: "(无内容)",
                                    style = textStyles.body2.copy(color = colorScheme.onBackground)
                                )
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = "包名: ${record.packageName}",
                                    style = textStyles.footnote1.copy(color = colorScheme.onBackground)
                                )
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = "时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(record.time))}",
                                    style = textStyles.footnote2.copy(color = colorScheme.onBackground)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainAppPreview() {
    MiuixTheme {
        MainApp()
    }
}