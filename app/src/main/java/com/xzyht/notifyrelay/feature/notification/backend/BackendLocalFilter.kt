package com.xzyht.notifyrelay.feature.notification.backend

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
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

    // 关键词持久化相关
    private const val KEY_FOREGROUND_KEYWORDS = "foreground_keywords"
    private const val KEY_ENABLED_FOREGROUND_KEYWORDS = "enabled_foreground_keywords"

    // 内置文本黑名单关键词（不可删除，支持标题+内容联合匹配）
    private val builtinCustomKeywords = setOf(
        "米家 设备状态",
        "米家 手表",
        "应用商店 正在安装",
        "新消息 你有一条新消息",
        "已隐藏敏感通知",
        "查找 正在处理",
        "小米汽车互联服务 小米汽车互联服务",
        "您有一条新消息 请点击查看",
        "正在运行", "服务运行中", "后台运行", "点按即可了解详情", "正在同步", "运行中", "service running", "is running", "tap for more info"
    )

    // 获取自定义文本关键词集合（不含服务相关关键词）
    fun getForegroundKeywords(context: Context): Set<String> {
        val saved = StorageManager.getStringSet(context, KEY_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER)
        // 自动补全内置关键词（不可删除）
        return builtinCustomKeywords + saved
    }

    // 获取内置默认关键词集合
    fun getBuiltinKeywords(): Set<String> = builtinCustomKeywords

    fun getEnabledForegroundKeywords(context: Context): Set<String> {
        val enabled = StorageManager.getStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        val all = getForegroundKeywords(context)
        val builtinKeywords = getBuiltinKeywords()

        // 首次无任何启用集时，默认启用全部（含内置）；否则确保新添加的内置关键词默认启用
        if (enabled.isEmpty()) {
            return all
        } else {
            // 确保所有内置关键词都默认启用
            builtinKeywords.forEach { keyword ->
                if (!enabled.contains(keyword)) {
                    enabled.add(keyword)
                }
            }
            // 保存更新后的启用集
            StorageManager.putStringSet(context, KEY_ENABLED_FOREGROUND_KEYWORDS, enabled, StorageManager.PrefsType.FILTER)
            return enabled.intersect(all)
        }
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
        if (BuildConfig.DEBUG) Log.v("NotifyRelay-Filter", "shouldForward: title='$title', text='$text'")

        // 过滤媒体通知（不存储，避免蓝牙歌词等标题频繁变化）
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return false

        // 持久化/前台服务过滤，包含服务相关关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            if (isOngoing) return false
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
