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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import androidx.compose.ui.graphics.Color


class DeviceForwardFragment : Fragment() {
    // 认证通过设备持久化key
    private val PREFS_NAME = "notifyrelay_device_prefs"
    private val KEY_AUTHED_UUIDS = "authed_device_uuids"

    companion object {
        // 全局单例，保证同一进程内所有页面共享同一个 deviceManager
        @Volatile
        private var sharedDeviceManager: DeviceConnectionManager? = null
        fun getDeviceManager(context: android.content.Context): DeviceConnectionManager {
            return sharedDeviceManager ?: synchronized(this) {
                sharedDeviceManager ?: DeviceConnectionManager(context.applicationContext).also { sharedDeviceManager = it }
            }
        }
    }

    // 加载已认证设备uuid集合
    fun loadAuthedUuids(): Set<String> {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        return prefs.getStringSet(KEY_AUTHED_UUIDS, emptySet()) ?: emptySet()
    }

    // 保存已认证设备uuid集合
    fun saveAuthedUuids(uuids: Set<String>) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        prefs.edit().putStringSet(KEY_AUTHED_UUIDS, uuids).apply()
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    DeviceForwardScreen(
                        getDeviceManager(requireContext()),
                        loadAuthedUuids = { loadAuthedUuids() },
                        saveAuthedUuids = { saveAuthedUuids(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceForwardScreen(
    deviceManager: DeviceConnectionManager,
    loadAuthedUuids: () -> Set<String>,
    saveAuthedUuids: (Set<String>) -> Unit
) {
    // 认证设备uuid集合（用于本地存储，实际渲染用deviceManager.devices）
    var authedDeviceUuids by rememberSaveable { mutableStateOf(loadAuthedUuids()) }
    fun addAuthedDevice(uuid: String) {
        if (!authedDeviceUuids.contains(uuid)) {
            val newSet = authedDeviceUuids + uuid
            authedDeviceUuids = newSet
            saveAuthedUuids(newSet)
        }
    }
    // 新增：删除已认证设备
    fun removeAuthedDevice(uuid: String) {
        if (authedDeviceUuids.contains(uuid)) {
            val newSet = authedDeviceUuids - uuid
            authedDeviceUuids = newSet
            saveAuthedUuids(newSet)
            // 反射移除DeviceConnectionManager的认证表项
            val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(deviceManager) as? MutableMap<String, *>
            map?.remove(uuid)
            // 同步保存
            val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
            saveMethod.isAccessible = true
            saveMethod.invoke(deviceManager)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    // 服务端握手请求弹窗
    var pendingHandshake by remember { mutableStateOf<Pair<DeviceInfo, String>?>(null) }
    var handshakeCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    // 被拒绝设备管理弹窗
    var showRejectedDialog by rememberSaveable { mutableStateOf(false) }
    // 监听服务端握手请求，弹窗确认，并在同意时同步认证集合
    DisposableEffect(Unit) {
        val oldHandshake = deviceManager.onHandshakeRequest
        deviceManager.onHandshakeRequest = { device, pubKey, cb ->
            pendingHandshake = device to pubKey
            handshakeCallback = { accepted ->
                cb(accepted)
                if (accepted) {
                    addAuthedDevice(device.uuid)
                }
            }
        }
        onDispose { deviceManager.onHandshakeRequest = oldHandshake }
    }
    val deviceMap by deviceManager.devices.collectAsState()
    val devices = deviceMap.values.map { it.first }
    val deviceStates = deviceMap.mapValues { it.value.second }
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectError by rememberSaveable { mutableStateOf<String?>(null) }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var isConnecting by rememberSaveable { mutableStateOf(false) }
    // 被拒绝设备uuid集合
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }

    // 启动设备发现
    LaunchedEffect(Unit) { deviceManager.startDiscovery() }

    // 复刻lancomm事件监听风格，Compose事件流监听消息
    DisposableEffect(Unit) {
        val oldHandler = deviceManager.onNotificationDataReceived
        deviceManager.onNotificationDataReceived = { data ->
            chatHistory = chatHistory + "收到: $data"
            oldHandler?.invoke(data)
        }
        onDispose { deviceManager.onNotificationDataReceived = oldHandler }
    }
    // 认证状态监听（简单轮询，实际可用回调）
    LaunchedEffect(deviceMap) {
        // 这里直接反射拿到DeviceConnectionManager的认证表
        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(deviceManager) as? Map<String, *>
        val newAuthed = map?.filter { (it.value as? Any)?.let { v ->
            val isAccepted = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }.getBoolean(v)
            isAccepted
        } == true }?.keys?.toSet() ?: emptySet()
        if (newAuthed != authedDeviceUuids) {
            authedDeviceUuids = newAuthed
            saveAuthedUuids(newAuthed)
        }
        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
        rejField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun isAuthed(uuid: String) = authedDeviceUuids.contains(uuid)

    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.background)
    ) {
        if (isLandscape) {
            // 横屏：设备列表左侧（1/3），聊天区右侧（2/3），聊天区显示聊天测试框
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    DeviceListPanel(
                        devices = devices,
                        rejectedDeviceUuids = rejectedDeviceUuids,
                        deviceStates = deviceStates,
                        selectedDevice = selectedDevice,
                        isAuthed = ::isAuthed,
                        onSelect = { selectedDevice = it },
                        onConnect = { showConfirmDialog = it },
                        onRemove = { removeAuthedDevice(it) },
                        onManageRejected = { showRejectedDialog = true },
                        context = context
                    )
                }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(2f)
                        .fillMaxSize()
                        .padding(12.dp)
                        .background(colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                ) {
                    ChatPanel(
                        chatHistory = chatHistory,
                        chatInput = chatInput,
                        onInputChange = { chatInput = it },
                        onSend = {
                            val dev = selectedDevice
                            if (dev != null && chatInput.isNotBlank() && isAuthed(dev.uuid)) {
                                deviceManager.sendNotificationData(dev, chatInput)
                                chatHistory = chatHistory + "发送: $chatInput"
                                chatInput = ""
                            }
                        },
                        canSend = selectedDevice != null && chatInput.isNotBlank() && selectedDevice?.uuid?.let { isAuthed(it) } == true,
                        isConnecting = isConnecting,
                        connectedDevice = connectedDevice
                    )
                }
            }
        } else {
            // 竖屏：设备列表横排在顶部，聊天区在下方
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().background(colorScheme.background).padding(16.dp)
            ) {
                Text(
                    text = "设备与转发",
                    style = textStyles.title2.copy(color = colorScheme.onBackground)
                )
                val deviceBarWeight = 0.2f
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(deviceBarWeight)
                ) {
                    DeviceRowPanel(
                        devices = devices,
                        rejectedDeviceUuids = rejectedDeviceUuids,
                        deviceStates = deviceStates,
                        selectedDevice = selectedDevice,
                        isAuthed = ::isAuthed,
                        onSelect = { selectedDevice = it },
                        onConnect = { showConfirmDialog = it },
                        onRemove = { removeAuthedDevice(it) },
                        onManageRejected = { showRejectedDialog = true },
                        context = context
                    )
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - deviceBarWeight)
                        .background(colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                ) {
                    ChatPanel(
                        chatHistory = chatHistory,
                        chatInput = chatInput,
                        onInputChange = { chatInput = it },
                        onSend = {
                            val dev = selectedDevice
                            if (dev != null && chatInput.isNotBlank() && isAuthed(dev.uuid)) {
                                deviceManager.sendNotificationData(dev, chatInput)
                                chatHistory = chatHistory + "发送: $chatInput"
                                chatInput = ""
                            }
                        },
                        canSend = selectedDevice != null && chatInput.isNotBlank() && selectedDevice?.uuid?.let { isAuthed(it) } == true,
                        isConnecting = isConnecting,
                        connectedDevice = connectedDevice
                    )
                }
            }
        }
        // 公共弹窗区域
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
                        isConnecting = true
                        showConfirmDialog = null
                        connectingDevice?.let { device ->
                            deviceManager.connectToDevice(device) { success, error ->
                                isConnecting = false
                                if (success) {
                                    connectedDevice = device
                                    addAuthedDevice(device.uuid)
                                } else {
                                    connectError = error ?: "连接失败"
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
}

@Composable
fun DeviceItem(
    device: DeviceInfo,
    onConnect: () -> Unit,
    onSelect: () -> Unit = {},
    onRemove: (() -> Unit)? = null,
    selected: Boolean = false,
    showConnect: Boolean = true,
    isOnline: Boolean = true,
    isAuthed: Boolean = false
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val textColor = if (!isOnline && isAuthed) colorScheme.outline else colorScheme.primary
    val bgColor = when {
        selected -> colorScheme.primaryContainer
        !isOnline && isAuthed -> colorScheme.surfaceVariant
        else -> colorScheme.surface
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = device.displayName + if (!isOnline && isAuthed) " (离线)" else "",
            style = textStyles.body1.copy(color = textColor),
            modifier = Modifier.weight(1f).padding(16.dp)
        )
        if (showConnect) {
            Button(
                onClick = onConnect,
                modifier = Modifier.padding(end = 8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
            ) {
                Text("连接", color = colorScheme.onPrimary)
            }
        }
        // 删除按钮：仅在选中且已认证时显示
        if (isAuthed && selected && onRemove != null) {
            Button(
                onClick = onRemove,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("删除", color = colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun DeviceListPanel(
    devices: List<DeviceInfo>,
    rejectedDeviceUuids: Set<String>,
    deviceStates: Map<String, Boolean>,
    selectedDevice: DeviceInfo?,
    isAuthed: (String) -> Boolean,
    onSelect: (DeviceInfo) -> Unit,
    onConnect: (DeviceInfo) -> Unit,
    onRemove: (String) -> Unit,
    onManageRejected: () -> Unit,
    context: android.content.Context
) {
    val colorScheme = MiuixTheme.colorScheme
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(devices.filter { !rejectedDeviceUuids.contains(it.uuid) }) { device ->
            val authed = isAuthed(device.uuid)
            val isOnline = deviceStates[device.uuid] == true
            DeviceItem(
                device = device,
                onConnect = { onConnect(device) },
                onSelect = {
                    onSelect(device)
                    android.widget.Toast.makeText(
                        context,
                        "UUID: ${device.uuid}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onRemove = if (authed && selectedDevice?.uuid == device.uuid) { { onRemove(device.uuid) } } else null,
                selected = selectedDevice?.uuid == device.uuid,
                showConnect = !authed,
                isOnline = isOnline,
                isAuthed = authed
            )
        }
        item {
            TextButton(
                onClick = onManageRejected,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.End)
                    .padding(8.dp)
            ) {
                Text("管理被拒绝设备", color = colorScheme.primary)
            }
        }
    }
}

@Composable
fun DeviceRowPanel(
    devices: List<DeviceInfo>,
    rejectedDeviceUuids: Set<String>,
    deviceStates: Map<String, Boolean>,
    selectedDevice: DeviceInfo?,
    isAuthed: (String) -> Boolean,
    onSelect: (DeviceInfo) -> Unit,
    onConnect: (DeviceInfo) -> Unit,
    onRemove: (String) -> Unit,
    onManageRejected: () -> Unit,
    context: android.content.Context
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxSize().padding(top = 12.dp, bottom = 8.dp)
    ) {
        items(devices.filter { !rejectedDeviceUuids.contains(it.uuid) }) { device ->
            val authed = isAuthed(device.uuid)
            val isOnline = deviceStates[device.uuid] == true
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        onSelect(device)
                        android.widget.Toast.makeText(
                            context,
                            "UUID: ${device.uuid}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = if (selectedDevice?.uuid == device.uuid) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                ) {
                    Text(device.displayName, style = textStyles.body2.copy(color = colorScheme.primary))
                }
                if (!authed) {
                    Button(
                        onClick = { onConnect(device) },
                        enabled = true,
                        modifier = Modifier.padding(top = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                    ) {
                        Text("连接", color = colorScheme.onPrimary)
                    }
                }
                if (authed && !isOnline) {
                    Text("(离线)", color = colorScheme.outline, style = textStyles.body2)
                }
                if (authed && selectedDevice?.uuid == device.uuid) {
                    Button(
                        onClick = { onRemove(device.uuid) },
                        modifier = Modifier.padding(top = 2.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("删除", color = colorScheme.onPrimary)
                    }
                }
            }
        }
        item {
            TextButton(onClick = onManageRejected, modifier = Modifier.padding(top = 8.dp)) {
                Text("管理被拒绝设备", color = colorScheme.primary)
            }
        }
    }
}

@Composable
fun ChatPanel(
    chatHistory: List<String>,
    chatInput: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    isConnecting: Boolean,
    connectedDevice: DeviceInfo?
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("聊天测试", style = textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (isConnecting) {
            Text("正在连接...", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        } else if (connectedDevice != null) {
            Text("已连接: ${connectedDevice.displayName}", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
            items(chatHistory) { msg ->
                Text(msg, style = textStyles.body2)
            }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                value = chatInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") }
            )
            Button(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("发送")
            }
        }
    }
}
