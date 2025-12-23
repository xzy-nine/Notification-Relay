package com.xzyht.notifyrelay.feature.notification.superisland

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.LifecycleOwner
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.HapticFeedbackUtils
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandSettingsKeys
import com.xzyht.notifyrelay.core.util.ImageLoader
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowLifecycleOwner
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.buildBigIslandCollapsedView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.buildComposeViewFromRawParam
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.FloatingComposeContainer
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.FloatingWindowContainer
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.LifecycleManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.CircularProgressBinding
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.CircularProgressView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.HighlightInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ProgressInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.SmallIslandArea
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.bindTimerUpdater
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.buildComposeViewFromTemplate
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.buildViewFromTemplate
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.formatTimerInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.parseParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.resolveHighlightIconBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 接收端的超级岛复刻实现骨架。
 * 说明：真正的系统级悬浮窗需要用户授予 "悬浮窗/Display over other apps" 权限（TYPE_APPLICATION_OVERLAY），
 * 如果没有权限则退化为发送高优先级临时通知来提示用户（不会获得和系统超级岛完全一致的视觉效果）。
 */
object FloatingReplicaManager {
    private const val TAG = "超级岛"
    private const val EXPANDED_DURATION_MS = 3_000L
    private const val TRANSITION_DURATION_MS = 220L
    private const val AUTO_DISMISS_DURATION_MS = 12_000L
    private const val PROGRESS_RESET_THRESHOLD = 5
    private const val FIXED_WIDTH_DP = 320 // 固定悬浮窗宽度，以确保MultiProgressRenderer完整显示

    private var overlayView: WeakReference<View>? = null
    private var stackContainer: WeakReference<LinearLayout>? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var windowManager: WeakReference<WindowManager>? = null
    // 提供给 Compose 的生命周期所有者（不依赖 ViewTree）
    private var overlayLifecycleOwner: FloatingWindowLifecycleOwner? = null

    // 通过反射调用 androidx.lifecycle.ViewTreeLifecycleOwner.set(view, owner)
    private fun tryInstallViewTreeLifecycleOwner(view: View, owner: LifecycleOwner) {
        try {
            val clazz = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val method = clazz.getMethod("set", View::class.java, LifecycleOwner::class.java)
            method.invoke(null, view, owner)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 安装 ViewTreeLifecycleOwner 失败: ${e.message}")
        }
    }

    // 通过反射调用 androidx.savedstate.ViewTreeSavedStateRegistryOwner.set(view, owner)
    private fun tryInstallViewTreeSavedStateRegistryOwner(view: View, owner: SavedStateRegistryOwner) {
        try {
            val clazz = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val method = clazz.getMethod(
                "set",
                View::class.java,
                Class.forName("androidx.savedstate.SavedStateRegistryOwner")
            )
            method.invoke(null, view, owner)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 安装 ViewTreeSavedStateRegistryOwner 失败: ${e.message}")
        }
    }
    // 单独的全屏关闭层（拖动时显示底部中心关闭指示器）
    private var closeOverlayView: WeakReference<View>? = null
    private var closeOverlayLayoutParams: WindowManager.LayoutParams? = null
    private var closeTargetView: WeakReference<View>? = null
    // 使用Handler.Callback避免内存泄漏
    private val handler = Handler(Looper.getMainLooper()) { message ->
        // 所有Runnable都会通过这个方法执行
        // 由于我们使用的是postDelayed，这里不需要处理具体message
        false
    }
    
    // Compose浮窗管理器
    private val floatingWindowManager = FloatingWindowManager()
    // Compose生命周期管理器
    private val lifecycleManager = LifecycleManager()

    // 会话级屏蔽池：进程结束后自然清空，value 为最后屏蔽时间戳
    private val blockedInstanceIds = ConcurrentHashMap<String, Long>()
    // 会话级屏蔽过期时间（默认 15 秒），避免用户刚刚关闭后立即再次弹出
    private const val BLOCK_EXPIRE_MS = 15_000L
    
    // 优化：缓存背景资源，避免重复创建GradientDrawable
    private val expandedBackgroundCache = mutableMapOf<Context, GradientDrawable>()
    private val summaryBackgroundCache = mutableMapOf<Context, GradientDrawable>()
    // 优化：缓存密度值，避免频繁计算density
    private val densityCache = mutableMapOf<Context, Float>()
    
    // 优化：获取密度值，优先从缓存中获取
    private fun getDensity(context: Context): Float {
        return densityCache.getOrPut(context) {
            context.resources.displayMetrics.density
        }
    }
    
    // 保存sourceId到entryKey列表的映射，以便后续能正确移除条目
    // 一个sourceId可能对应多个条目，所以使用列表保存
    private val sourceIdToEntryKeyMap = mutableMapOf<String, MutableList<String>>()

