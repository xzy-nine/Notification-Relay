package com.xzyht.notifyrelay.feature.notification.backend

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository

/**
 * 后端本机通知过滤器
 * 处理本机产生的通知的过滤逻辑
 */
object BackendLocalFilter {
    // 可配置项
    var filterSelf: Boolean = true // 过滤本应用
    var filterOngoing: Boolean = true // 过滤持久化
    var filterNoTitleOrText: Boolean = true // 过滤空标题内容
    var filterImportanceNone: Boolean = true // 过滤IMPORTANCE_NONE
    var filterMiPushGroupSummary: Boolean = true // 过滤mipush群组引导消息
    var filterSensitiveHidden: Boolean = true // 过滤敏感内容被隐藏的通知

    // 关键词持久化相关
    private const val KEY_FOREGROUND_KEYWORDS = "foreground_keywords"
    private const val KEY_ENABLED_FOREGROUND_KEYWORDS = "enabled_foreground_keywords"

    // 内置文本黑名单关键词（不可删除，支持标题+内容联合匹配）
    private val builtinCustomKeywords = setOf("米家 设备状态")

    // 服务相关关键词，仅用于持久通知过滤
    private val serviceKeywords = setOf(
        "正在运行", "服务运行中", "后台运行", "点按即可了解详情", "正在同步", "运行中", "service running", "is running", "tap for more info"
    )

    // 获取自定义文本关键词集合（不含服务相关关键词）
    fun getForegroundKeywords(context: Context): Set<String> {
        val saved = StorageManager.getStringSet(context, KEY_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER)
        // 自动补全内置关键词（不可删除）
        return builtinCustomKeywords + saved
    }

    fun getEnabledForegroundKeywords(context: Context): Set<String> {
        val enabled = StorageManager.getStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER)
        val all = getForegroundKeywords(context)
        // 首次无任何启用集时，默认启用全部（含内置）；否则严格以持久化内容为准（内置项可被禁用）
        return if (enabled.isEmpty()) all else enabled.intersect(all)
    }

    fun setKeywordEnabled(context: Context, keyword: String, enabled: Boolean) {
        val all = getForegroundKeywords(context)
        var enabledSet = StorageManager.getStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        if (enabledSet.isEmpty()) enabledSet = all.toMutableSet()
        if (enabled) enabledSet.add(keyword) else enabledSet.remove(keyword)
        StorageManager.putStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet, StorageManager.PrefsType.FILTER)
    }

    fun addForegroundKeyword(context: Context, keyword: String) {
        val set = StorageManager.getStringSet(context, KEY_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        set.add(keyword)
        StorageManager.putStringSet(context, KEY_FOREGROUND_KEYWORDS, set, StorageManager.PrefsType.FILTER)
        // 新增关键词默认启用
        var enabledSet = StorageManager.getStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        if (enabledSet.isEmpty()) enabledSet = set
        enabledSet.add(keyword)
        StorageManager.putStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet, StorageManager.PrefsType.FILTER)
    }

    fun removeForegroundKeyword(context: Context, keyword: String) {
        // 内置关键词不可删除
        if (builtinCustomKeywords.contains(keyword)) return
        val set = StorageManager.getStringSet(context, KEY_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        set.remove(keyword)
        StorageManager.putStringSet(context, KEY_FOREGROUND_KEYWORDS, set, StorageManager.PrefsType.FILTER)
        // 同时移除启用状态
        val enabledSet = StorageManager.getStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        enabledSet.remove(keyword)
        StorageManager.putStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet, StorageManager.PrefsType.FILTER)
    }

    /**
     * 判断本机通知是否应该被转发
     */
    fun shouldForward(sbn: StatusBarNotification, context: Context): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false
        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        // 日志辅助排查过滤内容
        android.util.Log.v("NotifyRelay-Filter", "shouldForward: title='$title', text='$text'")

        // 过滤媒体通知（不存储，避免蓝牙歌词等标题频繁变化）
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return false

        // 过滤mipush群组引导消息（title=新消息 且 text=你有一条新消息）
        if (filterMiPushGroupSummary && title == "新消息" && text == "你有一条新消息") return false

        // 过滤敏感内容被隐藏的通知（text包含已隐藏敏感通知等，放宽匹配）
        if (filterSensitiveHidden && text.trim().contains("已隐藏敏感通知")) return false

        // 持久化/前台服务过滤，包含服务相关关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            val hasServiceKeyword = serviceKeywords.any { k -> title.contains(k, true) || text.contains(k, true) }
            if (isOngoing || hasServiceKeyword) return false
        }

        // 文本关键词黑名单增强：支持"标题关键词 内容关键词"格式，前面匹配标题，后面匹配内容
        val enabledKeywords = getEnabledForegroundKeywords(context)
        for (k in enabledKeywords) {
            val parts = k.split(" ", limit = 2)
            if (parts.size == 2) {
                val t = parts[0].trim()
                val c = parts[1].trim()
                if (t.isNotEmpty() && c.isNotEmpty() && title.contains(t, true) && text.contains(c, true)) {
                    return false
                }
            }
        }

        // 兼容单关键词过滤（只要标题或内容包含即过滤）
        if (enabledKeywords.any { k -> title.contains(k, true) || text.contains(k, true) }) return false

        if (filterImportanceNone) {
            val channelId = sbn.notification.channelId
            if (channelId != null) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = nm.getNotificationChannel(channelId)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
        }

        if (filterNoTitleOrText) {
            if (title.isBlank() || text.isBlank()) return false
        }

        return true
    }
}
