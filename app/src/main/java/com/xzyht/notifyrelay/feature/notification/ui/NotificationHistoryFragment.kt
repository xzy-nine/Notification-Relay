package com.xzyht.notifyrelay.feature.notification.ui

import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecord
import com.xzyht.notifyrelay.feature.device.ui.GlobalSelectedDeviceHolder
import com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment
import com.xzyht.notifyrelay.feature.guide.GuideActivity
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xzyht.notifyrelay.R
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView

// 防抖 Toast（文件级顶层对象）
object ToastDebounce {
    var lastToastTime: Long = 0L
    const val debounceMillis: Long = 1500L
}

@Composable
fun NotificationCard(record: NotificationRecord, appName: String, appIcon: android.graphics.Bitmap?) {
    val notificationTextStyles = MiuixTheme.textStyles
    val cardColorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    // 修正：单条通知卡片标题应为原始通知标题
    val displayTitle = record.title ?: "(无标题)"
    val displayAppName = record.appName ?: appName
    Surface(
        onClick = {
            // 跳转到对应应用主界面
            val pkg = record.packageName
            if (!pkg.isNullOrEmpty()) {
                var canOpen = false
                var intent: android.content.Intent? = null
                try {
                    intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        canOpen = true
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - ToastDebounce.lastToastTime > ToastDebounce.debounceMillis) {
                            android.widget.Toast.makeText(context, "无法打开应用：$pkg", android.widget.Toast.LENGTH_SHORT).show()
                            ToastDebounce.lastToastTime = now
                        }
                    }
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (now - ToastDebounce.lastToastTime > ToastDebounce.debounceMillis) {
                        android.widget.Toast.makeText(context, "启动失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        ToastDebounce.lastToastTime = now
                    }
                }
                // 仅在即将跳转前显示通知标题和内容
                if (canOpen) {
                    // 发送高优先级悬浮通知
                    val title = record.title ?: "(无标题)"
                    val text = record.text ?: "(无内容)"
                    com.xzyht.notifyrelay.core.util.MessageSender.sendHighPriorityNotification(context, title, text)
                    intent!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = cardColorScheme.surfaceContainerHighest,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // 标题显示为原始通知标题
                top.yukonga.miuix.kmp.basic.Text(
                    text = displayTitle,
                    style = notificationTextStyles.body2.copy(color = cardColorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = record.text ?: "(无内容)",
                style = notificationTextStyles.body1.copy(color = cardColorScheme.onBackground)
            )
            Spacer(modifier = Modifier.height(4.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(record.time)),
                style = notificationTextStyles.body2.copy(color = cardColorScheme.outline)
            )
        }
    }
}

// 工具函数：获取应用名和图标（文件级顶层）
fun getAppNameAndIcon(context: android.content.Context, packageName: String?): Pair<String, android.graphics.Bitmap?> {
    var name = packageName ?: ""
    var icon: android.graphics.Bitmap? = null
    if (packageName != null) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            name = pm.getApplicationLabel(appInfo).toString()
            val drawable = pm.getApplicationIcon(appInfo)
            icon = drawableToBitmap(drawable)
        } catch (_: Exception) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(context.packageName, 0)
                name = pm.getApplicationLabel(appInfo).toString()
                val drawable = pm.getApplicationIcon(appInfo)
                icon = drawableToBitmap(drawable)
            } catch (_: Exception) {
                icon = null
            }
        }
    }
    return name to icon
}

// 工具函数：Drawable转Bitmap（文件级顶层）
fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        val bmp = drawable.bitmap
        if (bmp != null) return bmp
    }
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}


class NotificationHistoryFragment : Fragment() {
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): android.view.View? {
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    NotificationHistoryScreen()
                }
            }
        }
    }
}

