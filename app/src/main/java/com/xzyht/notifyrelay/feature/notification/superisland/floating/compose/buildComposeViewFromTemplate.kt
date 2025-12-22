package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.parseParamV2
import org.json.JSONObject

/**
 * 构建Compose UI视图的主函数
 */
suspend fun buildComposeViewFromTemplate(
    context: Context,
    paramV2: ParamV2,
    picMap: Map<String, String>? = null, 
    business: String? = null
): ComposeView {
    return ComposeView(context).apply {
        // Ensure the Compose view disposes with the view tree lifecycle
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SuperIslandComposeRoot {
                when {
                    paramV2.baseInfo != null -> {
                        BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
                    }
                    paramV2.chatInfo != null -> {
                        ChatInfoCompose(paramV2, picMap = picMap)
                    }
                    paramV2.animTextInfo != null -> {
                        AnimTextInfoCompose(paramV2.animTextInfo, picMap = picMap)
                    }
                    paramV2.highlightInfo != null -> {
                        HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
                    }
                    paramV2.picInfo != null -> {
                        PicInfoCompose(paramV2.picInfo, picMap = picMap)
                    }
                    paramV2.hintInfo != null -> {
                        HintInfoCompose(paramV2.hintInfo, picMap = picMap)
                    }
                    paramV2.textButton != null -> {
                        TextButtonCompose(paramV2.textButton, picMap = picMap)
                    }
                    paramV2.paramIsland != null -> {
                        ParamIslandCompose(paramV2.paramIsland)
                    }
                    paramV2.actions?.isNotEmpty() == true -> {
                        ActionCompose(paramV2.actions, picMap)
                    }
                    else -> {
                        // 默认模板：未支持的模板类型
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
    picMap: Map<String, String>? = null
): ComposeView {
    return ComposeView(context).apply {
        // 使用DisposeOnDetachedFromWindow替代DisposeOnViewTreeLifecycleDestroyed
        // 避免在浮窗中找不到LifecycleOwner时崩溃
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            SuperIslandComposeRoot {
                // 解析paramV2，在Composable函数之外处理异常
                val parseResult = parseParamV2WithResult(paramV2Raw)
                
                when (parseResult) {
                    is ParseResult.Success -> {
                        val paramV2 = parseResult.paramV2
                        // 根据paramV2的不同类型显示不同的Compose组件
                        when {
                            paramV2.baseInfo != null -> {
                                BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
                            }
                            paramV2.chatInfo != null -> {
                                ChatInfoCompose(paramV2, picMap = picMap)
                            }
                            paramV2.animTextInfo != null -> {
                                AnimTextInfoCompose(paramV2.animTextInfo, picMap = picMap)
                            }
                            paramV2.highlightInfo != null -> {
                                HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
                            }
                            paramV2.picInfo != null -> {
                                PicInfoCompose(paramV2.picInfo, picMap = picMap)
                            }
                            paramV2.hintInfo != null -> {
                                HintInfoCompose(paramV2.hintInfo, picMap = picMap)
                            }
                            paramV2.textButton != null -> {
                                TextButtonCompose(paramV2.textButton, picMap = picMap)
                            }
                            paramV2.paramIsland != null -> {
                                ParamIslandCompose(paramV2.paramIsland)
                            }
                            paramV2.actions?.isNotEmpty() == true -> {
                                ActionCompose(paramV2.actions, picMap)
                            }
                            else -> {
                                // 默认模板：未支持的模板类型
                                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                    is ParseResult.Failure -> {
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
            }
        }
    }
}

/**
 * 解析结果密封类
 */
private sealed class ParseResult {
    data class Success(val paramV2: ParamV2) : ParseResult()
    data class Failure(val title: String, val content: String) : ParseResult()
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
                
                ParseResult.Failure(
                    title = title.takeIf { it.isNotBlank() } ?: "超级岛",
                    content = content.takeIf { it.isNotBlank() } ?: ""
                )
            } catch (e: Exception) {
                // JSON解析也失败，显示基本信息
                ParseResult.Failure(
                    title = "超级岛通知",
                    content = ""
                )
            }
        }
    } catch (e: Exception) {
        // 任何异常都返回失败结果
        ParseResult.Failure(
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
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.92f)
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
