package com.xzyht.notifyrelay.feature.notification.superisland

import android.os.Bundle

data class SuperIslandData(
    val sourcePackage: String?,
    val appName: String?,
    val title: String?,
    val text: String?,
    val rawExtras: Map<String, Any?>,
    val paramV2Raw: String? = null,
    val picMap: Map<String, String>? = null,
    val pics: Bundle? = null
)
