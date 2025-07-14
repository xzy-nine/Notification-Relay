package com.xzyht.notifyrelay

import com.xzyht.notifyrelay.data.NotificationRepository
import com.xzyht.notifyrelay.data.NotificationRecord
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xzyht.notifyrelay.R
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView

@Composable
fun NotificationCard(record: NotificationRecord, appName: String, appIcon: android.graphics.Bitmap?) {
    val notificationTextStyles = MiuixTheme.textStyles
    val cardColorScheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = cardColorScheme.surface,
        cornerRadius = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                top.yukonga.miuix.kmp.basic.Text(
                    text = appName,
                    style = notificationTextStyles.body2.copy(color = cardColorScheme.primary)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = record.text ?: "(无内容)",
                style = notificationTextStyles.body1.copy(color = cardColorScheme.onBackground)
            )
            Spacer(modifier = Modifier.height(4.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(record.time)),
                style = notificationTextStyles.body2.copy(color = cardColorScheme.outline)
            )
        }
    }
}

// 工具函数：获取应用名和图标（文件级顶层）
fun getAppNameAndIcon(context: android.content.Context, packageName: String?): Pair<String, android.graphics.Bitmap?> {
    var name = packageName ?: ""
    var icon: android.graphics.Bitmap? = null
    if (packageName != null) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            name = pm.getApplicationLabel(appInfo).toString()
            val drawable = pm.getApplicationIcon(appInfo)
            icon = drawableToBitmap(drawable)
        } catch (_: Exception) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(context.packageName, 0)
                name = pm.getApplicationLabel(appInfo).toString()
                val drawable = pm.getApplicationIcon(appInfo)
                icon = drawableToBitmap(drawable)
            } catch (_: Exception) {
                icon = null
            }
        }
    }
    return name to icon
}

// 工具函数：Drawable转Bitmap（文件级顶层）
fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        val bmp = drawable.bitmap
        if (bmp != null) return bmp
    }
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}


class NotificationHistoryFragment : Fragment() {
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): android.view.View? {
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    NotificationHistoryScreen()
                }
            }
        }
    }
}

