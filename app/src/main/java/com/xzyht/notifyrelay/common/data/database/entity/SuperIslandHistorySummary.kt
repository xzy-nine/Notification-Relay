package com.xzyht.notifyrelay.common.data.database.entity

/**
 * 超级岛历史记录的摘要（不包含大文本/二进制字段），用于在列表/摘要视图中显示
 */
data class SuperIslandHistorySummary(
    val id: Long,
    val sourceDeviceUuid: String? = null,
    val originalPackage: String? = null,
    val mappedPackage: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val paramV2Raw: String? = null,
    val picMap: String = "{}"
)
