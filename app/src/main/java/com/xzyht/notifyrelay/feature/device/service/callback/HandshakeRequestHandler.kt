package com.xzyht.notifyrelay.feature.device.service.callback

import com.xzyht.notifyrelay.feature.device.model.DeviceInfo

/**
 * 配对请求处理接口。
 *
 * 当接收到 `PAIRING_INIT` 时通过此接口通知 UI 层显示配对码输入弹窗。
 * 回调由 [PairingCallbackHandler] 在主线程（`Looper.getMainLooper()`）上派发。
 */
interface HandshakeRequestHandler {
    fun onPairingInitRequest(
        deviceInfo: DeviceInfo,
        tmpPublicKey: String,
    )
}
