package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigisland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.clipboard.ClipboardSyncManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import github.xzynine.superislandui.floating.bigisland.components.ProgressInfoCompose
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.model.components.ActionInfo
import github.xzynine.superislandui.model.core.ParamIsland
import notifyrelay.base.util.Logger
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * ParamIsland的Compose组件实现
 */
@Composable
fun ParamIslandCompose(
    paramIsland: ParamIsland,
    modifier: Modifier = Modifier,
    actions: List<ActionInfo>? = null,
    picMap: Map<String, String>? = null,
) {
    // 调试日志
    paramIsland.bigIslandArea?.let {
        Logger.d("超级岛ParamIslandCompose", "渲染ParamIsland: isVerCode=${it.isVerificationCode}, code=${it.verificationCode}, primary=${it.primaryText}")
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp),
    ) {
        // SmallIslandArea渲染
        paramIsland.smallIslandArea?.let { smallArea ->
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                smallArea.primaryText?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                smallArea.secondaryText?.let {
                    Text(
                        text = it,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                smallArea.progressInfo?.let { progressInfo ->
                    ProgressInfoCompose(
                        progressInfo = progressInfo,
                    )
                }
            }
        }

        // BigIslandArea渲染（简化版，仅显示文本信息）
        paramIsland.bigIslandArea?.let { bigArea ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                // 内容区域：图标 + 文本
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 左侧图标
                    if (!bigArea.leftImage.isNullOrBlank()) {
                        CommonImageCompose(
                            picKey = bigArea.leftImage,
                            picMap = picMap,
                            size = 40.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // 文本信息
                    Column(modifier = Modifier.weight(1f)) {
                        bigArea.primaryText?.let {
                            Text(
                                text = it,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        bigArea.secondaryText?.let {
                            Text(
                                text = it,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    if (bigArea.isVerificationCode) {
                        val copyAction =
                            actions?.find {
                                it.actionTitle?.contains("复制") == true
                            } ?: ActionInfo(actionTitle = "复制")
                        val code = bigArea.verificationCode ?: bigArea.primaryText
                        if (!code.isNullOrBlank()) {
                            Logger.d("ParamIslandCompose", "渲染验证码复制按钮: code=$code")
                            val context = LocalContext.current
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                text = copyAction.actionTitle ?: "复制",
                                onClick = {
                                    if (code.contains("*")) {
                                        Toast.makeText(context, "请解锁对端设备", Toast.LENGTH_SHORT).show()
                                    } else {
                                        try {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("verification code", code)
                                            clipboard.setPrimaryClip(clip)
                                            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
                                            ClipboardSyncManager.syncTextDirectly(deviceManager, code, context)
                                            Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Logger.e("ParamIslandCompose", "复制验证码失败", e)
                                            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        } else {
                            Logger.w("超级岛ParamIslandCompose", "验证码按钮未渲染: copyAction=${true}, code=${!code.isNullOrBlank()}")
                        }
                    }

                    // 右侧图标
                    if (!bigArea.rightImage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        CommonImageCompose(
                            picKey = bigArea.rightImage,
                            picMap = picMap,
                            size = 40.dp,
                        )
                    }
                }
            }
        }
    }
}
