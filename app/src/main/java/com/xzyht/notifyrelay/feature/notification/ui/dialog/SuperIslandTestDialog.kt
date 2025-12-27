package com.xzyht.notifyrelay.feature.notification.ui.dialog

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 全局变量，用于保存递增循环的进度值
private var progressCounter = 0

/**
 * 获取进度值
 * @param isVariableProgress 是否使用可变进度值
 * @param fixedValue 固定进度值
 * @return 生成的进度值
 */
private fun getProgress(isVariableProgress: Boolean, fixedValue: Int): Int {
    return if (isVariableProgress) {
        // 以10为单位递增循环进度值，用于测试动画效果
        val currentProgress = progressCounter
        // 每次递增10，达到100时重置为0
        progressCounter = if (currentProgress >= 100) {
            0
        } else {
            currentProgress + 10
        }
        currentProgress
    } else {
        // 固定进度值，用于测试静态效果
        fixedValue
    }
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
    // 使用简单的黑色块替代被截断的base64图标
    return createBlackBlockDataUrl()
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
private fun testProgressInfo(context: Context, isVariableProgress: Boolean) {
    // 获取进度值
    val progress = getProgress(isVariableProgress, 75)
    
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "线性进度测试",
                "content": "这是一个线性进度组件的展开态测试示例"
            },
            "progressInfo": {
                "progress": $progress,
                "colorProgress": "#FF8514",
                "colorProgressEnd": "#FF8514"
            },
            "param_island": {
                "smallIslandArea": {
                    "primaryText": "线性进度测试",
                    "secondaryText": "$progress% 完成",
                    "iconKey": "progress_icon",
                    "progressInfo": {
                        "progress": $progress,
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
                            "content": "$progress%"
                        }
                    },
                    "sameWidthDigitInfo": {
                        "digit": "$progress",
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
private fun testMultiProgressInfo(context: Context, isVariableProgress: Boolean) {
    // 获取进度值
    val progress = getProgress(isVariableProgress, 60)
    
    val paramV2Raw = """
        {
            "baseInfo": {
                "title": "多节点进度测试",
                "content": "这是一个多节点进度组件的展开态测试示例"
            },
            "multiProgressInfo": {
                "title": "正在排水",
                "progress": $progress,
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
                        "progress": $progress,
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
                            "content": "$progress%"
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
private fun testMultiProgressWithIcons(context: Context, isVariableProgress: Boolean) {
    // 获取进度值
    val progress = getProgress(isVariableProgress, 75)
    
    // 根据进度值动态生成配送状态文本
    val deliveryStatus = when {
        progress <= 50 -> "骑士正在取餐"
        else -> "骑士正在配送"
    }
    
    val paramV2Raw = """
        {
            "baseInfo": {
                "showDivider": true,
                "title": "预计\u003cfont color\u003d\u0027#0FAD50\u0027\u003e19:31\u003c/font\u003e送达",
                "type": 2,
                "colorSubTitle": "#FF6200",
                "content": "$deliveryStatus",
                "subContent": "商家名aaaa"
            },
            "param_island": {
                "highlightColor": "#FF6200",
                "islandProperty": 1,
                "bigIslandArea": {
                    "imageTextInfoLeft": {
                        "textInfo": {
                            "showHighlightColor": false,
                            "title": "$deliveryStatus"
                        },
                        "picInfo": {
                            "pic": "miui.focus.pic_app_icon",
                            "type": 1
                        },
                        "type": 1
                    },
                    "textInfo": {
                        "showHighlightColor": true,
                        "title": "19:31",
                        "content": "送达"
                    }
                },
                "smallIslandArea": {
                    "picInfo": {
                        "pic": "miui.focus.pic_app_icon",
                        "type": 1
                    }
                }
            },
            "aodPic": "miui.focus.pic_app_icon",
            "business": "food_delivery",
            "picInfo": {
                "pic": "miui.focus.pic_app_icon",
                "type": 1
            },
            "orderId": "8043310281561250264",
            "enableFloat": false,
            "sequence": 1766745828259,
            "protocol": 1,
            "filterWhenNoPermission": true,
            "reopen": "close",
            "aodTitle": "19:31送达",
            "updatable": true,
            "progressInfo": {
                "picEnd": "miui.focus.pic_end",
                "picEndUnselected": "miui.focus.pic_end_unselected",
                "colorProgressEnd": "#FF6200",
                "picForward": "miui.focus.pic_forward",
                "picForwardWait": "miui.focus.pic_forward_wait",
                "picForwardBox": "miui.focus.pic_forward_box",
                "progress": $progress,
                "colorProgress": "#FF6200",
                "picMiddleUnselected": "miui.focus.pic_middel_unselected",
                "picMiddle": "miui.focus.pic_middle"
            },
            "islandFirstFloat": false
        }
    """

    FloatingReplicaManager.showFloating(
        context = context,
        sourceId = "test_multi_progress_with_icons",
        title = "带图标多节点进度测试",
        text = "这是一个带有图标的多节点进度组件测试示例",
        paramV2Raw = paramV2Raw,
        picMap = mapOf(
            "miui.focus.pic_forward" to "https://gw.alicdn.com/imgextra/i4/O1CN01RBCiIV26Q2ysJNney_!!6000000007655-2-tps-180-141.png",
            "miui.focus.pic_middle" to "https://gw.alicdn.com/imgextra/i4/O1CN01gVYsU51zTz4jBuPxN_!!6000000006716-2-tps-120-188.png",
            "miui.focus.pic_middel_unselected" to "https://gw.alicdn.com/imgextra/i3/O1CN011JQ8qv1QXdgUdtx6j_!!6000000001986-2-tps-132-194.png",
            "miui.focus.pic_end_unselected" to "https://gw.alicdn.com/imgextra/i2/O1CN013MEAhj1Q3PXgmGojQ_!!6000000001920-2-tps-120-191.png",
            "miui.focus.pic_end" to "https://gw.alicdn.com/imgextra/i2/O1CN01BtZfit1PrxdrYJaSH_!!6000000001895-2-tps-122-190.png",
            "miui.focus.pic_forward_wait" to "https://gw.alicdn.com/imgextra/i3/O1CN012aWbML1PqaSChXHK7_!!6000000001892-2-tps-180-141.png",
            "miui.focus.pic_forward_box" to "https://gw.alicdn.com/imgextra/i1/O1CN01LLnS7n1xM50ZBmNL7_!!6000000006428-2-tps-180-141.png",
            "miui.focus.pic_app_icon" to appimgDataUrl(),
        ),
        isLocked = false
    )
}

/**
 * 测试圆形进度组件（基于真实小米互传数据）
 */
private fun testCircularProgressInfo(context: Context, isVariableProgress: Boolean) {
    // 获取进度值
    val progress = getProgress(isVariableProgress, 56)
    
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
            "ticker": "$progress%",
            "aodTitle": "$progress%",
            "chatInfo": {
                "picProfile": "miui.focus.pic_thumbnail",
                "picProfileDark": "miui.focus.pic_thumbnail",
                "title": "正在接收1个文件",
                "content": "18.6 MB"
            },
            "actions": [
                {
                    "progressInfo": {
                        "progress": $progress,
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
                            "progress": $progress,
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
                            "progress": $progress,
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
 * 超级岛测试对话框，用于测试不同分支下的效果
 */
@Composable
fun SuperIslandTestDialog(
    show: MutableState<Boolean>,
    context: Context
) {
    // 进度可变开关状态
    var isVariableProgress by remember { mutableStateOf(false) }
    
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
                    .padding(16.dp)
            ) {
                // 进度可变开关
                SuperSwitch(
                    title = "测试设置",
                    summary = if (isVariableProgress) "进度可变 (测试动画效果)" else "进度固定 (测试静态效果)",
                    checked = isVariableProgress,
                    onCheckedChange = { isVariableProgress = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                item {
                    // 基础文本组件
                    Button(
                        onClick = {
                            testBaseInfo(context)
                        },
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试文本按钮组件 (textButton)")
                    }
                }

                item {
                    // 线性进度组件
                    Button(
                        onClick = {
                            testProgressInfo(context, isVariableProgress)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试线性进度组件 (progressInfo)")
                    }
                }

                item {
                    // 多节点进度组件
                    Button(
                        onClick = {
                            testMultiProgressInfo(context, isVariableProgress)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试多节点进度组件 (multiProgressInfo)")
                    }
                }

                item {
                    // 带有图标的多节点进度组件
                    Button(
                        onClick = {
                            testMultiProgressWithIcons(context, isVariableProgress)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试带图标多节点进度组件")
                    }
                }

                item {
                    // 圆形进度组件
                    Button(
                        onClick = {
                            testCircularProgressInfo(context, isVariableProgress)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试圆形进度组件 (circular)")
                    }
                }
            }
        }
    }
}}
