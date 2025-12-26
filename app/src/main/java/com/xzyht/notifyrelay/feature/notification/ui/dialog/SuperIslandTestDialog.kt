package com.xzyht.notifyrelay.feature.notification.ui.dialog

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 超级岛测试对话框，用于测试不同分支下的效果
 */
@Composable
fun SuperIslandTestDialog(
    show: MutableState<Boolean>,
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
            LazyColumn(
                modifier = Modifier.Companion
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    // 基础文本组件
                    Button(
                        onClick = {
                            testBaseInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试基础文本组件 (baseInfo)")
                    }
                }

                item {
                    // IM图文组件
                    Button(
                        onClick = {
                            testChatInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试IM图文组件 (chatInfo)")
                    }
                }

                item {
                    // 动画文本组件
                    Button(
                        onClick = {
                            testAnimTextInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试动画文本组件 (animTextInfo)")
                    }
                }

                item {
                    // 强调图文组件
                    Button(
                        onClick = {
                            testHighlightInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试强调图文组件 (highlightInfo)")
                    }
                }

                item {
                    // 识别图形组件
                    Button(
                        onClick = {
                            testPicInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试识别图形组件 (picInfo)")
                    }
                }

                item {
                    // 提示组件
                    Button(
                        onClick = {
                            testHintInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试提示组件 (hintInfo)")
                    }
                }

                item {
                    // 文本按钮组件
                    Button(
                        onClick = {
                            testTextButton(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试文本按钮组件 (textButton)")
                    }
                }

                item {
                    // 线性进度组件
                    Button(
                        onClick = {
                            testProgressInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试线性进度组件 (progressInfo)")
                    }
                }

                item {
                    // 多节点进度组件
                    Button(
                        onClick = {
                            testMultiProgressInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试多节点进度组件 (multiProgressInfo)")
                    }
                }

                item {
                    // 带有图标的多节点进度组件
                    Button(
                        onClick = {
                            testMultiProgressWithIcons(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试带图标多节点进度组件")
                    }
                }

                item {
                    // 圆形进度组件
                    Button(
                        onClick = {
                            testCircularProgressInfo(context)
                        },
                        modifier = Modifier.Companion.fillMaxWidth()
                    ) {
                        Text("测试圆形进度组件 (circular)")
                    }
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "基础文本测试",
                    "secondaryText": "这是摘要态示例",
                    "iconKey": "base_icon"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "base_icon"
                        },
                        "textInfo": {
                            "title": "基础文本",
                            "content": "测试"
                        }
                    },
                    "imageTextInfoRight": {
                        "type": 2,
                        "picInfo": {
                            "type": 1,
                            "pic": "base_icon"
                        },
                        "textInfo": {
                            "title": "展开态",
                            "content": "示例"
                        }
                    }
                }
            }
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_base_info",
        title = "基础文本测试",
        text = "这是一个基础文本组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "base_icon" to createBlackBlockDataUrl()
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "聊天测试",
                    "secondaryText": "新消息",
                    "iconKey": "profile_pic"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "profile_pic"
                        },
                        "textInfo": {
                            "title": "聊天",
                            "content": "测试"
                        }
                    },
                    "imageTextInfoRight": {
                        "type": 3,
                        "picInfo": {
                            "type": 1,
                            "pic": "profile_pic"
                        },
                        "textInfo": {
                            "title": "新消息",
                            "content": "1条"
                        }
                    }
                }
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
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "动画文本测试",
                    "secondaryText": "动画效果",
                    "iconKey": "anim_icon"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "anim_icon"
                        },
                        "textInfo": {
                            "title": "动画文本",
                            "content": "测试"
                        }
                    },
                    "imageTextInfoRight": {
                        "type": 4,
                        "textInfo": {
                            "title": "动画效果",
                            "content": "示例"
                        }
                    }
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
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "强调文本测试",
                    "secondaryText": "强调内容",
                    "iconKey": "highlight_pic"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 5,
                        "picInfo": {
                            "type": 1,
                            "pic": "highlight_pic"
                        },
                        "textInfo": {
                            "title": "强调文本",
                            "content": "测试"
                        }
                    },
                    "imageTextInfoRight": {
                        "type": 6,
                        "picInfo": {
                            "type": 4,
                            "pic": "highlight_pic"
                        },
                        "textInfo": {
                            "title": "强调内容",
                            "content": "示例"
                        }
                    }
                }
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
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "图形测试",
                    "secondaryText": "识别图形",
                    "iconKey": "test_pic"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "test_pic"
                        },
                        "textInfo": {
                            "title": "图形测试",
                            "content": "识别"
                        }
                    },
                    "picInfo": {
                        "type": 1,
                        "pic": "test_pic"
                    }
                }
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
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "提示测试",
                    "secondaryText": "提示信息",
                    "iconKey": "hint_pic"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "hint_pic"
                        },
                        "textInfo": {
                            "title": "提示测试",
                            "content": "信息"
                        }
                    },
                    "textInfo": {
                        "title": "提示组件",
                        "content": "示例"
                    }
                }
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
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "文本按钮测试",
                    "secondaryText": "点击操作",
                    "iconKey": "button_icon"
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "button_icon"
                        },
                        "textInfo": {
                            "title": "文本按钮",
                            "content": "测试"
                        }
                    },
                    "fixedWidthDigitInfo": {
                        "digit": "123",
                        "content": "数字显示",
                        "showHighlightColor": true
                    }
                }
            }
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_text_button",
        title = "文本按钮测试",
        text = "这是一个文本按钮组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "button_icon" to createBlackBlockDataUrl()
        ),
        isLocked = false
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
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "线性进度测试",
                    "secondaryText": "40% 完成",
                    "iconKey": "progress_icon",
                    "progressInfo": {
                        "progress": 40,
                        "colorProgress": "#FF8514",
                        "colorProgressEnd": "#FF8514"
                    }
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "progress_icon"
                        },
                        "textInfo": {
                            "title": "线性进度",
                            "content": "40%"
                        }
                    },
                    "sameWidthDigitInfo": {
                        "digit": "40",
                        "content": "进度",
                        "showHighlightColor": true
                    }
                }
            }
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_progress_info",
        title = "线性进度测试",
        text = "这是一个线性进度组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "progress_icon" to createBlackBlockDataUrl()
        ),
        isLocked = false
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
                "points": 3,
                "picForward": "forward_pic",
                "picForwardBox": "box_pic",
                "picMiddle": "middle_pic",
                "picMiddleUnselected": "middle_unselected_pic",
                "picEnd": "end_pic",
                "picEndUnselected": "end_unselected_pic"
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "多节点进度测试",
                    "secondaryText": "正在排水",
                    "iconKey": "forward_pic",
                    "progressInfo": {
                        "progress": 60,
                        "colorProgress": "#00FF00",
                        "colorProgressEnd": "#00FF00"
                    }
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "forward_pic"
                        },
                        "textInfo": {
                            "title": "多节点进度",
                            "content": "60%"
                        }
                    },
                    "sameWidthDigitInfo": {
                        "timer": {
                            "timerType": 1,
                            "timerWhen": 1717470687604,
                            "timerTotal": 3600000,
                            "timerSystemCurrent": 1717470687604
                        },
                        "content": "倒计时",
                        "showHighlightColor": true
                    }
                }
            }
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_multi_progress_info",
        title = "多节点进度测试",
        text = "这是一个多节点进度组件的展开态测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "forward_pic" to createBlackBlockDataUrl(),
            "box_pic" to createBlackBlockDataUrl(),
            "middle_pic" to createBlackBlockDataUrl(),
            "middle_unselected_pic" to createBlackBlockDataUrl(),
            "end_pic" to createBlackBlockDataUrl(),
            "end_unselected_pic" to createBlackBlockDataUrl()
        ),
        isLocked = false
    )
}

