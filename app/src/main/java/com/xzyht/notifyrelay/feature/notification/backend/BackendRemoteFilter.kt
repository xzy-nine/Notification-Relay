package com.xzyht.notifyrelay.feature.notification.backend

import android.content.Context
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecord
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后端接收通知过滤器
 * 处理从远程设备接收的通知的过滤逻辑
 */
object BackendRemoteFilter {

    // 延迟去重缓存（10秒内）- 用于智能去重机制
    private val dedupCache = mutableListOf<Triple<String, String, Long>>() // title, text, time

    // 待监控的通知撤回队列 - 先发送后撤回机制的核心
    private val pendingNotifications = mutableListOf<PendingNotification>()

    data class PendingNotification(
        val notifyId: Int,
        val title: String,
        val text: String,
        val packageName: String,
        val sendTime: Long,
        val context: Context
    )

    /**
     * 远程通知过滤结果
     */
    data class FilterResult(
        val shouldShow: Boolean,
        val mappedPkg: String,
        val title: String,
        val text: String,
        val rawData: String,
        val needsDelay: Boolean = false // 是否需要延迟验证（先发送后监控）
    )

    /**
     * 过滤远程通知
     * 包含包名映射、智能去重（先发送后撤回机制）、黑白名单/对等模式
     */
    fun filterRemoteNotification(data: String, context: Context): FilterResult {
        // 确保配置已加载（只加载一次）
        synchronized(RemoteFilterConfig) {
            if (!RemoteFilterConfig.isLoaded) {
                try {
                    RemoteFilterConfig.load(context)
                    RemoteFilterConfig.isLoaded = true
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "远程过滤配置加载成功")
                } catch (e: Exception) {
                    Log.e("NotifyRelay(狂鼠)", "远程过滤配置加载失败", e)
                    return FilterResult(true, "", "", "", data) // 默认通过
                }
            }
        }
        try {
            val json = org.json.JSONObject(data)
            var pkg = json.optString("packageName")
            val title = json.optString("title")
            val text = json.optString("text")
            val time = System.currentTimeMillis()
            val isLocked = json.optBoolean("isLocked", false)

            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)智能去重", "收到远程通知 - 时间:$time, 包名:$pkg, 标题:$title, 内容:$text, 锁屏:$isLocked")

            val installedPkgs = AppRepository.getInstalledPackageNamesSync(context)
            val mappedPkg = RemoteFilterConfig.mapToLocalPackage(pkg, installedPkgs)

            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 开始过滤 pkg=$pkg, mappedPkg=$mappedPkg, title=$title, text=$text")

