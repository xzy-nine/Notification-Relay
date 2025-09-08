package com.xzyht.notifyrelay.feature.device.service

interface DeviceConnectionUIHandler {
    fun showToast(message: String)
    fun onHandshakeRequest(device: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit)
    fun onNotificationDataReceived(data: String)
    fun onDeviceListUpdated()
}
