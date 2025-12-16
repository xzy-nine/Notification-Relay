package com.xzyht.notifyrelay.feature.notification.ui

// ========================= 基础组件导入区 =========================
// Android 基础组件
import android.os.Bundle
import android.util.Log
// Compose 核心布局与手势相关
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
// Compose 状态与UI基础
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
// AndroidX 碎片管理
import androidx.fragment.app.Fragment

// ========================= 业务模块导入区 =========================
// 项目构建配置
import com.xzyht.notifyrelay.BuildConfig
// 数据仓库层
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
// 全局状态管理
import com.xzyht.notifyrelay.feature.device.ui.GlobalSelectedDeviceHolder
// 数据模型
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecord

// ========================= 第三方UI库导入区 =========================
// MIUI X 跨平台UI组件
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Delete
import top.yukonga.miuix.kmp.basic.CardDefaults

// ========================= 工具函数/常量 =========================
import kotlin.math.roundToInt


enum class DragValue { Center, End }

// 防抖 Toast（文件级顶层对象）
object ToastDebounce {
    var lastToastTime: Long = 0L
    const val DEBOUNCE_MILLIS: Long = 1500L
}

@Composable
fun DeleteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = {
            if (BuildConfig.DEBUG) Log.d("NotifyRelay", "轮胎: 删除按钮被点击")
            onClick()
        },
        modifier = modifier.fillMaxHeight().width(80.dp),
        backgroundColor = Color.Red,
        cornerRadius = 8.dp,
        minHeight = 40.dp,
        minWidth = 80.dp
    ) {
        Icon(
            imageVector = MiuixIcons.Useful.Delete,
            contentDescription = "Settings",
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun NotificationCard(
    record: NotificationRecord, 
    appIcon: android.graphics.Bitmap?, 
    context: android.content.Context, 
    getCachedAppInfo: (String?) -> Pair<String, android.graphics.Bitmap?>,
    cardColor: Color,
    contentColor: Color
) {
    val notificationTextStyles = MiuixTheme.textStyles
    val cardColorScheme = MiuixTheme.colorScheme
    
    // 对包名进行等价映射，使用缓存的包名集合，避免同步加载
    val installedPkgs = AppRepository.getInstalledPackageNames(context)
    val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(record.packageName, installedPkgs)
    
    // 使用映射后的包名获取应用信息
    val appInfo: Pair<String, android.graphics.Bitmap?> = getCachedAppInfo(mappedPkg)
    val (_, mappedAppIcon) = appInfo
    val displayAppIcon = mappedAppIcon ?: appIcon
    
    // 修正：单条通知卡片标题应为原始通知标题
    val displayTitle = record.title ?: "(无标题)"
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = {
            // 跳转到对应应用主界面
            val pkg = record.packageName
            if (pkg.isNotEmpty()) {
                // 应用等价映射，使用缓存的包名集合，避免同步加载
                val installedPkgs = AppRepository.getInstalledPackageNames(context)
                val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(pkg, installedPkgs)
                
                var canOpen = false
                var intent: android.content.Intent? = null
                try {
                    intent = context.packageManager.getLaunchIntentForPackage(mappedPkg)
                    if (intent != null) {
                        canOpen = true
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - ToastDebounce.lastToastTime > ToastDebounce.DEBOUNCE_MILLIS) {
                            android.widget.Toast.makeText(context, "无法打开应用：$mappedPkg", android.widget.Toast.LENGTH_SHORT).show()
                            ToastDebounce.lastToastTime = now
                        }
                    }
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (now - ToastDebounce.lastToastTime > ToastDebounce.DEBOUNCE_MILLIS) {
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
        cornerRadius = 8.dp,
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(
            color = cardColor,
            contentColor = contentColor
        ),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (displayAppIcon != null) {
                Image(
                    bitmap = displayAppIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            // 标题显示为原始通知标题
            Text(
                text = displayTitle,
                style = notificationTextStyles.body2.copy(color = cardColorScheme.onSurface)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = record.text ?: "(无内容)",
            style = notificationTextStyles.body1.copy(color = cardColorScheme.onSurface)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(record.time)),
            style = notificationTextStyles.body2.copy(color = cardColorScheme.onSurfaceSecondary)
        )
    }
}

// 工具函数：获取应用名和图标（文件级顶层）
fun getAppNameAndIcon(context: android.content.Context, packageName: String?): Pair<String, android.graphics.Bitmap?> {
    var name = packageName ?: ""
    var icon: android.graphics.Bitmap? = null
    if (packageName != null) {
        try {
            // 优先使用缓存的图标（同步版本）
            icon = AppRepository.getAppIconSync(context, packageName)
            if (icon != null) {
                // 如果有缓存图标，获取应用名
                name = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    packageName
                }
            } else {
                // 尝试获取外部应用图标（来自其他设备的同步）
                icon = AppRepository.getExternalAppIcon(packageName)
                if (icon != null) {
                    // 如果有外部图标，使用记录中的应用名或包名
                    name = packageName // 外部应用使用包名作为应用名
                } else {
                    // 如果都没有，尝试直接获取（本地安装的应用）
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    name = pm.getApplicationLabel(appInfo).toString()
                    val drawable = pm.getApplicationIcon(appInfo)
                    icon = drawableToBitmap(drawable)
                }
            }
        } catch (_: Exception) {
            // 如果本地应用不存在，尝试获取外部应用图标
            icon = AppRepository.getExternalAppIcon(packageName)
            if (icon != null) {
                name = packageName // 外部应用使用包名作为应用名
            } else {
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
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): android.view.View {
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
        NotificationRepository.notifyHistoryChanged(selectedDevice, context)
    }
    // 订阅当前分组的通知历史
    val notifications by NotificationRepository.notificationHistoryFlow.collectAsState()

    val mixedList by remember(notifications) {
        derivedStateOf {
            // 使用缓存的包名集合，避免同步加载
            val installedPkgs = AppRepository.getInstalledPackageNames(context)
            val grouped = notifications.groupBy { record ->
                // 使用映射后的包名进行分组
                com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(record.packageName, installedPkgs)
            }
            val unifiedList = mutableListOf<List<NotificationRecord>>()
            for (entry in grouped.entries) {
                val list = entry.value.sortedByDescending { it.time }
                unifiedList.add(list)
            }
            unifiedList.sortedByDescending { it.firstOrNull()?.time ?: 0L }
        }
    }
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
            // 移除状态栏背景色设置，避免编译错误
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

    val clearHistory: () -> Unit = {
        try {
            // 只清除当前设备的历史，不删除其他设备的历史文件
            NotificationRepository.clearDeviceHistory(selectedDevice, context)
            appInfoCache.clear() // 清空应用信息缓存
            // 同步清理当前设备的本地json文件内容
            val store = com.xzyht.notifyrelay.common.data.PersistenceManager
            val fileKey = if (selectedDevice == "本机") "local" else selectedDevice
            kotlinx.coroutines.runBlocking {
                store.clearNotificationRecords(context, fileKey)
            }
            // 主动刷新 StateFlow
            NotificationRepository.notifyHistoryChanged(selectedDevice, context)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay", "清除历史异常", e)
            android.widget.Toast.makeText(
                context,
                "清除失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { 80.dp.toPx() }

    // 通用通知列表块
    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun NotificationListBlock(
        notifications: List<NotificationRecord>,
        mixedList: List<List<NotificationRecord>>,
        getCachedAppInfo: (String?) -> Pair<String, android.graphics.Bitmap?>
    ) {
        if (notifications.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(mixedList) { list ->
                    val anchoredDraggableState = remember {
                        AnchoredDraggableState(
                            initialValue = DragValue.Center
                        )
                    }
                    val anchors = DraggableAnchors {
                        DragValue.Center at 0f
                        DragValue.End at -deleteWidthPx
                    }
                    LaunchedEffect(anchors) {
                        anchoredDraggableState.updateAnchors(anchors)
                    }
                    LaunchedEffect(anchoredDraggableState.currentValue) {
                        if (anchoredDraggableState.currentValue == DragValue.End) {
                            if (BuildConfig.DEBUG) Log.d("NotifyRelay", "轮胎: 左滑显示删除按钮")
                        }
                    }
                    val offset = when {
                        anchoredDraggableState.currentValue == DragValue.End -> -deleteWidthPx
                        anchoredDraggableState.offset.isNaN() -> 0f
                        else -> anchoredDraggableState.offset
                    }
                    val deleteWidth = 80.dp
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 卡片
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .anchoredDraggable(
                                    state = anchoredDraggableState,
                                    orientation = Orientation.Horizontal
                                )
                                .offset { IntOffset(offset.roundToInt(), 0) }
                        ) {
                            if (list.size == 1) {
                                val record = list[0]
                                val (_, appIcon) = getCachedAppInfo(record.packageName)
                                NotificationCard(
                                    record = record, 
                                    appIcon = appIcon, 
                                    context = context, 
                                    getCachedAppInfo = getCachedAppInfo,
                                    cardColor = colorScheme.surface,
                                    contentColor = colorScheme.onSurface
                                )
                            } else {
                                val latest = list.maxByOrNull { it.time }
                                var expanded by remember { mutableStateOf(false) }
                                // 使用映射后的包名获取应用信息，使用缓存的包名集合，避免同步加载
                                val installedPkgs = AppRepository.getInstalledPackageNames(context)
                                val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(latest?.packageName ?: "", installedPkgs)
                                val appInfo: Pair<String, android.graphics.Bitmap?> = getCachedAppInfo(mappedPkg)
                                val (appName, appIcon) = appInfo
                                // 修正：分组折叠标题优先显示json中的appName字段，其次本地应用名，再次包名，最后(未知应用)
                                val groupTitle = when {
                                    !latest?.appName.isNullOrBlank() -> latest.appName
                                    appName.isNotBlank() -> appName
                                    mappedPkg.isNotBlank() -> mappedPkg
                                    else -> "(未知应用)"
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    onClick = { expanded = !expanded },
                                    cornerRadius = 12.dp,
                                    insideMargin = PaddingValues(12.dp),
                                    colors = CardDefaults.defaultColors(
                                        color = colorScheme.surface,
                                        contentColor = colorScheme.onSurface
                                    ),
                                    showIndication = true,
                                    pressFeedbackType = PressFeedbackType.Sink
                                ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
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
                                            Text(
                                                text = groupTitle,
                                                style = textStyles.title3.copy(color = colorScheme.onSurface)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = (latest?.time?.let {
                                                    java.text.SimpleDateFormat(
                                                        "yyyy-MM-dd HH:mm:ss",
                                                        java.util.Locale.US
                                                    ).format(java.util.Date(it))
                                                } ?: ""),
                                                style = textStyles.body2.copy(color = colorScheme.onSurfaceSecondary)
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(
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
                                                        Text(
                                                            text = record.title ?: "(无标题)",
                                                            style = textStyles.body2.copy(
                                                                color = colorScheme.onSurface,
                                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.weight(0.4f)
                                                        )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = record.text ?: "(无内容)",
                                                        style = textStyles.body2.copy(color = colorScheme.onSurfaceSecondary),
                                                        modifier = Modifier.weight(0.6f),
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (idx < showList.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                        color = colorScheme.outline,
                                                        thickness = 1.dp
                                                    )
                                                }
                                            }
                                            if (list.size > 3) {
                                                Text(
                                                    text = "... 共${list.size}条，点击展开",
                                                    style = textStyles.body2.copy(color = colorScheme.outline)
                                                )
                                            }
                                        } else {
                                            // 优化：限制展开时显示的通知数量，避免性能问题
                                            val expandedList = list.sortedByDescending { it.time }
                                            val maxExpandedItems = 50 // 限制最多显示50条通知
                                            val displayList = if (expandedList.size > maxExpandedItems) {
                                                expandedList.take(maxExpandedItems)
                                            } else {
                                                expandedList
                                            }

                                            displayList.forEach { record ->
                                                val density = LocalDensity.current
                                                val anchoredDraggableState = remember {
                                                    AnchoredDraggableState(
                                                        initialValue = DragValue.Center
                                                    )
                                                }
                                                val deleteWidthPx = with(density) { 80.dp.toPx() }
                                                val anchors = DraggableAnchors {
                                                    DragValue.Center at 0f
                                                    DragValue.End at -deleteWidthPx
                                                }
                                                LaunchedEffect(anchors) {
                                                    anchoredDraggableState.updateAnchors(anchors)
                                                }
                                                LaunchedEffect(anchoredDraggableState.currentValue) {
                                                    if (anchoredDraggableState.currentValue == DragValue.End) {
                                                        if (BuildConfig.DEBUG) Log.d("NotifyRelay", "轮胎: 左滑显示删除按钮 - 展开列表")
                                                    }
                                                }
                                                val offset = when {
                                                    anchoredDraggableState.currentValue == DragValue.End -> -deleteWidthPx
                                                    anchoredDraggableState.offset.isNaN() -> 0f
                                                    else -> anchoredDraggableState.offset
                                                }
                                                val deleteWidth = 80.dp
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                    // 卡片
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .anchoredDraggable(
                                                                state = anchoredDraggableState,
                                                                orientation = Orientation.Horizontal
                                                            )
                                                            .offset { IntOffset(offset.roundToInt(), 0) }
                                                    ) {
                                                        val (_, appIcon1) = getCachedAppInfo(record.packageName)
                                                        NotificationCard(
                                                            record = record,
                                                            appIcon = appIcon1,
                                                            context = context,
                                                            getCachedAppInfo = getCachedAppInfo,
                                                            cardColor = colorScheme.surfaceContainer,
                                                            contentColor = colorScheme.onSurface
                                                        )
                                                    }
                                                    // 删除按钮
                                                    if (anchoredDraggableState.currentValue == DragValue.End) {
                                                        DeleteButton(
                                                            onClick = {
                                                                NotificationRepository.currentDevice = selectedDevice
                                                                if (BuildConfig.DEBUG) Log.d("NotifyRelay", "轮胎: 删除按钮点击 - 展开列表单个通知, key=${record.key}")
                                                                NotificationRepository.removeNotification(record.key, context)
                                                                NotificationRepository.notifyHistoryChanged(selectedDevice, context)
                                                            },
                                                            modifier = Modifier.align(Alignment.CenterEnd).width(deleteWidth).fillMaxHeight()
                                                        )
                                                    }
                                                }
                                            }

                                            // 如果通知数量超过限制，显示提示信息
                                            if (expandedList.size > maxExpandedItems) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = "... 仅显示前${maxExpandedItems}条，共${expandedList.size}条通知",
                                                    style = textStyles.body2.copy(color = colorScheme.outline)
                                                )
                                            }
                                        }
                                }
                            }
                        }
                        // 删除按钮
                        if (anchoredDraggableState.currentValue == DragValue.End) {
                            DeleteButton(
                                onClick = {
                                    NotificationRepository.currentDevice = selectedDevice
                                    if (BuildConfig.DEBUG) Log.d("NotifyRelay", "轮胎: 删除按钮点击, key=${list[0].key}, size=${list.size}")
                                    try {
                                        if (list.size == 1) {
                                            NotificationRepository.removeNotification(list[0].key, context)
                                            NotificationRepository.notifyHistoryChanged(selectedDevice, context)
                                        } else {
                                            NotificationRepository.removeNotificationsByPackage(list[0].packageName, context)
                                            NotificationRepository.notifyHistoryChanged(selectedDevice, context)
                                        }
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) Log.e("NotifyRelay", "删除失败", e)
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).width(deleteWidth).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }

    // 使用 Miuix Scaffold 重构布局
    Scaffold(
        containerColor = colorScheme.background,
        popupHost = {  },
        floatingToolbar = {
            if (notifications.isNotEmpty()) {
                FloatingToolbar(
                    color = colorScheme.primary,
                    cornerRadius = 20.dp,
                    showDivider = false
                ) {
                    // 使用Row水平排列按钮
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 清除按钮 - 始终显示
                        Button(
                            onClick = {
                                if (BuildConfig.DEBUG) Log.d("NotifyRelay", "清除按钮点击事件触发")
                                clearHistory()
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            cornerRadius = 16.dp,
                            minWidth = 0.dp,
                            minHeight = 0.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "清除",
                                style = textStyles.body2.copy(color = colorScheme.onPrimary)
                            )
                        }
                        
                        // 垂直分割线
                        if (BuildConfig.DEBUG) {
                            VerticalDivider(
                                thickness = 1.dp,
                                modifier = Modifier.height(30.dp)
                            )
                        }
                        
                        // 引导按钮 - 仅在DEBUG模式下显示
                        if (BuildConfig.DEBUG) {
                            Button(
                                onClick = {
                                    Log.d("NotifyRelay", "引导按钮点击事件触发")
                                    try {
                                        // 跳转引导页面
                                        val intent = android.content.Intent(context, com.xzyht.notifyrelay.feature.guide.GuideActivity::class.java)
                                        intent.putExtra("fromInternal", true)
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                        Log.d("NotifyRelay", "引导跳转成功")
                                    } catch (e: Exception) {
                                        Log.e("NotifyRelay", "引导跳转失败", e)
                                        android.widget.Toast.makeText(
                                            context,
                                            "跳转失败: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                cornerRadius = 16.dp,
                                minWidth = 0.dp,
                                minHeight = 0.dp,
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "引导",
                                    style = textStyles.body2.copy(color = colorScheme.onPrimary)
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomEnd,
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = paddingValues.calculateTopPadding() + 16.dp,
                        bottom = paddingValues.calculateBottomPadding() + 16.dp,
                        start = 16.dp,
                        end = 16.dp
                    )
            ) {
                Text(
                    text = "通知历史",
                    style = textStyles.title2.copy(color = colorScheme.onSurface)
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (notifications.isEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无通知",
                        style = textStyles.body1.copy(color = colorScheme.onSurfaceSecondary)
                    )
                } else {
                    NotificationListBlock(
                        notifications = notifications,
                        mixedList = mixedList,
                        getCachedAppInfo = { pkg -> getCachedAppInfo(pkg) }
                    )
                }
            }
        }
    )
}