            // 对等模式过滤
            if (RemoteFilterConfig.filterMode == "peer" || RemoteFilterConfig.enablePeerMode) {
                if (mappedPkg !in installedPkgs) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 对等模式过滤 - mappedPkg=$mappedPkg 不在本机已安装应用")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 对等模式通过 - mappedPkg=$mappedPkg 已安装")
            }

            // 黑白名单过滤
            if (RemoteFilterConfig.filterMode == "black" || RemoteFilterConfig.filterMode == "white") {
                val match = RemoteFilterConfig.filterList.any { (filterPkg, keyword) ->
                    val pkgMatch = (mappedPkg == filterPkg || pkg == filterPkg)
                    val keywordMatch = keyword.isNullOrBlank() || title.contains(keyword) || text.contains(keyword)
                    val totalMatch = pkgMatch && keywordMatch
                    if (totalMatch) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 名单匹配 - filterPkg=$filterPkg, keyword=$keyword, pkgMatch=$pkgMatch, keywordMatch=$keywordMatch")
                    }
                    totalMatch
                }
                if (RemoteFilterConfig.filterMode == "black" && match) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 命中黑名单 - filtered=$match mappedPkg=$mappedPkg title=$title text=$text")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
                if (RemoteFilterConfig.filterMode == "white" && !match) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 未命中白名单 - mappedPkg=$mappedPkg title=$title text=$text")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 名单过滤通过 - mode=${RemoteFilterConfig.filterMode}, match=$match")
            }

            // 锁屏通知过滤
            if (RemoteFilterConfig.enableLockScreenOnly && !isLocked) {
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 锁屏过滤 - 非锁屏通知被过滤")
                return FilterResult(false, mappedPkg, title, text, data)
            }

            // 智能去重检查 - 优化性能和逻辑
            if (RemoteFilterConfig.enableDeduplication) {
                val now = System.currentTimeMillis()

                // 1. 快速缓存检查（10秒内）
                synchronized(dedupCache) {
                    dedupCache.removeAll { now - it.third > 10_000 } // 清理过期缓存
                    val cacheDup = dedupCache.any { it.first == title && it.second == text }
                    if (BuildConfig.DEBUG) Log.d("智能去重", "缓存检查 - 缓存大小:${dedupCache.size}, 是否重复:$cacheDup")
                    if (cacheDup) {
                        // 撤回匹配的待监控通知
                        synchronized(pendingNotifications) {
                            val toCancel = pendingNotifications.filter { it.title == title && it.text == text }
                            toCancel.forEach { cancelNotification(it.notifyId, context) }
                            pendingNotifications.removeAll(toCancel)
                        }
                        if (BuildConfig.DEBUG) Log.d("智能去重", "命中10秒缓存并撤回之前的通知 - 包名:$pkg, 标题:$title, 内容:$text")
                        return FilterResult(false, mappedPkg, title, text, data)
                    }
                }

                // 2. 历史重复检查优化
                try {
                    // 预先检查历史同步可靠性
                    val isHistoryReliable = checkHistorySyncReliability(context)
                    if (!isHistoryReliable) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "历史同步不可靠，强制刷新")
                        // Note: This line references a non-existent method, commenting out
                        // com.xzyht.notifyrelay.feature.device.model.NotificationRepository.notifyHistoryChanged("本机", context)
                    }

                    // 获取内存历史数据
                    // Note: This references non-existent classes, need to handle appropriately
                    val localList = com.xzyht.notifyrelay.feature.device.model.NotificationRepository.getNotificationsByDevice("本机")
                    val memoryDup = checkDuplicateInMemory(localList, title, text, now)

                    // 如果内存中有重复，直接过滤
                    if (memoryDup) {
                        if (BuildConfig.DEBUG) Log.d("智能去重", "命中内存历史重复")
                        return FilterResult(false, mappedPkg, title, text, data)
                    }

                    // 内存无重复，默认情况下标记为需要延迟验证（先发送后监控机制）。
                    // 但如果该远端通知标记为锁屏（isLocked），则避免先发送再撤回，改为不立即展示，
                    // 由上层在超期后再次检查并决定是否复刻（见 DeviceConnectionManager 的处理）。
                    if (isLocked) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "锁屏场景：内存无重复，改为不立即展示，等待超期后再复刻")
                        return FilterResult(false, mappedPkg, title, text, data, needsDelay = false)
                    }

                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "无历史重复，标记延迟验证")
                    return FilterResult(true, mappedPkg, title, text, data, needsDelay = true)

                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("智能去重", "历史检查异常", e)
                    // 异常情况下默认延迟验证
                    return FilterResult(true, mappedPkg, title, text, data, needsDelay = true)
                }
            }

            // 锁屏通知过滤
            if (RemoteFilterConfig.enableLockScreenOnly && !isLocked) {
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 锁屏过滤 - 非锁屏通知被过滤")
                return FilterResult(false, mappedPkg, title, text, data)
            }

            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 直接通过 - mappedPkg=$mappedPkg title=$title text=$text")
            return FilterResult(true, mappedPkg, title, text, data)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "filterRemoteNotification: 解析异常 - data=$data", e)
            return FilterResult(true, "", "", "", data)
        }
    }

    /**
     * 检查内存中的重复通知（优化性能）
     */
    private fun checkDuplicateInMemory(localList: List<NotificationRecord>, title: String, text: String, now: Long): Boolean {
        val timeWindowNotifications = mutableListOf<NotificationRecord>()
        var hasDuplicate = false

        for (notification in localList) {
            try {
                // 去除标题中的应用名称前缀再比较
                val normalizedLocalTitle = normalizeTitle(notification.title ?: "")
                val normalizedPendingTitle = normalizeTitle(title)
                val match = notification.device == "本机" && normalizedLocalTitle == normalizedPendingTitle && (notification.text ?: "") == text
                // 只要内容匹配即可，不检查时间
                val finalMatch = match
                if (finalMatch) {
                    hasDuplicate = true
                    if (BuildConfig.DEBUG) {
                        Log.d("智能去重", "命中内存历史重复 - 标题:$title, 内容:$text, 时间差:${Math.abs(notification.time - now)}ms")
                    }
                }
                // 收集时间区间内的所有通知（用于调试）
                if (Math.abs(notification.time - now) <= 5000) {
                    timeWindowNotifications.add(notification)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("智能去重", "内存检查异常", e)
            }
        }

        if (BuildConfig.DEBUG) Log.d("智能去重", "内存检查完成 - 历史数量:${localList.size}, 是否重复:$hasDuplicate")

        // 打印时间区间内的可能通知
        if (BuildConfig.DEBUG && timeWindowNotifications.isNotEmpty()) {
            Log.d("智能去重", "时间区间内可能通知 (${timeWindowNotifications.size}个):")
            timeWindowNotifications.forEach { notification ->
                val timeDiff = Math.abs(notification.time - now)
                Log.d("智能去重", "  - 标题:${notification.title}, 内容:${notification.text}, 时间差:${timeDiff}ms, 设备:${notification.device}")
            }
        }

        return hasDuplicate
    }

    /**
     * 标准化标题：去除应用名称前缀，如"(微博)" -> ""
     */
    private fun normalizeTitle(title: String): String {
        val prefixPattern = Regex("^\\([^)]+\\)")
        return title.replace(prefixPattern, "").trim()
    }

    /**
     * 添加到去重缓存
     */
    fun addToDedupCache(title: String, text: String) {
        synchronized(dedupCache) {
            dedupCache.add(Triple(title, text, System.currentTimeMillis()))
        }
    }

    /**
     * 检查是否在去重缓存中（10秒内）- 智能去重机制的一部分
     */
    fun isInDedupCache(title: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(dedupCache) {
            dedupCache.removeAll { now - it.third > 10_000 } // 10秒缓存
            return dedupCache.any { it.first == title && it.second == text }
        }
    }

    /**
     * 添加待监控的通知
     */
    fun addPendingNotification(notifyId: Int, title: String, text: String, packageName: String, context: Context) {
        // 只有在去重开关开启时才添加监控
        if (!RemoteFilterConfig.enableDeduplication) {
            return
        }

        synchronized(pendingNotifications) {
            pendingNotifications.add(PendingNotification(
                notifyId = notifyId,
                title = title,
                text = text,
                packageName = packageName,
                sendTime = System.currentTimeMillis(),
                context = context
            ))
        }
        if (BuildConfig.DEBUG) Log.d("智能去重", "添加待监控通知 - 包名:$packageName, 标题:$title, 内容:$text, 通知ID:$notifyId")
        // 启动监控协程
        startNotificationMonitoring()
    }

    /**
     * 撤回通知
     */
    private fun cancelNotification(notifyId: Int, context: Context) {
        try {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(notifyId)
            if (BuildConfig.DEBUG) Log.d("智能去重", "已撤回通知 - 通知ID:$notifyId")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("智能去重", "撤回通知失败 - 通知ID:$notifyId, 错误:${e.message}")
        }
    }

    /**
     * 启动通知监控协程
     */
    private fun startNotificationMonitoring() {
        if (BuildConfig.DEBUG) Log.d("智能去重", "启动通知监控协程 - 当前待监控通知数量:${pendingNotifications.size}")
        GlobalScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PendingNotification>()

                synchronized(pendingNotifications) {
                    for (pending in pendingNotifications) {
                        // 检查是否超过监控时间（15秒）
                        if (now - pending.sendTime > 15_000) {
                            if (BuildConfig.DEBUG) Log.d("智能去重", "监控超时移除 - 包名:${pending.packageName}, 标题:${pending.title}, 通知ID:${pending.notifyId}, 监控时长:${now - pending.sendTime}ms")
                            toRemove.add(pending)
                            continue
                        }

                        // 检查是否有重复的本机通知
                        val localList = com.xzyht.notifyrelay.feature.device.model.NotificationRepository.getNotificationsByDevice("本机")
                        val duplicateFound = checkDuplicateInMemory(localList, pending.title, pending.text, pending.sendTime)

                        if (duplicateFound) {
                            if (BuildConfig.DEBUG) Log.d("智能去重", "命中并撤回通知 - 包名:${pending.packageName}, 标题:${pending.title}, 内容:${pending.text}, 通知ID:${pending.notifyId}, 发送后${now - pending.sendTime}ms发现重复")
                            cancelNotification(pending.notifyId, pending.context)
                            toRemove.add(pending)
                        }
                    }

                    // 移除已处理的待监控通知
                    pendingNotifications.removeAll(toRemove)

                    // 为超时移除的通知添加去重缓存
                    toRemove.filter { now - it.sendTime > 15_000 }.forEach { timedOut ->
                        addToDedupCache(timedOut.title, timedOut.text)
                        if (BuildConfig.DEBUG) Log.d("智能去重", "超时通知添加到缓存 - 标题:${timedOut.title}, 内容:${timedOut.text}")
                    }
                }

                // 如果没有待监控的通知，退出监控
                if (pendingNotifications.isEmpty()) {
                    if (BuildConfig.DEBUG) Log.d("智能去重", "监控协程结束 - 所有通知已处理完成")
                    break
                }

                // 每秒检查一次
                delay(1_000)
            }
        }
    }

    /**
     * 检查历史同步的可靠性
     */
    private fun checkHistorySyncReliability(context: Context): Boolean {
        try {
            // Placeholder implementation
            return true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "历史同步检查异常", e)
            return false
        }
    }
}

