package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose


import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2

/**
 * 浮窗条目数据类，对应原有EntryRecord
 */
@Stable
data class FloatingEntry(
    val key: String,
    val paramV2: ParamV2?,
    val paramV2Raw: String?,
    val picMap: Map<String, String>?,
    val isExpanded: Boolean,
    val summaryOnly: Boolean,
    val business: String?,
    val title: String? = null,
    val text: String? = null,
    val appName: String? = null,
    val isOverlapping: Boolean = false
)

/**
 * 超级岛浮窗父容器组件
 */
@Composable
fun FloatingWindowContainer(
    entries: List<FloatingEntry>,
    onEntryClick: (String) -> Unit,
    lifecycleOwner: LifecycleOwner?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // 当entries列表为空时，不渲染任何内容，避免拦截触摸事件
    if (entries.isEmpty()) {
        return
    }
    
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 遍历所有条目，按顺序显示（最新的在顶部）
        entries.forEach { entry ->
            // 使用key函数确保Compose能正确识别不同的条目，特别是当条目内容更新时
            key(entry.key) {
                // 创建一个无交互源，用于移除点击效果
                val interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null, // 移除默认点击效果
                            onClick = { onEntryClick(entry.key) }
                        )
                ) {
                // 简化动画，避免闪烁
                val expandedEnterTransition = slideInVertically {
                    // 从顶部滑入
                    -it / 2 // 减少滑入距离，使动画更平滑
                } + fadeIn()
                
                val expandedExitTransition = slideOutVertically {
                    // 向顶部滑出
                    -it / 2 // 减少滑出距离，使动画更平滑
                } + fadeOut()
                
                val collapsedEnterTransition = slideInVertically {
                    // 从底部滑入
                    it / 2 // 减少滑入距离，使动画更平滑
                } + fadeIn()
                
                val collapsedExitTransition = slideOutVertically {
                    // 向底部滑出
                    it / 2 // 减少滑出距离，使动画更平滑
                } + fadeOut()
                
                // 展开态内容
                androidx.compose.animation.AnimatedVisibility(
                    visible = entry.isExpanded,
                    enter = expandedEnterTransition,
                    exit = expandedExitTransition
                ) {
                    SuperIslandComposeRoot(
                        content = {
                            val hasParamV2 = entry.paramV2 != null
                            
                            if (hasParamV2) {
                                entry.paramV2?.let { paramV2 ->
                                    when {
                                        paramV2.baseInfo != null -> {
                                            BaseInfoCompose(paramV2.baseInfo, picMap = entry.picMap)
                                        }
                                        paramV2.chatInfo != null -> {
                                            ChatInfoCompose(paramV2, picMap = entry.picMap)
                                        }
                                        paramV2.animTextInfo != null -> {
                                            AnimTextInfoCompose(paramV2.animTextInfo, picMap = entry.picMap)
                                        }
                                        paramV2.highlightInfo != null -> {
                                            HighlightInfoCompose(paramV2.highlightInfo, picMap = entry.picMap)
                                        }
                                        paramV2.picInfo != null -> {
                                            PicInfoCompose(paramV2.picInfo, picMap = entry.picMap)
                                        }
                                        paramV2.hintInfo != null -> {
                                            HintInfoCompose(paramV2.hintInfo, picMap = entry.picMap)
                                        }
                                        paramV2.textButton != null -> {
                                            TextButtonCompose(paramV2.textButton, picMap = entry.picMap)
                                        }
                                        paramV2.paramIsland != null -> {
                                            ParamIslandCompose(paramV2.paramIsland)
                                        }
                                        paramV2.actions?.isNotEmpty() == true -> {
                                            ActionCompose(paramV2.actions, entry.picMap)
                                        }
                                        else -> {
                                            // 默认模板：未支持的模板类型
                                            Box(modifier = Modifier.padding(16.dp)) {
                                                Text(text = "未支持的模板", color = Color.White)
                                            }
                                        }
                                    }
                                    
                                    // 进度组件
                                    paramV2.multiProgressInfo?.let {
                                        MultiProgressCompose(it, entry.picMap, entry.business)
                                    } ?: paramV2.progressInfo?.let {
                                        ProgressCompose(it, entry.picMap)
                                    }
                                }
                            } else {
                                // 兜底显示：当没有paramV2时，使用title和text作为fallback
                                Box(modifier = Modifier.padding(16.dp)) {
                                    Column {
                                        if (!entry.title.isNullOrEmpty()) {
                                            Text(
                                                text = entry.title,
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }
                                        if (!entry.text.isNullOrEmpty()) {
                                            Text(
                                                text = entry.text,
                                                color = Color(0xFFDDDDDD),
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        isOverlapping = entry.isOverlapping
                    )
                }
                
                // 摘要态内容
                androidx.compose.animation.AnimatedVisibility(
                    visible = !entry.isExpanded,
                    enter = collapsedEnterTransition,
                    exit = collapsedExitTransition
                ) {
                    // 提取回落文本：摘要态显示appName和title
                    val fallbackTitle = entry.appName?.takeIf { it.isNotBlank() }
                    val fallbackContent = entry.title?.takeIf { it.isNotBlank() }
                    
                    // 从paramV2Raw中正确解析bigIslandJson，与传统实现保持一致
                    // 使用remember块确保entry.paramV2Raw变化时重新解析
                    val bigIslandJson = remember(entry.paramV2Raw) {
                        entry.paramV2Raw?.let {
                            try {
                                val root = org.json.JSONObject(it)
                                val island = root.optJSONObject("param_island")
                                    ?: root.optJSONObject("paramIsland")
                                    ?: root.optJSONObject("islandParam")
                                island?.optJSONObject("bigIslandArea") ?: island?.optJSONObject("bigIsland")
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    
                    // 直接显示摘要态内容，不添加额外的Box包装，避免方形背景
                    // 将isOverlapping参数传递给SummaryAndroidView，让其内部处理背景色
                    SummaryAndroidView(
                        bigIslandJson = bigIslandJson,
                        picMap = entry.picMap,
                        fallbackTitle = fallbackTitle,
                        fallbackContent = fallbackContent,
                        isOverlapping = entry.isOverlapping,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                // 闭合Box组件
                }
            }
        }
    }
}