/**
 * 测试带有图标的多节点进度组件
 */
private fun testMultiProgressWithIcons(context: Context) {
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "带图标多节点进度测试",
                "content": "这是一个带有图标的多节点进度组件测试示例"
            },
            "multiProgressInfo": {
                "title": "配送中",
                "progress": 50,
                "color": "#3482FF",
                "points": 4,
                "picForward": "forward_pic",
                "picForwardBox": "box_pic",
                "picMiddle": "middle_pic",
                "picMiddleUnselected": "middle_unselected_pic",
                "picEnd": "end_pic",
                "picEndUnselected": "end_unselected_pic"
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "带图标多节点进度测试",
                    "secondaryText": "配送中 50%",
                    "iconKey": "forward_pic",
                    "progressInfo": {
                        "progress": 50,
                        "colorProgress": "#3482FF",
                        "colorProgressEnd": "#3482FF"
                    }
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 1,
                            "pic": "forward_pic"
                        },
                        "textInfo": {
                            "title": "带图标进度",
                            "content": "50%"
                        }
                    },
                    "progressTextInfo": {
                        "textInfo": {
                            "title": "配送中",
                            "content": "50%"
                        },
                        "progressInfo": {
                            "progress": 50,
                            "colorReach": "#3482FF",
                            "colorUnReach": "#33FFFFFF",
                            "isCCW": false
                        },
                        "picKey": "forward_pic"
                    }
                }
            }
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_multi_progress_with_icons",
        title = "带图标多节点进度测试",
        text = "这是一个带有图标的多节点进度组件测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "forward_pic" to appimgDataUrl(),
            "box_pic" to createBlackBlockDataUrl(),
            "middle_pic" to createBlackBlockDataUrl(),
            "middle_unselected_pic" to createBlackBlockDataUrl(),
            "end_pic" to createBlackBlockDataUrl(),
            "end_unselected_pic" to createBlackBlockDataUrl()
        ),
        isLocked = false
    )
}

