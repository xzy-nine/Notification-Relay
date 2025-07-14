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
                            notifications = NotificationRepository.getNotificationsByDevice(selectedDevice)
                                .groupBy { Pair(it.packageName, it.key ?: "") }
                                .map { (_, list) -> list.maxByOrNull { it.time }!! }
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
                items(multiGroups) { (pkg, list) ->
                    val isExpanded = expandedGroups.contains(pkg)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        cornerRadius = 12.dp,
                        pressFeedbackType = PressFeedbackType.Sink,
                        showIndication = true,
                        onClick = {
                            expandedGroups = if (isExpanded) expandedGroups - pkg else expandedGroups + pkg
                        }
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedGroups = if (isExpanded) expandedGroups - pkg else expandedGroups + pkg
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val iconBitmap = remember(pkg) {
                                    try {
                                        pm.getApplicationInfo(pkg, 0)
                                        val appIcon = pm.getApplicationIcon(pkg)
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
                                        defaultIconBitmap
                                    }
                                }
                                val finalBitmap = iconBitmap ?: defaultIconBitmap
                                Box(modifier = Modifier.size(40.dp).padding(end = 8.dp)) {
                                    if (finalBitmap != null) {
                                        Image(
                                            bitmap = finalBitmap,
                                            contentDescription = pkg,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = pkg,
                                    style = textStyles.body2.copy(color = colorScheme.onBackground)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = if (isExpanded) "收起" else "展开",
                                    style = textStyles.footnote2.copy(color = colorScheme.primary)
                                )
                            }
                            if (!isExpanded) {
                                list.take(3).forEach { record ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = (record.title ?: "(无标题)") + " ",
                                            style = textStyles.title4.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = colorScheme.onBackground),
                                            maxLines = 1
                                        )
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = record.text ?: "(无内容)",
                                            style = textStyles.body2.copy(color = colorScheme.onBackground),
                                            maxLines = 1
                                        )
                                    }
                                }
                                if (list.size > 3) {
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = "...共${list.size}条，点击展开",
                                        style = textStyles.footnote2.copy(color = colorScheme.outline),
                                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                                    )
                                }
                            } else {
                                list.forEach { record ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        cornerRadius = 8.dp,
                                        pressFeedbackType = PressFeedbackType.Sink,
                                        showIndication = true,
                                        onClick = {
                                            // 可扩展：通知详情、批量操作等
                                        }
                                    ) {
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
                items(singleList) { record ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        cornerRadius = 8.dp,
                        pressFeedbackType = PressFeedbackType.Sink,
                        showIndication = true,
                        onClick = {
                            // 可扩展：通知详情、批量操作等
                        }
                    ) {
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