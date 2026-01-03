package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import com.xzyht.notifyrelay.common.data.PersistenceManager
import java.io.File

object DeviceInfoManager {
    private const val TAG = "DeviceInfoManager"
    private const val DEVICE_INFO_FILE = "device_info.txt"

    fun generateDeviceInfoFile(context: Context) {
        try {
            // 获取设备的 UUID
            val deviceUuid = PersistenceManager.getLocalDeviceUuid(context)
            if (deviceUuid.isNullOrEmpty()) {
                Logger.e(TAG, "设备 UUID 获取失败")
                return
            }

            // 创建文件路径
            val externalFilesDir = context.getExternalFilesDir(null)
            val deviceInfoFile = File(externalFilesDir, DEVICE_INFO_FILE)

            // 写入 UUID 到文件
            deviceInfoFile.writeText(deviceUuid)
            Logger.d(TAG, "设备信息文件已生成: ${deviceInfoFile.absolutePath}")
            Logger.d(TAG, "写入的 UUID: $deviceUuid")
        } catch (e: Exception) {
            Logger.e(TAG, "生成设备信息文件失败", e)
        }
    }
}