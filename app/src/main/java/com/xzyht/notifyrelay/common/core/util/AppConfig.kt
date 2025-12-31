package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import com.xzyht.notifyrelay.common.data.StorageManager

/**
 * 应用配置管理类
 * 负责管理应用的各种配置，包括UI相关配置
 */
object AppConfig {
    /**
     * 是否启用UDP发现
     */
    fun getUdpDiscoveryEnabled(context: Context): Boolean {
        return StorageManager.getBoolean(context, "udp_discovery_enabled", true)
    }

    /**
     * 设置是否启用UDP发现
     */
    fun setUdpDiscoveryEnabled(context: Context, enabled: Boolean) {
        StorageManager.putBoolean(context, "udp_discovery_enabled", enabled)
    }
}