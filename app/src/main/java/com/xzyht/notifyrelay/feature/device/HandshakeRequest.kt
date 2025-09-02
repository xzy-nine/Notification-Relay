package com.xzyht.notifyrelay.feature.device

import com.xzyht.notifyrelay.feature.device.DeviceInfo

/**
 * 握手请求数据结构
 */
data class HandshakeRequest(
    val device: DeviceInfo,
    val publicKey: String?,
    val callback: (Boolean) -> Unit
)