/**
 * 测试圆形进度组件（基于真实小米互传数据）
 */
private fun testCircularProgressInfo(context: Context) {
    val paramV2Raw = """
        {
            "protocol": 1,
            "notifyId": "com.miui.mishare.connectivity10000",
            "islandFirstFloat": true,
            "enableFloat": false,
            "timeout": 720,
            "updatable": true,
            "reopen": "reopen",
            "filterWhenNoPermission": false,
            "ticker": "56%",
            "aodTitle": "56%",
            "chatInfo": {
                "picProfile": "miui.focus.pic_thumbnail",
                "picProfileDark": "miui.focus.pic_thumbnail",
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
                    "actionIcon": "miui.focus.pic_cancel",
                    "actionIconDark": "miui.focus.pic_cancelDark",
                    "action": "miui.focus.action_cancel"
                }
            ],
            "business": "mishare",
            "param_island": {
                "islandProperty": 1,
                "islandPriority": 1,
                "islandOrder": true,
                "islandTimeout": 3600,
                "expandedTime": 5,
                "smallIslandArea": {
                    "combinePicInfo": {
                        "picInfo": {
                            "type": 1,
                            "pic": "miui.focus.pic_thumbnail"
                        },
                        "progressInfo": {
                            "progress": 56,
                            "colorReach": "#3482FF",
                            "colorUnReach": "#33FFFFFF",
                            "isCCW": true
                        }
                    }
                },
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "type": 1,
                        "picInfo": {
                            "type": 4,
                            "pic": "miui.focus.pic_thumbnail"
                        }
                    },
                    "progressTextInfo": {
                        "progressInfo": {
                            "progress": 56,
                            "colorReach": "#3482FF",
                            "colorUnReach": "#33FFFFFF",
                            "isCCW": true
                        }
                    }
                }
            }
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
            "miui.focus.pic_island" to createBlackBlockDataUrl(),
            "miui.focus.pic_cancel" to createBlackBlockDataUrl(),
            "miui.focus.pic_cancelDark" to createBlackBlockDataUrl(),
            "miui.focus.pic_app_icon" to createBlackBlockDataUrl()
        ),
        isLocked = false
    )
}

/**
 * 创建一个黑色块的data URL，用于测试图片显示
 */
private fun createBlackBlockDataUrl(): String {
    // 100x100黑色块的base64编码
    return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
}

/**
 * 创建一个应用图标data URL，用于测试图片显示
 */
private fun appimgDataUrl(): String {
    // 应用图标的base64编码
    return "data:image/png;base64,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"
}