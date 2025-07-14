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
fun NotificationCard(record: NotificationRecord) {
    val notificationTextStyles = MiuixTheme.textStyles
    val cardColorScheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = cardColorScheme.surface,
        cornerRadius = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            top.yukonga.miuix.kmp.basic.Text(
                text = record.title ?: "(无标题)",
                style = notificationTextStyles.body2.copy(color = cardColorScheme.primary)
            )
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
    // 设置系统状态栏字体颜色
    LaunchedEffect(isDarkTheme) {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            val controller = androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            if (!isDarkTheme) {
                // 浅色模式，状态栏字体用深色
                controller.isAppearanceLightStatusBars = true
            } else {
                // 深色模式，状态栏字体用浅色
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
                .groupBy { Pair(it.packageName, it.key ?: "") }
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
    }.sortedByDescending { it.firstOrNull()?.time ?: 0L }
    val singleList = groupList.filter { it.size <= 2 }.flatMap { it }
    val multiGroups = groupList.filter { it.size > 2 }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            // MiuixTheme.shapes.medium 未定义，直接用默认圆角
            // CardDefaults.cardColors 未定义，直接用 color 参数
            // 这里用 MiuixTheme.colorScheme.surfaceContainer 作为背景色
            // 直接用 Card(color = ...)
            color = colorScheme.surfaceContainer,
            cornerRadius = 16.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = if (listenerEnabled) MiuixIcons.Basic.Check else Icons.Filled.Warning
                Button(
                    onClick = {
                        // 跳转系统通知监听设置
                        val intent =
                            android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        context.startActivity(intent)
                    },
                    colors = if (listenerEnabled) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    enabled = true
                ) {
                    top.yukonga.miuix.kmp.basic.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (listenerEnabled) colorScheme.primary else Color(0xFFF44336),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    top.yukonga.miuix.kmp.basic.Text(
                        text = if (listenerEnabled) "通知监听服务已启用" else "通知监听服务未启用",
                        style = textStyles.body2.copy(
                            color = if (listenerEnabled) colorScheme.primary else Color(
                                0xFFF44336
                            )
                        )
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                val permIcon =
                    if (notificationPermission) MiuixIcons.Basic.Check else Icons.Filled.Warning
                top.yukonga.miuix.kmp.basic.Icon(
                    imageVector = permIcon,
                    contentDescription = null,
                    tint = if (notificationPermission) colorScheme.primary else Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                top.yukonga.miuix.kmp.basic.Text(
                    text = if (notificationPermission) "通知权限已授权" else "通知权限未授权",
                    style = textStyles.body2.copy(
                        color = if (notificationPermission) colorScheme.primary else Color(
                            0xFFF44336
                        )
                    )
                )
            }
        }
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
                    // 渲染单条/少量分组（<=2条）
                    items(singleList) { record ->
                        NotificationCard(record)
                    }
                    // 渲染多条分组（>2条），分组块以分组最新时间排序
                    items(multiGroups) { list ->
                        val latest = list.maxByOrNull { it.time }
                        var expanded by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = colorScheme.surfaceContainer,
                            cornerRadius = 12.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = latest?.packageName ?: "",
                                        style = textStyles.title3.copy(color = colorScheme.primary)
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
                                showList.forEach { record ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = (record.title ?: "(无标题)") + " ",
                                            style = textStyles.body2.copy(color = colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        )
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = record.text ?: "(无内容)",
                                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                                        )
                                    }
                                }
                                if (!expanded && list.size > 3) {
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = "... 共${list.size}条，点击展开",
                                        style = textStyles.body2.copy(color = colorScheme.outline)
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