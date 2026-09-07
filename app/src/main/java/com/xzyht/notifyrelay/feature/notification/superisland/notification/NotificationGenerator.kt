package com.xzyht.notifyrelay.feature.notification.superisland.notification

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.xzyht.notifyrelay.feature.notification.superisland.receiver.NotificationBroadcastReceiver
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.formatter.SuperIslandDataFormatter
import com.xzyht.notifyrelay.feature.notification.superisland.config.SuperIslandConfigUtils
import com.xzyht.notifyrelay.feature.notification.superisland.data.SuperIslandStructuredDataHelper
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaMappingManager
import github.xzynine.superislandui.common.BitmapUtils
import github.xzynine.superislandui.common.CapsuleScrollManager
import github.xzynine.superislandui.common.TextSplitter
import github.xzynine.superislandui.floating.smallisland.left.AComponent
import github.xzynine.superislandui.floating.smallisland.left.aContent
import github.xzynine.superislandui.floating.smallisland.left.aPicKey
import github.xzynine.superislandui.floating.smallisland.left.aTitle
import github.xzynine.superislandui.floating.smallisland.right.BComponent
import github.xzynine.superislandui.floating.smallisland.right.BSameWidthDigitInfo
import github.xzynine.superislandui.floating.smallisland.right.bContent
import github.xzynine.superislandui.floating.smallisland.right.bPicKey
import github.xzynine.superislandui.floating.smallisland.right.bProgress
import github.xzynine.superislandui.floating.smallisland.right.bProgressColorReach
import github.xzynine.superislandui.floating.smallisland.right.bProgressColorUnReach
import github.xzynine.superislandui.floating.smallisland.right.bProgressIsCCW
import github.xzynine.superislandui.floating.smallisland.right.bTitle
import github.xzynine.superislandui.floating.smallisland.right.isTimerType
import github.xzynine.superislandui.floating.smallisland.right.textToRender
import github.xzynine.superislandui.model.core.ParamV2
import notifyrelay.base.util.DeviceUtils
import notifyrelay.base.util.Logger
import notifyrelay.core.util.image.ImageUtils
import notifyrelay.data.StorageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知生成器，负责处理超级岛通知的生成和注入
 *
 * 耦合逻辑说明:
 * 1. 浮窗功能与通知点击事件的耦合:
 *    - 当浮窗功能开启时，为通知设置点击意图和删除意图
 *    - 点击意图�?action �?com.xzyht.notifyrelay.ACTION_TOGGLE_FLOATING
 *    - 删除意图�?action �?com.xzyht.notifyrelay.ACTION_CLOSE_NOTIFICATION
 *    - 这些意图会触发 NotificationBroadcastReceiver 中的相应处理逻辑
 *
 * 2. 通知与浮窗的去耦合:
 *    - 通过 SUPER_ISLAND_FLOATING_WINDOW_KEY 开关控制浮窗功能
 *    - 浮窗功能关闭时，不设置与浮窗关联的通知点击和关闭意图
 *    - 浮窗功能关闭时，仅创建基础通知，不添加与浮窗相关的功能
 */
object NotificationGenerator {
    private const val TAG = "超级岛通知生成"

    // 通知渠道ID
    private const val NOTIFICATION_CHANNEL_ID = "super_island_replica"

    // 通知ID基础值
    private const val NOTIFICATION_BASE_ID = 20000
    // 浮窗功能开关键

    // 滚动更新相关
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scrollRunnable = mutableMapOf<String, Runnable>()

    // 缓存已注入的小图标，供滚动更新时复用（避免丢失实际意义图标）
    private val cachedSmallIcons = ConcurrentHashMap<String, Icon>()

