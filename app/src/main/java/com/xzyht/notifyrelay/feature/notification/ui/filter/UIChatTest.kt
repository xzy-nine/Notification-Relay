package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天测试独立组件，从 DeviceForwardFragment 中抽离
 */
@Composable
fun UIChatTest(
    deviceManager: DeviceConnectionManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    MiuixTheme {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

        var chatInput by rememberSaveable { mutableStateOf("") }
        val chatHistoryState = remember { mutableStateOf<List<String>>(emptyList()) }

        LaunchedEffect(context) {
            chatHistoryState.value = ChatMemory.getChatHistory(context)
        }
        val notificationCallback: (String) -> Unit = remember {
            { data: String ->
                if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.d("NotifyRelay(狂鼠)", "UIChatTest onNotificationDataReceived: $data")
                chatHistoryState.value = ChatMemory.getChatHistory(context)
            }
        }
        DisposableEffect(deviceManager) {
            deviceManager.registerOnNotificationDataReceived(notificationCallback)
            onDispose {
                deviceManager.unregisterOnNotificationDataReceived(notificationCallback)
            }
        }

        // 通知数据回调仅在上层 DeviceForwardScreen 注册并同步 ChatMemory，这里只读取显示与发送

        Column(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
            val listState = remember { androidx.compose.foundation.lazy.LazyListState() }
            val chatList = chatHistoryState.value
            var firstLoad by remember { mutableStateOf(true) }
            LaunchedEffect(chatList.size) {
                if (chatList.isNotEmpty()) {
                    if (firstLoad) {
                        listState.scrollToItem(chatList.lastIndex)
                        firstLoad = false
                    } else {
                        listState.animateScrollToItem(chatList.lastIndex)
                    }
                }
            }

            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                items(chatList) { msg ->
                    val isSend = msg.startsWith("发送:")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isSend) Arrangement.End else Arrangement.Start
                    ) {
                        // 增强：支持长按复制消息文本
                        top.yukonga.miuix.kmp.basic.Surface(
                            color = if (isSend) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        try {
                                            val toCopy = msg.removePrefix("发送:").removePrefix("收到:")
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("message", toCopy)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "已复制消息", android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "复制失败", e)
                                        }
                                    }
                                )
                        ) {
                            Text(
                                msg.removePrefix("发送:").removePrefix("收到:"),
                                style = textStyles.body2,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    modifier = Modifier.weight(1f),
                    label = "输入消息..."
                )
                Button(
                    onClick = {
                        com.xzyht.notifyrelay.core.util.MessageSender.sendChatMessage(
                            context,
                            chatInput,
                            deviceManager
                        )
                        chatHistoryState.value = ChatMemory.getChatHistory(context)
                        chatInput = ""
                    },
                    enabled = com.xzyht.notifyrelay.core.util.MessageSender.hasAvailableDevices(deviceManager) &&
                            com.xzyht.notifyrelay.core.util.MessageSender.isValidMessage(chatInput),
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text("发送")
                }
            }
        }
    }
}
