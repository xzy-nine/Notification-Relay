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
    val deviceManager = remember { DeviceConnectionManager(context) }
    // 服务端握手请求弹窗
    var pendingHandshake by remember { mutableStateOf<Pair<DeviceInfo, String>?>(null) }
    var handshakeCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    // 被拒绝设备管理弹窗
    var showRejectedDialog by remember { mutableStateOf(false) }
    // 监听服务端握手请求，弹窗确认
    DisposableEffect(Unit) {
        deviceManager.onHandshakeRequest = { device, pubKey, cb ->
            pendingHandshake = device to pubKey
            handshakeCallback = cb
        }
        onDispose {
            deviceManager.onHandshakeRequest = null
        }
    }
    val devices by deviceManager.devices.collectAsState()
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var chatInput by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    // 认证通过设备uuid集合
    var authedDeviceUuids by remember { mutableStateOf(setOf<String>()) }
    // 被拒绝设备uuid集合
    var rejectedDeviceUuids by remember { mutableStateOf(setOf<String>()) }

    // 启动设备发现
    LaunchedEffect(Unit) {
        deviceManager.startDiscovery()
    }

    // 复刻lancomm事件监听风格，Compose事件流监听消息
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
    // 认证状态监听（简单轮询，实际可用回调）
    LaunchedEffect(devices) {
        // 这里直接反射拿到DeviceConnectionManager的认证表
        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(deviceManager) as? Map<String, *>
        authedDeviceUuids = map?.filter { (it.value as? Any)?.let { v ->
            val isAccepted = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }.getBoolean(v)
            isAccepted
        } == true }?.keys?.toSet() ?: emptySet()
        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
        rejField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
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
                        items(devices.filter { !rejectedDeviceUuids.contains(it.uuid) }) { device ->
                            val isAuthed = authedDeviceUuids.contains(device.uuid)
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
                                selected = selectedDevice?.uuid == device.uuid,
                                showConnect = !isAuthed
                            )
                        }
                    }
                    // 被拒绝设备管理按钮
                    androidx.compose.material3.TextButton(onClick = { showRejectedDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                        Text("管理被拒绝设备", color = colorScheme.primary)
                    }
                }
                // 聊天框
                androidx.compose.foundation.layout.Box(Modifier.weight(2f).fillMaxSize().background(colorScheme.surface)) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        Text("聊天测试", style = textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
                        if (isConnecting) {
                            Text("正在连接...", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
                        } else if (connectedDevice != null) {
                            Text("已连接: ${connectedDevice?.displayName}", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
                        }
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
                                    if (dev != null && chatInput.isNotBlank() && authedDeviceUuids.contains(dev.uuid)) {
                                        deviceManager.sendNotificationData(dev, chatInput)
                                        chatHistory = chatHistory + "发送: $chatInput"
                                        chatInput = ""
                                    }
                                },
                                enabled = selectedDevice != null && chatInput.isNotBlank() && selectedDevice?.uuid?.let { authedDeviceUuids.contains(it) } == true,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
            }
            // 服务端握手弹窗
            if (pendingHandshake != null) {
                val (dev, pubKey) = pendingHandshake!!
                AlertDialog(
                    onDismissRequest = { pendingHandshake = null; handshakeCallback?.invoke(false); handshakeCallback = null },
                    title = { Text("有设备请求连接") },
                    text = { Text("设备：${dev.displayName}\nIP: ${dev.ip}\n公钥: $pubKey\n是否允许连接？") },
                    confirmButton = {
                        TextButton(onClick = {
                            handshakeCallback?.invoke(true)
                            pendingHandshake = null
                            handshakeCallback = null
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            handshakeCallback?.invoke(false)
                            pendingHandshake = null
                            handshakeCallback = null
                        }) { Text("拒绝") }
                    }
                )
            }
            // 被拒绝设备管理弹窗
            if (showRejectedDialog) {
                AlertDialog(
                    onDismissRequest = { showRejectedDialog = false },
                    title = { Text("被拒绝的设备") },
                    text = {
                        if (rejectedDeviceUuids.isEmpty()) Text("无被拒绝设备")
                        else LazyColumn {
                            items(devices.filter { rejectedDeviceUuids.contains(it.uuid) }) { device ->
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.displayName, Modifier.weight(1f))
                                    TextButton(onClick = {
                                        // 反射移除
                                        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                                        rejField.isAccessible = true
                                        @Suppress("UNCHECKED_CAST")
                                        val set = rejField.get(deviceManager) as? MutableSet<String>
                                        set?.remove(device.uuid)
                                        showRejectedDialog = false
                                    }) { Text("移除") }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showRejectedDialog = false }) { Text("关闭") }
                    }
                )
            }
            if (showConfirmDialog != null) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = null },
                    title = { Text("连接确认") },
                    text = { Text("是否连接到设备：${showConfirmDialog?.displayName}？") },
                    confirmButton = {
                        TextButton(onClick = {
                            connectingDevice = showConfirmDialog
                            isConnecting = true
                            showConfirmDialog = null
                            connectingDevice?.let { device ->
                                deviceManager.connectToDevice(device) { success, error ->
                                    isConnecting = false
                                    if (success) {
                                        connectedDevice = device
                                    } else {
                                        connectError = error ?: "连接失败"
                                        // 认证被拒绝，刷新rejectedDeviceUuids
                                        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                                        rejField.isAccessible = true
                                        @Suppress("UNCHECKED_CAST")
                                        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
                                    }
                                }
                            }
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                    }
                )
            }
            if (connectError != null) {
                AlertDialog(
                    onDismissRequest = { connectError = null },
                    title = { Text("连接失败") },
                    text = { Text(connectError ?: "未知错误") },
                    confirmButton = {
                        TextButton(onClick = { connectError = null }) { Text("确定") }
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
                        items(devices.filter { !rejectedDeviceUuids.contains(it.uuid) }) { device ->
                            val isAuthed = authedDeviceUuids.contains(device.uuid)
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
                                if (!isAuthed) {
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
                        // 被拒绝设备管理按钮
                        item {
                            androidx.compose.material3.TextButton(onClick = { showRejectedDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("管理被拒绝设备", color = colorScheme.primary)
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
                                    if (dev != null && chatInput.isNotBlank() && authedDeviceUuids.contains(dev.uuid)) {
                                        deviceManager.sendNotificationData(dev, chatInput)
                                        chatHistory = chatHistory + "发送: $chatInput"
                                        chatInput = ""
                                    }
                                },
                                enabled = selectedDevice != null && chatInput.isNotBlank() && selectedDevice?.uuid?.let { authedDeviceUuids.contains(it) } == true,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
            }
            // 服务端握手弹窗
            if (pendingHandshake != null) {
                val (dev, pubKey) = pendingHandshake!!
                AlertDialog(
                    onDismissRequest = { pendingHandshake = null; handshakeCallback?.invoke(false); handshakeCallback = null },
                    title = { Text("有设备请求连接") },
                    text = { Text("设备：${dev.displayName}\nIP: ${dev.ip}\n公钥: $pubKey\n是否允许连接？") },
                    confirmButton = {
                        TextButton(onClick = {
                            handshakeCallback?.invoke(true)
                            pendingHandshake = null
                            handshakeCallback = null
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            handshakeCallback?.invoke(false)
                            pendingHandshake = null
                            handshakeCallback = null
                        }) { Text("拒绝") }
                    }
                )
            }
            // 被拒绝设备管理弹窗
            if (showRejectedDialog) {
                AlertDialog(
                    onDismissRequest = { showRejectedDialog = false },
                    title = { Text("被拒绝的设备") },
                    text = {
                        if (rejectedDeviceUuids.isEmpty()) Text("无被拒绝设备")
                        else LazyColumn {
                            items(devices.filter { rejectedDeviceUuids.contains(it.uuid) }) { device ->
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.displayName, Modifier.weight(1f))
                                    TextButton(onClick = {
                                        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                                        rejField.isAccessible = true
                                        @Suppress("UNCHECKED_CAST")
                                        val set = rejField.get(deviceManager) as? MutableSet<String>
                                        set?.remove(device.uuid)
                                        showRejectedDialog = false
                                    }) { Text("移除") }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showRejectedDialog = false }) { Text("关闭") }
                    }
                )
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
                            connectingDevice?.let { device ->
                                deviceManager.connectToDevice(device) { success, error ->
                                    if (!success) connectError = error ?: "连接失败"
                                }
                            }
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                    }
                )
            }
            if (connectError != null) {
                AlertDialog(
                    onDismissRequest = { connectError = null },
                    title = { Text("连接失败") },
                    text = { Text(connectError ?: "未知错误") },
                    confirmButton = {
                        TextButton(onClick = { connectError = null }) { Text("确定") }
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
    selected: Boolean = false,
    showConnect: Boolean = true
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
        if (showConnect) {
            Button(onClick = onConnect, modifier = Modifier.padding(end = 8.dp)) {
                Text("连接")
            }
        }
    }
}