    private data class EntryRecord(
        val key: String,
        val container: FrameLayout,
        var expandedView: View,
        var summaryView: View,
        val summaryOnly: Boolean = false,
        var isExpanded: Boolean = true,
        var collapseRunnable: Runnable? = null,
        var removalRunnable: Runnable? = null,
        var lastExpandedProgress: Int? = null,
        var lastSummaryProgress: Int? = null
    )

    private data class SummaryViewResult(
        val view: View,
        val progressBinding: CircularProgressBinding?
    )

    // 使用FloatingWindowManager替代原有entries映射
    private val entries = ConcurrentHashMap<String, EntryRecord>()

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
        picMap: Map<String, String>? = null
    ) {
        try {
            // 会话级屏蔽检查：同一个 instanceId 在本轮被用户关闭后不再展示
            if (sourceId.isNotBlank() && isInstanceBlocked(sourceId)) {
                if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: instanceId=$sourceId 已在本轮会话中被屏蔽，忽略展示")
                return
            }

            if (!canShowOverlay(context)) {
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
                    val smallIsland = paramV2?.paramIsland?.smallIslandArea
                    val summaryBitmap = smallIsland?.iconKey?.let { iconKey -> downloadBitmapByKey(context, internedPicMap, iconKey) }
                    val fallbackBitmap = summaryBitmap ?: downloadFirstAvailableImage(context, internedPicMap)
                    // 使用Compose构建视图的开关（来自设置页）
                    val useCompose = StorageManager.getBoolean(context, SuperIslandSettingsKeys.RENDER_WITH_COMPOSE, true)
                    
                    // 更新Compose浮窗管理器的条目
                    // 直接通过FloatingWindowManager管理条目状态，不再创建传统View
                    floatingWindowManager.addOrUpdateEntry(
                        key = entryKey,
                        paramV2 = paramV2,
                        paramV2Raw = paramV2Raw,
                        picMap = internedPicMap,
                        isExpanded = !summaryOnly,
                        summaryOnly = summaryOnly,
                        business = paramV2?.business
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
                    
                    // 移除传统的View创建和添加逻辑，完全使用Compose渲染
                    // 直接调用addOrUpdateEntry来确保Compose容器被正确创建
                    // 注意：这里传入的是占位View，实际渲染由Compose负责
                    val placeholderView = View(context)
                    addOrUpdateEntry(
                        context,
                        entryKey,
                        placeholderView,
                        placeholderView,
                        null,
                        null,
                        summaryOnly
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 显示浮窗失败(协程): ${'$'}{e.message}")
                    // 若此前已创建 Overlay 但未成功添加条目，立即移除以避免空容器拦截触摸
                    removeOverlayIfNoEntries()
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 显示浮窗失败，退化为通知: ${e.message}")
            // 若此前已创建 Overlay 但未成功添加条目，立即移除以避免空容器拦截触摸
            removeOverlayIfNoEntries()
        }
    }

    // ---- 会话级屏蔽工具方法 ----

    private fun isInstanceBlocked(instanceId: String?): Boolean {
        if (instanceId.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val ts = blockedInstanceIds[instanceId] ?: return false
        // 超过过期时间则自动移除黑名单
        if (now - ts > BLOCK_EXPIRE_MS) {
            blockedInstanceIds.remove(instanceId)
            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 屏蔽过期，自动移除 instanceId=$instanceId")
            return false
        }
        return true
    }

    private fun blockInstance(instanceId: String?) {
        if (instanceId.isNullOrBlank()) return
        blockedInstanceIds[instanceId] = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 会话级屏蔽 instanceId=$instanceId")
    }

    // 关闭指示器高亮/还原动画
    private fun animateCloseTargetHighlight(highlight: Boolean) {
        val target = closeTargetView?.get() ?: return
        val endScale = if (highlight) 1.2f else 1.0f
        val endAlpha = if (highlight) 1.0f else 0.7f

        try {
            target.animate()
                .scaleX(endScale)
                .scaleY(endScale)
                .alpha(endAlpha)
                .setDuration(160L)
                .start()
        } catch (_: Exception) {
        }
    }

    // 触觉反馈：进入/离开关闭区域时短振动
    private fun performHapticFeedback(context: Context) {
        HapticFeedbackUtils.performLightHaptic(context)
    }

    /**
     * 构建“收起态（摘要态）”视图：完全独立于展开态模板。
     * 若 param_v2 中存在 bigIslandArea/bigIsland，则使用 BigIslandCollapsedRenderer 进行渲染；
     * 否则返回 null 以便上层回退到旧的摘要视图。
     */
    private fun buildCollapsedSummaryView(
        context: Context,
        paramV2Raw: String?,
        picMap: Map<String, String>?
    ): SummaryViewResult? {
        val bigIslandJson = try {
            val raw = paramV2Raw ?: return null
            if (raw.isBlank()) return null
            val root = JSONObject(raw)
            val island = root.optJSONObject("param_island")
                ?: root.optJSONObject("paramIsland")
                ?: root.optJSONObject("islandParam")
            island?.optJSONObject("bigIslandArea") ?: island?.optJSONObject("bigIsland")
        } catch (_: Exception) { null }

        bigIslandJson ?: return null

        // 提取回落文本（用于 B 为空时填充）：优先 baseInfo.title/content，其次 iconTextInfo.title/content
        var fbTitle: String? = null
        var fbContent: String? = null
        try {
            val raw2 = paramV2Raw ?: return null
            val root = JSONObject(raw2)
            root.optJSONObject("baseInfo")?.let { bi ->
                fbTitle = bi.optString("title", "").takeIf { it.isNotBlank() }
                fbContent = bi.optString("content", "").takeIf { it.isNotBlank() }
            }
            if (fbTitle.isNullOrBlank() && fbContent.isNullOrBlank()) {
                root.optJSONObject("iconTextInfo")?.let { ii ->
                    fbTitle = ii.optString("title", "").takeIf { it.isNotBlank() } ?: fbTitle
                    fbContent = ii.optString("content", "").takeIf { it.isNotBlank() } ?: fbContent
                }
            }
        } catch (_: Exception) { }

        val view = buildBigIslandCollapsedView(context, bigIslandJson, picMap, fbTitle, fbContent)

        // 若 B区包含 progressTextInfo，则尝试从视图树中定位圆环并创建绑定，以获得平滑进度动画与状态记忆
        var binding: CircularProgressBinding? = null
        try {
            val progressText = bigIslandJson.optJSONObject("progressTextInfo")
            val pInfoObj = progressText?.optJSONObject("progressInfo")
            if (pInfoObj != null) {
                val progress = pInfoObj.optInt("progress", -1)
                if (progress in 0..100) {
                    val colorReach = pInfoObj.optString("colorReach", "").takeIf { it.isNotBlank() }
                    val colorUnReach = pInfoObj.optString("colorUnReach", "").takeIf { it.isNotBlank() }
                    val isCCW = pInfoObj.optBoolean("isCCW", false)

                    val ring = view.findViewWithTag("collapsed_b_progress_ring") as? CircularProgressView
                    if (ring != null) {
                        val mapped = ProgressInfo(
                            progress = progress,
                            colorProgress = colorReach,
                            colorProgressEnd = colorUnReach,
                            isCCW = isCCW,
                            isAutoProgress = false
                        )
                        binding = CircularProgressBinding(
                            context = context,
                            progressView = ring,
                            completionView = null,
                            picMap = picMap,
                            progressInfo = mapped,
                            completionIcon = null,
                            completionIconDark = null
                        )
                    }
                }
            }
        } catch (_: Exception) { }

        return SummaryViewResult(view, binding)
    }

    // 兼容空值的 param_v2 解析包装，避免在调用点产生空值分支和推断问题
    private fun parseParamV2Safe(raw: String?): ParamV2? {
        return try {
            val s = raw ?: return null
            if (s.isBlank()) null else parseParamV2(s)
        } catch (_: Exception) { null }
    }

    // ---- 多条浮窗管理实现 ----

    // 当没有任何条目时，彻底移除 Overlay，避免占位区域拦截顶端触摸
    private fun removeOverlayIfNoEntries() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { removeOverlayIfNoEntries() }
            return
        }

        if (floatingWindowManager.getEntryCount() == 0) {
            try {
                // 首先隐藏关闭层
                hideCloseOverlay()
                val wm = windowManager?.get()
                val view = overlayView?.get()
                if (wm != null && view != null) {
                    // 通知生命周期结束，便于 Compose 清理
                    try {
                        overlayLifecycleOwner?.onDestroy()
                        lifecycleManager.onHide()
                        lifecycleManager.onDestroy()
                    } catch (_: Exception) { }
                    wm.removeView(view)
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "超级岛: 所有条目移除，销毁浮窗容器")
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "超级岛: 销毁浮窗容器失败: ${e.message}")
                }
            } finally {
                // 优化：清理Handler消息，避免内存泄漏
                handler.removeCallbacksAndMessages(null)
                
                // 清理背景缓存，避免内存泄漏
                expandedBackgroundCache.clear()
                summaryBackgroundCache.clear()
                
                // 清理所有映射
                entries.clear()
                sourceIdToEntryKeyMap.clear()
                
                // 无论移除是否成功，都置空全局引用，避免内存泄漏
                overlayView = null
                stackContainer = null
                overlayLayoutParams = null
                windowManager = null
                overlayLifecycleOwner = null
            }
        }
    }

    private fun addOrUpdateEntry(
        context: Context,
        key: String,
        expandedView: View,
        summaryView: View,
        expandedBinding: CircularProgressBinding?,
        summaryBinding: CircularProgressBinding?,
        summaryOnly: Boolean
    ) {
        try {
            // 首条条目到来时再创建 Overlay 容器，避免先有空容器
            if (overlayView?.get() == null || windowManager?.get() == null || overlayLayoutParams == null) {
                    try {
                        val appCtx = context.applicationContext
                        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                        
                        // 使用Compose容器替代传统的FrameLayout和LinearLayout
                        val composeContainer = FloatingComposeContainer(context).apply {
                            val padding = (12 * getDensity(context)).toInt()
                            setPadding(padding, padding, padding, padding)
                            // 设置浮窗管理器
                            this.floatingWindowManager = this@FloatingReplicaManager.floatingWindowManager
                            // 设置生命周期所有者
                            this.lifecycleOwner = overlayLifecycleOwner ?: lifecycleManager.lifecycleOwner
                            // 设置条目点击回调
                            this.onEntryClick = { entryKey -> onEntryClicked(entryKey) }
                            // 设置条目拖拽回调
                            this.onEntryDrag = { entryKey, offset -> 
                                // 处理拖拽逻辑
                                handleEntryDrag(entryKey, offset)
                            }
                        }

                        // 确保存在用于 Compose 的生命周期所有者（不依赖 ViewTree）
                        val lifecycleOwner = overlayLifecycleOwner ?: FloatingWindowLifecycleOwner().also {
                            overlayLifecycleOwner = it
                        }
                        // 安装 ViewTreeLifecycleOwner + ViewTreeSavedStateRegistryOwner，满足 ComposeView 附着校验
                        tryInstallViewTreeLifecycleOwner(composeContainer, lifecycleOwner)
                        tryInstallViewTreeLifecycleOwner(composeContainer, lifecycleManager.lifecycleOwner)
                        try {
                            val savedStateOwner = lifecycleOwner as SavedStateRegistryOwner
                            tryInstallViewTreeSavedStateRegistryOwner(composeContainer, savedStateOwner)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: lifecycleOwner 非 SavedStateRegistryOwner 或安装失败: ${e.message}")
                        }

                        // 移除浮窗容器中的关闭指示器，统一由showCloseOverlay函数创建和管理
                        // 浮窗容器只负责显示浮窗条目，关闭功能由专门的关闭层处理

                        val layoutParams = WindowManager.LayoutParams(
                            (FIXED_WIDTH_DP * getDensity(context)).toInt(),
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.LEFT or Gravity.TOP
                            x = ((context.resources.displayMetrics.widthPixels - (FIXED_WIDTH_DP * getDensity(context)).toInt()) / 2).coerceAtLeast(0)
                            y = 100
                        }

                        var added = false
                        try {
                            wm.addView(composeContainer, layoutParams)
                            added = true
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: addView 失败: ${e.message}")
                        }
                        if (added) {
                            // 标记浮窗进入前台生命周期，供 Compose 使用
                            try { lifecycleOwner.onShow() } catch (_: Exception) {}
                            // 通知Compose生命周期管理器浮窗显示
                            lifecycleManager.onShow()
                            overlayView = WeakReference(composeContainer)
                            // 不再使用stackContainer，Compose容器自己管理内容
                            overlayLayoutParams = layoutParams
                            windowManager = WeakReference(wm)
                            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 浮窗容器已创建(首条条目触发)，x=${layoutParams.x}, y=${layoutParams.y}")
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 创建浮窗容器失败: ${e.message}")
                    }
                }

            // 暂时保留原有逻辑，后续会移除
            val stack = stackContainer?.get()
            if (stack == null) {
                // 如果stackContainer为null，说明使用了Compose容器
                // 此时不需要添加传统View，直接返回
                return
            }
            val existing = entries[key]
            if (existing != null) {
                // 移除已删除方法的引用
                if (BuildConfig.DEBUG) Log.d(TAG, "超级岛: 刷新浮窗条目 key=$key")
                // 清理旧条目，让FloatingWindowManager重新管理
                entries.remove(key)
                // 直接返回，让新条目替换旧条目
                return
            }

            val container = object : FrameLayout(context) {
                private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                private var startX = 0f
                private var startY = 0f
                private var isDragging = false

                override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            isDragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isDragging) {
                                val dx = abs(event.rawX - startX)
                                val dy = abs(event.rawY - startY)
                                if (dx > touchSlop || dy > touchSlop) {
                                    isDragging = true
                                    // 拦截拖动事件，交给自己的OnTouchListener处理
                                    return true
                                }
                            }
                        }
                    }
                    // 不拦截点击事件，让事件传递给子视图
                    return super.onInterceptTouchEvent(event)
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val margin = (4 * getDensity(context)).toInt()
                lp.setMargins(0, margin, 0, margin)
                layoutParams = lp
                isClickable = true
                // 统一背景放在条目容器上，并进行圆角/描边/阴影管理
                background = createEntryBackground(context, /*isExpanded=*/!summaryOnly)
                elevation = 6f * getDensity(context)
                // 将 entryKey（通常为 instanceId/sourceId）挂到 tag，供拖动关闭逻辑使用
                tag = key
            }

            detachFromParent(summaryView)
            detachFromParent(expandedView)
            // 标记视图状态，并移除各自根背景，避免与容器背景叠加
            expandedView.tag = "state_expanded"
            summaryView.tag = "state_summary"
            stripRootBackground(expandedView)
            stripRootBackground(summaryView)

            if (summaryOnly) {
                summaryView.visibility = View.VISIBLE
                expandedView.visibility = View.GONE
            } else {
                summaryView.visibility = View.GONE
                expandedView.visibility = View.VISIBLE
            }
            container.addView(summaryView)
            container.addView(expandedView)
            container.setOnClickListener { onEntryClicked(key) }
            attachDragHandler(container, context)

            stack.addView(container, 0)

            val record = EntryRecord(
                key = key,
                container = container,
                expandedView = expandedView,
                summaryView = summaryView,
                summaryOnly = summaryOnly,
                isExpanded = !summaryOnly
            )
            entries[key] = record
            record.lastExpandedProgress = applyProgressBinding(expandedBinding, record.lastExpandedProgress)
            record.lastSummaryProgress = applyProgressBinding(summaryBinding, record.lastSummaryProgress)
            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 新增浮窗条目 key=$key")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: addOrUpdateEntry 出错: ${e.message}")
        }
    }

    // 移除传统的视图更新方法，这些功能现在由Compose处理
    // private fun updateRecordContent(record: EntryRecord, expandedView: View, summaryView: View)

    private fun applyProgressBinding(
        binding: CircularProgressBinding?,
        previousProgress: Int?
    ): Int? {
        if (binding == null) return null
        
        val target = binding.currentProgress
        // 确保target在合理范围内
        val safeTarget = target?.coerceIn(0, 100)
        
        val effectivePrevious = if (
            safeTarget != null && previousProgress != null && safeTarget < previousProgress && safeTarget <= PROGRESS_RESET_THRESHOLD
        ) {
            // 新一轮传输通常会重置到极小值，主动回退到0避免出现倒退动画
            0
        } else {
            previousProgress?.coerceIn(0, 100) // 确保previousProgress也在合理范围内
        }
        
        // 优化：只有当进度值有效时才应用更新
        if (safeTarget != null) {
            binding.apply(effectivePrevious)
        }
        
        return safeTarget
    }

    private fun onEntryClicked(key: String) {
        // 使用FloatingWindowManager管理条目状态
        val entry = floatingWindowManager.getEntry(key)
        if (entry == null) {
            return
        }
        
        if (entry.summaryOnly) {
            // 如果是摘要态，直接移除
            floatingWindowManager.removeEntry(key)
            return
        }
        
        // 切换展开/折叠状态
        floatingWindowManager.toggleEntryExpanded(key)
        
        // 暂不处理自动折叠和移除，这些逻辑将在Compose中实现
    }

    // 移除传统的视图管理方法，这些功能现在由Compose处理
    // private fun showExpanded(record: EntryRecord)
    // private fun showSummary(record: EntryRecord)
    // private fun crossfade(fromView: View, toView: View)

    private fun createEntryBackground(context: Context, isExpanded: Boolean): GradientDrawable {
        // 优化：使用缓存避免重复创建GradientDrawable
        val cache = if (isExpanded) expandedBackgroundCache else summaryBackgroundCache
        return cache.getOrPut(context) {
            val dens = getDensity(context)
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = if (isExpanded) 16f * dens else 999f
                setColor(0xEE000000.toInt())
                setStroke(dens.toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
        }
    }

    private fun stripRootBackground(view: View) {
        try {
            view.background = null
        } catch (_: Exception) {}
    }

    private fun cancelCollapse(record: EntryRecord) {
        record.collapseRunnable?.let { handler.removeCallbacks(it) }
        record.collapseRunnable = null
    }

    // 移除传统的自动折叠和移除方法，这些功能现在由Compose处理
    // private fun scheduleCollapse(record: EntryRecord, delayMs: Long = EXPANDED_DURATION_MS)
    // private fun scheduleRemoval(record: EntryRecord, key: String, delayMs: Long = AUTO_DISMISS_DURATION_MS)

    private fun removeEntry(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { removeEntry(key) }
            return
        }

        // 使用FloatingWindowManager移除条目
        floatingWindowManager.removeEntry(key)
        
        // 清理传统entries映射
        val record = entries.remove(key)
        
        // 清理sourceIdToEntryKeyMap映射
        for ((sourceId, entryKeys) in sourceIdToEntryKeyMap) {
            if (entryKeys.remove(key)) {
                // 如果列表为空，移除整个映射项
                if (entryKeys.isEmpty()) {
                    sourceIdToEntryKeyMap.remove(sourceId)
                }
                break
            }
        }
        
        // 清理视图资源
        if (record != null) {
            try {
                // 取消所有可能的动画
                record.expandedView.animate()?.cancel()
                record.summaryView.animate()?.cancel()
                
                // 简化：只移除必要的监听器
                record.container.setOnTouchListener(null)
                record.container.setOnClickListener(null)
                
                // 移除视图
                val parentContainer = overlayView?.get() as? ViewGroup
                parentContainer?.removeView(record.container)
                
                // 清理视图的背景
                record.expandedView.background = null
                record.summaryView.background = null
                record.container.background = null
                
                // 移除所有子视图，确保资源被释放
                record.container.removeAllViews()
                
                // 确保视图被完全分离，避免内存泄漏
                detachFromParent(record.expandedView)
                detachFromParent(record.summaryView)
                
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "超级岛: 移除浮窗条目资源失败: ${e.message}")
                }
            } finally {
                // 确保所有Runnable引用被清空
                record.collapseRunnable = null
                record.removalRunnable = null
            }
        }
        
        if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 自动移除浮窗条目 key=$key")
        // 若已无任何条目，彻底移除Overlay，避免空容器占位影响顶端触摸
        removeOverlayIfNoEntries()
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
                    entries.remove(entryKey)
                }
                // 清理映射关系
                sourceIdToEntryKeyMap.remove(sourceId)
            } else {
                // 如果没有找到映射，尝试直接使用sourceId移除
                floatingWindowManager.removeEntry(sourceId)
                entries.remove(sourceId)
            }
            // 如果对应的会话结束（SI_END），同步移除黑名单，允许后续同一通知重新展示
            blockedInstanceIds.remove(sourceId)
        } catch (_: Exception) {}
    }

    private fun detachFromParent(view: View) {
        try {
            val parent = view.parent as? ViewGroup ?: return
            parent.removeView(view)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "超级岛: 从父视图移除视图失败: ${e.message}")
            }
        }
    }

    private fun attachDragHandler(target: View, context: Context) {
        // 移除传统的拖拽处理逻辑，拖拽功能现在由Compose处理
    }
    
    /**
     * 处理来自Compose的拖拽事件
     */
    private fun handleEntryDrag(key: String, offset: androidx.compose.ui.geometry.Offset) {
        // 获取浮窗容器的LayoutParams和WindowManager
        val params = overlayLayoutParams ?: return
        val wm = windowManager?.get() ?: return
        val rootView = overlayView?.get() ?: return
        
        // 更新浮窗容器的位置
        params.x += offset.x.toInt()
        params.y += offset.y.toInt()
        
        // 边界检查：将位置限制在屏幕内
        val displayMetrics = rootView.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val windowWidth = params.width
        val windowHeight = params.height
        
        params.x = params.x.coerceIn(0, screenWidth - windowWidth)
        params.y = params.y.coerceIn(0, screenHeight - windowHeight)
        
        // 更新浮窗位置
        try {
            wm.updateViewLayout(rootView, params)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "超级岛: 更新浮窗位置失败: ${e.message}")
            }
        }
    }

    // 显示全屏关闭层，底部中心有关闭指示器
    private fun showCloseOverlay(context: Context) {
        if (closeOverlayView?.get() != null) return
        
        // 获取windowManager，优先使用全局引用，否则从context中获取
        val wm = windowManager?.get() ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = getDensity(context)
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
                // 使用实际的叉号图标（请在资源中提供 ic_pip_close）
                try {
                    setImageResource(com.xzyht.notifyrelay.R.drawable.ic_pip_close)
                } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.7f
                visibility = View.VISIBLE
                contentDescription = "close_overlay_target"
                setOnClickListener {
                    // 点击关闭按钮时，移除所有浮窗条目
                    // 使用FloatingWindowManager移除所有条目
                    floatingWindowManager.clearAllEntries()
                    // 清理传统entries映射
                    entries.keys.toList().forEach {
                        entries.remove(it)
                    }
                    // 隐藏全屏关闭层
                    hideCloseOverlay()
                }
            }
        container.addView(closeView)

        try {
            wm.addView(container, lp)
            closeOverlayView = WeakReference(container)
            closeOverlayLayoutParams = lp
            closeTargetView = WeakReference(closeView)
            closeView.animate().alpha(1f).setDuration(150L).start()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "超级岛: 显示关闭层失败: ${e.message}")
            }
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

    private fun hideCloseOverlay() {
        val view = closeOverlayView?.get() ?: return
        val wm = windowManager?.get()
        
        try {
            if (wm != null) {
                wm.removeView(view)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "超级岛: 关闭层已隐藏")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "超级岛: windowManager为空，无法移除关闭层")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "超级岛: 隐藏关闭层失败: ${e.message}")
            }
        } finally {
            // 无论移除是否成功，都置空全局引用，避免内存泄漏
            closeTargetView = null
            closeOverlayLayoutParams = null
            closeOverlayView = null
        }
    }

    private fun buildLegacyExpandedView(context: Context, title: String?, content: String?, image: Bitmap?): View {
        val density = getDensity(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            // 圆角矩形背景（展开态）
            background = createEntryBackground(context, true)
            clipToOutline = true
            elevation = 6f * density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (image != null) {
                val size = (56 * density).toInt()
                val iconView = ImageView(context).apply {
                    id = android.R.id.icon
                    setImageBitmap(image)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(iconView)
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val marginStart = if (image != null) (8 * density).toInt() else 0
                lp.setMargins(marginStart, 0, 0, 0)
                layoutParams = lp
            }

            val titleView = TextView(context).apply {
                id = android.R.id.text1
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                text = Html.fromHtml(unescapeHtml(title ?: "(无标题)"), Html.FROM_HTML_MODE_COMPACT)
            }

            val textView = TextView(context).apply {
                id = android.R.id.text2
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 12f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 2
                text = Html.fromHtml(unescapeHtml(content ?: "(无内容)"), Html.FROM_HTML_MODE_COMPACT)
            }

            textColumn.addView(titleView)
            textColumn.addView(textView)
            addView(textColumn)
        }
    }

    private fun buildSummaryView(
        context: Context,
        smallIsland: SmallIslandArea?,
        highlightInfo: HighlightInfo?,
        fallbackTitle: String?,
        fallbackText: String?,
        bitmap: Bitmap?,
        picMap: Map<String, String>?
    ): SummaryViewResult {
        val density = context.resources.displayMetrics.density
        val timerInfo = highlightInfo?.timerInfo
        val timerLine = timerInfo?.let { formatTimerInfo(it) }?.takeIf { it.isNotBlank() }
        val hasTimerLine = !timerLine.isNullOrBlank()

        val displayLines = mutableListOf<String>()
        timerLine?.let { displayLines.add(it) }

        fun addLineCandidate(value: String?) {
            val line = sanitizeSummaryLine(value, hasTimerLine)
            if (!line.isNullOrBlank() && !displayLines.contains(line)) {
                displayLines.add(line)
            }
        }

        addLineCandidate(smallIsland?.primaryText)
        addLineCandidate(highlightInfo?.title)
        addLineCandidate(highlightInfo?.content)
        addLineCandidate(highlightInfo?.subContent)
        addLineCandidate(smallIsland?.secondaryText)
        addLineCandidate(fallbackTitle)
        addLineCandidate(fallbackText)

        if (displayLines.isEmpty()) {
            displayLines.add("(无内容)")
        }

        val linesToRender = displayLines.take(2)

        var progressBinding: CircularProgressBinding? = null
        val iconSize = (48 * density).toInt()
        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }

        val highlightBitmap = highlightInfo?.let { resolveHighlightIconBitmap(context, it, picMap) }
        val iconBitmap = highlightBitmap ?: bitmap
        var hasIconContent = false

        if (iconBitmap != null) {
            val iconView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "focus_icon"
                setImageBitmap(iconBitmap)
            }
            iconContainer.addView(iconView)
            hasIconContent = true
        }

        val progressInfo = smallIsland?.progressInfo
        if (progressInfo != null) {
            val progressView = CircularProgressView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setStrokeWidthDp(3f)
                visibility = View.GONE
            }
            iconContainer.addView(progressView)
            progressBinding = CircularProgressBinding(
                context = context,
                progressView = progressView,
                completionView = null,
                picMap = picMap,
                progressInfo = progressInfo,
                completionIcon = null,
                completionIconDark = null
            )
            hasIconContent = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val paddingH = (10 * density).toInt()
            val paddingV = (6 * density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)
            // 胶囊背景
            background = createEntryBackground(context, false)
            elevation = 6f * density
            clipToPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (hasIconContent) {
                addView(iconContainer)
            }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            val marginStart = if (hasIconContent) (8 * density).toInt() else 0
            lp.setMargins(marginStart, 0, 0, 0)
            layoutParams = lp
        }

        linesToRender.forEachIndexed { index, text ->
            val tv = TextView(context).apply {
                setTextColor(if (index == 0) 0xFFFFFFFF.toInt() else 0xFFDDDDDD.toInt())
                textSize = if (index == 0) 13f else 11f
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                this.text = Html.fromHtml(unescapeHtml(text), Html.FROM_HTML_MODE_COMPACT)
            }
            // 简化条件：当hasTimerLine为true时，timerLine必然存在于linesToRender[0]，timerInfo也必然不为null
        if (index == 0 && hasTimerLine) {
            tv.ellipsize = null
            bindTimerUpdater(tv, timerInfo)
        } else {
            tv.ellipsize = TextUtils.TruncateAt.END
        }
            textColumn.addView(tv)
        }

        container.addView(textColumn)

        return SummaryViewResult(container, progressBinding)
    }

    private fun sanitizeSummaryLine(value: String?, suppressStatus: Boolean): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (suppressStatus && text.contains("进行中")) return null
        return text
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
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 请求悬浮窗权限失败: ${e.message}")
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
                if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            }
        }
        return null
    }

    private suspend fun downloadBitmap(context: Context, url: String, timeoutMs: Int): Bitmap? {
        return try {
            ImageLoader.loadBitmapSuspend(context, url, timeoutMs)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 下载图片失败: ${e.message}")
            null
        }
    }

    // 简单的触摸拖动实现
    private class FloatingTouchListener(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager,
        private val rootView: View,
        context: Context,
        private val entryKey: String?
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0
        private var startY = 0
        private var isDragging = false
        private val displayMetrics = context.resources.displayMetrics
        private val screenWidth = displayMetrics.widthPixels
        private val screenHeight = displayMetrics.heightPixels
        // 使用实际视图宽度替代固定值
        private val windowWidth: Int
            get() = rootView.width.takeIf { it > 0 } ?: (FIXED_WIDTH_DP * displayMetrics.density).toInt()
        // 使用实际视图高度替代固定值
        private val windowHeight: Int
            get() = rootView.height.takeIf { it > 0 } ?: (200 * displayMetrics.density).toInt()
        // 关闭区域：屏幕底部中间一块圆形区域（类似画中画关闭手势）
        private val closeRadius = (72 * displayMetrics.density)
        private val closeCenterX = screenWidth / 2f
        private val closeCenterY = screenHeight - (96 * displayMetrics.density)
        private var isInCloseArea = false
        // 优化：限制视图更新频率，避免频繁UI更新
        private var lastUpdateTime = 0L
        private val UPDATE_INTERVAL = 16L // 约60fps

        private fun isCenterInCloseArea(): Boolean {
            val centerX = params.x + windowWidth / 2f
            val centerY = params.y + windowHeight / 2f
            val dx = centerX - closeCenterX
            val dy = centerY - closeCenterY
            val distanceSq = dx * dx + dy * dy
            return distanceSq <= closeRadius * closeRadius
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    isInCloseArea = false
                    // 拖动开始时再显示关闭层，避免点击时不必要的显示
                    return true // 需要返回true以接收后续事件
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        // 检测到拖动开始，显示全屏关闭层
                        showCloseOverlay(rootView.context)
                    }
                    if (isDragging) {
                        val newX = startX + (event.rawX - lastX).toInt()
                        val newY = startY + (event.rawY - lastY).toInt()
                        // 边界检查：将位置限制在边界内，确保浮窗始终可见
                        val actualWidth = windowWidth
                        val actualHeight = windowHeight
                        
                        // 优化：将位置限制在边界内，而不是完全不更新
                        val boundedX = newX.coerceIn(0, screenWidth - actualWidth)
                        val boundedY = newY.coerceIn(0, screenHeight - actualHeight)
                        
                        params.x = boundedX
                        params.y = boundedY
                        
                        // 优化：限制视图更新频率，避免频繁UI更新
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime > UPDATE_INTERVAL) {
                            try {
                                wm.updateViewLayout(rootView, params)
                                // 减少频繁的日志输出，仅在必要时记录
                            } catch (_: Exception) {}
                            lastUpdateTime = currentTime
                            
                            // 仅在视图更新时检测关闭区域，减少计算频率
                            val nowInClose = isCenterInCloseArea()
                            if (nowInClose != isInCloseArea) {
                                isInCloseArea = nowInClose
                                animateCloseTargetHighlight(nowInClose)
                                performHapticFeedback(rootView.context)
                            }
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // 在松手时，如果中心点落在底部中间的关闭圆形区域内，则视为“关闭并屏蔽本轮会话的该 instanceId”
                        val nowInClose = isCenterInCloseArea()
                        if (nowInClose) {
                            // 命中关闭区域：会话级屏蔽 + 移除浮窗条目
                            val effectiveKey = entryKey ?: (v.tag as? String)
                            if (!effectiveKey.isNullOrBlank()) {
                                blockInstance(effectiveKey)
                                removeEntry(effectiveKey)
                            } else {
                                // 作为最后的兜底方案，移除所有条目
                                entries.keys.toList().forEach { key ->
                                    removeEntry(key)
                                }
                            }
                        }
                        isDragging = false
                        if (isInCloseArea) {
                            isInCloseArea = false
                            animateCloseTargetHighlight(false)
                        }
                        // 结束拖动时移除全屏关闭层
                        hideCloseOverlay()
                        return true
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // 非拖动的 ACTION_UP，视为点击事件，手动触发 onClickListener
                        v.performClick()
                    }
                    // 非拖动结束时，如果显示了关闭层，也需要隐藏
                    hideCloseOverlay()
                }
            }
            return false
        }
    }
}
