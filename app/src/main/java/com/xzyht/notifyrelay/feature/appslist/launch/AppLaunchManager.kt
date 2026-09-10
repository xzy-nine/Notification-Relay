package com.xzyht.notifyrelay.feature.appslist.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.sync.ProtocolSender
import notifyrelay.base.util.Logger
import org.json.JSONObject

object AppLaunchManager {
    private const val TAG = "AppLaunchManager"

    fun handleAppLaunchRequest(
        decrypted: String,
        deviceManager: DeviceConnectionManager,
        source: DeviceInfo,
        context: Context,
    ) {
        try {
            val json = JSONObject(decrypted)
            when (val action = json.optString("action", "")) {
                "launchApp" -> {
                    val targetPackageName = json.getString("packageName")
                    val displayId = json.optInt("displayId", -1)

                    Logger.i(TAG, "收到启动应用请求: $targetPackageName, displayId: $displayId")

                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val options = ActivityOptions.makeBasic()
                                options.launchDisplayId = displayId
                                context.startActivity(launchIntent, options.toBundle())
                                Logger.i(TAG, "成功在显示器 $displayId 上启动目标应用: $targetPackageName")
                            } else {
                                context.startActivity(launchIntent)
                                Logger.i(TAG, "成功启动目标应用: $targetPackageName")
                            }
                        } else {
                            Logger.w(TAG, "无法获取目标应用的启动 Intent: $targetPackageName")
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "启动目标应用失败: $targetPackageName", e)
                    }
                }
                else -> {
                    Logger.w(TAG, "未知的 action: $action")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理应用启动请求失败", e)
        }
    }

    fun sendAppLaunchRequest(
        context: Context,
        deviceManager: DeviceConnectionManager,
        target: DeviceInfo,
        packageName: String,
        displayId: Int = -1,
    ) {
        try {
            val raw =
                JSONObject()
                    .apply {
                        put("action", "launchApp")
                        put("packageName", packageName)
                        if (displayId > 0) {
                            put("displayId", displayId)
                        }
                    }.toString()
            Logger.d(TAG, "发送应用启动请求: $packageName, displayId: $displayId")
            ProtocolSender.sendEncrypted(deviceManager, target, "DATA_APP_LAUNCH", raw)
        } catch (e: Exception) {
            Logger.e(TAG, "发送应用启动请求失败", e)
        }
    }
}