@Composable
fun NotificationHistoryScreen() {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val context = LocalContext.current
    // 响应全局设备选中状态
    val selectedDeviceObj by GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceObj?.uuid ?: "本机"
    // 切换设备时，先切换 currentDevice，再刷新 flow
    LaunchedEffect(selectedDevice) {
        NotificationRepository.currentDevice = selectedDevice
        NotificationRepository.init(context)
        NotificationRepository.notifyHistoryChanged(selectedDevice, context)
    }
    // 订阅当前分组的通知历史
    val notifications by NotificationRepository.notificationHistoryFlow.collectAsState()

    val grouped = notifications.groupBy { it.packageName }
    val groupList = mutableListOf<List<NotificationRecord>>()
    val singleList = mutableListOf<NotificationRecord>()
    for (entry in grouped.entries) {
        val list = entry.value
        if (list.size >= 2) {
            groupList.add(list.sortedByDescending { it.time })
        } else {
            singleList.addAll(list)
        }
    }
    // 混合排序：分组按分组最新时间降序，单条按时间降序，合并展示
    val mixedList = groupList.sortedByDescending { it.firstOrNull()?.time ?: 0L } + singleList.sortedByDescending { it.time }.map { listOf(it) }
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    // 包名到应用名和图标的缓存
    val appInfoCache = remember { mutableStateMapOf<String, Pair<String, android.graphics.Bitmap?>>() }
    // 设置系统状态栏字体颜色和背景色
    LaunchedEffect(isDarkTheme) {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            val decorView = it.decorView
            // 统一使用 WindowInsetsControllerCompat 设置状态栏字体颜色
            androidx.core.view.WindowInsetsControllerCompat(it, decorView).isAppearanceLightStatusBars = !isDarkTheme
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                it.statusBarColor = colorScheme.background.toArgb()
            }
        }
    }
    LaunchedEffect(Unit) {
        NotificationRepository.init(context)
    }

    // 工具函数：获取并缓存应用名和图标
    fun getCachedAppInfo(packageName: String?): Pair<String, android.graphics.Bitmap?> {
        if (packageName == null) return "" to null
        return appInfoCache.getOrPut(packageName) {
            getAppNameAndIcon(context, packageName)
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val clearHistory: () -> Unit = {
        try {
            NotificationRepository.clearDeviceHistory(selectedDevice, context)
            appInfoCache.clear() // 清空应用信息缓存
            // 修正：同步清理本地json文件内容
            val store = com.xzyht.notifyrelay.feature.device.model.NotifyRelayStoreProvider.getInstance(context)
            val fileKey = if (selectedDevice == "本机") "local" else selectedDevice
            kotlinx.coroutines.runBlocking {
                store.clearByDevice(fileKey)
            }
            // 新增：仅删除当前设备和所有已不在认证设备列表的通知历史文件（本机除非当前选中，否则不删）
            try {
                val files = context.filesDir.listFiles()?.filter { it.name.startsWith("notification_records_") && it.name.endsWith(".json") } ?: emptyList()
                // 获取当前认证设备uuid集合（含本机local）
                val authedUuids: Set<String> = try {
                    val deviceManager = DeviceForwardFragment.getDeviceManager(context)
                    val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val map = field.get(deviceManager) as? Map<String, *>
                    val set = map?.filter { entry ->
                        val v = entry.value
                        v?.let {
                            val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                            isAcceptedField.getBoolean(v)
                        } ?: false
                    }?.keys?.toSet() ?: emptySet()
                    set + "local"
                } catch (_: Exception) {
                    setOf("local")
                }
                for (file in files) {
                    val name = file.name.removePrefix("notification_records_").removeSuffix(".json")
                    // 当前设备的历史文件始终删除
                    if (name == fileKey) {
                        try { file.delete() } catch (_: Exception) {}
                        continue
                    }
                    // 不是当前设备，且不在认证设备列表，且不是本机（除非当前选中）
                    if (!authedUuids.contains(name) && !(name == "local" && fileKey != "local")) {
                        try { file.delete() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            // 主动刷新 StateFlow
            NotificationRepository.notifyHistoryChanged(selectedDevice, context)
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "清除历史异常", e)
            android.widget.Toast.makeText(
                context,
                "清除失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    // 通用通知列表块
    @Composable
    fun NotificationListBlock(
        notifications: List<NotificationRecord>,
        mixedList: List<List<NotificationRecord>>,
        getCachedAppInfo: (String?) -> Pair<String, android.graphics.Bitmap?>
    ) {
        if (notifications.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(mixedList) { list ->
                    if (list.size == 1) {
                        val record = list[0]
                        val (localAppName, appIcon) = getCachedAppInfo(record.packageName)
                        NotificationCard(record, localAppName, appIcon)
                    } else {
                        val latest = list.maxByOrNull { it.time }
                        var expanded by remember { mutableStateOf(false) }
                        val (appName, appIcon) = getCachedAppInfo(latest?.packageName)
                        // 修正：分组折叠标题优先显示json中的appName字段，其次本地应用名，再次包名，最后(未知应用)
                        val groupTitle = when {
                            !latest?.appName.isNullOrBlank() -> latest?.appName!!
                            !appName.isNullOrBlank() -> appName
                            !latest?.packageName.isNullOrBlank() -> latest?.packageName!!
                            else -> "(未知应用)"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .then(if (!expanded) Modifier.clickable { expanded = true } else Modifier),
                            color = colorScheme.surfaceContainerHighest,
                            cornerRadius = 12.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = if (expanded)
                                        Modifier.fillMaxWidth().clickable { expanded = false }
                                    else Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (appIcon != null) {
                                        Image(
                                            bitmap = appIcon.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = groupTitle,
                                        style = textStyles.title3.copy(color = colorScheme.onBackground)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = "最新时间: " + (latest?.time?.let {
                                            java.text.SimpleDateFormat(
                                                "yyyy-MM-dd HH:mm:ss"
                                            ).format(java.util.Date(it))
                                        } ?: ""),
                                        style = textStyles.body2.copy(color = colorScheme.onBackground)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = if (expanded) "收起" else "展开",
                                        style = textStyles.body2.copy(color = colorScheme.primary)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                val showList = if (expanded) list.sortedByDescending { it.time } else list.sortedByDescending { it.time }.take(3)
                                if (!expanded) {
                                    showList.forEachIndexed { idx, record ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 修正：标题应为原始通知标题而非应用名
                                            top.yukonga.miuix.kmp.basic.Text(
                                                text = record.title ?: "(无标题)",
                                                style = textStyles.body2.copy(
                                                    color = androidx.compose.ui.graphics.Color(0xFF0066B2),
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                ),
                                                modifier = Modifier.weight(0.4f)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            top.yukonga.miuix.kmp.basic.Text(
                                                text = record.text ?: "(无内容)",
                                                style = textStyles.body2.copy(color = colorScheme.onBackground),
                                                modifier = Modifier.weight(0.6f),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                        if (idx < showList.lastIndex) {
                                            top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                color = colorScheme.outline,
                                                thickness = 1.dp
                                            )
                                        }
                                    }
                                    if (list.size > 3) {
                                        top.yukonga.miuix.kmp.basic.Text(
                                            text = "... 共${list.size}条，点击展开",
                                            style = textStyles.body2.copy(color = colorScheme.outline)
                                        )
                                    }
                                } else {
                                    list.sortedByDescending { it.time }.forEach { record ->
                                        val (localAppName, appIcon1) = getCachedAppInfo(record.packageName)
                                        NotificationCard(record, localAppName, appIcon1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 只显示通知列表和清除按钮
    Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(16.dp)
        ) {
            top.yukonga.miuix.kmp.basic.Text(
                text = "通知历史",
                style = textStyles.title2.copy(color = colorScheme.onBackground)
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (notifications.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                top.yukonga.miuix.kmp.basic.Text(
                    text = "暂无通知",
                    style = textStyles.body1.copy(color = colorScheme.onBackground)
                )
            } else {
                NotificationListBlock(
                    notifications = notifications,
                    mixedList = mixedList,
                    getCachedAppInfo = { pkg -> getCachedAppInfo(pkg) }
                )
            }
        }
        // 悬浮清除按钮
        if (notifications.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Card(
                    modifier = Modifier.padding(24.dp),
                    color = colorScheme.primary,
                    cornerRadius = 24.dp,
                    pressFeedbackType = PressFeedbackType.Sink,
                    showIndication = true,
                    onClick = clearHistory,
                    onLongPress = {
                        val intent = android.content.Intent(context, com.xzyht.notifyrelay.feature.guide.GuideActivity::class.java)
                        intent.putExtra("fromInternal", true)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                ) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = "清除",
                        style = textStyles.body2.copy(color = colorScheme.onPrimary),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}