@Composable
fun NotificationHistoryScreen() {
    val context = LocalContext.current
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    // 包名到应用名和图标的缓存
    val appInfoCache = remember { mutableStateMapOf<String, Pair<String, android.graphics.Bitmap?>>() }
    // 设置系统状态栏字体颜色
    LaunchedEffect(isDarkTheme) {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            val controller = androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            if (!isDarkTheme) {
                controller.isAppearanceLightStatusBars = true
            } else {
                controller.isAppearanceLightStatusBars = false
            }
        }
    }
    LaunchedEffect(Unit) {
        NotificationRepository.init(context)
    }
    var selectedDevice by remember { mutableStateOf(NotificationRepository.currentDevice) }
    val deviceList = NotificationRepository.deviceList
    val notifications by remember(selectedDevice) {
        derivedStateOf {
            NotificationRepository.getNotificationsByDevice(selectedDevice)
                .groupBy { Pair(it.packageName, it.key ) }
                .map { (_, list) -> list.maxByOrNull { it.time }!! }
        }
    }
    val textStyles = MiuixTheme.textStyles
    val colorScheme = MiuixTheme.colorScheme
    val notificationPermission = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    val listenerEnabled = enabledListeners?.contains(context.packageName) == true
    android.util.Log.i("NotifyRelay", "NotificationHistoryScreen 权限状态: POST_NOTIFICATIONS=$notificationPermission, ListenerEnabled=$listenerEnabled")
    val grouped = notifications.groupBy { it.packageName }
    val groupList = grouped.entries.map { (_, list) ->
        list.sortedByDescending { it.time }
    }
    // 混合排序：单条和分组都按分组最新时间降序排列
    val mixedList = groupList.sortedByDescending { it.firstOrNull()?.time ?: 0L }

    // 工具函数：获取并缓存应用名和图标
    fun getCachedAppInfo(packageName: String?): Pair<String, android.graphics.Bitmap?> {
        if (packageName == null) return "" to null
        return appInfoCache.getOrPut(packageName) {
            getAppNameAndIcon(context, packageName)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部分组与操作按钮始终显示
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
                        else ButtonDefaults.buttonColors(),
                        enabled = true // 设备切换始终可用
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
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay", "清除历史异常", e)
                            android.widget.Toast.makeText(
                                context,
                                "清除失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(),
                    enabled = notifications.isNotEmpty() // 空时禁用
                ) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = "清除",
                        style = textStyles.body2.copy(color = colorScheme.onBackground)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // 调试输出分组后通知数量
            top.yukonga.miuix.kmp.basic.Text(
                text = "通知分组后数量: ${notifications.size}",
                style = textStyles.body2.copy(color = colorScheme.primary)
            )
            if (notifications.isEmpty()) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "暂无通知",
                    style = textStyles.body1.copy(color = colorScheme.onBackground)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(mixedList) { list ->
                        if (list.size <= 2) {
                            // 单条或少量分组，直接渲染每条
                            list.forEach { record ->
                                val (appName, appIcon) = getCachedAppInfo(record.packageName)
                                NotificationCard(record, appName, appIcon)
                            }
                        } else {
                            // 多条分组，分组卡片
                            val latest = list.maxByOrNull { it.time }
                            var expanded by remember { mutableStateOf(false) }
                            val (appName, appIcon) = getCachedAppInfo(latest?.packageName)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .then(if (!expanded) Modifier.clickable { expanded = true } else Modifier),
                                color = colorScheme.surfaceContainer,
                                cornerRadius = 12.dp
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = if (expanded)
                                            Modifier.fillMaxWidth().clickable { expanded = false }
                                        else Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (appIcon != null) {
                                            Image(
                                                bitmap = appIcon.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = appName,
                                            style = textStyles.title3.copy(color = colorScheme.onBackground)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = "最新时间: " + (latest?.time?.let {
                                                java.text.SimpleDateFormat(
                                                    "yyyy-MM-dd HH:mm:ss"
                                                ).format(java.util.Date(it))
                                            } ?: ""),
                                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = if (expanded) "收起" else "展开",
                                            style = textStyles.body2.copy(color = colorScheme.primary)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val showList = if (expanded) list.sortedByDescending { it.time } else list.sortedByDescending { it.time }.take(3)
                                    if (!expanded) {
                                        showList.forEachIndexed { idx, record ->
                                            val (appNameItem, appIconItem) = getCachedAppInfo(record.packageName)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                top.yukonga.miuix.kmp.basic.Text(
                                                    text = record.title ?: "(无标题)",
                                                    style = textStyles.body2.copy(
                                                        color = androidx.compose.ui.graphics.Color(0xFF0066B2),
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                    ),
                                                    modifier = Modifier.weight(0.4f)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                top.yukonga.miuix.kmp.basic.Text(
                                                    text = record.text ?: "(无内容)",
                                                    style = textStyles.body2.copy(color = colorScheme.onBackground),
                                                    modifier = Modifier.weight(0.6f)
                                                )
                                            }
                                            if (idx < showList.lastIndex) {
                                                Divider(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    color = colorScheme.outline,
                                                    thickness = 1.dp
                                                )
                                            }
                                        }
                                        if (list.size > 3) {
                                            top.yukonga.miuix.kmp.basic.Text(
                                                text = "... 共${list.size}条，点击展开",
                                                style = textStyles.body2.copy(color = colorScheme.outline)
                                            )
                                        }
                                    } else {
                                        list.sortedByDescending { it.time }.forEach { record ->
                                            val (appName, appIcon) = getCachedAppInfo(record.packageName)
                                            NotificationCard(record, appName, appIcon)
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