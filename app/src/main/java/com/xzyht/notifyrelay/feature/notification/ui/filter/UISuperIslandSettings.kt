package com.xzyht.notifyrelay.feature.notification.ui.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.xzyht.notifyrelay.common.core.util.DataUrlUtils
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistory
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistoryEntry
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore
import com.xzyht.notifyrelay.feature.notification.ui.dialog.SuperIslandTestDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

private const val SUPER_ISLAND_IMAGE_MAX_DIMENSION = 320
private const val SUPER_ISLAND_DOWNLOAD_MAX_BYTES = 4 * 1024 * 1024

private data class SuperIslandHistoryGroup(
    val packageName: String,
    val entries: List<SuperIslandHistoryEntry>
)

@Composable
fun UISuperIslandSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_KEY, true)) }
    var includeImageDataOnCopy by remember { mutableStateOf(StorageManager.getBoolean(context, SUPER_ISLAND_COPY_IMAGE_DATA_KEY, false)) }

    val historyState = remember(context) { SuperIslandHistory.historyState(context) }
    val history by historyState.collectAsState()
    val groups = remember(history) {
        val sorted = history.sortedByDescending { it.id }
        val grouped = sorted.groupBy { entry ->
            entry.mappedPackage?.takeIf { it.isNotBlank() }
                ?: entry.originalPackage?.takeIf { it.isNotBlank() }
                ?: "(未知应用)"
        }
        grouped.entries
            .map { (pkg, items) ->
                SuperIslandHistoryGroup(pkg, items.sortedByDescending { it.id })
            }
            .sortedByDescending { it.entries.firstOrNull()?.id ?: Long.MIN_VALUE }
    }

    // 测试对话框状态
    val showTestDialog = remember { mutableStateOf(false) }

    MiuixTheme {
        val colorScheme = MiuixTheme.colorScheme
        val textStyles = MiuixTheme.textStyles

        Scaffold(
            popupHost = { }, // 置空，避免与顶层Scaffold冲突
            // 悬浮工具栏
            floatingToolbar = {
                FloatingToolbar {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 测试按钮
                        Button(
                            onClick = {
                                showTestDialog.value = true
                            }
                        ) {
                            Text("测试超级岛分支")
                        }
                    }
                }
            },
            floatingToolbarPosition = ToolbarPosition.BottomEnd
        ) {
            Surface(color = colorScheme.background) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SuperSwitch(
                            title = "超级岛读取",
                            summary = "控制是否尝试从本机通知中读取小米超级岛数据并转发",
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                StorageManager.putBoolean(context, SUPER_ISLAND_KEY, it)
                            }
                        )

                        Spacer(modifier = Modifier.height(0.dp))

                        SuperSwitch(
                title = "复制图片详细信息",
                summary = "长按条目可复制原始消息，关闭时图片数据将在文本中替换为 \"图片\"。",
                checked = includeImageDataOnCopy,
                onCheckedChange = {
                    includeImageDataOnCopy = it
                    StorageManager.putBoolean(context, SUPER_ISLAND_COPY_IMAGE_DATA_KEY, it)
                }
            )

            Spacer(modifier = Modifier.height(0.dp))

                        if (groups.isEmpty()) {
                            Text("暂无超级岛历史记录", style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary)
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        SuperIslandHistory.clearAll(context)
                                    }
                                ) {
                                    Text("清空超级岛历史")
                                }
                            }
                        }
                    }
                    
                    if (groups.isNotEmpty()) {
                        items(groups, key = { it.packageName }) { group ->
                            SuperIslandHistoryGroupCard(group, includeImageDataOnCopy)
                        }
                    }
                }
            }
        }
        
        // 显示测试对话框
        SuperIslandTestDialog(showTestDialog, context)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuperIslandHistoryGroupCard(
    group: SuperIslandHistoryGroup,
    includeImageDataOnCopy: Boolean
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val headerEntry = group.entries.firstOrNull()
    val groupTitle = headerEntry?.appName?.takeIf { !it.isNullOrBlank() }
        ?: headerEntry?.title?.takeIf { !it.isNullOrBlank() }
        ?: group.packageName
    var expanded by rememberSaveable(group.packageName) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(groupTitle, style = textStyles.body1, color = colorScheme.onSurface)
                    Text(
                        text = "${group.packageName} · ${group.entries.size} 条记录",
                        style = textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (headerEntry != null) {
                        Text(
                            text = "最新时间: ${formatTimestamp(headerEntry.id)}",
                            style = textStyles.body2,
                            color = colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = textStyles.body2,
                    color = colorScheme.primary
                )
            }

            HorizontalDivider(color = colorScheme.outline)

            if (expanded) {
                group.entries.forEachIndexed { index, entry ->
                    SuperIslandHistoryEntryCard(entry, includeImageDataOnCopy)
                    if (index < group.entries.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = colorScheme.outline)
                    }
                }
            } else {
                val previewList = group.entries.take(3)
                previewList.forEachIndexed { index, entry ->
                    SuperIslandHistorySummaryRow(entry, includeImageDataOnCopy)
                    if (index < previewList.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = colorScheme.outline)
                    }
                }
                if (group.entries.size > previewList.size) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "... 共${group.entries.size}条，点击展开",
                        style = textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun SuperIslandHistorySummaryRow(
    entry: SuperIslandHistoryEntry,
    includeImageDataOnCopy: Boolean
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val titleText = entry.title?.takeIf { it.isNotBlank() }
        ?: entry.appName?.takeIf { it.isNotBlank() }
        ?: entry.mappedPackage?.takeIf { it.isNotBlank() }
        ?: entry.originalPackage?.takeIf { it.isNotBlank() }
        ?: "超级岛事件"

    val displayTitle = titleText.let { SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it) }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    triggerFloatingReplica(context, entry)
                },
                onLongClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val full = try { SuperIslandHistory.loadEntryDetail(context, entry.id) } catch (_: Exception) { null }
                        val final = full ?: entry
                        val text = buildEntryCopyText(final, includeImageDataOnCopy)
                        withContext(Dispatchers.Main) {
                            copyEntryToClipboard(context, text)
                        }
                    }
                }
            )
    ) {
        Text(displayTitle, style = textStyles.body2, color = colorScheme.onSurface)
        val summaryText = entry.text
        if (!summaryText.isNullOrBlank()) {
            val summaryDisplay = if (includeImageDataOnCopy) summaryText else sanitizeImageContent(summaryText, false)
            Text(
                text = summaryDisplay,
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatTimestamp(entry.id),
            style = textStyles.body2,
            color = colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.picMap.isNotEmpty()) {
            Text(
                text = "包含图片 ${entry.picMap.size} 张",
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuperIslandHistoryEntryCard(
    entry: SuperIslandHistoryEntry,
    includeImageDataOnCopy: Boolean
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    triggerFloatingReplica(context, entry)
                },
                onLongClick = {
                    coroutineScope.launch {
                        val full = try { SuperIslandHistory.loadEntryDetail(context, entry.id) } catch (_: Exception) { null }
                        val final = full ?: entry
                        val text = buildEntryCopyText(final, includeImageDataOnCopy)
                        copyEntryToClipboard(context, text)
                    }
                }
            )
    ) {
        val titleText = entry.appName?.takeIf { it.isNotBlank() }
            ?: entry.title?.takeIf { it.isNotBlank() }
            ?: entry.mappedPackage?.takeIf { it.isNotBlank() }
            ?: entry.originalPackage?.takeIf { it.isNotBlank() }
            ?: "超级岛事件"
        val displayTitle = titleText.let { SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it) }
        Text(displayTitle, style = textStyles.body1, color = colorScheme.onSurface)

        val detailText = entry.text
        if (!detailText.isNullOrBlank()) {
            val displayDetail = if (includeImageDataOnCopy) detailText else sanitizeImageContent(detailText, false)
            Text(displayDetail, style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary)
        }

        if (entry.picMap.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                entry.picMap.forEach { (key, data) ->
                    val displayKey = key.ifBlank { "(未命名图片)" }
                        SuperIslandHistoryImage(displayKey, data)
                }
            }
        }

        val mappedPackage = entry.mappedPackage
        if (!mappedPackage.isNullOrBlank()) {
            Text("映射包名: $mappedPackage", style = textStyles.body2, color = colorScheme.outline)
        }
        val originalPackage = entry.originalPackage
        if (!originalPackage.isNullOrBlank()) {
            Text("原始包名: $originalPackage", style = textStyles.body2, color = colorScheme.outline)
        }
        val sourceDevice = entry.sourceDeviceUuid
        if (!sourceDevice.isNullOrBlank()) {
            Text("来源设备: $sourceDevice", style = textStyles.body2, color = colorScheme.outline)
        }

        Text(
            text = formatTimestamp(entry.id),
            style = textStyles.body2,
            color = colorScheme.outline
        )

        val paramV2Raw = entry.paramV2Raw
        if (!paramV2Raw.isNullOrBlank()) {
            val displayParam = if (includeImageDataOnCopy) paramV2Raw else sanitizeImageContent(paramV2Raw, false)
            Text(
                text = displayParam,
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        var loadedDetail by remember { mutableStateOf<SuperIslandHistoryEntry?>(null) }
        val displayPayloadRaw = loadedDetail?.rawPayload ?: entry.rawPayload
        if (!displayPayloadRaw.isNullOrBlank()) {
            val displayPayload = if (includeImageDataOnCopy) displayPayloadRaw else sanitizeImageContent(displayPayloadRaw, false)
            Text(
                text = displayPayload,
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            // 未加载 rawPayload，提供按需加载按钮
            androidx.compose.material3.TextButton(onClick = {
                coroutineScope.launch {
                    val full = try { SuperIslandHistory.loadEntryDetail(context, entry.id) } catch (_: Exception) { null }
                    if (full != null) {
                        loadedDetail = full
                    }
                }
            }) {
                Text("加载详情")
            }
        }
    }
}

@Composable
private fun SuperIslandHistoryImage(imageKey: String, data: String, modifier: Modifier = Modifier) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val context = LocalContext.current

    val bitmap by produceState<Bitmap?>(initialValue = SuperIslandImageCache.get(data), key1 = data) {
        val cached = SuperIslandImageCache.get(data)
        if (cached != null) {
            value = cached
            return@produceState
        }

        val loaded = withContext(Dispatchers.IO) {
            try {
                // 先尝试将可能的 ref: 引用解析为原始 data: 或 http URL
                val resolved = try {
                    SuperIslandImageStore.resolve(context, data) ?: data
                } catch (_: Exception) { data }

                val decoded = when {
                    DataUrlUtils.isDataUrl(resolved) -> DataUrlUtils.decodeDataUrlToBitmap(resolved)
                    resolved.startsWith("http", ignoreCase = true) -> downloadBitmap(context, resolved)
                    else -> null
                }
                // 缓存仍以传入的 key (可能是 ref:...) 作为索引，便于下次直接命中
                decoded?.let { SuperIslandImageCache.put(data, it) }
            } catch (_: Exception) {
                null
            }
        }

        value = loaded
    }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = imageKey,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = data.take(120),
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceVariant)
                    .padding(8.dp)
            )
        }
        if (imageKey.isNotBlank()) {
            Text(
                text = imageKey,
                style = textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private suspend fun downloadBitmap(context: Context, urlString: String, timeoutMs: Int = 5_000): Bitmap? {
    return try {
        SuperIslandImageUtil.loadBitmapSuspend(context, urlString, timeoutMs)
    } catch (_: Exception) { null }
}

private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val sampleSize = computeInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, maxDimension)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
}

