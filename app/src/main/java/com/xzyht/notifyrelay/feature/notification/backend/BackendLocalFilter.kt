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
    // 统一的过滤条目（文本 + 包名）持久化
    private const val KEY_FILTER_ENTRIES = "filter_entries"
    private const val KEY_ENABLED_FILTER_ENTRIES = "enabled_filter_entries"
    // 兼容旧存储 key（用于首次迁移）
    private const val KEY_FOREGROUND_KEYWORDS = "foreground_keywords"
    private const val KEY_ENABLED_FOREGROUND_KEYWORDS = "enabled_foreground_keywords"

    // 旧包名过滤存储 key（用于首次迁移）
    private const val KEY_PACKAGE_FILTER_LIST = "package_filter_list"
    private const val KEY_ENABLED_PACKAGE_FILTERS = "enabled_package_filters"

    // 内置过滤条目（包含文本关键词条目和默认包名条目，均为不可删除的默认黑名单）
    private val builtinFilterEntries: Set<FilterEntry> = setOf(
        FilterEntry("米家 设备状态", ""),
        FilterEntry("米家 手表", ""),
        FilterEntry("应用商店 正在安装", ""),
        FilterEntry("新消息 你有一条新消息", ""),
        FilterEntry("已隐藏敏感通知", ""),
        FilterEntry("查找 正在处理", ""),
        FilterEntry("电话 正在通话录音", ""),
        FilterEntry("正在使用妙享桌面", ""),
        FilterEntry("录音机 正在录音", ""),
        FilterEntry("录音机 录音已完成", ""),
        FilterEntry("小米汽车互联服务 小米汽车互联服务", ""),
        FilterEntry("您有一条新消息 请点击查看", ""),
        FilterEntry("正在运行", ""),
        FilterEntry("服务运行中", ""),
        FilterEntry("后台运行", ""),
        FilterEntry("点按即可了解详情", ""),
        FilterEntry("正在同步", ""),
        FilterEntry("运行中", ""),
        FilterEntry("service running", ""),
        FilterEntry("is running", ""),
        FilterEntry("tap for more info", ""),
        // 默认包名作为内置条目
        FilterEntry("", "com.miui.systemAdSolution")
    )

    // 序列化分隔符（很少出现在关键词/包名里）
    private const val ENTRY_DELIM = "\u001F"

    // （已整合为 builtinFilterEntries）

    // 获取自定义文本关键词集合（不含服务相关关键词）
    // FilterEntry 表示一条复合过滤规则：keyword 可空，packageName 可空
    data class FilterEntry(val keyword: String, val packageName: String)

    private fun serialize(entry: FilterEntry): String {
        val k = entry.keyword.replace(ENTRY_DELIM, "\\u001F")
        val p = entry.packageName.replace(ENTRY_DELIM, "\\u001F")
        return "$k$ENTRY_DELIM$p"
    }

    private fun deserialize(s: String): FilterEntry {
        val parts = s.split(ENTRY_DELIM, limit = 2)
        val k = parts.getOrNull(0)?.replace("\\u001F", ENTRY_DELIM) ?: ""
        val p = parts.getOrNull(1)?.replace("\\u001F", ENTRY_DELIM) ?: ""
        return FilterEntry(k, p)
    }

    // 获取所有过滤条目（包含内置关键词和默认包名过滤），会在首次读取时从旧存储迁移
    fun getFilterEntries(context: Context): Set<FilterEntry> {
        val saved = StorageManager.getStringSet(context, KEY_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER)
        if (saved.isNotEmpty()) {
            return saved.map { deserialize(it) }.toSet()
        }

        // 迁移旧数据：把旧的关键词和包名列表合并为统一条目
        val keywords = StorageManager.getStringSet(context, KEY_FOREGROUND_KEYWORDS, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        val packages = StorageManager.getStringSet(context, KEY_PACKAGE_FILTER_LIST, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
    // 包含内置关键词（从 builtinFilterEntries 中提取文本条目）
    val builtinKeywords = builtinFilterEntries.filter { it.keyword.isNotBlank() }.map { it.keyword }
    keywords.addAll(builtinKeywords)

        val entries = mutableSetOf<FilterEntry>()
        keywords.forEach { k -> entries.add(FilterEntry(k, "")) }
    // 默认包名过滤（从内置条目提取）
    builtinFilterEntries.filter { it.packageName.isNotBlank() }.forEach { pkgEntry -> entries.add(pkgEntry) }
        packages.forEach { pkg -> entries.add(FilterEntry("", pkg)) }

        // 保存迁移结果
        val ser = entries.map { serialize(it) }.toSet()
        StorageManager.putStringSet(context, KEY_FILTER_ENTRIES, ser, StorageManager.PrefsType.FILTER)
        return entries
    }

    // 获取内置默认关键词集合（兼容旧接口）
    fun getBuiltinKeywords(): Set<String> = builtinFilterEntries.filter { it.keyword.isNotBlank() }.map { it.keyword }.toSet()

    // 获取启用的过滤条目集合（如果无启用集则默认启用全部）
    fun getEnabledFilterEntries(context: Context): Set<FilterEntry> {
        val saved = StorageManager.getStringSet(context, KEY_ENABLED_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        val all = getFilterEntries(context)

        if (saved.isEmpty()) {
            // 首次：默认启用全部
            return all
        }

        // 有启用集合：解析并确保内置关键词条目被启用
        val enabled = saved.map { deserialize(it) }.toMutableSet()
        // 确保所有内置条目（包含内置关键词和默认包名）都默认启用
        builtinFilterEntries.forEach { entry ->
            if (!enabled.contains(entry) && all.contains(entry)) enabled.add(entry)
        }

        // 保存回已标准化的启用集合（和所有条目取交集以移除无效条目）
        val normalized = enabled.intersect(all).map { serialize(it) }.toSet()
        StorageManager.putStringSet(context, KEY_ENABLED_FILTER_ENTRIES, normalized, StorageManager.PrefsType.FILTER)
        return enabled.intersect(all)
    }

    fun setFilterEntryEnabled(context: Context, entry: FilterEntry, enabled: Boolean) {
        val all = getFilterEntries(context)
        var enabledSet = StorageManager.getStringSet(context, KEY_ENABLED_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        if (enabledSet.isEmpty()) enabledSet = all.map { serialize(it) }.toMutableSet()
        val ser = serialize(entry)
        if (enabled) enabledSet.add(ser) else enabledSet.remove(ser)
        StorageManager.putStringSet(context, KEY_ENABLED_FILTER_ENTRIES, enabledSet, StorageManager.PrefsType.FILTER)
    }

    fun addFilterEntry(context: Context, keyword: String, packageName: String) {
        val entries = StorageManager.getStringSet(context, KEY_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        val entry = FilterEntry(keyword.trim(), packageName.trim())
        entries.add(serialize(entry))
        StorageManager.putStringSet(context, KEY_FILTER_ENTRIES, entries, StorageManager.PrefsType.FILTER)
        // 新增默认启用
        var enabled = StorageManager.getStringSet(context, KEY_ENABLED_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        if (enabled.isEmpty()) enabled = entries.toMutableSet()
        enabled.add(serialize(entry))
        StorageManager.putStringSet(context, KEY_ENABLED_FILTER_ENTRIES, enabled, StorageManager.PrefsType.FILTER)
    }

    fun removeFilterEntry(context: Context, keyword: String, packageName: String) {
    // 内置条目不可删除（包括内置文本关键词和默认包名）
    if ((packageName.isBlank() && builtinFilterEntries.any { it.keyword == keyword && it.keyword.isNotBlank() }) || (keyword.isBlank() && builtinFilterEntries.any { it.packageName == packageName && it.packageName.isNotBlank() })) return
        val entries = StorageManager.getStringSet(context, KEY_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        val ser = serialize(FilterEntry(keyword, packageName))
        entries.remove(ser)
        StorageManager.putStringSet(context, KEY_FILTER_ENTRIES, entries, StorageManager.PrefsType.FILTER)
        // 同时移除启用状态
        val enabled = StorageManager.getStringSet(context, KEY_ENABLED_FILTER_ENTRIES, emptySet(), StorageManager.PrefsType.FILTER).toMutableSet()
        enabled.remove(ser)
        StorageManager.putStringSet(context, KEY_ENABLED_FILTER_ENTRIES, enabled, StorageManager.PrefsType.FILTER)
    }

    // 兼容的旧 API：返回仅 keyword（package 为空）的集合
    fun getForegroundKeywords(context: Context): Set<String> {
        return getFilterEntries(context).filter { it.packageName.isBlank() }.map { it.keyword }.toSet()
    }

    // 兼容的旧 API：返回仅 package 的集合（包含默认）
    fun getPackageFilterList(context: Context): Set<String> {
        val pkgs = getFilterEntries(context).filter { it.keyword.isBlank() && it.packageName.isNotBlank() }.map { it.packageName }.toMutableSet()
        // 添加内置默认包名
        pkgs.addAll(builtinFilterEntries.filter { it.packageName.isNotBlank() }.map { it.packageName })
        return pkgs
    }

    fun addPackageFilter(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        addFilterEntry(context, "", packageName)
    }

    fun removePackageFilter(context: Context, packageName: String) {
        // 默认包名不可删除
        if (builtinFilterEntries.any { it.packageName == packageName && it.packageName.isNotBlank() }) return
        removeFilterEntry(context, "", packageName)
    }

    fun getEnabledPackageFilters(context: Context): Set<String> {
        return getEnabledFilterEntries(context).filter { it.keyword.isBlank() && it.packageName.isNotBlank() }.map { it.packageName }.toSet()
    }

    // 获取默认包名过滤集合（从内置条目提取）
    fun getDefaultPackageFilters(): Set<String> = builtinFilterEntries.filter { it.packageName.isNotBlank() }.map { it.packageName }.toSet()

    fun setPackageEnabled(context: Context, packageName: String, enabled: Boolean) {
        setFilterEntryEnabled(context, FilterEntry("", packageName), enabled)
    }

    /**
     * 判断本机通知是否应该被转发
     * @param isFromPeriodicCheck 是否来自定时检查，避免调试日志刷屏
     */
    fun shouldForward(sbn: StatusBarNotification, context: Context, isFromPeriodicCheck: Boolean = false): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false

        // 包名过滤
        val enabledPackageFilters = getEnabledPackageFilters(context)
        if (enabledPackageFilters.contains(sbn.packageName)) return false

        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        // 日志辅助排查过滤内容 - 只在非定时检查时输出，避免刷屏
        if (BuildConfig.DEBUG && !isFromPeriodicCheck) {
            Log.v("NotifyRelay-Filter", "shouldForward: title='$title', text='$text'")
        }

        // 过滤媒体通知（不存储，避免蓝牙歌词等标题频繁变化）
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return false

        // 持久化/前台服务过滤，包含服务相关关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            if (isOngoing) return false
        }

        // 使用统一的启用过滤条目进行匹配：
        // - entry.keyword 非空 且 packageName 为空 -> 只匹配文本（任意应用）
        // - entry.keyword 为空 且 packageName 非空 -> 只匹配包名
        // - 两者均非空 -> 同时匹配才命中
        val enabledEntries = getEnabledFilterEntries(context)
        for (entry in enabledEntries) {
            val kw = entry.keyword.trim()
            val pkg = entry.packageName.trim()

            val kwMatches = kw.isNotEmpty() && (title.contains(kw, true) || text.contains(kw, true))
            val pkgMatches = pkg.isNotEmpty() && sbn.packageName == pkg

            // 仅关键字
            if (kw.isNotEmpty() && pkg.isEmpty() && kwMatches) return false
            // 仅包名
            if (kw.isEmpty() && pkg.isNotEmpty() && pkgMatches) return false
            // 同时存在 -> 要同时匹配
            if (kw.isNotEmpty() && pkg.isNotEmpty() && kwMatches && pkgMatches) return false
        }

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
