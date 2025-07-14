package com.xzyht.notifyrelay

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
                controller?.isAppearanceLightStatusBars = true
            } else {
                // 深色模式，状态栏字体用浅色
                controller?.isAppearanceLightStatusBars = false
            }
        }
    }
    var selectedDevice by remember { mutableStateOf(NotificationRepository.currentDevice) }
    val deviceList = NotificationRepository.deviceList
    var notifications by remember {
        mutableStateOf(
            NotificationRepository.getNotificationsByDevice(selectedDevice)
                .groupBy { Pair(it.packageName, it.key ?: "") }
                .map { (_, list) -> list.maxByOrNull { it.time }!! }
        )
    }
    val textStyles = MiuixTheme.textStyles
    val colorScheme = MiuixTheme.colorScheme
    val pm = context.packageManager
    val defaultIconBitmap = remember {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)?.asImageBitmap()
    }
    val notificationPermission = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    val listenerEnabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
    android.util.Log.i("NotifyRelay", "NotificationHistoryScreen 权限状态: POST_NOTIFICATIONS=$notificationPermission, ListenerEnabled=$listenerEnabled")
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    val grouped = notifications.groupBy { it.packageName }
    val groupList = grouped.entries.map { (pkg, list) ->
        pkg to list.sortedByDescending { it.time }
    }.sortedByDescending { it.second.firstOrNull()?.time ?: 0L }
    val singleList = groupList.filter { it.second.size <= 2 }.flatMap { it.second }
    val multiGroups = groupList.filter { it.second.size > 2 }

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
                            notifications = NotificationRepository.getNotificationsByDevice(selectedDevice)
                                .groupBy { Pair(it.packageName, it.key ?: "") }
                                .map { (_, list) -> list.maxByOrNull { it.time }!! }
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
                            notifications = NotificationRepository.getNotificationsByDevice(selectedDevice)
                                .groupBy { Pair(it.packageName, it.key ?: "") }
                                .map { (_, list) -> list.maxByOrNull { it.time }!! }
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay", "清除历史异常", e)
                            android.widget.Toast.makeText(context, "清除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (notifications.isEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(
                text = "暂无通知",
                style = textStyles.body1.copy(color = colorScheme.onBackground)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ...existing code...
                items(multiGroups) { (pkg, list) ->
                    // ...existing code...
                }
                items(singleList) { record ->
                    // ...existing code...
                }
            }
        }
    }
}