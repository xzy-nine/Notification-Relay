package com.xzyht.notifyrelay.ui.guide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import notifyrelay.base.util.IntentUtils
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.GuidePermissionRequester
import notifyrelay.base.util.ToastUtils
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GuideOptionalPermissionPage(
    permissionRequester: GuidePermissionRequester,
    permissionState: GuidePermissionUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current

    fun showToast(message: String) {
        ToastUtils.showShortToast(context, message)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        GuidePageHeader(
            stepLabel = "4 / 6",
            title = "可选权限",
            subtitle = "以下权限建议开启，也可以稍后在系统设置或应用内设置中开启",
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
        ) {
            GuideSectionLabel(
                title = "建议开启",
                description = "用于优化设备发现、FTP、超级岛等增强功能",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    GuidePermissionItem(
                        title = "蓝牙连接权限",
                        summary =
                            if (permissionState.bluetoothConnect) {
                                "已授权，可优化设备发现速度并显示真实设备名"
                            } else {
                                "用于优化设备发现速度，显示真实设备名"
                            },
                        granted = permissionState.bluetoothConnect,
                        onClick = {
                            permissionRequester.requestBluetoothConnect()
                            showToast("开启后可优化设备发现速度，并以设备实际名称而非型号作为设备名")
                        },
                    )
                    GuidePermissionItem(
                        title = "文件管理权限",
                        summary =
                            if (permissionState.manageExternalStorage) {
                                "已授权，FTP 功能可正常管理设备文件"
                            } else {
                                "用于支持 FTP 功能，管理设备文件"
                            },
                        granted = permissionState.manageExternalStorage,
                        onClick = {
                            showToast("跳转文件管理权限设置")
                            PermissionHelper.requestManageExternalStoragePermission(context)
                        },
                    )
                    GuidePermissionItem(
                        title = "后台无限制权限",
                        summary =
                            if (permissionState.backgroundUnlimited) {
                                "已设置，应用可在后台保持运行"
                            } else {
                                "用于确保应用在后台正常运行，防止被系统杀死"
                            },
                        granted = permissionState.backgroundUnlimited,
                        grantedText = "已设置",
                        pendingText = "去设置",
                        onClick = {
                            showToast("跳转到电池优化设置，请将应用设为无限制")
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = "package:${context.packageName}".toUri()
                            IntentUtils.startActivity(context, intent, true)
                        },
                    )
                    GuidePermissionItem(
                        title = "悬浮窗权限",
                        summary =
                            if (permissionState.overlay) {
                                "已授权，可显示超级岛/悬浮岛复刻"
                            } else {
                                "用于支持超级岛/悬浮岛复刻，提升通知交互体验"
                            },
                        granted = permissionState.overlay,
                        onClick = {
                            showToast("跳转悬浮窗权限设置")
                            try {
                                (context as? Activity)?.let { act ->
                                    PermissionHelper.requestOverlayPermission(act)
                                } ?: run {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    intent.data = "package:${context.packageName}".toUri()
                                    IntentUtils.startActivity(context, intent, true)
                                }
                            } catch (_: Exception) {
                                showToast("无法跳转悬浮窗设置，请手动在系统设置中允许悬浮窗权限")
                            }
                        },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 35) {
                Spacer(modifier = Modifier.height(12.dp))

                // 敏感通知权限无法可靠读取状态，按需求同样使用 ArrowPreference 引导。
                ArrowPreference(
                    title = "敏感通知访问权限",
                    summary = "Android 15+ 可选权限。状态无法直接读取；未开启时部分通知只会显示“已隐藏敏感通知”。点击尝试跳转授权，也可以复制下方 adb 命令授权。",
                    onClick = {
                        (context as? Activity)?.let { act ->
                            PermissionHelper.requestSensitiveNotificationPermission(act)
                        }
                    },
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            IntentUtils.startActivity(
                                context,
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                                true,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        minHeight = 36.dp,
                    ) {
                        Text(text = "去设置", style = MiuixTheme.textStyles.body2)
                    }
                    Button(
                        onClick = {
                            val adbCmd = "adb shell appops set ${context.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null) {
                                val clip = ClipData.newPlainText("adb", adbCmd)
                                clipboard.setPrimaryClip(clip)
                                showToast("已复制 adb 命令到剪贴板")
                            } else {
                                showToast("剪贴板服务不可用")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        minHeight = 36.dp,
                    ) {
                        Text(text = "复制 adb 命令", style = MiuixTheme.textStyles.body2)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        GuidePageFooter(
            hint = null,
            nextText = "下一步",
            nextEnabled = true,
            onBack = onBack,
            onNext = onNext,
        )
    }
}
