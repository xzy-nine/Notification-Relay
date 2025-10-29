package com.xzyht.notifyrelay.feature.notification.ui.filter

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.superisland.SuperIslandHistory
import com.xzyht.notifyrelay.feature.superisland.SuperIslandHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Date

@Composable
fun UISuperIslandSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_KEY, true)) }
    val historyState = remember(context) { SuperIslandHistory.historyState(context) }
    val history by historyState.collectAsState()
    val entries = remember(history) { history.sortedByDescending { it.id } }

    MiuixTheme {
        val colorScheme = MiuixTheme.colorScheme
        val textStyles = MiuixTheme.textStyles

        Surface(color = colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("超级岛（Super Island）读取", style = textStyles.body1, color = colorScheme.onSurface)
                Text(
                    "说明：此开关控制是否尝试从本机通知中读取小米超级岛数据并转发到远端设备。该功能只读取数据并转发，不会触发任何系统聚焦或白名单行为。",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        StorageManager.putBoolean(context, SUPER_ISLAND_KEY, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (entries.isEmpty()) {
                    Text("暂无超级岛历史记录", style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(entries) { entry ->
                            SuperIslandHistoryCard(entry)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuperIslandHistoryCard(entry: SuperIslandHistoryEntry) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val titleText = entry.appName?.takeIf { it.isNotBlank() }
                ?: entry.title?.takeIf { it.isNotBlank() }
                ?: entry.mappedPackage?.takeIf { it.isNotBlank() }
                ?: entry.originalPackage?.takeIf { it.isNotBlank() }
                ?: "超级岛事件"
            Text(titleText, style = textStyles.body1, color = colorScheme.onSurface)

                entry.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary)
            }

            if (entry.picMap.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    entry.picMap.values.forEach { data ->
                        SuperIslandHistoryImage(data)
                    }
                }
            }

            entry.mappedPackage?.takeIf { it.isNotBlank() }?.let {
                Text("映射包名: $it", style = textStyles.body2, color = colorScheme.outline)
            }
            entry.originalPackage?.takeIf { it.isNotBlank() }?.let {
                Text("原始包名: $it", style = textStyles.body2, color = colorScheme.outline)
            }
            entry.sourceDeviceUuid?.takeIf { it.isNotBlank() }?.let {
                Text("来源设备: $it", style = textStyles.body2, color = colorScheme.outline)
            }

            Text(
                text = formatTimestamp(entry.id),
                style = textStyles.body2,
                color = colorScheme.outline
            )

            entry.paramV2Raw?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            entry.rawPayload?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SuperIslandHistoryImage(data: String, modifier: Modifier = Modifier) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = data) {
        value = withContext(Dispatchers.IO) {
            try {
                if (data.startsWith("data:", ignoreCase = true)) {
                    DataUrlUtils.decodeDataUrlToBitmap(data)
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            modifier = modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp)),
            update = { imageView ->
                try {
                    imageView.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }
        )
    } else {
        Text(
            text = data.take(120),
            style = textStyles.body2,
            color = colorScheme.onSurfaceVariantSummary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colorScheme.surfaceVariant)
                .padding(8.dp)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(timestamp)).toString()
    } catch (_: Exception) {
        timestamp.toString()
    }
}

private const val SUPER_ISLAND_KEY = "superisland_enabled"
