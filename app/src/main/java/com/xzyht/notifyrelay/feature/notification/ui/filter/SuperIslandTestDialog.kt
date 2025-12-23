package com.xzyht.notifyrelay.feature.notification.ui.filter

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.extra.SuperDialog
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.parseParamV2

/**
 * 超级岛测试对话框，用于测试不同分支下的效果
 */
@Composable
fun SuperIslandTestDialog(
    show: androidx.compose.runtime.MutableState<Boolean>,
    context: Context
) {
    MiuixTheme {
        SuperDialog(
            title = "超级岛测试",
            summary = "点击下方按钮测试不同分支下的超级岛效果",
            show = show,
            onDismissRequest = { show.value = false },
            enableWindowDim = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 基础文本组件
                Button(
                    onClick = {
                        testBaseInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试基础文本组件 (baseInfo)")
                }

                // IM图文组件
                Button(
                    onClick = {
                        testChatInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试IM图文组件 (chatInfo)")
                }

                // 动画文本组件
                Button(
                    onClick = {
                        testAnimTextInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试动画文本组件 (animTextInfo)")
                }

                // 强调图文组件
                Button(
                    onClick = {
                        testHighlightInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试强调图文组件 (highlightInfo)")
                }

                // 识别图形组件
                Button(
                    onClick = {
                        testPicInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试识别图形组件 (picInfo)")
                }

                // 提示组件
                Button(
                    onClick = {
                        testHintInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试提示组件 (hintInfo)")
                }

                // 文本按钮组件
                Button(
                    onClick = {
                        testTextButton(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试文本按钮组件 (textButton)")
                }
                
                // 线性进度组件
                Button(
                    onClick = {
                        testProgressInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试线性进度组件 (progressInfo)")
                }
                
                // 多节点进度组件
                Button(
                    onClick = {
                        testMultiProgressInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试多节点进度组件 (multiProgressInfo)")
                }
                
                // 圆形进度组件
                Button(
                    onClick = {
                        testCircularProgressInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试圆形进度组件 (circular)")
                }
            }
        }
    }
}

/**
 * 测试基础文本组件
 */
private fun testBaseInfo(context: Context) {
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "基础文本测试",
                "content": "这是一个基础文本组件的展开态测试示例",
                "colorTitle": "#FFFFFF",
                "colorContent": "#CCCCCC"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_base_info",
        title = "基础文本测试",
        text = "这是一个基础文本组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = emptyMap()
    )
}

/**
 * 测试IM图文组件
 */
private fun testChatInfo(context: Context) {
    val paramV2Raw = """
        {
            "chatInfo": {
                "title": "聊天测试",
                "content": "这是一条聊天消息展开态测试示例",
                "picProfile": "profile_pic"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_chat_info",
        title = "聊天测试",
        text = "这是一条聊天消息展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "profile_pic" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 测试动画文本组件
 */
private fun testAnimTextInfo(context: Context) {
    val paramV2Raw = """
        {
            "animTextInfo": {
                "title": "动画文本测试",
                "content": "这是一个动画文本组件的展开态测试示例",
                "animIconInfo": {
                    "src": "anim_icon",
                    "type": 2
                }
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_anim_text_info",
        title = "动画文本测试",
        text = "这是一个动画文本组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "anim_icon" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 测试强调图文组件
 */
private fun testHighlightInfo(context: Context) {
    val paramV2Raw = """
        {
            "highlightInfo": {
                "title": "强调文本测试",
                "content": "这是一个强调图文组件的展开态测试示例",
                "picFunction": "highlight_pic"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_highlight_info",
        title = "强调文本测试",
        text = "这是一个强调图文组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "highlight_pic" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 测试识别图形组件
 */
private fun testPicInfo(context: Context) {
    val paramV2Raw = """
        {
            "picInfo": {
                "title": "图形测试",
                "pic": "test_pic"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_pic_info",
        title = "图形测试",
        text = "这是一个识别图形组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "test_pic" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 测试提示组件
 */
private fun testHintInfo(context: Context) {
    val paramV2Raw = """
        {
            "hintInfo": {
                "title": "提示测试",
                "content": "这是一个提示组件的展开态测试示例",
                "picContent": "hint_pic"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_hint_info",
        title = "提示测试",
        text = "这是一个提示组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "hint_pic" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 测试文本按钮组件
 */
private fun testTextButton(context: Context) {
    val paramV2Raw = """
        {
            "textButton": {
                "title": "文本按钮测试",
                "content": "这是一个文本按钮组件的展开态测试示例"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_text_button",
        title = "文本按钮测试",
        text = "这是一个文本按钮组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = emptyMap()
    )
}

/**
 * 测试线性进度组件
 */
private fun testProgressInfo(context: Context) {
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "线性进度测试",
                "content": "这是一个线性进度组件的展开态测试示例"
            },
            "progressInfo": {
                "progress": 40,
                "colorProgress": "#FF8514",
                "colorProgressEnd": "#FF8514"
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_progress_info",
        title = "线性进度测试",
        text = "这是一个线性进度组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = emptyMap()
    )
}

/**
 * 测试多节点进度组件
 */
private fun testMultiProgressInfo(context: Context) {
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "多节点进度测试",
                "content": "这是一个多节点进度组件的展开态测试示例"
            },
            "multiProgressInfo": {
                "title": "正在排水",
                "progress": 60,
                "color": "#00FF00",
                "points": 3
            }
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_multi_progress_info",
        title = "多节点进度测试",
        text = "这是一个多节点进度组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = emptyMap()
    )
}

/**
 * 测试圆形进度组件（基于真实小米互传数据）
 */
private fun testCircularProgressInfo(context: Context) {
    val paramV2Raw = """
        {
            "protocol": 1,
            "islandFirstFloat": true,
            "enableFloat": true,
            "timeout": 720,
            "updatable": true,
            "ticker": "56%",
            "chatInfo": {
                "picProfile": "miui.focus.pic_thumbnail",
                "title": "正在接收1个文件",
                "content": "18.6 MB"
            },
            "actions": [
                {
                    "progressInfo": {
                        "progress": 56,
                        "colorProgress": "#3482FF",
                        "colorProgressDark": "#3482FF",
                        "colorProgressEnd": "#1A000000",
                        "colorProgressEndDark": "#33FFFFFF",
                        "isCCW": true
                    },
                    "type": 1,
                    "actionTitle": "取消",
                    "action": "miui.focus.action_cancel"
                }
            ],
            "business": "mishare"
        }
    """
    
    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_circular_progress_info",
        title = "圆形进度测试",
        text = "这是一个基于真实数据的圆形进度组件测试",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "miui.focus.pic_thumbnail" to createBlackBlockDataUrl(),
            "miui.focus.pic_cancel" to createBlackBlockDataUrl(),
            "miui.focus.pic_cancelDark" to createBlackBlockDataUrl()
        )
    )
}

/**
 * 创建一个黑色块的data URL，用于测试图片显示
 */
private fun createBlackBlockDataUrl(): String {
    // 100x100黑色块的base64编码
    return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
}