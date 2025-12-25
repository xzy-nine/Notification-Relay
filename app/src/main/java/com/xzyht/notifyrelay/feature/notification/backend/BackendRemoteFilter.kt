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

    // 待监控的通知撤回队列
    private val pendingNotifications = mutableListOf<PendingNotification>()
    // 延迟复刻占位队列（用于锁屏延迟复刻的占位，15s 可被本机入队取消）
    private val pendingPlaceholders = mutableListOf<Placeholder>()

    data class Placeholder(
        val title: String,
        val text: String,
        val packageName: String,
        val createTime: Long,
        val ttl: Long
    )

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
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 锁屏过滤开启 - 非锁屏通知被过滤")
                return FilterResult(false, mappedPkg, title, text, data)
            }

            // 智能去重检查 - 优化性能和逻辑
            if (RemoteFilterConfig.enableDeduplication) {
                val now = System.currentTimeMillis()

                // 性能优化：仅在满足以下情况时跳过去重：
                //  - 开启了包名等价组映射
                //  - 远端包名不属于任何等价组
                //  - 且映射到的本地包未安装
                // 对于本机已安装映射包（包括 mappedPkg == pkg 的同包名场景）仍然执行去重。
                val pkgInGroups = if (RemoteFilterConfig.enablePackageGroupMapping) {
                    RemoteFilterConfig.packageGroups.any { pkg in it }
                } else {
                    true
                }

                val shouldSkipDedup = RemoteFilterConfig.enablePackageGroupMapping && !pkgInGroups && (mappedPkg !in installedPkgs)

                if (shouldSkipDedup) {
                    if (BuildConfig.DEBUG) Log.d("智能去重", "跳过去重：包名不属于等价组且本机未安装映射包，包名=$pkg, mappedPkg=$mappedPkg")
                    // 跳过去重，继续走后续流程（如锁屏过滤和最终通过）
                } else {
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
                        // 但如果该远端通知接受到时本机锁屏，则避免先发送再撤回，改为不立即展示，
                        // 由上层在超期后再次检查并决定是否复刻（见 DeviceConnectionManager 的处理）。
                        if (isLocked) {
                            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "本机锁屏：内存无重复，改为不立即展示，等待超期后再复刻")
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
     * 添加占位（用于锁屏延迟复刻场景）。
     */
    fun addPlaceholder(title: String, text: String, packageName: String, ttl: Long = 15_000L) {
        if (!RemoteFilterConfig.enableDeduplication) return
        val ph = Placeholder(title = title, text = text, packageName = packageName, createTime = System.currentTimeMillis(), ttl = ttl)
        synchronized(pendingPlaceholders) {
            pendingPlaceholders.add(ph)
        }
        if (BuildConfig.DEBUG) Log.d("智能去重", "添加延迟复刻占位 - 标题:$title, 包名:$packageName, ttl=${ttl}ms")
    }

    /**
     * 移除匹配的占位（通常由本机入队触发），返回是否有移除项
     */
    fun removePlaceholderMatching(title: String?, text: String?, packageName: String): Boolean {
        val normalizedTitle = normalizeTitle(title ?: "")
        val pendingText = text ?: ""
        synchronized(pendingPlaceholders) {
            val matches = pendingPlaceholders.filter { ph -> normalizeTitle(ph.title) == normalizedTitle && ph.text == pendingText && ph.packageName == packageName }
            if (matches.isNotEmpty()) {
                pendingPlaceholders.removeAll(matches)
                if (BuildConfig.DEBUG) Log.d("智能去重", "移除占位 - 标题:${title}, 包名:$packageName, 数量:${matches.size}")
                return true
            }
        }
        return false
    }

    /**
     * 检查占位是否仍然存在（并清理过期项）
     */
    fun isPlaceholderPresent(title: String?, text: String?, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val normalizedTitle = normalizeTitle(title ?: "")
        val pendingText = text ?: ""
        synchronized(pendingPlaceholders) {
            // 清理过期占位
            pendingPlaceholders.removeAll { now - it.createTime > it.ttl }
            return pendingPlaceholders.any { ph -> normalizeTitle(ph.title) == normalizedTitle && ph.text == pendingText && ph.packageName == packageName }
        }
    }

    /**
     * 被动匹配：当本机通知入队（已完成本地过滤并写入历史/内存）时调用。
     * 如果与待撤回队列命中，则立即撤回对应通知并移除待监控项，进入被动撤回模式，减少轮询与IO。
     */
    @Suppress("UNUSED_PARAMETER")
    fun onLocalNotificationEnqueued(title: String?, text: String?, packageName: String, time: Long, context: Context) {
        if (!RemoteFilterConfig.enableDeduplication) return
        val normalizedPendingTitle = normalizeTitle(title ?: "")
        val pendingText = text ?: ""
        // 先处理占位匹配（用于延迟复刻的占位）——在单独的锁上操作以避免并发问题
        synchronized(pendingPlaceholders) {
            val placeholderMatches = pendingPlaceholders.filter { ph ->
                normalizeTitle(ph.title) == normalizedPendingTitle && ph.text == pendingText && ph.packageName == packageName
            }
            if (placeholderMatches.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d("智能去重", "被动命中占位（阻止延迟复刻） - 标题:${title}, 内容:${text}, 匹配数量:${placeholderMatches.size}")
            }
            // 移除命中的占位并将其写入去重缓存
            placeholderMatches.forEach { ph ->
                try {
                    pendingPlaceholders.remove(ph)
                    addToDedupCache(ph.title, ph.text)
                    if (BuildConfig.DEBUG) Log.d("智能去重", "占位已取消 - 标题:${ph.title}")
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("智能去重", "取消占位失败 - 标题:${ph.title}", e)
                }
            }
        }

        // 再处理已发送但在可撤回期的通知
        synchronized(pendingNotifications) {
            val matches = pendingNotifications.filter { pending ->
                normalizeTitle(pending.title) == normalizedPendingTitle && pending.text == pendingText && pending.packageName == packageName
            }
            if (matches.isNotEmpty() && BuildConfig.DEBUG) {
                Log.d("智能去重", "被动命中待撤回通知 - 本机入队 标题:${title}, 内容:${text}, 匹配数量:${matches.size}")
            }
            matches.forEach { matched ->
                try {
                    cancelNotification(matched.notifyId, matched.context)
                    if (BuildConfig.DEBUG) Log.d("智能去重", "被动撤回成功 - 通知ID:${matched.notifyId}, 标题:${matched.title}")
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("智能去重", "被动撤回失败 - 通知ID:${matched.notifyId}", e)
                }
            }
            // 移除已命中的待监控通知
            if (matches.isNotEmpty()) pendingNotifications.removeAll(matches)

            // 对于命中的通知，也可以把本地这条记录记入去重缓存，避免短时间内再次复刻
            matches.forEach { addToDedupCache(it.title, it.text) }
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
        if (BuildConfig.DEBUG) Log.d("智能去重", "添加待监控通知 - 进入可撤回期(15s) 包名:$packageName, 标题:$title, 通知ID:$notifyId")
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
        if (BuildConfig.DEBUG) Log.d("智能去重", "启动通知监控协程（仅处理超时） - 当前待监控通知数量:${pendingNotifications.size}")
        GlobalScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PendingNotification>()

                synchronized(pendingNotifications) {
                        for (pending in pendingNotifications) {
                            // 仅处理监控超时逻辑：我们改为被动匹配（由本机历史入队触发匹配），减少频繁IO读取历史
                            if (now - pending.sendTime > 15_000) {
                                if (BuildConfig.DEBUG) Log.d("智能去重", "监控超时移除 - 包名:${pending.packageName}, 标题:${pending.title}, 通知ID:${pending.notifyId}, 监控时长:${now - pending.sendTime}ms")
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
                // 清理过期占位，避免内存泄露
                val now2 = System.currentTimeMillis()
                synchronized(pendingPlaceholders) {
                    val before = pendingPlaceholders.size
                    pendingPlaceholders.removeAll { now2 - it.createTime > it.ttl }
                    val after = pendingPlaceholders.size
                    if (BuildConfig.DEBUG && before != after) Log.d("智能去重", "清理过期占位: removed=${before - after}")
                }

                if (pendingNotifications.isEmpty()) {
                    if (BuildConfig.DEBUG) Log.d("智能去重", "监控协程结束 - 所有通知已处理完成")
                    break
                }
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
