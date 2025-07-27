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
    // 设备认证、删除等逻辑已交由DeviceListFragment统一管理
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    // 聊天区相关状态
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 只监听全局选中设备
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceState.value
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
}
}
