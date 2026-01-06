package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.common.core.notification.servers.NotifyRelayNotificationListenerService
import com.xzyht.notifyrelay.common.core.sync.ProtocolSender
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.core.util.MediaControlUtil
import com.xzyht.notifyrelay.common.core.util.ToastUtils
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.ui.GlobalSelectedDeviceHolder
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 音频转发功能页面，与远程通知过滤同级
 */
@Composable
fun UIAudioForwarding() {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    
    // 响应全局设备选中状态
    val selectedDeviceObj by GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceObj
    
    // 监听当前设备的通知历史变化
    val notifications by NotificationRepository.notificationHistoryFlow.collectAsState()
    
    // 滚动状态
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "音频转发",
            style = textStyles.title1,
            color = colorScheme.onSurface
        )
        
        // 说明文本
        Text(
            text = "向当前选中的设备发送音频转发请求",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary
        )
        
        // 显示当前选中的设备
        Text(
            text = if (BuildConfig.DEBUG) {
                "当前选中设备: ${selectedDevice?.displayName ?: "本机"}${selectedDevice?.uuid?.let { " ($it)" } ?: ""}"
            } else {
                "当前选中设备: ${selectedDevice?.displayName ?: "本机"}"
            },
            style = textStyles.body1,
            color = colorScheme.onSurface
        )
        
        // 音频转发按钮
        Button(
            onClick = {
                if (selectedDevice == null) {
                    // 如果选中的是本机，显示提示
                    ToastUtils.showShortToast(context, "当前选中的是本机，无法转发音频到本机")
                    return@Button
                }
                
                try {
                    // 获取设备连接管理器实例
                    val deviceManager = DeviceConnectionManager.getInstance(context)
                    
                    // 直接向当前选中的设备发送音频转发请求
                    val success = deviceManager.requestAudioForwarding(selectedDevice)
                    
                    if (success) {
                        ToastUtils.showShortToast(context, "音频转发请求已发送")
                    } else {
                        ToastUtils.showShortToast(context, "音频转发请求发送失败")
                    }
                } catch (e: Exception) {
                    Logger.e("NotifyRelay", "音频转发请求发送异常", e)
                    ToastUtils.showShortToast(context, "音频转发请求发送异常: ${e.message}")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始音频转发")
        }
        
        // 媒体控制标题
        Text(
            text = "媒体控制",
            style = textStyles.title1,
            color = colorScheme.onSurface
        )
        
        // 媒体控制说明文本
        Text(
            text = "控制当前选中设备的媒体播放",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary
        )
        
        // 媒体控制按钮组
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 上一首按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            // 控制本机媒体，优先使用通知中的 PendingIntent 触发
                            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
                            if (sbn != null) {
                                MediaControlUtil.triggerPreviousFromNotification(sbn)
                                ToastUtils.showShortToast(context, "已发送上一首指令到本机")
                            } else {
                                ToastUtils.showShortToast(context, "未找到媒体通知，请启用通知监听服务或使用 PendingIntent")
                            }
                        } else {
                            // 向其他设备发送指令
                            val deviceManager = DeviceConnectionManager.getInstance(context)
                            val request = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"previous\"}"
                            ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", request)
                            ToastUtils.showShortToast(context, "已发送上一首指令到${selectedDevice.displayName}")
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送上一首指令失败", e)
                        ToastUtils.showShortToast(context, "发送上一首指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp)
            ) {
                Text("上一首")
            }
            
            // 播放/暂停按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            // 控制本机媒体，优先使用通知中的 PendingIntent 触发
                            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
                            if (sbn != null) {
                                MediaControlUtil.triggerPlayPauseFromNotification(sbn)
                                ToastUtils.showShortToast(context, "已发送播放/暂停指令到本机")
                            } else {
                                ToastUtils.showShortToast(context, "未找到媒体通知，请启用通知监听服务或使用 PendingIntent")
                            }
                        } else {
                            // 向其他设备发送指令
                            val deviceManager = DeviceConnectionManager.getInstance(context)
                            val request = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"playPause\"}"
                            ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", request)
                            ToastUtils.showShortToast(context, "已发送播放/暂停指令到${selectedDevice.displayName}")
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送播放/暂停指令失败", e)
                        ToastUtils.showShortToast(context, "发送播放/暂停指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp)
            ) {
                Text("播放/暂停")
            }
            
            // 下一首按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            // 控制本机媒体，优先使用通知中的 PendingIntent 触发
                            val sbn = NotifyRelayNotificationListenerService.latestMediaSbn
                            if (sbn != null) {
                                MediaControlUtil.triggerNextFromNotification(sbn)
                                ToastUtils.showShortToast(context, "已发送下一首指令到本机")
                            } else {
                                ToastUtils.showShortToast(context, "未找到媒体通知，请启用通知监听服务或使用 PendingIntent")
                            }
                        } else {
                            // 向其他设备发送指令
                            val deviceManager = DeviceConnectionManager.getInstance(context)
                            val request = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"next\"}"
                            ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", request)
                            ToastUtils.showShortToast(context, "已发送下一首指令到${selectedDevice.displayName}")
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送下一首指令失败", e)
                        ToastUtils.showShortToast(context, "发送下一首指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp)
            ) {
                Text("下一首")
            }
        }
        
        // 提示信息
        Text(
            text = "注意：",
            style = textStyles.body1,
            color = colorScheme.onSurface
        )
        
        Text(
            text = "1. 请确保目标设备已连接且在线\n" +
                    "2. 音频转发功能需要目标设备支持\n" +
                    "3. 目标设备暂时只能是pc,且需要adb调试开启,因为转发利用的是scrcpy\n" +
                    "4. 媒体控制功能支持播放/暂停、上一首、下一首操作",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary
        )
    }
}