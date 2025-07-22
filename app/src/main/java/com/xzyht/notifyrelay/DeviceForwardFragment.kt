package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo

class DeviceForwardFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    DeviceForwardScreen()
                }
            }
        }
    }
}

@Composable
fun DeviceForwardScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val deviceManager = remember { DeviceConnectionManager() }
    val devices by deviceManager.devices.collectAsState()
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var chatInput by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    // 用于接收消息的回调，实际项目建议用事件流
    LaunchedEffect(Unit) {
        deviceManager.startDiscovery()
    }

    // 简单地将onNotificationDataReceived挂钩到UI
    DisposableEffect(Unit) {
        val oldHandler = deviceManager.onNotificationDataReceived
        deviceManager.onNotificationDataReceived = { data ->
            chatHistory = chatHistory + "收到: $data"
            oldHandler?.invoke(data)
        }
        onDispose {
            deviceManager.onNotificationDataReceived = oldHandler
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // 横屏：原有左右分栏
        Box(
            modifier = Modifier.fillMaxSize().background(colorScheme.background)
        ) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                // 设备列表
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(devices) { device ->
                            DeviceItem(
                                device = device,
                                onConnect = {
                                    showConfirmDialog = device
                                },
                                onSelect = {
                                    selectedDevice = device
                                    android.widget.Toast.makeText(
                                        context,
                                        "UUID: ${device.uuid}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                selected = selectedDevice?.uuid == device.uuid
                            )
                        }
                    }
                }
                // 聊天框
                androidx.compose.foundation.layout.Box(Modifier.weight(2f).fillMaxSize().background(colorScheme.surface)) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        Text("聊天测试", style = textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                            items(chatHistory) { msg ->
                                Text(msg, style = textStyles.body2)
                            }
                        }
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                            androidx.compose.material3.OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入消息...") }
                            )
                            Button(
                                onClick = {
                                    val dev = selectedDevice
                                    if (dev != null && chatInput.isNotBlank()) {
                                        deviceManager.sendNotificationData(dev, chatInput)
                                        chatHistory = chatHistory + "发送: $chatInput"
                                        chatInput = ""
                                    }
                                },
                                enabled = selectedDevice != null && chatInput.isNotBlank(),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
            }
            if (showConfirmDialog != null) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = null },
                    title = { Text("连接确认") },
                    text = { Text("是否连接到设备：${showConfirmDialog?.displayName}？") },
                    confirmButton = {
                        TextButton(onClick = {
                            connectingDevice = showConfirmDialog
                            showConfirmDialog = null
                            connectingDevice?.let { deviceManager.connectToDevice(it) }
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                    }
                )
            }
        }
    } else {
        // 竖屏：设备列表横排在顶部，聊天区在下方
        Box(
            modifier = Modifier.fillMaxSize().background(colorScheme.background)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().background(colorScheme.background).padding(16.dp)
            ) {
                Text(
                    text = "设备与转发",
                    style = textStyles.title2.copy(color = colorScheme.onBackground)
                )
                // 设备按钮区固定高度为1/5，超出可横向滚动
                val deviceBarWeight = 0.2f
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(deviceBarWeight)
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxSize().padding(top = 12.dp, bottom = 8.dp)
                    ) {
                        items(devices) { device ->
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier.padding(end = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = {
                                        selectedDevice = device
                                        android.widget.Toast.makeText(
                                            context,
                                            "UUID: ${device.uuid}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    enabled = true,
                                    colors = if (selectedDevice?.uuid == device.uuid) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors()
                                ) {
                                    Text(device.displayName, style = textStyles.body2.copy(color = colorScheme.primary))
                                }
                                Button(
                                    onClick = { showConfirmDialog = device },
                                    enabled = true,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text("连接")
                                }
                            }
                        }
                    }
                }
                // 聊天区占据剩余空间
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - deviceBarWeight)
                        .background(colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("聊天测试", style = textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                            items(chatHistory) { msg ->
                                Text(msg, style = textStyles.body2)
                            }
                        }
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                            androidx.compose.material3.OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入消息...") }
                            )
                            Button(
                                onClick = {
                                    val dev = selectedDevice
                                    if (dev != null && chatInput.isNotBlank()) {
                                        deviceManager.sendNotificationData(dev, chatInput)
                                        chatHistory = chatHistory + "发送: $chatInput"
                                        chatInput = ""
                                    }
                                },
                                enabled = selectedDevice != null && chatInput.isNotBlank(),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
            }
            if (showConfirmDialog != null) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = null },
                    title = { Text("连接确认") },
                    text = { Text("是否连接到设备：${showConfirmDialog?.displayName}？") },
                    confirmButton = {
                        TextButton(onClick = {
                            connectingDevice = showConfirmDialog
                            showConfirmDialog = null
                            connectingDevice?.let { deviceManager.connectToDevice(it) }
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                    }
                )
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: DeviceInfo,
    onConnect: () -> Unit,
    onSelect: () -> Unit = {},
    selected: Boolean = false
) {
    val colorScheme = MiuixTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxSize()
            .background(if (selected) colorScheme.primaryContainer else colorScheme.surface)
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = device.displayName,
            style = MiuixTheme.textStyles.body1.copy(color = colorScheme.primary),
            modifier = Modifier.weight(1f).padding(16.dp)
        )
        Button(onClick = onConnect, modifier = Modifier.padding(end = 8.dp)) {
            Text("连接")
        }
    }
}
