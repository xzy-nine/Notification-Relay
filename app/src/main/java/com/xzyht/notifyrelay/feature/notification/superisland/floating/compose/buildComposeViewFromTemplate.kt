package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xzyht.notifyrelay.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.parseParamV2
import org.json.JSONObject

/**
 * 解析结果密封类
 */
private sealed class ParseResult {
    data class Success(val paramV2: ParamV2) : ParseResult()
    data class Error(val title: String, val content: String) : ParseResult()
}

/**
 * 解析paramV2，返回解析结果
 */
private fun parseParamV2WithResult(paramV2Raw: String): ParseResult {
    return try {
        val paramV2 = parseParamV2(paramV2Raw)
        if (paramV2 != null) {
            ParseResult.Success(paramV2)
        } else {
            // 解析失败，尝试从原始JSON中提取基本信息
            try {
                val json = JSONObject(paramV2Raw)
                val baseInfo = json.optJSONObject("baseInfo")
                val title = baseInfo?.optString("title", "") ?: ""
                val content = baseInfo?.optString("content", "") ?: ""
                
                ParseResult.Error(
                    title = title.takeIf { it.isNotBlank() } ?: "超级岛",
                    content = content.takeIf { it.isNotBlank() } ?: ""
                )
            } catch (_: Exception) {
                // JSON解析也失败，显示基本信息
                ParseResult.Error(
                    title = "超级岛通知",
                    content = ""
                )
            }
        }
    } catch (_: Exception) {
        // 任何异常都返回失败结果
        ParseResult.Error(
            title = "超级岛通知",
            content = ""
        )
    }
}

/**
 * 超级岛Compose根组件
 */
@Composable
fun SuperIslandComposeRoot(
    content: @Composable () -> Unit,
    isOverlapping: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isOverlapping) {
                    // 重叠时显示红色背景
                    Color.Red.copy(alpha = 0.92f)
                } else {
                    // 正常时显示黑色背景
                    Color.Black.copy(alpha = 0.92f)
                }
            ),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 从paramV2对象构建Compose UI视图
 */
suspend fun buildComposeViewFromParam(
    context: Context,
    paramV2: ParamV2,
    picMap: Map<String, String>? = null,
    business: String? = null,
    lifecycleOwner: LifecycleOwner? = null
): ComposeView {
    return ComposeView(context).apply {
        // 使用DisposeOnDetachedFromWindow替代DisposeOnViewTreeLifecycleDestroyed
        // 避免在浮窗中找不到LifecycleOwner时崩溃
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // 设置为不可点击、不可聚焦，确保触摸事件能传递到父容器
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        // 设置触摸监听器，不消耗事件，让事件自然传递到父容器
        setOnTouchListener { _, event ->
            // 返回false表示不消耗事件，让事件继续传递给父容器的onTouch监听器
            false
        }
        setContent {
            val contentBlock: @Composable () -> Unit = {
                SuperIslandComposeRoot(content = {
                    when {
                        paramV2.baseInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: baseInfo")
                            BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
                        }
                        paramV2.chatInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: chatInfo")
                            ChatInfoCompose(paramV2, picMap = picMap)
                        }
                        paramV2.animTextInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: animTextInfo")
                            AnimTextInfoCompose(paramV2.animTextInfo, picMap = picMap)
                        }
                        paramV2.highlightInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: highlightInfo")
                            HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
                        }
                        paramV2.picInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: picInfo")
                            PicInfoCompose(paramV2.picInfo, picMap = picMap)
                        }
                        paramV2.hintInfo != null -> {
                            Logger.i("超级岛", "分支选择-Compose: hintInfo")
                            HintInfoCompose(paramV2.hintInfo, picMap = picMap)
                        }
                        paramV2.textButton != null -> {
                            Logger.i("超级岛", "分支选择-Compose: textButton")
                            TextButtonCompose(paramV2.textButton, picMap = picMap)
                        }
                        paramV2.paramIsland != null -> {
                            Logger.i("超级岛", "分支选择-Compose: paramIsland")
                            ParamIslandCompose(paramV2.paramIsland)
                        }
                        paramV2.actions?.isNotEmpty() == true -> {
                            Logger.i("超级岛", "分支选择-Compose: actions")
                            ActionCompose(paramV2.actions, picMap)
                        }
                        else -> {
                            Logger.i("超级岛", "分支选择-Compose: default")
                            // 默认模板：未支持的模板类型
                            Box(modifier = Modifier.padding(16.dp)) {
                                Text(text = "未支持的模板", color = Color.White)
                            }
                        }
                    }
                    
                    // 进度组件
                    paramV2.multiProgressInfo?.let {
                        MultiProgressCompose(it, picMap, business)
                    } ?: paramV2.progressInfo?.let {
                        ProgressCompose(it, picMap)
                    }
                })
            }
            if (lifecycleOwner != null) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    contentBlock()
                }
            } else {
                contentBlock()
            }
        }
    }
}