/**
 * 远程过滤配置
 * 包含包名映射、智能去重（先发送后撤回机制）、黑白名单/对等模式配置
 */
object RemoteFilterConfig {
    private const val KEY_PACKAGE_GROUPS = "package_groups"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_FILTER_LIST = "filter_list"
    private const val KEY_ENABLE_DEDUP = "enable_dedup"
    private const val KEY_ENABLE_PEER = "enable_peer"

    // 标记配置是否已加载，避免重复加载
    var isLoaded: Boolean = false

    // 包名等价功能总开关
    var enablePackageGroupMapping: Boolean = true
    val defaultPackageGroups: List<List<String>> = listOf(
        listOf("tv.danmaku.bilibilihd", "tv.danmaku.bili"),
        listOf("com.sina.weibo", "com.sina.weibog3", "com.weico.international", "com.sina.weibolite", "com.hengye.share", "com.caij.see"),
        listOf("com.tencent.mobileqq", "com.tencent.tim")
    )
    var defaultGroupEnabled: MutableList<Boolean> = mutableListOf(true, true, true)

    // 用户自定义包名等价组，每组为包名列表
    var customPackageGroups: MutableList<MutableList<String>> = mutableListOf()

    // 每个自定义组的开关
    var customGroupEnabled: MutableList<Boolean> = mutableListOf()

