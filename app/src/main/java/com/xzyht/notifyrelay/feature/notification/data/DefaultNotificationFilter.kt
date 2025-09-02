package com.xzyht.notifyrelay.feature.notification.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository

object DefaultNotificationFilter {
    // 内置文本黑名单关键词（不可删除，支持标题+内容联合匹配）
    private val builtinCustomKeywords = setOf("米家 设备状态")
    private const val KEY_ENABLED_FOREGROUND_KEYWORDS = "enabled_foreground_keywords"

    // 可配置项
    var filterSelf: Boolean = true // 过滤本应用
    var filterOngoing: Boolean = true // 过滤持久化
    var filterNoTitleOrText: Boolean = true // 过滤空标题内容
    var filterImportanceNone: Boolean = true // 过滤IMPORTANCE_NONE
    var filterMiPushGroupSummary: Boolean = true // 过滤mipush群组引导消息
    var filterSensitiveHidden: Boolean = true // 过滤敏感内容被隐藏的通知
    // 关键词持久化相关
    private const val PREFS_NAME = "notifyrelay_filter_prefs"
    private const val KEY_FOREGROUND_KEYWORDS = "foreground_keywords"

    // 服务相关关键词，仅用于持久通知过滤
    private val serviceKeywords = setOf(
        "正在运行", "服务运行中", "后台运行", "点按即可了解详情", "正在同步", "运行中", "service running", "is running", "tap for more info"
    )

    // 获取自定义文本关键词集合（不含服务相关关键词）
    fun getForegroundKeywords(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)
        // 自动补全内置关键词（不可删除）
        return if (saved != null) builtinCustomKeywords + saved else builtinCustomKeywords
    }

    fun getEnabledForegroundKeywords(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)
        val all = getForegroundKeywords(context)
        // 首次无任何启用集时，默认启用全部（含内置）；否则严格以持久化内容为准（内置项可被禁用）
        return if (enabled == null) all else enabled.intersect(all)
    }

    fun setKeywordEnabled(context: Context, keyword: String, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = getForegroundKeywords(context)
        var enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: all.toMutableSet()
        if (enabled) enabledSet.add(keyword) else enabledSet.remove(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun addForegroundKeyword(context: Context, keyword: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        set.add(keyword)
        prefs.edit().putStringSet(KEY_FOREGROUND_KEYWORDS, set).apply()
        // 新增关键词默认启用
        val enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: set.toMutableSet()
        enabledSet.add(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun removeForegroundKeyword(context: Context, keyword: String) {
        // 内置关键词不可删除
        if (builtinCustomKeywords.contains(keyword)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        set.remove(keyword)
        prefs.edit().putStringSet(KEY_FOREGROUND_KEYWORDS, set).apply()
        // 同时移除启用状态
        val enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        enabledSet.remove(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun shouldForward(sbn: StatusBarNotification, context: Context): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false
        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        // 日志辅助排查过滤内容
        android.util.Log.v("NotifyRelay-Filter", "shouldForward: title='$title', text='$text'")
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
        // 文本关键词黑名单增强：支持“标题关键词 内容关键词”格式，前面匹配标题，后面匹配内容
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
