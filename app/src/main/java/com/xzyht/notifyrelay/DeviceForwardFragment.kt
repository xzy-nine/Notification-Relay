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
    val colorScheme = MiuixTheme.colorScheme
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

    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.background)
    ) {
        // 设备列表和聊天框左右分栏
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
                            },
                            selected = selectedDevice?.uuid == device.uuid
                        )
                    }
                }
            }
            // 聊天框
            androidx.compose.foundation.layout.Box(Modifier.weight(2f).fillMaxSize().background(colorScheme.surface)) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Text("聊天测试", style = MiuixTheme.textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                        items(chatHistory) { msg ->
                            Text(msg, style = MiuixTheme.textStyles.body2)
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
