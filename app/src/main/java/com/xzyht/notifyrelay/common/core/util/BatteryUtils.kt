package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import android.os.BatteryManager

/**
 * 电池工具类，提供统一的电池信息获取方法
 */
object BatteryUtils {

    /**
     * 获取设备电量百分比
     * @param context 上下文
     * @return 电量百分比，范围 0-100
     */
    fun getBatteryLevel(context: Context): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // 确保电量值在0-100之间
            batteryLevel.coerceIn(0, 100)
        } catch (e: Exception) {
            // 获取电量失败时返回默认值100
            100
        }
    }
}