package com.xzyht.notifyrelay.feature.superisland

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
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
import org.json.JSONObject
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.DataUrlUtils
import com.xzyht.notifyrelay.feature.superisland.SuperIslandImageStore
import com.xzyht.notifyrelay.core.util.ImageLoader
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.unescapeHtml
import com.xzyht.notifyrelay.core.util.MessageSender
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.SmallIslandArea
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.buildViewFromTemplate
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.parseParamV2
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.CircularProgressBinding
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.CircularProgressView
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.HighlightInfo
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.bindTimerUpdater
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.formatTimerInfo
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.resolveHighlightIconBitmap
import com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.bigIsandArea.buildBigIslandCollapsedView
import com.xzyht.notifyrelay.core.util.HapticFeedbackUtils
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

    private var overlayView: View? = null
    private var stackContainer: LinearLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    // 单独的全屏关闭层（拖动时显示底部中心关闭指示器）
    private var closeOverlayView: View? = null
    private var closeOverlayLayoutParams: WindowManager.LayoutParams? = null
    private var closeTargetView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    // 会话级屏蔽池：进程结束后自然清空，value 为最后屏蔽时间戳
    private val blockedInstanceIds = mutableMapOf<String, Long>()
    private const val BLOCK_EXPIRE_MS = 10_000L

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

    private val entries = mutableMapOf<String, EntryRecord>()

    /**
     * 显示超级岛复刻悬浮窗。
     * paramV2Raw: miui.focus.param 中 param_v2 的原始 JSON 字符串（可为 null）
     * picMap: 从 extras 中解析出的图片键->URL 映射（可为 null）
     */
    // sourceId: 用于区分不同来源的超级岛通知（通常传入 superPkg），用于刷新/去重同一来源的浮窗
    fun showFloating(
        context: Context,
        sourceId: String?,
        title: String?,
        text: String?,
        paramV2Raw: String? = null,
        picMap: Map<String, String>? = null
    ) {
        try {
            // 会话级屏蔽检查：同一个 instanceId 在本轮被用户关闭后不再展示
            if (!sourceId.isNullOrBlank() && isInstanceBlocked(sourceId)) {
                if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: instanceId=$sourceId 已在本轮会话中被屏蔽，忽略展示")
                return
            }

            if (!canShowOverlay(context)) {
                requestOverlayPermission(context)
                return
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val paramV2 = parseParamV2Safe(paramV2Raw)
                    val summaryOnly = when (paramV2?.business) {
                        // 仅摘要态的业务模板在这里枚举
                        "miui_flashlight" -> true
                        else -> false
                    }
                    // 将所有图片 intern 为引用，避免重复保存相同图片
                    val internedPicMap = SuperIslandImageStore.internAll(context, picMap)
                    val entryKey = sourceId ?: "${title ?: ""}|${text ?: ""}"
                    val smallIsland = paramV2?.paramIsland?.smallIslandArea
                    val summaryBitmap = smallIsland?.iconKey?.let { iconKey -> downloadBitmapByKey(context, internedPicMap, iconKey) }
                    val fallbackBitmap = summaryBitmap ?: downloadFirstAvailableImage(context, internedPicMap)
                    val templateResult = paramV2?.let { buildViewFromTemplate(context, it, internedPicMap, null) }
                    val expandedView = templateResult?.view
                        ?: buildLegacyExpandedView(context, title, text, fallbackBitmap)
                    val collapsedSummary = buildCollapsedSummaryView(context, paramV2Raw, internedPicMap)
                        ?: buildSummaryView(
                            context,
                            smallIsland,
                            paramV2?.highlightInfo,
                            title,
                            text,
                            summaryBitmap ?: fallbackBitmap,
                            internedPicMap
                        )

                    addOrUpdateEntry(
                        context,
                        entryKey,
                        expandedView,
                        collapsedSummary.view,
                        templateResult?.progressBinding,
                        collapsedSummary.progressBinding,
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
        val ts = blockedInstanceIds[instanceId]
        if (ts == null) return false
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
        val target = closeTargetView ?: return
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
                fbTitle = bi.optString("title", "").takeIf { it.isNotBlank() } ?: fbTitle
                fbContent = bi.optString("content", "").takeIf { it.isNotBlank() } ?: fbContent
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
                        val mapped = com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.ProgressInfo(
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
    private fun parseParamV2Safe(raw: String?): com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager.ParamV2? {
        return try {
            val s = raw ?: return null
            if (s.isBlank()) null else parseParamV2(s)
        } catch (_: Exception) { null }
    }

    // ---- 多条浮窗管理实现 ----

    // 当没有任何条目时，彻底移除 Overlay，避免占位区域拦截顶端触摸
    private fun removeOverlayIfNoEntries() {
        if (entries.isEmpty()) {
            try {
                val wm = windowManager
                val view = overlayView
                if (wm != null && view != null) {
                    wm.removeView(view)
                }
            } catch (_: Exception) {}
            overlayView = null
            stackContainer = null
            overlayLayoutParams = null
            windowManager = null
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "超级岛: 所有条目移除，销毁浮窗容器")
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
            if (overlayView == null || stackContainer == null || windowManager == null || overlayLayoutParams == null) {
                try {
                    val appCtx = context.applicationContext
                    val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val container = FrameLayout(context)
                    val padding = (12 * (context.resources.displayMetrics.density)).toInt()
                    val innerStack = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding, padding, padding)
                    }
                    container.addView(innerStack)

                    // 创建底部中心的关闭指示器（圆形叉号区域），默认隐藏
                    val closeSize = (72 * context.resources.displayMetrics.density).toInt()
                    val closeLp = FrameLayout.LayoutParams(closeSize, closeSize).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        bottomMargin = (24 * context.resources.displayMetrics.density).toInt()
                    }
                    val closeView = ImageView(context).apply {
                        layoutParams = closeLp
                        // 半透明深色圆背景 + 白色叉号
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x99000000.toInt())
                        }
                        val cross = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(0xFFFFFFFF.toInt())
                            cornerRadius = (2 * context.resources.displayMetrics.density)
                        }
                        // 用两条旋转矩形组合叉号太复杂，这里先用内容描述占位，后续可换成真正图标
                        contentDescription = "close_target"
                        alpha = 0f
                        visibility = View.GONE
                    }
                    container.addView(closeView)
                    closeTargetView = closeView

                    val layoutParams = WindowManager.LayoutParams(
                        (FIXED_WIDTH_DP * (context.resources.displayMetrics.density)).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.LEFT or Gravity.TOP
                        x = ((context.resources.displayMetrics.widthPixels - (FIXED_WIDTH_DP * context.resources.displayMetrics.density).toInt()) / 2).coerceAtLeast(0)
                        y = 100
                    }

                    var added = false
                    try {
                        wm.addView(container, layoutParams)
                        added = true
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: addView 失败: ${e.message}")
                    }
                    if (added) {
                        overlayView = container
                        stackContainer = innerStack
                        overlayLayoutParams = layoutParams
                        windowManager = wm
                        if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 浮窗容器已创建(首条条目触发)，x=${layoutParams.x}, y=${layoutParams.y}")
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: 创建浮窗容器失败: ${e.message}")
                }
            }

            val stack = stackContainer ?: return
            val existing = entries[key]
            if (existing != null) {
                val wasExpanded = existing.isExpanded
                updateRecordContent(existing, expandedView, summaryView)
                attachDragHandler(existing.container, context)
                existing.lastExpandedProgress = applyProgressBinding(expandedBinding, existing.lastExpandedProgress)
                existing.lastSummaryProgress = applyProgressBinding(summaryBinding, existing.lastSummaryProgress)
                if (!existing.summaryOnly) {
                    if (wasExpanded) {
                        scheduleCollapse(existing)
                    } else {
                        cancelCollapse(existing)
                    }
                } else {
                    existing.isExpanded = false
                    existing.expandedView.visibility = View.GONE
                    existing.summaryView.visibility = View.VISIBLE
                    cancelCollapse(existing)
                }
                scheduleRemoval(existing, key)
                if (BuildConfig.DEBUG) Log.d(TAG, "超级岛: 刷新浮窗条目 key=$key，保持${if (wasExpanded) "展开" else "摘要"}态")
                return
            }

            val container = FrameLayout(context).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val margin = (4 * context.resources.displayMetrics.density).toInt()
                lp.setMargins(0, margin, 0, margin)
                layoutParams = lp
                isClickable = true
                // 统一背景放在条目容器上，并进行圆角/描边/阴影管理
                background = createEntryBackground(context, /*isExpanded=*/!summaryOnly)
                elevation = 6f * context.resources.displayMetrics.density
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
            if (!summaryOnly) {
                showExpanded(record)
                scheduleCollapse(record)
            } else {
                record.isExpanded = false
            }
            scheduleRemoval(record, key)
            if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 新增浮窗条目 key=$key")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "超级岛: addOrUpdateEntry 出错: ${e.message}")
        }
    }

    private fun updateRecordContent(record: EntryRecord, expandedView: View, summaryView: View) {
        val wasExpanded = record.isExpanded
        detachFromParent(summaryView)
        detachFromParent(expandedView)
        record.container.removeAllViews()
        // 确保容器具备统一背景
        if (record.container.background !is GradientDrawable) {
            record.container.background = createEntryBackground(record.container.context, wasExpanded && !record.summaryOnly)
            record.container.elevation = 6f * record.container.resources.displayMetrics.density
        }

        // 标记状态并移除子根背景
        expandedView.tag = "state_expanded"
        summaryView.tag = "state_summary"
        stripRootBackground(expandedView)
        stripRootBackground(summaryView)

        record.container.addView(summaryView)
        record.container.addView(expandedView)
        record.expandedView = expandedView
        record.summaryView = summaryView
        record.isExpanded = wasExpanded && !record.summaryOnly
        if (record.summaryOnly || !record.isExpanded) {
            record.expandedView.visibility = View.GONE
            record.summaryView.visibility = View.VISIBLE
        } else {
            record.summaryView.visibility = View.GONE
            record.expandedView.visibility = View.VISIBLE
        }
    }

    private fun applyProgressBinding(
        binding: CircularProgressBinding?,
        previousProgress: Int?
    ): Int? {
        if (binding == null) return null
        val target = binding.currentProgress
        val effectivePrevious = if (
            target != null && previousProgress != null && target < previousProgress && target <= PROGRESS_RESET_THRESHOLD
        ) {
            // 新一轮传输通常会重置到极小值，主动回退到0避免出现倒退动画
            0
        } else {
            previousProgress
        }
        binding.apply(effectivePrevious)
        return binding.currentProgress
    }

    private fun onEntryClicked(key: String) {
        val record = entries[key] ?: return
        if (record.summaryOnly) {
            scheduleRemoval(record, key)
            return
        }
        showExpanded(record)
        scheduleCollapse(record)
        scheduleRemoval(record, key)
    }

    private fun showExpanded(record: EntryRecord) {
        if (record.isExpanded) return
        crossfade(record.summaryView, record.expandedView)
        record.isExpanded = true
    }

    private fun showSummary(record: EntryRecord) {
        if (!record.isExpanded) return
        crossfade(record.expandedView, record.summaryView)
        record.isExpanded = false
    }

    private fun crossfade(fromView: View, toView: View) {
        try {
            // 取消可能存在的动画
            fromView.animate()?.cancel()
            toView.animate()?.cancel()

            val density = fromView.resources.displayMetrics.density
            val up = 4f * density

            // 以上边框为基准进行缩放
            fromView.pivotY = 0f
            toView.pivotY = 0f

            // 准备目标视图（向上边框缩 + 渐入）
            toView.alpha = 0f
            toView.scaleX = 0.96f
            toView.scaleY = 0.96f
            toView.translationY = -up
            toView.visibility = View.VISIBLE

            // 淡出 + 向上边框缩
            fromView.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .translationY(-up)
                .setDuration(TRANSITION_DURATION_MS)
                .withEndAction {
                    fromView.visibility = View.GONE
                    fromView.alpha = 1f
                    fromView.scaleX = 1f
                    fromView.scaleY = 1f
                    fromView.translationY = 0f
                }
                .start()

            // 淡入 + 回弹
            toView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(TRANSITION_DURATION_MS)
                .start()

            // 同步圆角过渡（在容器背景上）
            val parent = (fromView.parent as? View)
            val bg = parent?.background as? GradientDrawable
            if (bg != null) {
                val dens = fromView.resources.displayMetrics.density
                val expandedR = 16f * dens
                val summaryR = 999f
                val toIsExpanded = (toView.tag == "state_expanded")
                val startR = if (toIsExpanded) summaryR else expandedR
                val endR = if (toIsExpanded) expandedR else summaryR
                val animator = ValueAnimator.ofFloat(startR, endR).apply {
                    duration = TRANSITION_DURATION_MS
                    addUpdateListener { a ->
                        val r = a.animatedValue as Float
                        bg.cornerRadius = r
                    }
                }
                animator.start()
            }
        } catch (_: Exception) {
            // 回退：无动画切换
            fromView.visibility = View.GONE
            toView.visibility = View.VISIBLE
            fromView.alpha = 1f
            fromView.scaleX = 1f
            fromView.scaleY = 1f
            fromView.translationY = 0f
            toView.alpha = 1f
            toView.scaleX = 1f
            toView.scaleY = 1f
            toView.translationY = 0f
        }
    }

    private fun createEntryBackground(context: Context, isExpanded: Boolean): GradientDrawable {
        val dens = context.resources.displayMetrics.density
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (isExpanded) 16f * dens else 999f
            setColor(0xEE000000.toInt())
            setStroke(dens.toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
        }
        return gd
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

    private fun scheduleCollapse(record: EntryRecord, delayMs: Long = EXPANDED_DURATION_MS) {
        record.collapseRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!entries.containsKey(record.key)) return@Runnable
            showSummary(record)
            record.collapseRunnable = null
        }
        record.collapseRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun scheduleRemoval(record: EntryRecord, key: String, delayMs: Long = AUTO_DISMISS_DURATION_MS) {
        record.removalRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { removeEntry(key) }
        record.removalRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun removeEntry(key: String) {
        val record = entries.remove(key) ?: return
        record.collapseRunnable?.let { handler.removeCallbacks(it) }
        record.removalRunnable?.let { handler.removeCallbacks(it) }
        try {
            stackContainer?.removeView(record.container)
        } catch (_: Exception) {}
        if (BuildConfig.DEBUG) Log.i(TAG, "超级岛: 自动移除浮窗条目 key=$key")
        // 若已无任何条目，彻底移除Overlay，避免空容器占位影响顶端触摸
        removeOverlayIfNoEntries()
    }

    // 新增：按来源键立刻移除指定浮窗（用于接收终止事件SI_END时立即消除）
    fun dismissBySource(sourceId: String) {
        try {
            removeEntry(sourceId)
            // 如果对应的会话结束（SI_END），同步移除黑名单，允许后续同一通知重新展示
            blockedInstanceIds.remove(sourceId)
        } catch (_: Exception) {}
    }

    private fun detachFromParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.removeView(view)
    }

    private fun attachDragHandler(target: View, context: Context) {
        val params = overlayLayoutParams
        val wm = windowManager
        val root = overlayView
        if (params != null && wm != null && root != null) {
            // 这里的 tag 保存的是 entryKey，一般等于 instanceId/sourceId
            val entryKey = (target.tag as? String)
            target.setOnTouchListener(FloatingTouchListener(params, wm, root, context, entryKey))
        } else {
            target.setOnTouchListener(null)
        }
    }

    // 显示全屏关闭层，底部中心有关闭指示器
    private fun showCloseOverlay(context: Context) {
        val wm = windowManager ?: return
        if (closeOverlayView != null) return

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
                    entries.keys.toList().forEach { key ->
                        removeEntry(key)
                    }
                    // 隐藏全屏关闭层
                    hideCloseOverlay()
                }
            }
        container.addView(closeView)

        try {
            wm.addView(container, lp)
            closeOverlayView = container
            closeOverlayLayoutParams = lp
            closeTargetView = closeView
            closeView.animate().alpha(1f).setDuration(150L).start()
        } catch (_: Exception) {
        }
    }

    private fun hideCloseOverlay() {
        val wm = windowManager ?: return
        val view = closeOverlayView ?: return
        closeTargetView = null
        closeOverlayLayoutParams = null
        closeOverlayView = null
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun buildLegacyExpandedView(context: Context, title: String?, content: String?, image: Bitmap?): View {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            // 圆角矩形背景（展开态）
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(0xEE000000.toInt())
                setStroke(density.toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
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
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0xEE000000.toInt())
                setStroke((density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
            background = bg
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
            if (index == 0 && hasTimerLine && timerInfo != null && text == timerLine) {
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
        private val windowWidth = (FIXED_WIDTH_DP * displayMetrics.density).toInt()
        private var windowHeight = 0
        // 关闭区域：屏幕底部中间一块圆形区域（类似画中画关闭手势）
        private val closeRadius = (72 * displayMetrics.density)
        private val closeCenterX = screenWidth / 2f
        private val closeCenterY = screenHeight - (96 * displayMetrics.density)
        private var isInCloseArea = false

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
                    windowHeight = rootView.height.takeIf { it > 0 } ?: (200 * displayMetrics.density).toInt()
                    isDragging = false
                    isInCloseArea = false
                    // 开始拖动时，显示全屏关闭层
                    showCloseOverlay(rootView.context)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val newX = startX + (event.rawX - lastX).toInt()
                        val newY = startY + (event.rawY - lastY).toInt()
                        // 边界检查：如果新位置在边界内，则更新，否则停止拖动（不更新位置）
                        if (newX in 0..(screenWidth - windowWidth) && newY in 0..(screenHeight - windowHeight)) {
                            params.x = newX
                            params.y = newY
                            try {
                                wm.updateViewLayout(rootView, params)
                                if (BuildConfig.DEBUG) Log.d(TAG, "超级岛: 浮窗移动到 x=${params.x}, y=${params.y}")
                            } catch (_: Exception) {}

                            // 检测是否进入/离开关闭区域
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
                            if (!entryKey.isNullOrBlank()) {
                                blockInstance(entryKey)
                                removeEntry(entryKey)
                            } else {
                                // 没有明确键时，仅做普通移除
                                // 这里 rootView 是整个 Overlay 容器，无法定位单条，保持不动
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
                    }
                }
            }
            return false
        }
    }
}