    // 合并后的包名等价组
    val packageGroups: List<Set<String>>
        get() = if (!enablePackageGroupMapping) emptyList()
        else defaultPackageGroups.withIndex().filter { defaultGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() } +
                customPackageGroups.withIndex().filter { customGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() }

    // 智能去重开关（先发送后撤回机制）
    var enableDeduplication: Boolean = true

    // 黑白名单模式："none"=无，"black"=黑名单，"white"=白名单，"peer"=对等
    var filterMode: String = "none"

    // 黑/白名单内容（包名或通用包名+可选文本关键词）
    var filterList: List<Pair<String, String?>> = emptyList() // Pair<包名, 关键词?>

    // 对等模式开关（仅本机存在的应用或通用应用）
    var enablePeerMode: Boolean = false
    // 锁屏通知过滤开关
    var enableLockScreenOnly: Boolean = false

    // 加载设置
    fun load(context: Context) {
        enablePackageGroupMapping = StorageManager.getBoolean(context, "enable_package_group_mapping", true, StorageManager.PrefsType.FILTER)
        val defaultEnabledStr = StorageManager.getString(context, "default_group_enabled", "1,1,1", StorageManager.PrefsType.FILTER)
        val defaultEnabled = defaultEnabledStr.split(",").map { it == "1" }
        defaultGroupEnabled = defaultEnabled.toMutableList().apply {
            while (size < defaultPackageGroups.size) add(true)
        }
        val customGroupsStr = StorageManager.getStringSet(context, KEY_PACKAGE_GROUPS, emptySet(), StorageManager.PrefsType.FILTER)
        val customGroups = customGroupsStr.mapNotNull {
            it.split("|").map { s->s.trim() }.filter { s->s.isNotBlank() }.toMutableList().takeIf { set->set.isNotEmpty() }
        }.toMutableList()
        customPackageGroups = customGroups
        val customEnabledStr = StorageManager.getString(context, "custom_group_enabled", "", StorageManager.PrefsType.FILTER)
        customGroupEnabled = if (customEnabledStr.isNotEmpty()) {
            customEnabledStr.split(",").map { it == "1" }.toMutableList().apply {
                while (size < customGroups.size) add(true)
            }
        } else MutableList(customGroups.size) { true }
        filterMode = StorageManager.getString(context, KEY_FILTER_MODE, "none", StorageManager.PrefsType.FILTER)
        enableDeduplication = StorageManager.getBoolean(context, KEY_ENABLE_DEDUP, true, StorageManager.PrefsType.FILTER)
        enablePeerMode = StorageManager.getBoolean(context, KEY_ENABLE_PEER, false, StorageManager.PrefsType.FILTER)
        enableLockScreenOnly = StorageManager.getBoolean(context, "enable_lock_screen_only", false, StorageManager.PrefsType.FILTER)
        val filterListStr = StorageManager.getStringSet(context, KEY_FILTER_LIST, emptySet(), StorageManager.PrefsType.FILTER)
        filterList = filterListStr.map {
            val arr = it.split("|", limit=2)
            arr[0] to arr.getOrNull(1)?.takeIf { k->k.isNotBlank() }
        }
        isLoaded = true
    }

