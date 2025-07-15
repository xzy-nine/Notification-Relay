package com.xzyht.notifyrelay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import com.xzyht.notifyrelay.data.DeviceConnect.DeviceConnectionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable

class DeviceForwardFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DeviceForwardScreen()
            }
        }
    }

@Composable
fun DeviceForwardScreen() {
    val textStyles = MiuixTheme.textStyles
    val colorScheme = MiuixTheme.colorScheme
    var pin by remember { mutableStateOf("") }
    val isConnected = remember { mutableStateOf(false) }
    var remotePin by remember { mutableStateOf("") }
    val context = LocalContext.current
    var deviceName by remember { mutableStateOf(DeviceConnectionManager.getDeviceName(context)) }
    val deviceUUID = remember { DeviceConnectionManager.getDeviceUUID(context) }
    val discoveredDevices = remember { mutableStateOf(listOf<com.xzyht.notifyrelay.data.DeviceConnect.DeviceConnectionManager.DiscoveredDevice>()) }

    // 初始化设备发现与 PIN 展示回调
    androidx.compose.runtime.LaunchedEffect(Unit) {
        DeviceConnectionManager.init(context) { pinCode ->
            remotePin = pinCode
        }
    }
    // 定时刷新已发现设备列表和PIN有效性
    androidx.compose.runtime.LaunchedEffect(isConnected.value) {
        while (!isConnected.value) {
            discoveredDevices.value = DeviceConnectionManager.getDiscoveredDevices()
            // 检查PIN是否超时，超时则刷新PIN
            val pinValid = try {
                val method = DeviceConnectionManager::class.java.getDeclaredMethod("isRemotePinValid", String::class.java)
                method.isAccessible = true
                method.invoke(DeviceConnectionManager, remotePin) as Boolean
            } catch (_: Exception) { true }
            if (!pinValid) {
                // 刷新PIN（服务端）
                try {
                    val genMethod = DeviceConnectionManager::class.java.getDeclaredMethod("generatePin")
                    genMethod.isAccessible = true
                    genMethod.invoke(DeviceConnectionManager)
                } catch (_: Exception) {}
                remotePin = ""
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    Scaffold(
        topBar = {
            SmallTopAppBar(title = "设备与转发设置")
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                // 连接状态与本机信息
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp))
                        .padding(16.dp)
                        .clickable {
                            val name = DeviceConnectionManager.getDeviceName(context)
                            deviceName = name
                        }
                ) {
                    Column {
                        Text(
                            text = if (isConnected.value) "已连接" else "未连接",
                            style = textStyles.body1.copy(color = if (isConnected.value) colorScheme.primary else colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "本机名称: $deviceName",
                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                        )
                        Text(
                            text = "本机UUID: $deviceUUID",
                            style = textStyles.body2.copy(color = colorScheme.outline)
                        )
                        if (!isConnected.value && remotePin.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "本机PIN码: $remotePin (1分钟内有效)",
                                style = textStyles.body2.copy(color = colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 已发现设备列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "已发现设备",
                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                        )
                        if (discoveredDevices.value.isEmpty()) {
                            Text(
                                text = "暂无设备发现",
                                style = textStyles.body2.copy(color = colorScheme.outline)
                            )
                        } else {
                            // UI层保险去重
                            val uniqueDevices = discoveredDevices.value.distinctBy { it.uuid }
                            uniqueDevices.forEach { device ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp))
                                        .padding(10.dp)
                                        .clickable {
                                            pin = device.pin
                                        }
                                ) {
                                    Text(text = "名称: ${device.name}", style = textStyles.body2)
                                    Text(text = "主机: ${device.host}", style = textStyles.body2)
                                    Text(text = "端口: ${device.port}", style = textStyles.body2)
                                    Text(text = "UUID: ${device.uuid}", style = textStyles.body2.copy(color = colorScheme.outline))
                                    Text(text = "PIN: ${device.pin}", style = textStyles.body2.copy(color = colorScheme.outline))
                                    Text(text = "公钥: ${device.pubKey}", style = textStyles.body2.copy(color = colorScheme.outline))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            pin = device.pin
                                        },
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("连接")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (isConnected.value) {
                    Button(
                        onClick = {
                            DeviceConnectionManager.stopConnection()
                            isConnected.value = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Text("断开连接")
                    }
                }
            }
        }
    )
}
}