/**
 * 从原始paramV2字符串构建Compose UI视图
 */
suspend fun buildComposeViewFromRawParam(
    context: Context,
    paramV2Raw: String,
    picMap: Map<String, String>? = null,
    lifecycleOwner: LifecycleOwner? = null
): ComposeView {
    return ComposeView(context).apply {
        // 使用DisposeOnDetachedFromWindow替代DisposeOnViewTreeLifecycleDestroyed
        // 避免在浮窗中找不到LifecycleOwner时崩溃
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // 设置为不可点击、不可聚焦，确保触摸事件能传递到父容器
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        // 设置触摸监听器，不消耗事件，让事件自然传递到父容器
        setOnTouchListener { _, event ->
            // 返回false表示不消耗事件，让事件继续传递给父容器的onTouch监听器
            false
        }
        setContent {
            val contentBlock: @Composable () -> Unit = {
                SuperIslandComposeRoot(content = {
                    // 解析paramV2，在Composable函数之外处理异常
                    when (val parseResult = parseParamV2WithResult(paramV2Raw)) {
                        is ParseResult.Success -> {
                            val paramV2 = parseResult.paramV2
                            // 根据paramV2的不同类型显示不同的Compose组件
                            when {
                                paramV2.baseInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: baseInfo")
                                    BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
                                }
                                paramV2.chatInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: chatInfo")
                                    ChatInfoCompose(paramV2, picMap = picMap)
                                }
                                paramV2.animTextInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: animTextInfo")
                                    AnimTextInfoCompose(paramV2.animTextInfo, picMap = picMap)
                                }
                                paramV2.highlightInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: highlightInfo")
                                    HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
                                }
                                paramV2.picInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: picInfo")
                                    PicInfoCompose(paramV2.picInfo, picMap = picMap)
                                }
                                paramV2.hintInfo != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: hintInfo")
                                    HintInfoCompose(paramV2.hintInfo, picMap = picMap)
                                }
                                paramV2.textButton != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: textButton")
                                    TextButtonCompose(paramV2.textButton, picMap = picMap)
                                }
                                paramV2.paramIsland != null -> {
                                    Logger.i("超级岛", "分支选择-Compose: paramIsland")
                                    ParamIslandCompose(paramV2.paramIsland)
                                }
                                paramV2.actions?.isNotEmpty() == true -> {
                                    Logger.i("超级岛", "分支选择-Compose: actions")
                                    ActionCompose(paramV2.actions, picMap)
                                }
                                else -> {
                                    Logger.i("超级岛", "分支选择-Compose: default")
                                    // 默认模板：未支持的模板类型
                                    Box(modifier = Modifier.padding(16.dp)) {
                                        Text(text = "未支持的模板", color = Color.White)
                                    }
                                }
                            }
                            
                            // 进度组件
                            paramV2.multiProgressInfo?.let {
                                MultiProgressCompose(it, picMap, paramV2.business)
                            } ?: paramV2.progressInfo?.let {
                                ProgressCompose(it, picMap)
                            }
                        }
                        is ParseResult.Error -> {
                            // 解析失败，显示默认信息
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                Column {
                                    Text(text = parseResult.title, 
                                         color = Color.White, 
                                         fontSize = 16.sp, 
                                         fontWeight = FontWeight.Bold)
                                    Text(text = parseResult.content, 
                                         color = Color(0xFFDDDDDD), 
                                         fontSize = 14.sp)
                                }
                            }
                        }
                    }
                })
            }
            if (lifecycleOwner != null) {
                CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner) {
                    contentBlock()
                }
            } else {
                contentBlock()
            }
        }
    }
}