    // 保存设置（优化性能）
    fun save(context: Context) {
        try {
            StorageManager.putBoolean(context, "enable_package_group_mapping", enablePackageGroupMapping, StorageManager.PrefsType.FILTER)
            StorageManager.putString(context, "default_group_enabled", defaultGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
            StorageManager.putString(context, "custom_group_enabled", customGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
            StorageManager.putStringSet(context, KEY_PACKAGE_GROUPS, customPackageGroups.map { it.joinToString("|") }.toSet(), StorageManager.PrefsType.FILTER)
            StorageManager.putString(context, KEY_FILTER_MODE, filterMode, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(context, KEY_ENABLE_DEDUP, enableDeduplication, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(context, KEY_ENABLE_PEER, enablePeerMode, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(context, "enable_lock_screen_only", enableLockScreenOnly, StorageManager.PrefsType.FILTER)
            StorageManager.putStringSet(context, KEY_FILTER_LIST, filterList.map { it.first + (it.second?.let { k->"|"+k } ?: "") }.toSet(), StorageManager.PrefsType.FILTER)

            if (BuildConfig.DEBUG) Log.d("RemoteFilterConfig", "Configuration saved successfully")
        } catch (e: Exception) {
            Log.e("RemoteFilterConfig", "Failed to save configuration", e)
        }
    }

    // 验证包名是否有效（能获取到应用信息）
    private fun isValidPackage(context: Context, pkg: String): Boolean {
        return try {
            val pm = context.packageManager
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // 包名映射：返回本地等价包名
    fun mapToLocalPackage(pkg: String, installedPkgs: Set<String>): String {
        for (group in packageGroups) {
            if (pkg in group) {
                // 优先本机已安装且有效的包名，按组中顺序尝试
                for (candidatePkg in group) {
                    if (candidatePkg in installedPkgs) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "mapToLocalPackage: 尝试包名 $candidatePkg")
                        return candidatePkg
                    }
                }
                // 如果没有已安装的包名，则取第一个（用于显示原始包名）
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "mapToLocalPackage: 无已安装包名，使用组第一个 ${group.first()}")
                return group.first()
            }
        }
        return pkg
    }
}
