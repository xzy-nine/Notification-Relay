package com.xzyht.notifyrelay

import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManagerUtil
import com.xzyht.notifyrelay.data.Notify.NotificationRepository
import com.xzyht.notifyrelay.data.Notify.NotificationRecord
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
fun NotificationCard(record: com.xzyht.notifyrelay.data.Notify.NotificationRecord, appName: String, appIcon: android.graphics.Bitmap?) {
    val notificationTextStyles = MiuixTheme.textStyles
    val cardColorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
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
                    val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "notifyrelay_temp"
                    // 仅支持 API 26+，不再兼容旧版
                    if (notificationManager.getNotificationChannel(channelId) == null) {
                        val channel = android.app.NotificationChannel(channelId, "跳转通知", android.app.NotificationManager.IMPORTANCE_HIGH)
                        channel.description = "应用内跳转指示通知"
                        channel.enableLights(true)
                        channel.lightColor = android.graphics.Color.BLUE
                        channel.enableVibration(false)
                        channel.setSound(null, null)
                        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        channel.setShowBadge(false)
                        channel.importance = android.app.NotificationManager.IMPORTANCE_HIGH
                        channel.setBypassDnd(true)
                        notificationManager.createNotificationChannel(channel)
                    }
                    val builder = android.app.Notification.Builder(context, channelId)
                    builder.setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                        .setAutoCancel(true)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .setOngoing(false)
                    // 设置应用图标
                    if (appIcon != null) {
                        builder.setLargeIcon(appIcon)
                    }
                    // 发送通知，ID用当前时间戳
                    val notifyId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    notificationManager.notify(notifyId, builder.build())
                    // 2.5秒后自动销毁通知
                    android.os.Handler(context.mainLooper).postDelayed({
                        notificationManager.cancel(notifyId)
                    }, 2500)
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
                top.yukonga.miuix.kmp.basic.Text(
                    text = appName,
                    style = notificationTextStyles.body2.copy(color = cardColorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // ...设备名标识已移除...
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
    // 设备选择逻辑交由DeviceListFragment统一管理，这里只读取当前设备
    val selectedDevice = NotificationRepository.currentDevice
    val notifications = remember { mutableStateListOf<com.xzyht.notifyrelay.data.Notify.NotificationRecord>() }
    LaunchedEffect(selectedDevice) {
        NotificationRepository.currentDevice = selectedDevice
        NotificationRepository.init(context)
        val store = com.xzyht.notifyrelay.data.Notify.NotifyRelayStoreProvider.getInstance(context)
        val fileKey = if (selectedDevice == "本机") "local" else selectedDevice
        val history = store.getAll(fileKey)
        notifications.clear()
        notifications.addAll(history.map {
            com.xzyht.notifyrelay.data.Notify.NotificationRecord(
                key = it.key,
                packageName = it.packageName,
                title = it.title,
                text = it.text,
                time = it.time,
                device = it.device
            )
        })
        android.util.Log.i("NotifyRelay", "[NotificationHistoryScreen] selectedDevice=$selectedDevice, fileKey=$fileKey, loaded history.size=${notifications.size}")
    }
    val grouped = notifications.groupBy { it.packageName }
    val groupList = grouped.entries.map { (_, list) ->
        list.sortedByDescending { it.time }
    }
    // 混合排序：单条和分组都按分组最新时间降序排列
    val mixedList = groupList.sortedByDescending { it.firstOrNull()?.time ?: 0L }
    // val context = LocalContext.current // 删除重复声明，避免冲突
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
                // statusBarColor 已废弃，推荐使用 WindowInsetsControllerCompat 控制外观
                // 但 Miuix 主题要求背景色一致，暂保留设置
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
            notifications.clear() // 清空通知列表
            appInfoCache.clear() // 清空应用信息缓存
            // 修正：同步清理本地json文件内容
            val store = com.xzyht.notifyrelay.data.Notify.NotifyRelayStoreProvider.getInstance(context)
            val fileKey = if (selectedDevice == "本机") "local" else selectedDevice
            kotlinx.coroutines.runBlocking {
                store.clearByDevice(fileKey)
            }
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
        notifications: List<com.xzyht.notifyrelay.data.Notify.NotificationRecord>,
        mixedList: List<List<com.xzyht.notifyrelay.data.Notify.NotificationRecord>>,
        getCachedAppInfo: (String?) -> Pair<String, android.graphics.Bitmap?>
    ) {
        if (notifications.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(mixedList) { list ->
                    if (list.size <= 2) {
                        list.forEach { record ->
                            val (_, appIcon) = getCachedAppInfo(record.packageName)
                            NotificationCard(record, record.title ?: "(无标题)", appIcon)
                        }
                    } else {
                        val latest = list.maxByOrNull { it.time }
                        var expanded by remember { mutableStateOf(false) }
                        val (appName, appIcon) = getCachedAppInfo(latest?.packageName)
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
                                        text = appName,
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
                                                modifier = Modifier.weight(0.6f)
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
                                        val (appName1, appIcon1) = getCachedAppInfo(record.packageName)
                                        NotificationCard(record, appName1, appIcon1)
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
                        val intent = android.content.Intent(context, com.xzyht.notifyrelay.GuideActivity::class.java)
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