private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    var largestSide = max(width, height)
    while (largestSide / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

// Keeps a small in-memory cache of decoded bitmaps to avoid repeated work during recompositions.
private object SuperIslandImageCache {
    private const val MAX_CACHE_SIZE = 32
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    fun get(key: String): Bitmap? = synchronized(this) {
        val cached = cache[key]
        if (cached != null && cached.isRecycled) {
            cache.remove(key)
            return@synchronized null
        }
        cached
    }

    fun put(key: String, bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val normalized = normalizeBitmap(bitmap)
        synchronized(this) {
            cache[key] = normalized
        }
        return normalized
    }

    private fun normalizeBitmap(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        var working = source
        val largestSide = max(width, height)
        if (largestSide > SUPER_ISLAND_IMAGE_MAX_DIMENSION) {
            val scale = SUPER_ISLAND_IMAGE_MAX_DIMENSION.toFloat() / largestSide.toFloat()
            val targetWidth = max(1, (width * scale).roundToInt())
            val targetHeight = max(1, (height * scale).roundToInt())
            working = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

        if (working.config == Bitmap.Config.HARDWARE) {
            working.copy(Bitmap.Config.ARGB_8888, false)?.let { working = it }
        }
        if (working !== source && !source.isRecycled) {
            try { source.recycle() } catch (_: Exception) {}
        }
        return working
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(timestamp)).toString()
    } catch (_: Exception) {
        timestamp.toString()
    }
}

private fun buildEntryCopyText(
    entry: SuperIslandHistoryEntry,
    includeImageDataOnCopy: Boolean
): String {
    return buildString {
        appendLine("id: ${entry.id}")
        appendLine("timestamp: ${formatTimestamp(entry.id)}")
        entry.sourceDeviceUuid?.takeIf { it.isNotBlank() }?.let {
            appendLine("sourceDeviceUuid: $it")
        }
        entry.originalPackage?.takeIf { it.isNotBlank() }?.let {
            appendLine("originalPackage: $it")
        }
        entry.mappedPackage?.takeIf { it.isNotBlank() }?.let {
            appendLine("mappedPackage: $it")
        }
        entry.appName?.takeIf { it.isNotBlank() }?.let {
            appendLine("appName: $it")
        }
        entry.title?.takeIf { it.isNotBlank() }?.let {
            appendLine("title: $it")
        }
        entry.text?.takeIf { it.isNotBlank() }?.let {
            appendLine("text: ${sanitizeImageContent(it, includeImageDataOnCopy)}")
        }
        if (entry.picMap.isNotEmpty()) {
            appendLine("picMap:")
            entry.picMap.forEach { (label, data) ->
                val finalLabel = label.ifBlank { "(未命名图片)" }
                val finalData = if (includeImageDataOnCopy) data else "图片"
                appendLine("  $finalLabel: $finalData")
            }
        }
        entry.paramV2Raw?.takeIf { it.isNotBlank() }?.let {
            appendMultilineField("paramV2Raw", it, includeImageDataOnCopy)
        }
        entry.rawPayload?.takeIf { it.isNotBlank() }?.let {
            appendMultilineField("rawPayload", it, includeImageDataOnCopy)
        }
    }.trim()
}

private fun copyEntryToClipboard(context: android.content.Context, content: String) {
    if (content.isBlank()) {
        android.widget.Toast.makeText(context, "当前条目无可复制内容", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("super_island_entry", content)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "已复制原始消息到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Logger.e("NotifyRelay", "复制超级岛原始消息失败", e)
        android.widget.Toast.makeText(context, "复制失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun triggerFloatingReplica(context: Context, entry: SuperIslandHistoryEntry) {
    val sourceId = entry.mappedPackage?.takeIf { it.isNotBlank() }
        ?: entry.originalPackage?.takeIf { it.isNotBlank() }
        ?: entry.appName?.takeIf { it.isNotBlank() }
        ?: entry.id.toString()
    val title = entry.title?.takeIf { it.isNotBlank() }
        ?: entry.appName?.takeIf { it.isNotBlank() }
        ?: entry.mappedPackage?.takeIf { it.isNotBlank() }
        ?: entry.originalPackage?.takeIf { it.isNotBlank() }
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = sourceId,
        title = title,
        text = entry.text,
        paramV2Raw = entry.paramV2Raw,
        picMap = entry.picMap.takeIf { it.isNotEmpty() },
        isLocked = false
    )
}

private const val SUPER_ISLAND_KEY = "superisland_enabled"
private const val SUPER_ISLAND_COPY_IMAGE_DATA_KEY = "superisland_copy_image_data"

private fun sanitizeImageContent(source: String, includeImageDataOnCopy: Boolean): String {
    if (includeImageDataOnCopy) return source
    // 先替换可能存在的 ref: 引用为占位，避免在 UI 上显示内部引用字符串
    var sanitized = REF_URL_REGEX.replace(source) { "图片" }
    // 替换 data: URI 与常见图片 URL
    sanitized = DATA_URL_REGEX.replace(sanitized) { "图片" }
    sanitized = IMAGE_URL_REGEX.replace(sanitized) { "图片" }
    return sanitized
}

private val DATA_URL_REGEX = Regex(
    pattern = "data:[^,]+;base64,[^\\s\"]+",
    options = setOf(RegexOption.IGNORE_CASE)
)

private val IMAGE_URL_REGEX = Regex(
    pattern = "https?:[^\\s\"]+\\.(?:png|jpe?g|gif|webp|bmp|svg)",
    options = setOf(RegexOption.IGNORE_CASE)
)

// 匹配已被 intern 为引用的图片标识（例如 ref:abcdef...），展示时应替换为占位而非原样显示
private val REF_URL_REGEX = Regex(pattern = "(?i)ref:[0-9a-f]{16,}")

private fun formatMultilineContent(content: String): List<String> {
    if (content.isBlank()) return emptyList()
    prettyPrintJson(content)?.let { return it }
    return wrapPlainText(content)
}

private fun prettyPrintJson(text: String): List<String>? {
    val firstNonWhitespace = text.firstOrNull { !it.isWhitespace() } ?: return emptyList()
    if (firstNonWhitespace != '{' && firstNonWhitespace != '[') return null
    return try {
        val jsonElement = JsonParser.parseString(text)
        val pretty = prettyGson.toJson(jsonElement)
        pretty.lineSequence()
            .flatMap { wrapPlainText(it).asSequence() }
            .toList()
    } catch (_: Exception) {
        null
    }
}

private fun wrapPlainText(text: String): List<String> {
    val firstNonWhitespaceIndex = text.indexOfFirst { !it.isWhitespace() }
    val indent = if (firstNonWhitespaceIndex > 0) text.substring(0, firstNonWhitespaceIndex) else ""
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length <= SANITIZED_LINE_WRAP) return listOf(indent + trimmed)
    val result = mutableListOf<String>()
    var remaining = trimmed
    while (remaining.length > SANITIZED_LINE_WRAP) {
        val window = remaining.substring(0, SANITIZED_LINE_WRAP)
        val breakIndex = window.lastIndexOfAny(WRAP_BREAK_CHARS)
        val cut = if (breakIndex <= 0) SANITIZED_LINE_WRAP else breakIndex + 1
        val segment = remaining.substring(0, cut).trimEnd()
        result += indent + segment
        remaining = remaining.substring(cut).trimStart()
    }
    if (remaining.isNotEmpty()) {
        result += indent + remaining
    }
    return result
}

private val prettyGson by lazy { GsonBuilder().setPrettyPrinting().create() }

private const val SANITIZED_LINE_WRAP = 80
private val WRAP_BREAK_CHARS = charArrayOf(',', ' ', ';', ')', ']', '}', '"')

private fun StringBuilder.appendMultilineField(
    label: String,
    content: String,
    includeImageDataOnCopy: Boolean
) {
    val sanitized = sanitizeImageContent(content, includeImageDataOnCopy).trim()
    if (sanitized.isBlank()) return
    appendLine("$label:")
    formatMultilineContent(sanitized).forEach { line ->
        appendLine("  $line")
    }
}
