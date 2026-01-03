package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.ui.GlobalSelectedDeviceHolder
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.core.util.ToastUtils
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
            text = "当前选中设备: ${selectedDevice?.displayName ?: "本机"}",
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
        
        // 提示信息
        Text(
            text = "注意：",
            style = textStyles.body1,
            color = colorScheme.onSurface
        )
        
        Text(
            text = "1. 请确保目标设备已连接且在线\n" +
                    "2. 音频转发功能需要目标设备支持\n" +
                    "3. 目标设备暂时只能是pc,且需要adb调试开启,因为转发利用的是scrcpy",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary
        )
    }
}