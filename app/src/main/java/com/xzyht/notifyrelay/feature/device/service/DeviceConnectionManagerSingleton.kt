package com.xzyht.notifyrelay.feature.device.service

import android.content.Context

/**
 * DeviceConnectionManager单例管理类
 * 用于管理DeviceConnectionManager实例，避免服务层依赖UI层
 */
object DeviceConnectionManagerSingleton {
    @Volatile
    private var sharedDeviceManager: DeviceConnectionManager? = null

    /**
     * 获取DeviceConnectionManager实例
     */
    fun getDeviceManager(context: Context): DeviceConnectionManager {
        return sharedDeviceManager ?: synchronized(this) {
            sharedDeviceManager ?: DeviceConnectionManager(context.applicationContext).also { sharedDeviceManager = it }
        }
    }

    /**
     * 清除DeviceConnectionManager实例（仅在测试时使用）
     */
    fun clearDeviceManager() {
        sharedDeviceManager = null
    }
}