    /**
     * 设置滚动更新
     */
    private fun setupScrollUpdate(
        key: String,
        scrollKey: String,
        capsuleText: String,
        context: Context,
        notificationId: Int,
        originalBuilder: NotificationCompat.Builder,
        notificationManager: NotificationManager,
    ) {
        // 移除旧的滚动Runnable
        scrollRunnable.remove(key)?.let {
            mainHandler.removeCallbacks(it)
        }

        // 创建新的滚动Runnable
        val scrollRunnable =
            Runnable {
                try {
                    // 检查是否需要更新通知
                    if (!CapsuleScrollManager.shouldUpdateNotification(scrollKey)) {
                        return@Runnable
                    }

                    // 获取当前应该显示的内容
                    val displayText = CapsuleScrollManager.getCurrentDisplayText(scrollKey, capsuleText)

                    // 构建原始通知以获取其属性
                    val originalNotification = originalBuilder.build()

                    // 获取原始通知的标题和内容
                    val contentTitle = originalNotification.extras.getString("android.title")
                    val contentText = originalNotification.extras.getString("android.text")

                    // 更新通知（必须设置 smallIcon，否则会抛出 IllegalArgumentException）
                    val updatedBuilder =
                        NotificationCompat
                            .Builder(context, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle(contentTitle ?: "")
                            .setContentText(contentText ?: "")
                            .setSmallIcon(R.drawable.stat_notify_more)
                            .setAutoCancel(false)
                            .setOngoing(true)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setShowWhen(false)
                            .setWhen(System.currentTimeMillis())
                            .setOnlyAlertOnce(true)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setRequestPromotedOngoing(true)
                            .setShortCriticalText(displayText)

                    // 复制extras
                    val updatedNotification = updatedBuilder.build()
                    updatedNotification.extras.putAll(originalNotification.extras)

                    // 恢复之前缓存的小图标，避免滚动更新时丢失实际意义图标
                    val cachedIcon = cachedSmallIcons[key]
                    if (cachedIcon != null) {
                        try {
                            val field = Notification::class.java.getDeclaredField("mSmallIcon")
                            field.isAccessible = true
                            field.set(updatedNotification, cachedIcon)
                        } catch (e: Exception) {
                            Logger.w(TAG, "滚动更新: 恢复小图标失败: ${e.message}")
                        }
                    }

                    // 发送更新后的通知
                    notificationManager.notify(notificationId, updatedNotification)

                    // 继续调度下一次更新
                    val delay = CapsuleScrollManager.getScrollDelay(scrollKey)
                    this.scrollRunnable[key]?.let { mainHandler.postDelayed(it, delay) }
                } catch (e: Exception) {
                    Logger.e(TAG, "滚动更新失败", e)
                }
            }

        // 存储Runnable
        NotificationGenerator.scrollRunnable[key] = scrollRunnable

        // 调度第一次更新，初始延迟0，确保滚动直接开始
        mainHandler.postDelayed(scrollRunnable, 0)
    }

    /**
     * 停止滚动更新
     */
    fun stopScrollUpdate(key: String) {
        scrollRunnable.remove(key)?.let {
            mainHandler.removeCallbacks(it)
        }
        cachedSmallIcons.remove(key)
        CapsuleScrollManager.resetScrollState("${key}_scroll")
    }

    /**
     * 清理所有滚动更新
     */
    fun clearAllScrollUpdates() {
        scrollRunnable.forEach {
            mainHandler.removeCallbacks(it.value)
        }
        scrollRunnable.clear()
        cachedSmallIcons.clear()
        CapsuleScrollManager.clearAll()
    }

    /**
     * 发送复刻通知，与原通知保持一致
     * @return 通知ID，如果发送失败则返回null
     */
    internal suspend fun sendReplicaNotification(
        context: Context,
        key: String,
        title: String?,
        text: String?,
        appName: String?,
        paramV2: ParamV2?,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        sourceId: String,
        floatingWindowManager: FloatingWindowManager,
        overrideNotificationId: Int? = null,
    ): Int? {
        try {
            // 验证规范信息注入开关状态，确保至少有一种开启
            SuperIslandConfigUtils.validateSpecInjectionSwitches(context)

            // 共享通知ID模式下（列表模式），清理旧的滚动任务避免冲突
            if (overrideNotificationId != null) {
                scrollRunnable.keys.toList().forEach { oldKey ->
                    if (oldKey != key) {
                        stopScrollUpdate(oldKey)
                    }
                }
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 生成唯一的通知ID（列表模式使用固定ID，以便原地更新）
            val notificationId = overrideNotificationId ?: (key.hashCode().and(0xffff) + NOTIFICATION_BASE_ID)

            // 检查浮窗功能是否开启
            val floatingWindowEnabled = SuperIslandConfigUtils.isFloatingWindowEnabled(context)
            // 检查列表模式（仅通知 + 切换）
            val notificationListMode = !floatingWindowEnabled && SuperIslandConfigUtils.isNotificationListMode(context)
            // 需要设置 click intent 的条件：浮窗开启 或 列表模式
            val needClickIntent = floatingWindowEnabled || notificationListMode

            // 创建点击意图，用于处理用户点击通知时切换浮窗或切换列表
            val contentIntent =
                if (needClickIntent) {
                    Intent(context, NotificationBroadcastReceiver::class.java).apply {
                        action = "com.xzyht.notifyrelay.ACTION_TOGGLE_FLOATING"
                        putExtra("sourceId", sourceId)
                        putExtra("title", title)
                        putExtra("text", text)
                        putExtra("appName", appName)

                        // 优先使用传入的paramV2Raw参数，其次从entry中获取
                        val entry = floatingWindowManager.getEntry(key)
                        val rawParamV2 = paramV2Raw ?: entry?.paramV2Raw
                        if (!rawParamV2.isNullOrBlank()) {
                            putExtra("paramV2Raw", rawParamV2)
                        }

                        // 传入图片映射
                        if (!picMap.isNullOrEmpty()) {
                            val bundle = Bundle()
                            picMap.forEach { (k, v) -> bundle.putString(k, v) }
                            putExtra("picMap", bundle)
                        }
                    }
                } else {
                    null
                }

            val pendingContentIntent =
                if (needClickIntent && contentIntent != null) {
                    PendingIntent.getBroadcast(
                        context,
                        notificationId,
                        contentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                } else {
                    null
                }

            // 检查是否为媒体类型的超级岛浮窗
            val isMediaType = paramV2?.business == "media"

            // 创建删除意图，用于处理用户移除通知时关闭浮窗
            val deleteIntent =
                if (needClickIntent) {
                    SuperIslandConfigUtils.createDeletePendingIntent(context, notificationId)
                } else {
                    null
                }

            // 统一使用"超级岛复刻"通知渠道
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "超级岛复刻",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            notificationManager.createNotificationChannel(channel)

            // 对于媒体类型，使用HyperCeiler焦点歌词的特殊处理
            if (isMediaType) {
                val builder =
                    NotificationCompat
                        .Builder(context, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(appName ?: "媒体应用") // 使用实际应用名作为通知标题
                        .setContentText(title ?: "未知")
                        .setSmallIcon(R.drawable.stat_notify_more) // 使用系统默认图标
                        // 调整为不可被一键清除的属性，只能手动划去
                        .setOngoing(true) // 不允许通知被一键清除
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setShowWhen(false)
                        .setWhen(System.currentTimeMillis())
                        .setOnlyAlertOnce(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setRequestPromotedOngoing(true)

                // 浮窗或列表模式下设置删除意图和点击意图
                if (needClickIntent) {
                    builder
                        .setDeleteIntent(deleteIntent)
                        .setContentIntent(pendingContentIntent)
                }

                // 添加胶囊形式支持
                try {
                    // 使用 ProgressStyle 设置胶囊样式
                    val segment = NotificationCompat.ProgressStyle.Segment(100)
                    val segments = ArrayList<NotificationCompat.ProgressStyle.Segment>()
                    segments.add(segment)

                    val progressStyle =
                        NotificationCompat
                            .ProgressStyle()
                            .setProgressSegments(segments)
                            .setStyledByProgress(true)
                            .setProgress(0)

                    builder.setStyle(progressStyle)
                } catch (e: Exception) {
                    Logger.e(TAG, "设置胶囊样式失败: ${e.message}")
                }

                // 处理歌词拆分和显示
                val lyricText = title ?: ""
                var capsuleText = lyricText
                var iconText = ""

                // 检查歌词分割模式设置                // 0=默认（平板不分割，手机分割）�?=分割�?=不分�?
                val lyricsSplitMode = StorageManager.getInt(context, "lyrics_split_mode", 0)
                val shouldSplit =
                    when (lyricsSplitMode) {
                        1 -> true
                        2 -> false
                        else -> !DeviceUtils.isTablet(context)
                    }

                if (shouldSplit) {
                    // 当歌词超过阈值时，拆分为图标文本和胶囊文本
                    // 远端和本地都保持6字符开始分�?
                    val threshold = 12
                    val textLength = TextSplitter.calculateTextLength(lyricText)
                    if (textLength > threshold) {
                        // 使用TextSplitter工具类进行歌词拆分
                        val (splitIconText, splitCapsuleText) = TextSplitter.splitLyric(lyricText, threshold)
                        iconText = splitIconText
                        capsuleText = splitCapsuleText
                    }
                } else {
                    // 不分割时，不进行任何截断和拆分，完整显示所有文本
                    capsuleText = lyricText
                    iconText = ""
                }

                // 使用CapsuleScrollManager处理胶囊文本滚动
                val scrollKey = "${key}_scroll"
                val displayText = CapsuleScrollManager.getCurrentDisplayText(scrollKey, capsuleText)

                // 设置胶囊文本
                builder.setShortCriticalText(displayText)

                // 设置滚动更新机制
                setupScrollUpdate(
                    key,
                    scrollKey,
                    capsuleText,
                    context,
                    notificationId,
                    originalBuilder = builder,
                    notificationManager,
                )

                // picMap 已在调用方通过 SuperIslandDataFormatter 解析，直接使用
                val resolvedPicMap = picMap ?: emptyMap()

                // ... (后续构建extras的代码保持不变)
                // 添加焦点歌词相关的结构化数据
                SuperIslandStructuredDataHelper.addMediaSuperIslandStructuredData(
                    builder = builder,
                    context = context,
                    title = title,
                    text = text,
                    picMap = resolvedPicMap,
                )

                // 构建通知
                val notification = builder.build()

                // 生成并注入动态图标
                if (iconText.isNotEmpty()) {
                    val albumBitmap = loadAlbumBitmapOrNull(context, picMap, iconText.length)
                    val iconBitmap = BitmapUtils.textToBitmap(iconText, albumBitmap = albumBitmap)
                    if (iconBitmap != null) {
                        injectSmallIcon(notification, iconBitmap, key)
                    }
                } else {
                    // 没有图标文本时，尝试使用专辑图作为小图标
                    var albumIconSet = false
                    val coverKey = "miui.focus.pic_cover"
                    if (!picMap.isNullOrEmpty() && picMap.containsKey(coverKey)) {
                        val coverUrl = picMap[coverKey]
                        if (!coverUrl.isNullOrBlank()) {
                            val bitmap = downloadBitmap(context, coverUrl)
                            if (bitmap != null) {
                                injectSmallIcon(notification, bitmap, key)
                                albumIconSet = true
                            }
                        }
                    }
                    // 专辑图加载失败时，尝试使用应用图标作为小图标
                    if (!albumIconSet) {
                        val appIconKey = "miui.focus.pic_app_icon"
                        if (!picMap.isNullOrEmpty() && picMap.containsKey(appIconKey)) {
                            val appIconUrl = picMap[appIconKey]
                            if (!appIconUrl.isNullOrBlank()) {
                                val bitmap = downloadBitmap(context, appIconUrl)
                                if (bitmap != null) {
                                    injectSmallIcon(notification, bitmap, key)
                                }
                            }
                        }
                    }
                }

                // 检查是否已经有图标文本，如果有，就不再生成新的图标
                if (iconText.isEmpty()) {
                    // 尝试从A/B区数据中获取图标或生成位图
                    var smallIconBitmap: Bitmap? = null

                    // 使用已解析的 paramV2 中的组件数据
                    val bigIslandArea = paramV2.paramIsland?.bigIslandArea
                    val bComponent = bigIslandArea?.bComponent

                    // 提取进度数据
                    val bProgress = bComponent.bProgress
                    val bProgressColorReach = bComponent.bProgressColorReach
                    val bProgressColorUnReach = bComponent.bProgressColorUnReach
                    val bProgressIsCCW = bComponent.bProgressIsCCW

                    // 处理进度数据，生成位图
                    if (bProgress != null) {
                        smallIconBitmap = BitmapUtils.progressToBitmap(bProgress, bProgressColorReach, bProgressColorUnReach, bProgressIsCCW)
                    }

                    // 如果没有进度数据，尝试生成文本位图
                    if (smallIconBitmap == null) {
                        // 优先使用B区文本生成位图
                        val textToRender = bComponent.textToRender

                        if (!textToRender.isNullOrBlank()) {
                            smallIconBitmap = BitmapUtils.textToBitmap(textToRender)
                        }
                    }

                    // 如果没有文本数据，尝试使用应用图标
                    if (smallIconBitmap == null) {
                        // 优先使用应用图标（大图标的键值提供的图标）
                        val appIconKey = "miui.focus.pic_app_icon"
                        if (!picMap.isNullOrEmpty() && picMap.containsKey(appIconKey)) {
                            val appIconUrl = picMap[appIconKey]
                            if (!appIconUrl.isNullOrBlank()) {
                                // 异步下载应用图标
                                val bitmap = downloadBitmap(context, appIconUrl)
                                if (bitmap != null) {
                                    smallIconBitmap = bitmap
                                }
                            }
                        }
                    }

                    // 注入小图标
                    // 只有当smallIconBitmap不为null时才注入，否则保留之前的图标
                    if (smallIconBitmap != null) {
                        injectSmallIcon(notification, smallIconBitmap, key)
                    } else {
                        // 保留之前的图标，不进行修改
                        Logger.i(TAG, "超级�? 保留之前的小图标，不进行修改")
                    }
                } else {
                    // 已经有图标文本，保留之前的图标，不进行修改
                    Logger.i(TAG, "超级�? 已有图标文本，保留之前的小图标，不进行修改")
                }

                // 发送通知
                notificationManager.notify(notificationId, notification)
            } else {
                // 非媒体类型，使用原来的通知渠道和构建方式
                // 使用已解析的 paramV2 中的组件数据，避免重复解析
                val bigIslandArea = paramV2?.paramIsland?.bigIslandArea
                val aComponent = bigIslandArea?.aComponent
                val bComponent = bigIslandArea?.bComponent

                // 判断是否为计时器类型（包括运行中和暂停状态）
                val isTimerType = bComponent is BSameWidthDigitInfo && bComponent.timer != null

                // 计时器通知的标题和内容设置
                // 标题显示状态，内容显示应用名，时间流逝由chronometer自动处理
                var timerTitle: String = title ?: appName ?: "超级岛通知"
                var timerContent: String = text ?: ""
                if (isTimerType) {
                    val timer = bComponent.timer
                    timer?.let {
                        when (it.timerType) {
                            -2 -> {
                                timerTitle = "暂停"
                                timerContent = appName ?: "计时器"
                            }

                            -1 -> {
                                timerTitle = "倒计时中"
                                timerContent = appName ?: "计时器"
                            }

                            1 -> {
                                timerTitle = "正计时中"
                                timerContent = appName ?: "秒表"
                            }

                            2 -> {
                                timerTitle = "暂停"
                                timerContent = appName ?: "秒表"
                            }

                            else -> {
                                timerTitle = title ?: appName ?: "超级岛通知"
                                timerContent = text ?: ""
                            }
                        }
                    }
                }

                // 判断是否为正在运行的计时器类型（用于chronometer自动更新）
                val isRunningTimer =
                    isTimerType &&
                        (bComponent.timer!!.timerType == -1 || bComponent.timer!!.timerType == 1)

                // 构建基础通知，调整属性使其更接近实际超级岛通知
                val builder =
                    NotificationCompat
                        .Builder(context, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(timerTitle)
                        .setContentText(timerContent)
                        .setSmallIcon(R.drawable.stat_notify_more) // 使用系统默认图标
                        // 调整为与实际超级岛通知一致的属性
                        .setOngoing(true) // 实际通知通常是持续的
                        // 提高优先级到最高，与原始通知一致
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        // 计时器需要显示时间以支持chronometer自动流更新
                        .setShowWhen(isRunningTimer)
                        // 计时器需要使用chronometer功能
                        .setUsesChronometer(isRunningTimer)
                        .setWhen(System.currentTimeMillis()) // 设置时间
                        .setOnlyAlertOnce(true) // 只提示一次
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 公开可见

                // 浮窗或列表模式下设置删除意图和点击意图
                if (needClickIntent) {
                    builder
                        .setDeleteIntent(deleteIntent)
                        .setContentIntent(pendingContentIntent)
                }

                // 对于计时器类通知，添加计时器相关字段
                if (title?.contains("计时") == true || title?.contains("秒表") == true) {
                    if (bComponent is BSameWidthDigitInfo && bComponent.timer != null) {
                        // 根据timerType设置计时模式
                        val timer = bComponent.timer
                        val timerType = timer?.timerType
                        // timerType: -2倒计时暂停，-1倒计时开始，0默认正计时开始，2正计时暂停
                        // 只对正在进行中的计时器启用自动流更新
                        if (timerType == -1 || timerType == 1) {
                            val isCountDown = timerType < 0

// 使用NotificationCompat的计时器功能
                            if (isCountDown) {
                                // 倒计时：计算剩余时间并设置chronometer自动倒计时
                                val now = System.currentTimeMillis()
                                val remaining = timer.timerWhen - now
                                remaining.let {
                                    if (it > 0) {
                                        // 对于倒计时，设置chronometer自动倒计时
                                        builder.setUsesChronometer(true)
                                        builder.setChronometerCountDown(true)
                                        builder.setShowWhen(true) // 确保显示时间
                                        // 设置倒计时的终点时间
                                        timer.let { builder.setWhen(it.timerWhen) }
                                        Logger.i(TAG, "超级岛 倒计时通知已设置chronometer，自动更新，key=$key")
                                    }
                                }
                            } else {
                                // 正计时：使用timerWhen作为起点
                                builder.setUsesChronometer(true)
                                builder.setChronometerCountDown(false)
                                builder.setShowWhen(true) // 确保显示时间
                                // 设置正计时的起点时间
                                timer?.let { builder.setWhen(it.timerWhen) }
                                Logger.i(TAG, "超级岛 正计时通知已设置chronometer，自动更新，key=$key")
                            }
                        }
                    }
                }

                // 检查是否为进度类型通知，如果是，则可能已经通过 LiveUpdatesNotificationManager 处理
                val isProgressType = SuperIslandDataFormatter.isProgressType(paramV2)

                // 构建通知
                val notification =
                    if (!isProgressType) {
                        // 非进度类型通知，添加胶囊兼容字段并注入图标
                        val builtNotification =
                            buildCapsuleCompatibleNotificationWithIconInjection(
                                context,
                                builder,
                                title,
                                text,
                                appName,
                                picMap,
                                paramV2Raw,
                                aComponent,
                                bComponent,
                            )
                        Logger.i(TAG, "超级岛 非进度类型通知已构建，key=$key")
                        builtNotification
                    } else {
                        // 进度类型通知，已经通过 LiveUpdatesNotificationManager 处理，不重复添加胶囊兼容字段
                        Logger.i(TAG, "超级岛 进度类型通知，已通过 LiveUpdatesNotificationManager 处理，不重复添加胶囊兼容字段")
                        // 构建通知
                        val builtNotification = builder.build()
                        // 尝试从A/B 区数据中获取图标或生成位图
                        var smallIconBitmap: Bitmap? = null

                        // 提取 A/B 区数据（使用已解析的组件）
                        val aPicKey = aComponent.aPicKey
                        val bPicKey = bComponent.bPicKey

                        // 处理图标
                        // 优先使用 A 区图标或B区图标
                        val picKeyToUse = aPicKey ?: bPicKey
                        if (!picKeyToUse.isNullOrBlank() && !picMap.isNullOrEmpty()) {
                            val picUrl = picMap[picKeyToUse]
                            if (!picUrl.isNullOrBlank()) {
                                // 异步下载图标
                                val bitmap = downloadBitmap(context, picUrl)
                                if (bitmap != null) {
                                    smallIconBitmap = bitmap
                                }
                            }
                        }

                        // 如果没有 A 区图标或B区图标，再使用应用图标
                        if (smallIconBitmap == null) {
                            val appIconKey = "miui.focus.pic_app_icon"
                            if (!picMap.isNullOrEmpty() && picMap.containsKey(appIconKey)) {
                                val appIconUrl = picMap[appIconKey]
                                if (!appIconUrl.isNullOrBlank()) {
                                    // 同步下载应用图标
                                    val bitmap = downloadBitmap(context, appIconUrl)
                                    if (bitmap != null) {
                                        smallIconBitmap = bitmap
                                    }
                                }
                            }
                        }

                        // 注入小图标
                        if (smallIconBitmap != null) {
                            injectSmallIcon(builtNotification, smallIconBitmap)
                        }

                        Logger.i(TAG, "超级岛 进度类型通知已构建，key=$key")
                        builtNotification
                    }

                // 发送通知
                notificationManager.notify(notificationId, notification)
            }

            // 保存entryKey到notificationId的映射
            FloatingReplicaMappingManager
                .putNotificationId(key, notificationId)

            Logger.i(TAG, "超级岛 发送复刻通知成功，key=$key, notificationId=$notificationId")
            return notificationId
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 发送复刻通知失败: ${e.message}")
            return null
        }
    }

    /**
     * 构建胶囊兼容的通知，添加标准通知字段和 smallIcon 注入
     */
    private fun buildCapsuleCompatibleNotification(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String?,
        text: String?,
        appName: String?,
        picMap: Map<String, String>?,
        paramV2Raw: String?,
        aComponent: AComponent?,
        bComponent: BComponent?,
    ): NotificationCompat.Builder {
        try {
            // 提取 A/B 区数据（使用已解析的组件）
            val aTitle = aComponent.aTitle
            val aContent = aComponent.aContent
            val aPicKey = aComponent.aPicKey
            val bTitle = bComponent.bTitle
            val bContent = bComponent.bContent
            val bPicKey = bComponent.bPicKey
            val bProgress = bComponent.bProgress
            val bProgressColorReach = bComponent.bProgressColorReach
            val bProgressColorUnReach = bComponent.bProgressColorUnReach
            val bProgressIsCCW = bComponent.bProgressIsCCW

            // 设置标准通知字段
            // 判断是否为计时器类型（包括运行中和暂停状态）
            val isTimerType = bComponent.isTimerType

            // 根据计时器状态设置标题和内容
            if (bComponent is BSameWidthDigitInfo && bComponent.timer != null) {
                val timer = bComponent.timer
                val timerTitle =
                    timer?.let {
                        when (it.timerType) {
                            -2 -> "暂停"
                            -1 -> "倒计时中"
                            1 -> "正计时中"
                            2 -> "暂停"
                            else -> title ?: appName ?: "超级岛通知"
                        }
                    }
                val timerContent = appName ?: "超级岛通知"

                builder
                    .setContentTitle(timerTitle)
                    .setContentText(timerContent)
                    .setSubText(appName ?: "超级岛通知")
                    .setShortCriticalText(timerTitle)
            } else {
                // 非计时器类型，使用原有逻辑
                val timerText =
                    when (bComponent) {
                        is BSameWidthDigitInfo -> {
                            null
                        }
                        else -> null
                    }

                val capsuleTitle = aTitle ?: bTitle ?: title
                val capsuleText = timerText ?: aContent ?: bContent ?: text
                val capsuleSubText = appName ?: "超级岛通知"
                val capsuleShortText = timerText ?: bTitle ?: bContent ?: aTitle ?: aContent ?: text

                builder
                    .setContentTitle(capsuleTitle ?: capsuleSubText)
                    .setContentText(capsuleText ?: "")
                    .setSubText(capsuleSubText)
                    .setShortCriticalText(capsuleShortText ?: "")
            }

            builder
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setRequestPromotedOngoing(true)

            // 确保右胶囊文本被正确设置到 miui.focus.param 字段
            // 使用 SuperIslandStructuredDataHelper 添加结构化数据
            SuperIslandStructuredDataHelper.addSuperIslandStructuredData(
                builder = builder,
                context = context,
                paramV2Raw = paramV2Raw,
                picMap = picMap,
                title = title,
                text = text,
                isSuperIslandSpecInjectionEnabled = SuperIslandConfigUtils.isSuperIslandSpecInjectionEnabled(context),
            )

            // 处理 smallIcon - 设置系统默认图标作为占位符
            builder.setSmallIcon(R.drawable.stat_notify_more)
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 构建胶囊兼容通知失败: ${e.message}")
        }

        return builder
    }

    // ---- 图标注入辅助方法 ----

    /**
     * 注入小图标到通知，同时缓存供滚动更新复用
     */
    private fun injectSmallIcon(
        notification: Notification,
        bitmap: Bitmap?,
        cacheKey: String? = null,
    ) {
        bitmap?.let {
            try {
                val icon = Icon.createWithBitmap(it)
                val field = Notification::class.java.getDeclaredField("mSmallIcon")
                field.isAccessible = true
                field.set(notification, icon)
                // 同时缓存供滚动更新复用，避免反射读取
                if (cacheKey != null) {
                    cachedSmallIcons[cacheKey] = icon
                }
                Logger.i(TAG, "超级岛 成功注入小图标到胶囊通知")
            } catch (e: Exception) {
                Logger.w(TAG, "超级岛 注入小图标失败: ${e.message}")
            }
        }
    }

    /**
     * 解析小图标位图，遵循优先级：progress -> text -> picMap aPicKey/bPicKey -> appIconKey -> null
     */
    private suspend fun resolveSmallIconBitmap(
        context: Context,
        picMap: Map<String, String>?,
        aComponent: AComponent?,
        bComponent: BComponent?,
    ): Bitmap? {
        // 提取 A/B 区图片键
        val aPicKey = aComponent.aPicKey
        val bPicKey = bComponent.bPicKey
        val bProgress = bComponent.bProgress
        val bProgressColorReach = bComponent.bProgressColorReach
        val bProgressColorUnReach = bComponent.bProgressColorUnReach
        val bProgressIsCCW = bComponent.bProgressIsCCW

        // 处理 smallIcon
        // 优先处理进度数据
        Logger.d(TAG, "超级岛 处理小图标位图 - bProgress: $bProgress")
        if (bProgress != null) {
            Logger.d(TAG, "超级岛 使用进度数据生成位图")
            val bitmap = BitmapUtils.progressToBitmap(bProgress, bProgressColorReach, bProgressColorUnReach, bProgressIsCCW)
            Logger.d(TAG, "超级岛 进度位图生成结果: ${bitmap != null}")
            if (bitmap != null) return bitmap
        }

        // 处理文本位图
        // 检查是否为计时器类型，如果是，不生成文本位图，保留之前的图标
        val isTimerType = bComponent.isTimerType
        if (!isTimerType) {
            // 优先使用 A 区（左侧）文本生成位图，然后才是 B 区（右侧）文本
            val aText = aComponent.aTitle ?: aComponent.aContent
            val textToRender =
                if (!aText.isNullOrBlank()) {
                    aText
                } else {
                    bComponent.textToRender
                }

            Logger.d(TAG, "超级岛 处理文本位图 - textToRender: $textToRender")
            if (!textToRender.isNullOrBlank()) {
                Logger.d(TAG, "超级岛 使用文本生成位图")
                val albumBitmap = loadAlbumBitmapOrNull(context, picMap, textToRender.length)
                val bitmap = BitmapUtils.textToBitmap(textToRender, albumBitmap = albumBitmap)
                Logger.d(TAG, "超级岛 文本位图生成结果: ${bitmap != null}")
                if (bitmap != null) return bitmap
            }
        } else {
            // 计时器类型，不生成文本位图，保留之前的图标
            Logger.d(TAG, "超级岛 计时器类型，保留之前的小图标，不生成文本位图")
        }

        // 处理图标
        // 优先使用 A 区图标或B区图标
        val picKeyToUse = aPicKey ?: bPicKey
        Logger.d(TAG, "超级岛 处理 A 区图标或B区图标 - picKeyToUse: $picKeyToUse, picMap: ${picMap?.keys}")
        if (!picKeyToUse.isNullOrBlank() && !picMap.isNullOrEmpty()) {
            val picUrl = picMap[picKeyToUse]
            if (!picUrl.isNullOrBlank()) {
                // 异步下载图标
                Logger.d(TAG, "超级岛 使用 A 区图标或B区图标作为小图标")
                val bitmap = downloadBitmap(context, picUrl)
                if (bitmap != null) {
                    Logger.d(TAG, "超级岛 A 区图标或B区图标加载成功")
                    return bitmap
                } else {
                    Logger.w(TAG, "超级岛 A 区图标或B区图标加载失败")
                }
            }
        }

        // 如果没有 A 区图标或B区图标，再使用应用图标（大图标的键值提供的图标）
        val appIconKey = "miui.focus.pic_app_icon"
        Logger.d(TAG, "超级岛 处理应用图标 - appIconKey: $appIconKey, picMap: ${picMap?.keys}")
        if (!picMap.isNullOrEmpty() && picMap.containsKey(appIconKey)) {
            val appIconUrl = picMap[appIconKey]
            if (!appIconUrl.isNullOrBlank()) {
                // 异步下载应用图标
                Logger.d(TAG, "超级岛 使用应用图标作为小图标")
                val bitmap = downloadBitmap(context, appIconUrl)
                if (bitmap != null) {
                    Logger.d(TAG, "超级岛 应用图标加载成功")
                    return bitmap
                } else {
                    Logger.w(TAG, "超级岛 应用图标加载失败")
                }
            }
        }

        // 如果没有生成位图，返回null
        Logger.d(TAG, "超级岛 没有生成小图标")
        return null
    }

    private suspend fun buildCapsuleCompatibleNotificationWithIconInjection(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String?,
        text: String?,
        appName: String?,
        picMap: Map<String, String>?,
        paramV2Raw: String?,
        aComponent: AComponent?,
        bComponent: BComponent?,
    ): Notification {
        try {
            // 先构建胶囊兼容的通知
            val capsuleBuilder =
                buildCapsuleCompatibleNotification(
                    context,
                    builder,
                    title,
                    text,
                    appName,
                    picMap,
                    paramV2Raw,
                    aComponent,
                    bComponent,
                )

            // 构建通知并注入图标
            val notification = capsuleBuilder.build()

            // 解析小图标位图
            val smallIconBitmap = resolveSmallIconBitmap(context, picMap, aComponent, bComponent)

            // 如果没有生成位图，使用默认图标（改为本应用图标）
            if (smallIconBitmap == null) {
                Logger.d(TAG, "超级岛 没有生成位图，使用本应用图标作为默认图标")
            } else {
                Logger.d(TAG, "超级岛 成功生成小图标")
            }

            // 注入小图标
            injectSmallIcon(notification, smallIconBitmap)

            // 返回注入图标后的通知对象
            return notification
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 构建胶囊兼容通知并注入图标失败 ${e.message}")
            e.printStackTrace()
            // 发生异常时，返回原始构建器构建的通知
            return builder.build()
        }
    }

    // ---- 辅助方法 ----

    /**
     * 下载位图
     */
    private suspend fun downloadBitmap(
        context: Context,
        url: String,
    ): Bitmap? =
        try {
            ImageUtils.loadBitmap(context, url)
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 下载图片失败: ${e.message}")
            null
        }

    /**
     * 加载专辑图位图，仅在文本长度 <= 6 且coverUrl 存在时执行
     */
    private suspend fun loadAlbumBitmapOrNull(
        context: Context,
        picMap: Map<String, String>?,
        textLength: Int,
    ): Bitmap? {
        if (textLength > 6) return null
        val coverKey = "miui.focus.pic_cover"
        if (picMap.isNullOrEmpty() || !picMap.containsKey(coverKey)) return null
        val coverUrl = picMap[coverKey] ?: return null
        if (coverUrl.isBlank()) return null
        return downloadBitmap(context, coverUrl)
    }

    /**
     * 取消复刻通知
     */
    internal fun cancelReplicaNotification(
        context: Context,
        key: String,
    ) {
        try {
            val notificationId =
                FloatingReplicaMappingManager
                    .removeNotificationId(key)
            if (notificationId != null) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
                // 停止对应的滚动更新
                stopScrollUpdate(key)
                Logger.i(TAG, "超级岛 取消复刻通知成功，key=$key, notificationId=$notificationId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 取消复刻通知失败: ${e.message}")
        }
    }

    /**
     * 清除所有复刻通知
     */
    internal fun clearAllReplicaNotifications(context: Context?) {
        try {
            if (context != null) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // 取消所有映射中的通知
                val allIds =
                    FloatingReplicaMappingManager
                        .getAllNotificationIds()
                allIds.forEach { (key, notificationId) ->
                    notificationManager.cancel(notificationId)
                    // 停止对应的滚动更新
                    stopScrollUpdate(key)
                    Logger.i(TAG, "超级岛 取消复刻通知成功，key=$key, notificationId=$notificationId")
                }
            }

            // 清空映射
            FloatingReplicaMappingManager
                .clearAllNotificationIds()
            // 清空所有内容指纹
            FloatingReplicaMappingManager
                .clearAllNotificationFingerprints()
            // 清空所有滚动更新
            clearAllScrollUpdates()
            Logger.i(TAG, "超级岛 清除所有复刻通知成功")
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛 清除所有复刻通知失败: ${e.message}")
        }
    }
}
