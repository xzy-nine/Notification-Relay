package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import com.xzyht.notifyrelay.GlobalSelectedDeviceHolder
import top.yukonga.miuix.kmp.basic.Text


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
                        deviceManager = getDeviceManager(requireContext()),
                        loadAuthedUuids = { loadAuthedUuids() },
                        saveAuthedUuids = { saveAuthedUuids(it) }
                    )
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
    // 连接弹窗与错误弹窗相关状态
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectError by rememberSaveable { mutableStateOf<String?>(null) }
    // 认证设备uuid集合（用于本地存储，实际渲染用deviceManager.devices）
    // 本地管理认证设备uuid集合
    var authedDeviceUuids by rememberSaveable { mutableStateOf(loadAuthedUuids()) }
    // 添加认证设备
    fun addAuthedDevice(uuid: String) {
        if (!authedDeviceUuids.contains(uuid)) {
            val newSet = authedDeviceUuids + uuid
            authedDeviceUuids = newSet
            saveAuthedUuids(newSet)
        }
    }
    // 移除认证设备
    fun removeAuthedDevice(uuid: String) {
        if (authedDeviceUuids.contains(uuid)) {
            val newSet = authedDeviceUuids - uuid
            authedDeviceUuids = newSet
            saveAuthedUuids(newSet)
        }
    }
    // 设备认证、删除等逻辑交由DeviceListFragment统一管理
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    // 服务端握手请求弹窗
    var pendingHandshake by remember { mutableStateOf<Pair<DeviceInfo, String>?>(null) }
    var handshakeCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
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
    // 聊天区相关状态
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    LaunchedEffect(selectedDeviceState.value) {
        selectedDevice = selectedDeviceState.value
    }
    var connectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var isConnecting by rememberSaveable { mutableStateOf(false) }
    val deviceMap by deviceManager.devices.collectAsState()
    val devices = deviceMap.values.map { it.first }
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    // 认证状态监听（简单轮询，实际可用回调）
    LaunchedEffect(deviceMap) {
        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
        rejField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
    }
    // 复刻lancomm事件监听风格，Compose事件流监听消息
    DisposableEffect(Unit) {
        val oldHandler = deviceManager.onNotificationDataReceived
        deviceManager.onNotificationDataReceived = { data ->
            chatHistory = chatHistory + "收到: $data"
            oldHandler?.invoke(data)
        }
        onDispose { deviceManager.onNotificationDataReceived = oldHandler }
    }
    // 聊天区UI
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("聊天测试", style = textStyles.headline1, modifier = Modifier.align(Alignment.CenterHorizontally))
        val connected = connectedDevice
        if (isConnecting) {
            Text("正在连接...", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        } else if (connected != null) {
            Text("已连接: ${connected.displayName}", color = colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
            items(chatHistory) { msg ->
                Text(msg, style = textStyles.body2)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") }
            )
            Button(
                onClick = {
                    val dev = selectedDevice
                    if (dev != null && chatInput.isNotBlank()) {
                        // 发送消息
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
