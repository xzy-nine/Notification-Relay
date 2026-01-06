package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model

data class MediaSessionData(
    val packageName: String,
    val appName: String?,
    val title: String,
    val text: String,
    val coverUrl: String?,
    val appIconUrl: String? = null,
    val deviceName: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean = title.isNotBlank() || text.isNotBlank()
}
