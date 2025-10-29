package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.Image
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
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
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
                    val display = msg.removePrefix("发送:").removePrefix("收到:")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isSend) Arrangement.End else Arrangement.Start
                    ) {
                        // 增强：支持长按复制消息文本（复制原始消息）
                        top.yukonga.miuix.kmp.basic.Surface(
                            color = if (isSend) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        try {
                                            // 复制原始消息（包含 data URI 等原始信息）到剪贴板
                                            val original = msg
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("message", original)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "已复制原始消息到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "复制失败", e)
                                        }
                                    }
                                )
                        ) {
                            // 使用 DataUrlUtils 将文本分段：文本或 data URL
                            val parts = remember(display) { DataUrlUtils.splitByDataUrls(display) }
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 本消息内已成功并呈现的 data URI（清理后的 candidate）集合，
                                // 用于在显示文本时移除对应的原始 base64 片段。
                                val consumed = remember { androidx.compose.runtime.mutableStateListOf<String>() }

                                for (part in parts) {
                                    val textPart = part.first
                                    val dataPart = part.second
                                    if (!textPart.isNullOrEmpty()) {
                                        // 如果 textPart 本身包含未被分割出的 data URI（例如转义/包裹导致 splitByDataUrls 未切分），
                                        // 再次尝试内嵌分割并分别渲染图片或文本。
                                        if (textPart.contains("data:", ignoreCase = true)) {
                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                try { android.util.Log.d("NotifyRelay", "base64: textPart contains data:, attempting inner split preview=${textPart.take(80)}") } catch (_: Exception) {}
                                            }
                                            val inner = remember(textPart) { DataUrlUtils.splitByDataUrls(textPart) }
                                            for (ip in inner) {
                                                val itext = ip.first
                                                val idata = ip.second
                                                if (!itext.isNullOrEmpty()) {
                                                    Text(
                                                        itext,
                                                        style = textStyles.body2,
                                                        color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
                                                    )
                                                } else if (!idata.isNullOrEmpty()) {
                                                    // 复用与上面相同的解码逻辑：异步解码并显示图片或回退文本
                                                    val innerBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = idata) {
                                                        value = try {
                                                            withContext(Dispatchers.IO) {
                                                                var candidate = idata.trim().removeSurrounding("\"")
                                                                try { candidate = java.net.URLDecoder.decode(candidate, "UTF-8") } catch (_: Exception) {}
                                                                if (!candidate.startsWith("data:", ignoreCase = true)) {
                                                                    val idx = candidate.indexOf("data:", ignoreCase = true)
                                                                    if (idx >= 0) candidate = candidate.substring(idx)
                                                                }
                                                                try { candidate = candidate.replace("\\/", "/") } catch (_: Exception) {}
                                                                try { candidate = candidate.replace(Regex("\\\\+"), "") } catch (_: Exception) {}
                                                                val commaIdx = candidate.indexOf(',')
                                                                val cleanedCandidate = if (commaIdx > 0 && commaIdx < candidate.length - 1) {
                                                                    val metaPart = candidate.substring(0, commaIdx)
                                                                    var dataPartRaw = candidate.substring(commaIdx + 1)
                                                                    dataPartRaw = dataPartRaw.replace(Regex("[^A-Za-z0-9+/=]"), "")
                                                                    "$metaPart,$dataPartRaw"
                                                                } else candidate
                                                                var bmp: android.graphics.Bitmap? = null
                                                                try { bmp = DataUrlUtils.decodeDataUrlToBitmap(cleanedCandidate) } catch (_: Exception) { bmp = null }
                                                                if (bmp == null) {
                                                                    try {
                                                                        var candidateForFallback = cleanedCandidate
                                                                        try { candidateForFallback = java.net.URLDecoder.decode(candidateForFallback, "UTF-8") } catch (_: Exception) {}
                                                                        val regex = Regex("data:[^,]*,([A-Za-z0-9+/=\\s\"]+)", RegexOption.IGNORE_CASE)
                                                                        val m = regex.find(candidateForFallback)
                                                                        val base64Part = if (m != null && m.groupValues.size > 1) m.groupValues[1] else {
                                                                            val idx2 = candidateForFallback.indexOf(',')
                                                                            if (idx2 >= 0 && idx2 < candidateForFallback.length - 1) candidateForFallback.substring(idx2 + 1) else ""
                                                                        }
                                                                        var cleanedB64 = base64Part.replace(Regex("[^A-Za-z0-9+/=]"), "")
                                                                        if (cleanedB64.isNotEmpty()) {
                                                                            var bytes: ByteArray? = null
                                                                            try { bytes = android.util.Base64.decode(cleanedB64, android.util.Base64.NO_WRAP) } catch (_: Exception) { try { bytes = android.util.Base64.decode(cleanedB64, android.util.Base64.DEFAULT) } catch (_: Exception) { bytes = null } }
                                                                            if (bytes != null) {
                                                                                try { bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) {}
                                                                            }
                                                                        }
                                                                    } catch (_: Exception) {}
                                                                }
                                                                if (bmp != null && com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                                    try { android.util.Log.d("NotifyRelay", "base64: inner decoded bitmap: ${bmp.width}x${bmp.height} preview=${idata?.take(80) ?: ""}") } catch (_: Exception) {}
                                                                }
                                                                if (bmp != null) {
                                                                    try { withContext(Dispatchers.Main) { if (!consumed.contains(cleanedCandidate)) consumed.add(cleanedCandidate) } } catch (_: Exception) {}
                                                                }
                                                                bmp
                                                            }
                                                        } catch (e: Exception) {
                                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "inner decode failed", e)
                                                            null
                                                        }
                                                    }

                                                    val ibmp = innerBitmap
                                                    if (ibmp != null) {
                                                        val imageBitmap = remember(ibmp) { ibmp.asImageBitmap() }
                                                        Image(
                                                            imageBitmap,
                                                            contentDescription = "image",
                                                            modifier = Modifier
                                                                .size(56.dp)
                                                                .padding(start = 6.dp)
                                                        )
                                                    } else {
                                                        // 回退：将常见的 JSON/字符串转义还原为实际字符并显示完整文本（与外层回退一致）
                                                        val innerFallback = remember(idata) {
                                                            try {
                                                                val sb = StringBuilder()
                                                                var i = 0
                                                                while (i < idata.length) {
                                                                    val ch = idata[i]
                                                                    if (ch == '\\' && i + 1 < idata.length) {
                                                                        val next = idata[i + 1]
                                                                        when (next) {
                                                                            'n' -> { sb.append('\n'); i += 2 }
                                                                            'r' -> { sb.append('\r'); i += 2 }
                                                                            't' -> { sb.append('\t'); i += 2 }
                                                                            '"' -> { sb.append('"'); i += 2 }
                                                                            '/' -> { sb.append('/'); i += 2 }
                                                                            '\\' -> { sb.append('\\'); i += 2 }
                                                                            else -> { sb.append(next); i += 2 }
                                                                        }
                                                                    } else {
                                                                        sb.append(ch)
                                                                        i += 1
                                                                    }
                                                                }
                                                                sb.toString()
                                                            } catch (e: Exception) {
                                                                if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "inner unescape fallback failed", e)
                                                                idata ?: ""
                                                            }
                                                        }

                                                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                            try { android.util.Log.d("NotifyRelay", "base64: displaying fallback inner text preview=${innerFallback.take(120)} len=${innerFallback.length}") } catch (_: Exception) {}
                                                        }

                                                        Text(
                                                            innerFallback,
                                                            style = textStyles.body2,
                                                            color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer,
                                                            maxLines = Int.MAX_VALUE
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // 在显示文本前移除已被解码并显示的 data URI 片段，避免重复显示 base64 文本
                                            val displayText = remember(textPart, consumed) {
                                                var t = textPart ?: ""
                                                for (c in consumed) {
                                                    try { t = t.replace(c, "") } catch (_: Exception) {}
                                                }
                                                t
                                            }
                                            if (displayText.isNotBlank()) {
                                                Text(
                                                    displayText,
                                                    style = textStyles.body2,
                                                    color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    } else if (!dataPart.isNullOrEmpty()) {
                                        // 异步在 IO 线程解码 data URI 为 Bitmap，避免阻塞 UI
                                        val bitmapState by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = dataPart) {
                                            value = try {
                                                withContext(Dispatchers.IO) {
                                                    // 尝试清理 data URI：去除两端引号/空白、URL 解码、并定位真正的 data: 开头（若被包裹）
                                                    var candidate = dataPart.trim().removeSurrounding("\"")
                                                    try {
                                                        candidate = java.net.URLDecoder.decode(candidate, "UTF-8")
                                                    } catch (_: Exception) {}

                                                    if (!candidate.startsWith("data:", ignoreCase = true)) {
                                                        val idx = candidate.indexOf("data:", ignoreCase = true)
                                                        if (idx >= 0) candidate = candidate.substring(idx)
                                                    }

                                                    // 常见存储/转义问题修复：
                                                    // - JSON 字符串中可能为 "data:image\/png;..." 的形式，先把 \/ 还原为 /
                                                    // - 删除所有反斜杠 (JSON 转义留下的)，以避免干扰 base64 解码
                                                    try { candidate = candidate.replace("\\/", "/") } catch (_: Exception) {}
                                                    try { candidate = candidate.replace(Regex("\\\\+"), "") } catch (_: Exception) {}

                                                    // 如果包含逗号, 对 base64 部分做进一步清理：去掉非 base64 字符(例如空格、换行、引号等)
                                                    val commaIdx = candidate.indexOf(',')
                                                    val cleanedCandidate = if (commaIdx > 0 && commaIdx < candidate.length - 1) {
                                                        val metaPart = candidate.substring(0, commaIdx)
                                                        var dataPartRaw = candidate.substring(commaIdx + 1)
                                                        dataPartRaw = dataPartRaw.replace(Regex("[^A-Za-z0-9+/=]"), "")
                                                        "$metaPart,$dataPartRaw"
                                                    } else candidate

                                                    var bmp: android.graphics.Bitmap? = null
                                                    try {
                                                        // 首先尝试通用解码器
                                                        bmp = DataUrlUtils.decodeDataUrlToBitmap(cleanedCandidate)
                                                    } catch (e: Exception) {
                                                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "decode data url exception", e)
                                                        bmp = null
                                                    }

                                                    // 额外的容错尝试：如果通用方法失败，尝试通过正则提取 base64 段并直接解码（尝试 NO_WRAP 与 DEFAULT）
                                                    if (bmp == null) {
                                                        try {
                                                            var candidateForFallback = cleanedCandidate
                                                            // URL 解码一次以应对被 encode 的 data URI
                                                            try { candidateForFallback = java.net.URLDecoder.decode(candidateForFallback, "UTF-8") } catch (_: Exception) {}

                                                            // 寻找 base64 部分
                                                            val regex = Regex("data:[^,]*,([A-Za-z0-9+/=\\s\"]+)", RegexOption.IGNORE_CASE)
                                                            val m = regex.find(candidateForFallback)
                                                            val base64Part = if (m != null && m.groupValues.size > 1) m.groupValues[1] else {
                                                                // 退路：找到第一个逗号后的片段作为 base64 候选
                                                                val idx2 = candidateForFallback.indexOf(',')
                                                                if (idx2 >= 0 && idx2 < candidateForFallback.length - 1) candidateForFallback.substring(idx2 + 1) else ""
                                                            }

                                                            var cleanedB64 = base64Part.replace(Regex("[^A-Za-z0-9+/=]"), "")
                                                            if (cleanedB64.isNotEmpty()) {
                                                                var bytes: ByteArray? = null
                                                                try {
                                                                    bytes = android.util.Base64.decode(cleanedB64, android.util.Base64.NO_WRAP)
                                                                } catch (_: Exception) {
                                                                    try { bytes = android.util.Base64.decode(cleanedB64, android.util.Base64.DEFAULT) } catch (_: Exception) { bytes = null }
                                                                }

                                                                if (bytes != null) {
                                                                    try {
                                                                        bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                                    } catch (e: Exception) {
                                                                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "fallback decode bytearray failed", e)
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "fallback decode failed", e)
                                                        }
                                                    }

                                                    if (bmp == null && com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                        try {
                                                            android.util.Log.d("NotifyRelay", "base64: decodeDataUrlToBitmap returned null; preview=${candidate.take(120)}, len=${candidate.length}")
                                                        } catch (_: Exception) {}

                                                        // 直接把清理后的 base64 打到 logcat，便于直接在设备上查看或用 adb logcat 导出
                                                        if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                            try {
                                                                android.util.Log.d("NotifyRelay", "base64: cleanedCandidate=${cleanedCandidate}")
                                                            } catch (_: Exception) {}
                                                        }
                                                    }

                                                    if (bmp != null && com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                        try {
                                                            android.util.Log.d("NotifyRelay", "base64: decoded bitmap: ${bmp.width}x${bmp.height}, candidatePreview=${candidate.take(80)}")
                                                        } catch (_: Exception) {}
                                                    }
                                                    if (bmp != null) {
                                                        try { withContext(Dispatchers.Main) { if (!consumed.contains(cleanedCandidate)) consumed.add(cleanedCandidate) } } catch (_: Exception) {}
                                                    }

                                                    bmp
                                                }
                                            } catch (e: Exception) {
                                                if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "decode data url failed", e)
                                                null
                                            }
                                        }

                                        val bitmap = bitmapState
                                        if (bitmap != null) {
                                            if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                                                try {
                                                    android.util.Log.d("NotifyRelay", "base64: UI displaying bitmap for preview=${dataPart.take(80)} size=${bitmap.width}x${bitmap.height}")
                                                } catch (_: Exception) {}
                                            }

                                            // cache the ImageBitmap so recomposition reliably updates the Image when bitmap changes
                                            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                                            Image(
                                                imageBitmap,
                                                contentDescription = "image",
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .padding(start = 6.dp)
                                            )
                                        } else {
                                            // 无法解码则显示原始文本：尝试将常见的 JSON/字符串转义还原为实际字符（保留换行等），
                                            // 并显示完整文本而不是截断。
                                            val fallbackText = remember(dataPart) {
                                                try {
                                                    // 轻量级、确定性的反转义：处理常见的转义序列 \n, \r, \t, \", \/, \\
                                                    val sb = StringBuilder()
                                                    var i = 0
                                                    while (i < dataPart.length) {
                                                        val ch = dataPart[i]
                                                        if (ch == '\\' && i + 1 < dataPart.length) {
                                                            val next = dataPart[i + 1]
                                                            when (next) {
                                                                'n' -> { sb.append('\n'); i += 2 }
                                                                'r' -> { sb.append('\r'); i += 2 }
                                                                't' -> { sb.append('\t'); i += 2 }
                                                                '"' -> { sb.append('"'); i += 2 }
                                                                '/' -> { sb.append('/'); i += 2 }
                                                                '\\' -> { sb.append('\\'); i += 2 }
                                                                else -> { sb.append(next); i += 2 }
                                                            }
                                                        } else {
                                                            sb.append(ch)
                                                            i += 1
                                                        }
                                                    }
                                                    sb.toString()
                                                } catch (e: Exception) {
                                                    // 反转义出错时回退到原始片段
                                                    if (com.xzyht.notifyrelay.BuildConfig.DEBUG) android.util.Log.e("NotifyRelay", "unescape fallback failed", e)
                                                    dataPart
                                                }
                                            }

                                            Text(
                                                fallbackText,
                                                style = textStyles.body2,
                                                color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer,
                                                // 显式允许显示任意行以保留换行内容
                                                maxLines = Int.MAX_VALUE
                                            )
                                        }
                                    }
                                }
                            }
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
