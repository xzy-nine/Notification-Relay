package com.xzyht.notifyrelay.feature.device.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionUIHandler
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.HandshakeRequest

class DeviceUIHandler(private val context: Context) : DeviceConnectionUIHandler {

    // 握手请求状态
    val pendingHandshakeRequest: MutableState<HandshakeRequest?> = mutableStateOf(null)
    val showHandshakeDialog: MutableState<Boolean> = mutableStateOf(false)

    // 通知数据接收状态
    val receivedNotificationData: MutableState<String?> = mutableStateOf(null)

    // 设备列表更新状态
    val deviceListUpdated: MutableState<Boolean> = mutableStateOf(false)

    override fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun onHandshakeRequest(device: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit) {
        pendingHandshakeRequest.value = HandshakeRequest(device, publicKey, callback)
        showHandshakeDialog.value = true
    }

    override fun onNotificationDataReceived(data: String) {
        receivedNotificationData.value = data
        // 这里可以触发UI更新，比如显示新通知
    }

    override fun onDeviceListUpdated() {
        deviceListUpdated.value = !deviceListUpdated.value
        // 这里可以触发设备列表刷新
    }
}
