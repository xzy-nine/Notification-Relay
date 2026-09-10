package com.xzyht.notifyrelay.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import com.xzyht.notifyrelay.feature.media.MediaMessageReceiveMode
import com.xzyht.notifyrelay.feature.media.RemoteMediaSessionManager
import com.xzyht.notifyrelay.feature.media.MediaControlUtil
import com.xzyht.notifyrelay.sync.ProtocolSender
import com.xzyht.notifyrelay.ui.common.MiuixSpinnerPreference
import com.xzyht.notifyrelay.ui.screen.GlobalSelectedDeviceHolder
import notifyrelay.base.util.Logger
import notifyrelay.base.util.ToastUtils
import notifyrelay.data.StorageManager
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 音乐控制功能页面
 */
@Composable
fun MusicControlPage() {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    // 响应全局设备选中状态
    val selectedDeviceObj by GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceObj

    // 滚动状态
    val scrollState = rememberScrollState()

    var mediaMessageReceiveMode by remember {
        mutableStateOf(RemoteMediaSessionManager.getReceiveMode(context))
    }

    // 胶囊歌词开关状态
    var capsuleLyricsEnabled by remember { mutableStateOf(StorageManager.getBoolean(context, "capsule_lyrics_enabled")) }

    // 发送媒体通知到对端
    var sendMediaNotificationsEnabled by remember { mutableStateOf(StorageManager.getBoolean(context, "send_media_notifications_enabled", true)) }

    // 音频转发方式（0: scrcpy, 1: 中继）
    var audioRelayMode by remember { mutableStateOf(StorageManager.getInt(context, "audio_relay_mode", 0)) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 标题
        Text(
            text = "音乐控制",
            style = textStyles.title1,
            color = colorScheme.onSurface,
        )

        // 说明文本
        Text(
            text = "管理设备间的音频转发和媒体控制功能",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary,
        )

        // 显示当前选中的设备
        Text(
            text = "当前选中设备: ${selectedDevice?.displayName ?: "本机"}",
            style = textStyles.body1,
            color = colorScheme.onSurface,
        )

        // 音频转发标题
        Text(
            text = "音频转发",
            style = textStyles.title2,
            color = colorScheme.onSurface,
        )

        // 音频转发说明
        Text(
            text = "与选中设备进行音频转发",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary,
        )

        // 音频转发按钮组
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 接收对端音频按钮
            Button(
                onClick = {
                    if (selectedDevice == null) {
                        ToastUtils.showShortToast(context, "当前选中的是本机，无法接收本机音频")
                        return@Button
                    }

                    try {
                        val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
                        val audioRelay = DeviceConnectionManagerSingleton.getAudioRelay(context)
                        val relayMode = StorageManager.getInt(context, "audio_relay_mode", 0)

                        if (relayMode == 1) {
                            // 中继模式：先请求 MediaProjection 授权，再启动发送
                            audioRelay.requestSendTo(selectedDevice.ip, selectedDevice.displayName, selectedDevice.uuid)
                        } else {
                            // scrcpy 模式：发送 audioRequest（现有逻辑）
                            val success = deviceManager.requestAudioForwarding(selectedDevice)
                            if (success) {
                                ToastUtils.showShortToast(context, "已请求${selectedDevice.displayName}转发音频")
                            } else {
                                ToastUtils.showShortToast(context, "请求发送失败")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "请求音频转发异常", e)
                        ToastUtils.showShortToast(context, "请求发送异常: ${e.message}")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("发送本端音频到对端")
            }

            // 播放对端音频按钮
            Button(
                onClick = {
                    if (selectedDevice == null) {
                        ToastUtils.showShortToast(context, "当前选中的是本机，无法播放本机音频")
                        return@Button
                    }

                    try {
                        val relayMode = StorageManager.getInt(context, "audio_relay_mode", 0)

                        if (relayMode == 1) {
                            // 中继模式：通过控制器启动接收（设置 currentRemoteUuid、注册停止广播并显示中继前台通知）
                            DeviceConnectionManagerSingleton
                                .getAudioRelay(context)
                                .startReceive(selectedDevice.uuid, 48000, 2)
                            ToastUtils.showShortToast(context, "已启动中继音频接收")
                        } else {
                            // scrcpy 模式：启动 scrcpy 音频转发（现有逻辑）
                            val adbPort = notifyrelay.data.config.ScrcpyDefaults.ADB_PORT
                            val success =
                                io.github.miuzarte.scrcpyforandroid.services.AudioForwardingService.startAudioForwarding(
                                    context,
                                    selectedDevice.ip,
                                    adbPort,
                                    selectedDevice.displayName,
                                )
                            if (success) {
                                ToastUtils.showShortToast(context, "正在连接${selectedDevice.displayName}...")
                            } else {
                                ToastUtils.showShortToast(context, "启动失败，可能已有转发在进行中")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "播放对端音频异常", e)
                        ToastUtils.showShortToast(context, "启动异常: ${e.message}")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("播放对端音频")
            }
        }

        // 停止音频转发按钮
        Button(
            onClick = {
                io.github.miuzarte.scrcpyforandroid.services.AudioForwardingService
                    .stopAudioForwarding(context)
                DeviceConnectionManagerSingleton.getAudioRelay(context).stop()
                ToastUtils.showShortToast(context, "已停止音频转发")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("停止音频转发")
        }

        // 音频转发方式
        MiuixSpinnerPreference(
            title = "音频转发方式",
            summary = "选择音频转发方式：scrcpy（经 adb 转发）或中继（直接音频流），同时控制发送与接收",
            items =
                listOf(
                    "scrcpy（默认）",
                    "中继",
                ),
            selectedIndex = audioRelayMode,
            onSelectedIndexChange = { index ->
                audioRelayMode = index
                StorageManager.putInt(context, "audio_relay_mode", index)
            },
        )

        MiuixSpinnerPreference(
            title = "接收媒体消息",
            summary = "接收远端设备媒体播放信息并以超级岛形式显示",
            items =
                listOf(
                    "开",
                    "关",
                    "仅音频时开",
                ),
            selectedIndex =
                when (mediaMessageReceiveMode) {
                    MediaMessageReceiveMode.On -> 0
                    MediaMessageReceiveMode.Off -> 1
                    MediaMessageReceiveMode.AudioOnly -> 2
                },
            onSelectedIndexChange = { index ->
                val mode =
                    when (index) {
                        1 -> MediaMessageReceiveMode.Off
                        2 -> MediaMessageReceiveMode.AudioOnly
                        else -> MediaMessageReceiveMode.On
                    }
                mediaMessageReceiveMode = mode
                RemoteMediaSessionManager.setReceiveMode(context, mode)
            },
        )

        // 胶囊歌词开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "启用胶囊歌词",
                    style = textStyles.body1,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "接收本机媒体播放信息并以超级岛形式显示",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceSecondary,
                )
            }
            Switch(
                checked = capsuleLyricsEnabled,
                onCheckedChange = { enabled ->
                    capsuleLyricsEnabled = enabled
                    StorageManager.putBoolean(context, "capsule_lyrics_enabled", enabled)
                },
            )
        }

        // 发送媒体通知到对端
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "发送媒体通知到它端",
                    style = textStyles.body1,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "关闭后不再发送媒体通知到对端",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceSecondary,
                )
            }
            Switch(
                checked = sendMediaNotificationsEnabled,
                onCheckedChange = { enabled ->
                    sendMediaNotificationsEnabled = enabled
                    StorageManager.putBoolean(context, "send_media_notifications_enabled", enabled)
                },
            )
        }

        // 歌词分割模式设置
        var lyricsSplitMode by remember { mutableStateOf(StorageManager.getInt(context, "lyrics_split_mode", 0)) }

        MiuixSpinnerPreference(
            title = "歌词分割模式",
            summary = "默认：平板时不分割，手机时分割",
            items =
                listOf(
                    "默认",
                    "分割",
                    "不分割",
                ),
            selectedIndex = lyricsSplitMode,
            onSelectedIndexChange = { index ->
                lyricsSplitMode = index
                StorageManager.putInt(context, "lyrics_split_mode", index)
            },
        )

        // 媒体控制标题
        Text(
            text = "媒体控制",
            style = textStyles.title2,
            color = colorScheme.onSurface,
        )

        // 媒体控制说明文本
        Text(
            text = "控制当前选中设备的媒体播放",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary,
        )

        // 媒体控制按钮组
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 上一首按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            MediaControlUtil.previous()
                            ToastUtils.showShortToast(context, "已发送上一首指令到本机")
                        } else {
                            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
                            val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"previous\"}"
                            val result = ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", raw)
                            if (result == ProtocolSender.EnqueueResult.SUCCESS) {
                                ToastUtils.showShortToast(context, "已发送上一首指令到${selectedDevice.displayName}")
                            } else {
                                ToastUtils.showShortToast(context, "发送上一首指令失败")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送上一首指令失败", e)
                        ToastUtils.showShortToast(context, "发送上一首指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp),
            ) {
                Text("上一首")
            }

            // 播放/暂停按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            MediaControlUtil.playPause()
                            ToastUtils.showShortToast(context, "已发送播放/暂停指令到本机")
                        } else {
                            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
                            val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"playPause\"}"
                            val result = ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", raw)
                            if (result == ProtocolSender.EnqueueResult.SUCCESS) {
                                ToastUtils.showShortToast(context, "已发送播放/暂停指令到${selectedDevice.displayName}")
                            } else {
                                ToastUtils.showShortToast(context, "发送播放/暂停指令失败")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送播放/暂停指令失败", e)
                        ToastUtils.showShortToast(context, "发送播放/暂停指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp),
            ) {
                Text("播放\n暂停")
            }

            // 下一首按钮
            Button(
                onClick = {
                    try {
                        if (selectedDevice == null) {
                            MediaControlUtil.next()
                            ToastUtils.showShortToast(context, "已发送下一首指令到本机")
                        } else {
                            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
                            val raw = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"next\"}"
                            val result = ProtocolSender.sendEncrypted(deviceManager, selectedDevice, "DATA_MEDIA_CONTROL", raw)
                            if (result == ProtocolSender.EnqueueResult.SUCCESS) {
                                ToastUtils.showShortToast(context, "已发送下一首指令到${selectedDevice.displayName}")
                            } else {
                                ToastUtils.showShortToast(context, "发送下一首指令失败")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("NotifyRelay", "发送下一首指令失败", e)
                        ToastUtils.showShortToast(context, "发送下一首指令失败: ${e.message}")
                    }
                },
                modifier = Modifier.width(100.dp),
            ) {
                Text("下一首")
            }
        }

        // 提示信息
        Text(
            text = "注意：",
            style = textStyles.body1,
            color = colorScheme.onSurface,
        )

        Text(
            text =
                "1. 请确保目标设备已连接且在线\n" +
                    "2. 音频转发功能需要目标设备开启 ADB 调试\n" +
                    "3. 目标设备需要先完成 ADB 配对\n" +
                    "4. 媒体控制功能支持播放/暂停、上一首、下一首操作\n",
            style = textStyles.body2,
            color = colorScheme.onSurfaceSecondary,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
