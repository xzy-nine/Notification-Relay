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
import top.yukonga.miuix.kmp.extra.SuperDialog
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
// Miuix 主题库优先，部分基础布局用 Compose 官方包
import com.xzyht.notifyrelay.data.DeviceConnect.DeviceConnectionManager

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
    val showPinDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current
    var deviceName by remember { mutableStateOf(DeviceConnectionManager.getDeviceName(context)) }
    val deviceUUID = remember { DeviceConnectionManager.getDeviceUUID(context) }
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
                // PIN弹窗触发按钮
                Button(
                    onClick = { showPinDialog.value = true },
                    enabled = !isConnected.value,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("连接设备")
                }
                if (isConnected.value) {
                    Spacer(modifier = Modifier.height(8.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
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
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 已发现设备列表（占位）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "已发现设备（占位）",
                            style = textStyles.body2.copy(color = colorScheme.onBackground)
                        )
                        Text(
                            text = "暂无设备发现功能，后续扩展",
                            style = textStyles.body2.copy(color = colorScheme.outline)
                        )
                    }
                }
            }
            // PIN弹窗
            if (showPinDialog.value) {
                SuperDialog(
                    show = showPinDialog,
                    title = "输入PIN码",
                    onDismissRequest = { showPinDialog.value = false }
                ) {
                    Column {
                        TextField(
                            value = pin,
                            onValueChange = { pin = it },
                            label = "输入PIN码",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                DeviceConnectionManager.startConnectionService(pin)
                                isConnected.value = true
                                showPinDialog.value = false
                            },
                            enabled = pin.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("确认连接")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showPinDialog.value = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    )
    }
}
