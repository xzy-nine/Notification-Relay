package com.xzyht.notifyrelay.feature.notification.superisland

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.xzyht.notifyrelay.R
import com.xzyht.notifyrelay.common.core.util.HapticFeedbackUtils
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowLifecycleOwner
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingComposeContainer
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.LifecycleManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.parseParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private const val TAG = "超级岛"
    private const val FIXED_WIDTH_DP = 320 // 固定悬浮窗宽度，以确保MultiProgressRenderer完整显示

    // Compose浮窗管理器
    private val floatingWindowManager = FloatingWindowManager().apply {
        // 设置条目为空时的回调
        onEntriesEmpty = { removeOverlayContainer() }
    }
    // Compose生命周期管理器
    private val lifecycleManager = LifecycleManager()
    // 提供给 Compose 的生命周期所有者（不依赖 ViewTree）
    private var overlayLifecycleOwner: FloatingWindowLifecycleOwner? = null

    // 浮窗容器视图
    private var overlayView: WeakReference<View>? = null
    // 浮窗布局参数
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    // WindowManager实例
    private var windowManager: WeakReference<WindowManager>? = null

    // 会话级屏蔽池：进程结束后自然清空，value 为最后屏蔽时间戳
    private val blockedInstanceIds = ConcurrentHashMap<String, Long>()
    // 会话级屏蔽过期时间（默认 15 秒），避免用户刚刚关闭后立即再次弹出
    private const val BLOCK_EXPIRE_MS = 15_000L

    // 保存sourceId到entryKey列表的映射，以便后续能正确移除条目
    // 一个sourceId可能对应多个条目，所以使用列表保存
    private val sourceIdToEntryKeyMap = mutableMapOf<String, MutableList<String>>()

    // 单独的全屏关闭层（拖动时显示底部中心关闭指示器）
    private var closeOverlayView: WeakReference<View>? = null
    private var closeOverlayLayoutParams: WindowManager.LayoutParams? = null
    private var closeTargetView: WeakReference<View>? = null

    // 当前是否正在拖动容器
    private var isContainerDragging = false

    // 关闭区位置信息
    private var closeAreaTop: Int = 0
    private var closeAreaBottom: Int = 0
    private var closeAreaLeft: Int = 0
    private var closeAreaRight: Int = 0

    /**
     * 显示超级岛复刻悬浮窗。
     * paramV2Raw: miui.focus.param 中 param_v2 的原始 JSON 字符串（可为 null）
     * picMap: 从 extras 中解析出的图片键->URL 映射（可为 null）
     */
    // sourceId: 用于区分不同来源的超级岛通知（通常传入 superPkg），用于刷新/去重同一来源的浮窗
    fun showFloating(
        context: Context,
        sourceId: String,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null,
        appName: String? = null,
        isLocked: Boolean = false
    ) {
        try {
            // 会话级屏蔽检查：同一个 instanceId 在本轮被用户关闭后不再展示
            if (sourceId.isNotBlank() && isInstanceBlocked(sourceId)) {
                Logger.i(TAG, "超级岛: instanceId=$sourceId 已在本轮会话中被屏蔽，忽略展示")
                return
            }

            if (!canShowOverlay(context)) {
                Logger.i(TAG, "超级岛: 无悬浮窗权限，尝试请求权限")
                requestOverlayPermission(context)
                return
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // 预先准备生命周期所有者，供 Compose 注入 LocalLifecycleOwner 使用
                    if (overlayLifecycleOwner == null) {
                        overlayLifecycleOwner = FloatingWindowLifecycleOwner()
                    }
                    // 通知Compose生命周期管理器浮窗显示
                    lifecycleManager.onShow()
                    // 尝试解析paramV2
                    val paramV2 = parseParamV2Safe(paramV2Raw)

                    // 判断是否为摘要态
                    val summaryOnly = when {
                        paramV2?.business == "miui_flashlight" -> true
                        paramV2Raw?.contains("miui_flashlight") == true -> true
                        else -> false
                    }

                    // 将所有图片 intern 为引用，避免重复保存相同图片
                    val internedPicMap = SuperIslandImageStore.internAll(context, picMap)
                    // 生成唯一的entryKey，确保包含sourceId，以便后续能正确移除
                    // 对于同一通知的不同时间更新，应该使用相同的key，所以不能包含时间戳
                    val entryKey = sourceId

                    // 更新Compose浮窗管理器的条目
                    // 锁屏状态下不自动展开，非锁屏状态保持原有逻辑
                    floatingWindowManager.addOrUpdateEntry(
                        key = entryKey,
                        paramV2 = paramV2,
                        paramV2Raw = paramV2Raw,
                        picMap = internedPicMap,
                        isExpanded = if (isLocked) false else !summaryOnly,
                        summaryOnly = summaryOnly,
                        business = paramV2?.business,
                        title = title,
                        text = text,
                        appName = appName
                    )

                    // 保存sourceId到entryKey的映射，以便后续能正确移除
                    if (sourceId.isNotBlank()) {
                        // 如果sourceId已存在，添加到列表中；否则创建新列表
                        val entryKeys = sourceIdToEntryKeyMap.getOrPut(sourceId) { mutableListOf() }
                        // 确保每个entryKey只添加一次
                        if (!entryKeys.contains(entryKey)) {
                            entryKeys.add(entryKey)
                        }
                    }

                    // 创建或更新浮窗UI
                    addOrUpdateEntry(context, entryKey, summaryOnly)
                } catch (e: Exception) {
                    Logger.w(TAG, "超级岛: 显示浮窗失败(协程): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
        }
    }

    // ---- 会话级屏蔽工具方法 ----

    private fun isInstanceBlocked(instanceId: String?): Boolean {
        if (instanceId.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val ts = blockedInstanceIds[instanceId] ?: return false
        // 检查是否超过过期时间
        if (now - ts > BLOCK_EXPIRE_MS) {
            blockedInstanceIds.remove(instanceId)
            Logger.i(TAG, "超级岛: 屏蔽过期，自动移除 instanceId=$instanceId")
            return false
        }
        // 如果会话仍在活跃（有新请求），更新屏蔽时间，让屏蔽继续保持
        blockedInstanceIds[instanceId] = now
        return true
    }

    private fun blockInstance(instanceId: String?) {
        if (instanceId.isNullOrBlank()) return
        blockedInstanceIds[instanceId] = System.currentTimeMillis()
        Logger.i(TAG, "超级岛: 会话级屏蔽 instanceId=$instanceId")
    }

    // 兼容空值的 param_v2 解析包装，避免在调用点产生空值分支和推断问题
    private fun parseParamV2Safe(raw: String?): ParamV2? {
        return try {
            val s = raw ?: return null
            if (s.isBlank()) null else parseParamV2(s)
        } catch (_: Exception) { null }
    }

    private fun onEntryClicked(key: String) {
        // 使用FloatingWindowManager管理条目状态
        val entry = floatingWindowManager.getEntry(key)
        if (entry == null) {
            return
        }

        // 切换展开/折叠状态，toggleEntryExpanded内部会处理摘要态的情况
        floatingWindowManager.toggleEntryExpanded(key)
    }

    // 关闭指示器高亮/还原动画
    private fun animateCloseTargetHighlight(highlight: Boolean) {
        val target = closeTargetView?.get() ?: return
        val endScale = if (highlight) 1.2f else 1.0f
        val endAlpha = if (highlight) 1.0f else 0.7f

        try {
            // 使用ViewPropertyAnimatorCompat处理动画
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    val currentScale = 1.0f + (endScale - 1.0f) * progress
                    val currentAlpha = 0.7f + (endAlpha - 0.7f) * progress
                    target.scaleX = currentScale
                    target.scaleY = currentScale
                    target.alpha = currentAlpha
                }
                start()
            }
        } catch (_: Exception) {
        }
    }

    // 触觉反馈：进入/离开关闭区域时短振动
    private fun performHapticFeedback(context: Context) {
        HapticFeedbackUtils.performLightHaptic(context)
    }

    /**
     * 显示关闭层
     */
    private fun showCloseOverlay() {
        // 获取上下文并显示关闭层
        overlayView?.get()?.context?.let { showCloseOverlay(it) }
    }

    /**
     * 隐藏关闭层
     */
    private fun hideCloseOverlay() {
        // 获取上下文并隐藏关闭层
        overlayView?.get()?.context?.let { hideCloseOverlay(it) }
    }

    /**
     * 处理容器拖动开始事件
     */
    private fun onContainerDragStarted() {
        // 显示关闭层
        showCloseOverlay()
        // 记录容器正在拖动
        isContainerDragging = true
        // 立即更新一次重叠状态，确保初始状态正确
        updateEntriesOverlappingStatus()
    }

    /**
     * 处理容器拖动结束事件
     */
    private fun onContainerDragEnded() {
        // 检查是否有条目与关闭区重叠
        checkEntriesInCloseArea()
        // 清除所有条目的重叠状态
        floatingWindowManager.clearAllEntriesOverlapping()
        // 隐藏关闭层
        hideCloseOverlay()
        // 记录容器拖动结束
        isContainerDragging = false
    }

    /**
     * 更新条目的重叠状态，用于实时检测
     */
    private fun updateEntriesOverlappingStatus() {
        try {
            // 获取所有条目
            val entries = floatingWindowManager.entriesList
            if (entries.isEmpty()) return

            // 获取容器当前位置
            val containerX = overlayLayoutParams?.x ?: 0
            val containerY = overlayLayoutParams?.y ?: 0

            // 获取显示密度
            val density = overlayView?.get()?.context?.resources?.displayMetrics?.density ?: 1f

            // 计算容器宽度
            val containerWidth = (FIXED_WIDTH_DP * density).toInt()

            // 检查关闭区是否已经初始化（非默认值）
            if (closeAreaLeft == 0 && closeAreaTop == 0 && closeAreaRight == 0 && closeAreaBottom == 0) {
                // 如果关闭区位置没有初始化，直接返回
                return
            }

            // 记录之前的重叠状态，用于判断是否需要振动
            val previousOverlappingKeys = mutableSetOf<String>()
            entries.forEach { entry ->
                if (entry.isOverlapping) {
                    previousOverlappingKeys.add(entry.key)
                }
            }

            // 遍历所有条目，检查每个条目是否与关闭区重叠
            var currentY = containerY
            for ((index, entry) in entries.withIndex()) {
                // 使用条目实际高度，如果高度为0（未测量）则使用默认值
                val currentHeight = if (entry.height > 0) entry.height else (100f * density).toInt()
                
                // 计算当前条目的位置（垂直排列，最新的在底部）
                val entryTop = currentY
                val entryBottom = entryTop + currentHeight
                val entryCenterX = containerX + containerWidth / 2
                
                // 更新当前Y坐标，为下一个条目做准备
                currentY += currentHeight

                // 检查条目是否与关闭区重叠
                val isVerticallyOverlapping = entryBottom > closeAreaTop && entryTop < closeAreaBottom
                val isHorizontallyOverlapping = entryCenterX > closeAreaLeft && entryCenterX < closeAreaRight
                val isOverlapping = isVerticallyOverlapping && isHorizontallyOverlapping

                // 如果条目重叠状态发生变化
                if (entry.isOverlapping != isOverlapping) {
                    // 更新条目重叠状态
                    floatingWindowManager.setEntryOverlapping(entry.key, isOverlapping)

                    // 如果条目开始重叠，执行振动反馈
                    if (isOverlapping) {
                        // 获取上下文，用于振动反馈
                        val context = overlayView?.get()?.context
                        if (context != null) {
                            // 执行振动反馈
                            try {
                                HapticFeedbackUtils.performLightHaptic(context)
                                Logger.i(TAG, "超级岛: 执行振动反馈 - Key: ${entry.key}")
                            } catch (e: Exception) {
                                Logger.w(TAG, "超级岛: 振动反馈执行失败: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 更新条目重叠状态失败: ${e.message}")
               e.printStackTrace()
        }
    }

    /**
     * 检查条目是否与关闭区重叠
     */
    private fun checkEntriesInCloseArea() {
        try {
            // 获取所有条目
            val entries = floatingWindowManager.entriesList
            if (entries.isEmpty()) return

            // 获取容器当前位置
            val containerX = overlayLayoutParams?.x ?: 0
            val containerY = overlayLayoutParams?.y ?: 0

            // 获取显示密度
            val density = overlayView?.get()?.context?.resources?.displayMetrics?.density ?: 1f

            // 计算容器宽度
            val containerWidth = (FIXED_WIDTH_DP * density).toInt()

            // 添加调试日志，便于检查关闭区和容器位置
            Logger.i(TAG, "超级岛: 关闭区位置 - Left: $closeAreaLeft, Top: $closeAreaTop, Right: $closeAreaRight, Bottom: $closeAreaBottom")
            Logger.i(TAG, "超级岛: 容器位置 - X: $containerX, Y: $containerY, Width: $containerWidth")

            // 检查关闭区是否已经初始化（非默认值）
            if (closeAreaLeft == 0 && closeAreaTop == 0 && closeAreaRight == 0 && closeAreaBottom == 0) {
                // 如果关闭区位置没有初始化，直接返回
                return
            }

            // 遍历所有条目，检查每个条目是否与关闭区重叠
            var currentY = containerY
            for ((index, entry) in entries.withIndex()) {
                // 使用条目实际高度，如果高度为0（未测量）则使用默认值
                val currentHeight = if (entry.height > 0) entry.height else (100f * density).toInt()
                
                // 计算当前条目的位置（垂直排列，最新的在底部）
                val entryTop = currentY
                val entryBottom = entryTop + currentHeight
                val entryCenterX = containerX + containerWidth / 2
                
                // 更新当前Y坐标，为下一个条目做准备
                currentY += currentHeight

                // 检查条目是否与关闭区重叠
                val isVerticallyOverlapping = entryBottom > closeAreaTop && entryTop < closeAreaBottom
                val isHorizontallyOverlapping = entryCenterX > closeAreaLeft && entryCenterX < closeAreaRight

                // 添加调试日志
                Logger.i(TAG, "超级岛: 条目重叠检测 - Key: ${entry.key}, Index: $index, 垂直重叠: $isVerticallyOverlapping, 水平重叠: $isHorizontallyOverlapping")

                // 如果条目与关闭区重叠
                if (isVerticallyOverlapping && isHorizontallyOverlapping) {
                    // 获取上下文，用于振动反馈
                    val context = overlayView?.get()?.context
                    if (context != null) {
                        // 执行振动反馈
                        try {
                            HapticFeedbackUtils.performLightHaptic(context)
                            Logger.i(TAG, "超级岛: 执行振动反馈")
                        } catch (e: Exception) {
                            Logger.w(TAG, "超级岛: 振动反馈执行失败: ${e.message}")
                        }
                    }

                    // 关闭重叠的条目
                    Logger.i(TAG, "超级岛: 关闭重叠条目 - Key: ${entry.key}")

                    // 添加会话级屏蔽，避免用户刚关闭就再次弹出
                    blockInstance(entry.key)

                    floatingWindowManager.removeEntry(entry.key)

                    // 只关闭一个重叠条目，然后退出循环
                    break
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 检查条目与关闭区重叠失败: ${e.message}")
        }
    }

    // 显示全屏关闭层，底部中心有关闭指示器
    private fun showCloseOverlay(context: Context) {
        if (closeOverlayView?.get() != null) return

        // 获取windowManager
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = context.resources.displayMetrics.density
        val container = FrameLayout(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
        }

        val closeSize = (72 * density).toInt()
        val closeLp = FrameLayout.LayoutParams(closeSize, closeSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (24 * density).toInt()
        }
        val closeView = ImageView(context).apply {
                layoutParams = closeLp
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                // 使用实际的叉号图标
                try {
                    setImageResource(R.drawable.ic_pip_close)
                } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.7f
                visibility = View.VISIBLE
                contentDescription = "close_overlay_target"
                setOnClickListener {
                    // 点击关闭按钮时，移除所有浮窗条目
                    // 为每个条目添加会话级屏蔽
                    floatingWindowManager.entriesList.forEach { entry ->
                        blockInstance(entry.key)
                    }
                    floatingWindowManager.clearAllEntries()
                    // 隐藏全屏关闭层
                    hideCloseOverlay(context)
                }
            }
        container.addView(closeView)

        try {
            wm.addView(container, lp)
            closeOverlayView = WeakReference(container)
            closeOverlayLayoutParams = lp
            closeTargetView = WeakReference(closeView)

            // 计算关闭区位置信息 - 确保在屏幕底部中心
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // 关闭区大小和边距（与关闭按钮一致）
            val closeAreaSize = closeSize
            val closeAreaMargin = (24 * density).toInt()

            // 计算关闭区的边界 - 底部中心位置
            closeAreaLeft = (screenWidth - closeAreaSize) / 2
            closeAreaRight = closeAreaLeft + closeAreaSize
            closeAreaTop = screenHeight - closeAreaSize - closeAreaMargin
            closeAreaBottom = closeAreaTop + closeAreaSize

            // 添加调试日志，便于检查关闭区位置
            Logger.i(TAG, "超级岛: 关闭区位置 - Left: $closeAreaLeft, Top: $closeAreaTop, Right: $closeAreaRight, Bottom: $closeAreaBottom")

            // 使用ValueAnimator实现淡入动画
            ValueAnimator.ofFloat(0.7f, 1.0f).apply {
                duration = 150L
                addUpdateListener { animator ->
                    closeView.alpha = animator.animatedValue as Float
                }
                start()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 显示关闭层失败: ${e.message}")
            // 清理资源
            try {
                container.removeAllViews()
                // 移除所有监听器，避免内存泄漏
                closeView.setOnClickListener(null)
                // 清理背景资源
                closeView.background = null
            } catch (_: Exception) {}
        }
    }

    private fun hideCloseOverlay(context: Context? = null) {
        val view = closeOverlayView?.get() ?: return
        val actualContext = context ?: view.context
        val wm = actualContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        try {
            wm.removeView(view)
            //Logger.d(TAG, "超级岛: 关闭层已隐藏")
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 隐藏关闭层失败: ${e.message}")
        } finally {
            // 无论移除是否成功，都置空全局引用，避免内存泄漏
            closeTargetView = null
            closeOverlayLayoutParams = null
            closeOverlayView = null
        }
    }

    // 新增：按来源键立刻移除指定浮窗（用于接收终止事件SI_END时立即消除）
    fun dismissBySource(sourceId: String) {
        try {
            // 从映射中获取所有对应的entryKey
            val entryKeys = sourceIdToEntryKeyMap[sourceId]
            if (entryKeys != null) {
                // 移除所有相关条目
                entryKeys.forEach { entryKey ->
                    floatingWindowManager.removeEntry(entryKey)
                }
                // 清理映射关系
                sourceIdToEntryKeyMap.remove(sourceId)
            } else {
                // 如果没有找到映射，尝试直接使用sourceId移除
                floatingWindowManager.removeEntry(sourceId)
            }
            // 如果对应的会话结束（SI_END），同步移除黑名单，允许后续同一通知重新展示
            blockedInstanceIds.remove(sourceId)
        } catch (_: Exception) {}
    }

    /**
     * 移除浮窗容器
     */
    private fun removeOverlayContainer() {
        try {
            // 关闭关闭层，确保在移除浮窗容器前清理
            hideCloseOverlay()

            val view = overlayView?.get()
            val wm = windowManager?.get()
            val lp = overlayLayoutParams

            if (view != null && wm != null && lp != null) {
                // 移除浮窗容器
                wm.removeView(view)
                Logger.i(TAG, "超级岛: 浮窗容器已移除")

                // 清理资源
                overlayView = null
                overlayLayoutParams = null
                windowManager = null

                // 通知Compose生命周期管理器浮窗隐藏
                lifecycleManager.onHide()

                // 调用生命周期所有者的onHide方法
                overlayLifecycleOwner?.let {
                    try { it.onHide() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 移除浮窗容器失败: ${e.message}")
            // 即使移除失败，也要清理资源引用，避免内存泄漏
            overlayView = null
            overlayLayoutParams = null
            windowManager = null
            // 确保关闭层被清理
            hideCloseOverlay()
        }
    }

    /**
     * 添加或更新浮窗条目
     * @param context 上下文
     * @param key 条目唯一标识
     * @param summaryOnly 是否为摘要态
     */
    private fun addOrUpdateEntry(
        context: Context,
        key: String,
        summaryOnly: Boolean
    ) {
        try {
            // 首条条目到来时再创建 Overlay 容器，避免先有空容器
            if (overlayView?.get() == null || windowManager?.get() == null || overlayLayoutParams == null) {
                    try {
                        val appCtx = context.applicationContext
                        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                            ?: return

                        // 确保存在用于 Compose 的生命周期所有者（不依赖 ViewTree）
                        val lifecycleOwner = overlayLifecycleOwner ?: FloatingWindowLifecycleOwner().also {
                            overlayLifecycleOwner = it
                        }
                        // 标记浮窗进入前台生命周期，供 Compose 使用
                        try { lifecycleOwner.onShow() } catch (_: Exception) {}
                        // 通知Compose生命周期管理器浮窗显示
                        lifecycleManager.onShow()

                        val density = context.resources.displayMetrics.density
                        val layoutParams = WindowManager.LayoutParams(
                            (FIXED_WIDTH_DP * density).toInt(),
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.LEFT or Gravity.TOP
                            x = ((context.resources.displayMetrics.widthPixels - (FIXED_WIDTH_DP * density).toInt()) / 2).coerceAtLeast(0)
                            y = 100
                        }

            // 使用Compose容器替代传统的FrameLayout和LinearLayout
            val composeContainer = FloatingComposeContainer(context).apply {
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                // 设置浮窗管理器
                this.floatingWindowManager = this@FloatingReplicaManager.floatingWindowManager
                // 设置生命周期所有者
                this.lifecycleOwner = lifecycleOwner
                // 设置WindowManager和LayoutParams，用于更新浮窗位置
                this.windowManager = wm
                this.windowLayoutParams = layoutParams
                // 设置条目点击回调
                this.onEntryClick = { entryKey -> onEntryClicked(entryKey) }
                // 设置容器拖动开始回调
                this.onContainerDragStart = { onContainerDragStarted() }
                // 设置容器拖动中回调，用于实时检测重叠
                this.onContainerDragging = { updateEntriesOverlappingStatus() }
                // 设置容器拖动结束回调
                this.onContainerDragEnd = { onContainerDragEnded() }
            }

                        var added = false
                        try {
                            wm.addView(composeContainer, layoutParams)
                            added = true
                        } catch (e: Exception) {
                            Logger.w(TAG, "超级岛: addView 失败: ${e.message}")
                        }
                        if (added) {
                            overlayView = WeakReference(composeContainer)
                            overlayLayoutParams = layoutParams
                            windowManager = WeakReference(wm)
                            Logger.i(TAG, "超级岛: 浮窗容器已创建(首条条目触发)，x=${layoutParams.x}, y=${layoutParams.y}")
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "超级岛: 创建浮窗容器失败: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: addOrUpdateEntry 出错: ${e.message}")
        }
    }

    private fun canShowOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    private fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 请求悬浮窗权限失败: ${e.message}")
        }
    }

    private suspend fun downloadBitmapByKey(context: Context, picMap: Map<String, String>?, key: String?): Bitmap? {
        if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
        val raw = picMap[key] ?: return null
        val url = SuperIslandImageStore.resolve(context, raw) ?: raw
        return withContext(Dispatchers.IO) { downloadBitmap(context, url, 5000) }
    }

    private suspend fun downloadFirstAvailableImage(context: Context, picMap: Map<String, String>?): Bitmap? {
        if (picMap.isNullOrEmpty()) return null
        for ((_, url) in picMap) {
            try {
                val resolved = SuperIslandImageStore.resolve(context, url) ?: url
                val bmp = withContext(Dispatchers.IO) { downloadBitmap(context, resolved, 5000) }
                if (bmp != null) return bmp
            } catch (e: Exception) {
                Logger.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            }
        }
        return null
    }

    private suspend fun downloadBitmap(context: Context, url: String, timeoutMs: Int): Bitmap? {
        return try {
            SuperIslandImageUtil.loadBitmapSuspend(context, url, timeoutMs)
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            null
        }
    }
}