package com.xzyht.notifyrelay.feature.notification.filter

import android.app.NotificationManager
import android.content.Context
import com.sun.jna.Pointer
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.sync.notification.data.NotificationRecord
import com.xzyht.notifyrelay.ui.activity.DeveloperModeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notifyrelay.base.util.Logger
import notifyrelay.data.FilterConfigDefaults
import notifyrelay.data.StorageManager
import notifyrelay.data.database.entity.BlackListEntryEntity
import notifyrelay.data.database.entity.PackageGroupEntity
import notifyrelay.data.database.entity.WhiteListEntryEntity
import notifyrelay.data.database.repository.DatabaseRepository
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后端接收通知过滤器
 * 处理从远程设备接收的通知的过滤逻辑
 * 过滤决策委托给 Rust NativeCore
 */
object BackendRemoteFilter {
    /** Rust 上下文指针，由 DeviceConnectionManager 创建时设置 */
    var rustContext: Pointer? = null

    // 结构化协程作用域，替代 GlobalScope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val ttl: Long,
    )

    data class PendingNotification(
        val notifyId: Int,
        val title: String,
        val text: String,
        val packageName: String,
        val sendTime: Long,
        val context: Context,
    )

    /**
     * 清空待处理列表（供公平运行内存回调使用）。
     */
    fun clearPending() {
        synchronized(dedupCache) { dedupCache.clear() }
        synchronized(pendingNotifications) { pendingNotifications.clear() }
        synchronized(pendingPlaceholders) { pendingPlaceholders.clear() }
    }

    /**
     * 远程通知过滤结果
     */
    data class FilterResult(
        val shouldShow: Boolean,
        val mappedPkg: String,
        val title: String,
        val text: String,
        val rawData: String,
        val needsDelay: Boolean = false, // 是否需要延迟验证（先发送后监控）
    )

    /**
     * 过滤远程通知
     * 包含包名映射（Rust）、智能去重、黑白名单/对等模式（Rust）
     *
     * 同步契约：被消息处理链（NotificationProcessor.process，非 suspend）调用，
     * 无法直接挂起。配置加载通过 loadBlocking 兜底（仅首次加载，后续 isLoaded 短路）。
     */
    fun filterRemoteNotification(
        data: String,
        context: Context,
    ): FilterResult {
        // 确保配置已加载（只加载一次）
        synchronized(RemoteFilterConfig) {
            if (!RemoteFilterConfig.isLoaded) {
                try {
                    RemoteFilterConfig.loadBlocking(context)
                    RemoteFilterConfig.isLoaded = true
                    rustContext?.let { ctx ->
                        val installedPkgs = AppRepository.getInstalledPackageNamesSync(context)
                        RemoteFilterConfig.syncToRust(ctx, installedPkgs)
                    }
                } catch (e: Exception) {
                    Logger.e("NotifyRelay(狂鼠)", "远程过滤配置加载失败", e)
                    return FilterResult(true, "", "", "", data)
                }
            }
        }
        try {
            val json = JSONObject(data)
            val pkg = json.optString("packageName")
            val title = json.optString("title")
            val text = json.optString("text")
            val isLocked = json.optBoolean("isLocked", false)

            val installedPkgs = AppRepository.getInstalledPackageNamesSync(context)
            val mappedPkg = RemoteFilterConfig.mapToLocalPackage(pkg, installedPkgs)

            // 对等模式过滤（保持 Kotlin 实现）
            if (RemoteFilterConfig.enablePeerMode) {
                if (mappedPkg !in installedPkgs) {
                    return FilterResult(false, mappedPkg, title, text, data)
                }
            }

            // 黑白名单过滤 — 委托给 Rust Core（含关键词匹配）
            val filterMode = RemoteFilterConfig.filterMode
            if (filterMode == "black" || filterMode == "white") {
                val pass = RemoteFilterConfig.checkFilterWithRust(mappedPkg, title, text)
                if (!pass) {
                    return FilterResult(false, mappedPkg, title, text, data)
                }
            }

            // 锁屏通知过滤
            if (RemoteFilterConfig.enableLockScreenOnly && !isLocked) {
                // Logger.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 锁屏过滤开启 - 非锁屏通知被过滤")
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
                val pkgInGroups =
                    if (RemoteFilterConfig.enablePackageGroupMapping) {
                        RemoteFilterConfig.packageGroups.any { pkg in it }
                    } else {
                        true
                    }

                val shouldSkipDedup = RemoteFilterConfig.enablePackageGroupMapping && !pkgInGroups && (mappedPkg !in installedPkgs)

                if (shouldSkipDedup) {
                    // Logger.d("智能去重", "跳过去重：包名不属于等价组且本机未安装映射包，包名=$pkg, mappedPkg=$mappedPkg")
                    // 跳过去重，继续走后续流程（如锁屏过滤和最终通过）
                } else {
                    // 1. 快速缓存检查（10秒内）
                    synchronized(dedupCache) {
                        dedupCache.removeAll { now - it.third > 10_000 } // 清理过期缓存
                        val cacheDup = dedupCache.any { it.first == title && it.second == text }
                        // Logger.d("智能去重", "缓存检查 - 缓存大小:${dedupCache.size}, 是否重复:$cacheDup")
                        if (cacheDup) {
                            // 撤回匹配的待监控通知
                            synchronized(pendingNotifications) {
                                val toCancel = pendingNotifications.filter { it.title == title && it.text == text }
                                toCancel.forEach { cancelNotification(it.notifyId, context) }
                                pendingNotifications.removeAll(toCancel)
                            }
                            // Logger.d("智能去重", "命中10秒缓存并撤回之前的通知 - 包名:$pkg, 标题:$title, 内容:$text")
                            return FilterResult(false, mappedPkg, title, text, data)
                        }
                    }

                    // 2. 历史重复检查优化
                    try {
                        checkHistorySyncReliability()
                        // Logger.d("NotifyRelay(狂鼠)", "历史同步不可靠，强制刷新")
                        // Note: This line references a non-existent method, commenting out
                        // com.xzyht.notifyrelay.feature.device.model.NotificationRepository.notifyHistoryChanged("本机", context)

                        // 获取内存历史数据
                        // Note: This references non-existent classes, need to handle appropriately
                        val localList =
                            NotificationRepository
                                .getNotificationsByDevice("本机")
                        val memoryDup = checkDuplicateInMemory(localList, title, text)

                        // 如果内存中有重复，直接过滤
                        if (memoryDup) {
                            // Logger.d("智能去重", "命中内存历史重复")
                            return FilterResult(false, mappedPkg, title, text, data)
                        }

                        // 内存无重复，默认情况下标记为需要延迟验证（先发送后监控机制）。
                        // 但如果该远端通知接受到时本机锁屏，则避免先发送再撤回，改为不立即展示，
                        // 由上层在超期后再次检查并决定是否复刻（见 DeviceConnectionManager 的处理）。
                        if (isLocked) {
                            // Logger.d("NotifyRelay(狂鼠)", "本机锁屏：内存无重复，改为不立即展示，等待超期后再复刻")
                            return FilterResult(false, mappedPkg, title, text, data, needsDelay = false)
                        }

                        // Logger.d("NotifyRelay(狂鼠)", "无历史重复，标记延迟验证")
                        return FilterResult(true, mappedPkg, title, text, data, needsDelay = true)
                    } catch (_: Exception) {
                        // Logger.e("智能去重", "历史检查异常", e)
                        // 异常情况下默认延迟验证
                        return FilterResult(true, mappedPkg, title, text, data, needsDelay = true)
                    }
                }
            }

            // 锁屏通知过滤
            if (RemoteFilterConfig.enableLockScreenOnly && !isLocked) {
                // Logger.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 锁屏过滤 - 非锁屏通知被过滤")
                return FilterResult(false, mappedPkg, title, text, data)
            }

            // Logger.d("NotifyRelay(狂鼠)", "filterRemoteNotification: 直接通过 - mappedPkg=$mappedPkg title=$title text=$text")
            return FilterResult(true, mappedPkg, title, text, data)
        } catch (e: Exception) {
            Logger.e("NotifyRelay(狂鼠)", "filterRemoteNotification: 解析异常 - data=$data", e)
            return FilterResult(true, "", "", "", data)
        }
    }

    /**
     * 检查内存中的重复通知 — 使用 Rust shouldDeduplicate 比较文本相似度
     */
    private fun checkDuplicateInMemory(
        localList: List<NotificationRecord>,
        title: String,
        text: String,
    ): Boolean {
        var hasDuplicate = false

        for (notification in localList) {
            try {
                if (notification.device != "本机") continue
                val oldTitle = normalizeTitle(notification.title ?: "")
                val oldText = notification.text ?: ""
                val newTitle = normalizeTitle(title)
                if (NativeCore.shouldDeduplicate(newTitle, text, oldTitle, oldText)) {
                    hasDuplicate = true
                }
            } catch (e: Exception) {
                Logger.e("智能去重", "内存检查异常", e)
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
    fun addToDedupCache(
        title: String,
        text: String,
    ) {
        synchronized(dedupCache) {
            dedupCache.add(Triple(title, text, System.currentTimeMillis()))
        }
    }

    /**
     * 添加占位（用于锁屏延迟复刻场景）。
     */
    fun addPlaceholder(
        title: String,
        text: String,
        packageName: String,
        ttl: Long = 15_000L,
    ) {
        if (!RemoteFilterConfig.enableDeduplication) return
        val ph = Placeholder(title = title, text = text, packageName = packageName, createTime = System.currentTimeMillis(), ttl = ttl)
        synchronized(pendingPlaceholders) {
            pendingPlaceholders.add(ph)
        }
        // Logger.d("智能去重", "添加延迟复刻占位 - 标题:$title, 包名:$packageName, ttl=${ttl}ms")
    }

    /**
     * 移除匹配的占位（通常由本机入队触发），返回是否有移除项
     */
    fun removePlaceholderMatching(
        title: String?,
        text: String?,
        packageName: String,
    ): Boolean {
        val normalizedTitle = normalizeTitle(title ?: "")
        val pendingText = text ?: ""
        synchronized(pendingPlaceholders) {
            val matches = pendingPlaceholders.filter { ph -> normalizeTitle(ph.title) == normalizedTitle && ph.text == pendingText && ph.packageName == packageName }
            if (matches.isNotEmpty()) {
                pendingPlaceholders.removeAll(matches)
                // Logger.d("智能去重", "移除占位 - 标题:${title}, 包名:$packageName, 数量:${matches.size}")
                return true
            }
        }
        return false
    }

    /**
     * 检查占位是否仍然存在（并清理过期项）
     */
    fun isPlaceholderPresent(
        title: String?,
        text: String?,
        packageName: String,
    ): Boolean {
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
    fun onLocalNotificationEnqueued(
        title: String?,
        text: String?,
        packageName: String,
        time: Long,
        context: Context,
    ) {
        if (!RemoteFilterConfig.enableDeduplication) return
        val normalizedPendingTitle = normalizeTitle(title ?: "")
        val pendingText = text ?: ""
        // 先处理占位匹配（用于延迟复刻的占位）——在单独的锁上操作以避免并发问题
        synchronized(pendingPlaceholders) {
            val placeholderMatches =
                pendingPlaceholders.filter { ph ->
                    normalizeTitle(ph.title) == normalizedPendingTitle && ph.text == pendingText && ph.packageName == packageName
                }
            if (placeholderMatches.isNotEmpty()) {
                // Logger.d("智能去重", "被动命中占位（阻止延迟复刻） - 标题:${title}, 内容:${text}, 匹配数量:${placeholderMatches.size}")
            }
            // 移除命中的占位并将其写入去重缓存
            placeholderMatches.forEach { ph ->
                try {
                    pendingPlaceholders.remove(ph)
                    addToDedupCache(ph.title, ph.text)
                    // Logger.d("智能去重", "占位已取消 - 标题:${ph.title}")
                } catch (e: Exception) {
                    Logger.e("智能去重", "取消占位失败 - 标题:${ph.title}", e)
                }
            }
        }

        // 再处理已发送但在可撤回期的通知
        synchronized(pendingNotifications) {
            val matches =
                pendingNotifications.filter { pending ->
                    normalizeTitle(pending.title) == normalizedPendingTitle && pending.text == pendingText && pending.packageName == packageName
                }
            if (matches.isNotEmpty() && DeveloperModeActivity.DEBUG_UI_ENABLED.value) {
                val titlePreview = if ((title?.length ?: 0) > 10) "${title?.take(10)}..." else (title ?: "")
                Logger.d("智能去重", "被动命中待撤回通知 - 包名:$packageName, 标题预览:$titlePreview, 匹配数量:${matches.size}")
            }
            matches.forEach { matched ->
                try {
                    cancelNotification(matched.notifyId, matched.context)
                    // Logger.d("智能去重", "被动撤回成功 - 通知ID:${matched.notifyId}, 标题:${matched.title}")
                } catch (e: Exception) {
                    Logger.e("智能去重", "被动撤回失败 - 通知ID:${matched.notifyId}", e)
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
    fun addPendingNotification(
        notifyId: Int,
        title: String,
        text: String,
        packageName: String,
        context: Context,
    ) {
        // 只有在去重开关开启时才添加监控
        if (!RemoteFilterConfig.enableDeduplication) {
            return
        }

        synchronized(pendingNotifications) {
            pendingNotifications.add(
                PendingNotification(
                    notifyId = notifyId,
                    title = title,
                    text = text,
                    packageName = packageName,
                    sendTime = System.currentTimeMillis(),
                    context = context,
                ),
            )
        }
        // Logger.d("智能去重", "添加待监控通知 - 进入可撤回期(15s) 包名:$packageName, 标题:$title, 通知ID:$notifyId")
        // 启动监控协程
        startNotificationMonitoring()
    }

    /**
     * 撤回通知
     */
    private fun cancelNotification(
        notifyId: Int,
        context: Context,
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notifyId)
            // Logger.d("智能去重", "已撤回通知 - 通知ID:$notifyId")
        } catch (e: Exception) {
            Logger.e("智能去重", "撤回通知失败 - 通知ID:$notifyId, 错误:${e.message}")
        }
    }

    /**
     * 启动通知监控协程
     */
    private fun startNotificationMonitoring() {
        // Logger.d("智能去重", "启动通知监控协程（仅处理超时） - 当前待监控通知数量:${pendingNotifications.size}")
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PendingNotification>()

                synchronized(pendingNotifications) {
                    for (pending in pendingNotifications) {
                        // 仅处理监控超时逻辑：我们改为被动匹配（由本机历史入队触发匹配），减少频繁IO读取历史
                        if (now - pending.sendTime > 15_000) {
                            // Logger.d("智能去重", "监控超时移除 - 包名:${pending.packageName}, 标题:${pending.title}, 通知ID:${pending.notifyId}, 监控时长:${now - pending.sendTime}ms")
                            toRemove.add(pending)
                        }
                    }

                    // 移除已处理的待监控通知
                    pendingNotifications.removeAll(toRemove)

                    // 为超时移除的通知添加去重缓存
                    toRemove.filter { now - it.sendTime > 15_000 }.forEach { timedOut ->
                        addToDedupCache(timedOut.title, timedOut.text)
                        // Logger.d("智能去重", "超时通知添加到缓存 - 标题:${timedOut.title}, 内容:${timedOut.text}")
                    }
                }

                // 如果没有待监控的通知，退出监控
                // 清理过期占位，避免内存泄露
                val now2 = System.currentTimeMillis()
                synchronized(pendingPlaceholders) {
                    pendingPlaceholders.removeAll { now2 - it.createTime > it.ttl }
                }

                if (pendingNotifications.isEmpty()) {
                    // Logger.d("智能去重", "监控协程结束 - 所有通知已处理完成")
                    break
                }

                // 避免无待处理任务时忙转（最多损失 100ms 判定精度，15s 撤回窗口不受影响）
                delay(100.milliseconds)
            }
        }
    }

    /**
     * 检查历史同步的可靠性
     */
    private fun checkHistorySyncReliability(): Boolean {
        try {
            // Placeholder implementation
            return true
        } catch (e: Exception) {
            Logger.e("NotifyRelay(狂鼠)", "历史同步检查异常", e)
            return false
        }
    }
}

/**
 * 远程过滤配置
 * 包含包名映射、智能去重（先发送后撤回机制）、黑白名单/对等模式配置
 * 名单类配置（黑白名单、包名组）存独立表，标量配置存 app_config
 */
object RemoteFilterConfig {
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_ENABLE_DEDUP = "enable_dedup"
    private const val KEY_ENABLE_PEER = "enable_peer"

    /**
     * 保存串行化互斥锁：多个 UI 回调通过 scope.launch { save() } 并发触发时，
     * 用 Mutex.withLock 让保存排队执行，避免后触发者先完成、先触发者后完成
     * 覆盖回旧状态的竞态。注意必须用同一把跨实例锁（object 单例），
     * 因此声明在 object 顶层而非 save() 局部。
     */
    private val saveLock = Mutex()

    // 标记配置是否已加载，避免重复加载
    var isLoaded: Boolean = false

    // 包名等价功能总开关
    var enablePackageGroupMapping: Boolean = true
    val defaultPackageGroups: List<List<String>>
        get() = FilterConfigDefaults.defaultPackageGroups
    var defaultGroupEnabled: MutableList<Boolean> = mutableListOf(true, true, true)

    // 用户自定义包名等价组，每组为包名列表
    var customPackageGroups: MutableList<MutableList<String>> = mutableListOf()

    // 每个自定义组的开关
    var customGroupEnabled: MutableList<Boolean> = mutableListOf()

    // 合并后的包名等价组
    val packageGroups: List<Set<String>>
        get() =
            if (!enablePackageGroupMapping) {
                emptyList()
            } else {
                defaultPackageGroups.withIndex().filter { defaultGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() } +
                    customPackageGroups.withIndex().filter { customGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() }
            }

    // 智能去重开关（先发送后撤回机制）
    var enableDeduplication: Boolean = true

    // 黑白名单模式："none"=无，"black"=黑名单，"white"=白名单，"peer"=对等
    var filterMode: String = "none"

    // 黑名单内容（包名或通用包名+可选文本关键词），Pair<包名, 关键词?>
    var blackList: List<Pair<String, String?>> = emptyList()

    // 白名单内容（包名或通用包名+可选文本关键词），Pair<包名, 关键词?>
    var whiteList: List<Pair<String, String?>> = emptyList()

    // 黑/白名单的启用条目（序列化字符串集合，允许禁用单条规则）
    var blackListEnabled: Set<String> = emptySet()
    var whiteListEnabled: Set<String> = emptySet()

    // 对等模式开关（仅本机存在的应用或通用应用）
    var enablePeerMode: Boolean = false

    // 锁屏通知过滤开关
    var enableLockScreenOnly: Boolean = true

    // 加载设置
    suspend fun load(context: Context) {
        withContext(Dispatchers.IO) {
            enablePackageGroupMapping = StorageManager.getBoolean(context, "enable_package_group_mapping", true, StorageManager.PrefsType.FILTER)
            filterMode = StorageManager.getString(context, KEY_FILTER_MODE, "none", StorageManager.PrefsType.FILTER)
            enableDeduplication = StorageManager.getBoolean(context, KEY_ENABLE_DEDUP, true, StorageManager.PrefsType.FILTER)
            enablePeerMode = StorageManager.getBoolean(context, KEY_ENABLE_PEER, false, StorageManager.PrefsType.FILTER)
            enableLockScreenOnly = StorageManager.getBoolean(context, "enable_lock_screen_only", true, StorageManager.PrefsType.FILTER)
            loadFilterLists(context)
            loadPackageGroups(context)
            isLoaded = true
        }
    }

    /** 同步兜底：仅供 filterRemoteNotification 等无法挂起的同步入口使用 */
    fun loadBlocking(context: Context) = runBlocking { load(context) }

    private suspend fun loadFilterLists(context: Context) {
        withContext(Dispatchers.IO) {
            val repo =
                DatabaseRepository
                    .getInstance(context)
            val blackRows = repo.getBlackList()
            blackList = blackRows.map { it.packageName to it.keyword.takeIf { k -> k.isNotBlank() } }
            blackListEnabled =
                blackRows
                    .filter { it.enabled }
                    .map { serializeFilterEntry(it.packageName, it.keyword) }
                    .toSet()
            val whiteRows = repo.getWhiteList()
            whiteList = whiteRows.map { it.packageName to it.keyword.takeIf { k -> k.isNotBlank() } }
            whiteListEnabled =
                whiteRows
                    .filter { it.enabled }
                    .map { serializeFilterEntry(it.packageName, it.keyword) }
                    .toSet()
        }
    }

    private suspend fun loadPackageGroups(context: Context) {
        withContext(Dispatchers.IO) {
            val repo =
                DatabaseRepository
                    .getInstance(context)
            val groups = repo.getPackageGroups()
            val items =
                repo
                    .getPackageGroupItems()
                    .groupBy { it.groupId }
                    .mapValues { (_, v) -> v.map { it.packageName } }
            val defaultRows = groups.filter { it.isDefault }.sortedBy { it.id }
            defaultGroupEnabled = MutableList(defaultPackageGroups.size) { true }
            defaultRows.forEachIndexed { idx, g ->
                if (idx < defaultGroupEnabled.size) defaultGroupEnabled[idx] = g.enabled
            }
            val customRows = groups.filter { !it.isDefault }.sortedBy { it.id }
            customPackageGroups = customRows.map { (items[it.id] ?: emptyList()).toMutableList() }.toMutableList()
            customGroupEnabled = customRows.map { it.enabled }.toMutableList()
        }
    }

    // 保存设置（优化性能）
    suspend fun save(context: Context) {
        // 并发串行化：UI 回调通过 scope.launch 并发触发 save 时，用 Mutex 排队，
        // 保证保存按触发顺序完成，最终持久化结果与最新 UI 状态一致。
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    StorageManager.putBoolean(context, "enable_package_group_mapping", enablePackageGroupMapping, StorageManager.PrefsType.FILTER)
                    StorageManager.putString(context, KEY_FILTER_MODE, filterMode, StorageManager.PrefsType.FILTER)
                    StorageManager.putBoolean(context, KEY_ENABLE_DEDUP, enableDeduplication, StorageManager.PrefsType.FILTER)
                    StorageManager.putBoolean(context, KEY_ENABLE_PEER, enablePeerMode, StorageManager.PrefsType.FILTER)
                    StorageManager.putBoolean(context, "enable_lock_screen_only", enableLockScreenOnly, StorageManager.PrefsType.FILTER)
                    saveFilterLists(context)
                    savePackageGroups(context)

                    // 保存后立即同步到 Rust 侧
                    val ctx = BackendRemoteFilter.rustContext
                    if (ctx != null) {
                        val installedPkgs = AppRepository.getInstalledPackageNamesSync(context)
                        syncToRust(ctx, installedPkgs)
                    }
                } catch (e: Exception) {
                    Logger.e("RemoteFilterConfig", "Failed to save configuration", e)
                }
            }
        }
    }

    /** 同步兜底：仅供无法挂起的同步入口使用（如 UI 回调未改协程处） */
    fun saveBlocking(context: Context) = runBlocking { save(context) }

    private suspend fun saveFilterLists(context: Context) {
        withContext(Dispatchers.IO) {
            val repo =
                DatabaseRepository
                    .getInstance(context)
            repo.replaceBlackList(
                blackList.map {
                    BlackListEntryEntity(
                        packageName = it.first,
                        keyword = it.second ?: "",
                        enabled = blackListEnabled.contains(serializeFilterEntry(it.first, it.second)),
                    )
                },
            )
            repo.replaceWhiteList(
                whiteList.map {
                    WhiteListEntryEntity(
                        packageName = it.first,
                        keyword = it.second ?: "",
                        enabled = whiteListEnabled.contains(serializeFilterEntry(it.first, it.second)),
                    )
                },
            )
        }
    }

    private suspend fun savePackageGroups(context: Context) {
        withContext(Dispatchers.IO) {
            val repo =
                DatabaseRepository
                    .getInstance(context)
            val groups = mutableListOf<PackageGroupEntity>()
            val itemPackages = mutableListOf<List<String>>()
            defaultPackageGroups.forEachIndexed { idx, pkgs ->
                groups.add(
                    PackageGroupEntity(
                        groupName = "默认组${idx + 1}",
                        enabled = defaultGroupEnabled.getOrNull(idx) ?: true,
                        isDefault = true,
                    ),
                )
                itemPackages.add(pkgs)
            }
            customPackageGroups.forEachIndexed { idx, pkgs ->
                groups.add(
                    PackageGroupEntity(
                        groupName = "自定义组${idx + 1}",
                        enabled = customGroupEnabled.getOrNull(idx) ?: true,
                        isDefault = false,
                    ),
                )
                itemPackages.add(pkgs)
            }
            repo.replacePackageGroups(groups, itemPackages)
        }
    }

    /** 将当前配置同步到 Rust Core */
    fun syncToRust(
        ctx: Pointer,
        installedPkgs: Set<String>,
    ): Boolean {
        val json = buildRustConfigJson(installedPkgs)
        return NativeCore.setFilterConfig(ctx, json) == 0
    }

    // ---------- 黑白名单操作（按当前 filterMode 生效） ----------

    private fun serializeFilterEntry(
        pkg: String,
        keyword: String?,
    ): String = pkg + (keyword?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: "")

    /** 当前模式对应的名单 */
    fun getActiveFilterList(): List<Pair<String, String?>> =
        when (filterMode) {
            "black" -> blackList
            "white" -> whiteList
            else -> emptyList()
        }

    /** 条目是否启用（迁移后默认启用） */
    fun isActiveEntryEnabled(
        pkg: String,
        keyword: String,
    ): Boolean {
        val ser = serializeFilterEntry(pkg, keyword)
        return if (filterMode == "black") {
            blackListEnabled.contains(ser)
        } else if (filterMode == "white") {
            whiteListEnabled.contains(ser)
        } else {
            true
        }
    }

    /** 向当前模式的名单添加条目（默认启用） */
    suspend fun addFilterEntry(
        context: Context,
        pkg: String,
        keyword: String,
    ) {
        val kw = keyword.takeIf { it.isNotBlank() }
        when (filterMode) {
            "black" -> {
                blackList = blackList + (pkg to kw)
                blackListEnabled = blackListEnabled + serializeFilterEntry(pkg, kw)
            }
            "white" -> {
                whiteList = whiteList + (pkg to kw)
                whiteListEnabled = whiteListEnabled + serializeFilterEntry(pkg, kw)
            }
            else -> return
        }
        save(context)
    }

    /** 从当前模式的名单移除条目 */
    suspend fun removeFilterEntry(
        context: Context,
        pkg: String,
        keyword: String,
    ) {
        val ser = serializeFilterEntry(pkg, keyword)
        when (filterMode) {
            "black" -> {
                blackList = blackList.filterNot { it.first == pkg && (it.second ?: "") == keyword }
                blackListEnabled = blackListEnabled - ser
            }
            "white" -> {
                whiteList = whiteList.filterNot { it.first == pkg && (it.second ?: "") == keyword }
                whiteListEnabled = whiteListEnabled - ser
            }
        }
        save(context)
    }

    /** 启用/禁用当前模式的条目 */
    suspend fun setFilterEntryEnabled(
        context: Context,
        pkg: String,
        keyword: String,
        enabled: Boolean,
    ) {
        val ser = serializeFilterEntry(pkg, keyword)
        when (filterMode) {
            "black" -> blackListEnabled = if (enabled) blackListEnabled + ser else blackListEnabled - ser
            "white" -> whiteListEnabled = if (enabled) whiteListEnabled + ser else whiteListEnabled - ser
        }
        save(context)
    }

    /** 构建 Rust nrc_set_filter_config 所需的 JSON */
    private fun buildRustConfigJson(installedPkgs: Set<String>): String {
        val root = JSONObject()

        root.put("enablePackageGroupMapping", enablePackageGroupMapping)

        // 包名组：使用连续索引
        val pkgGroupsArr = JSONArray()
        val enabledMap = JSONObject()
        var groupIdx = 0
        for ((i, group) in defaultPackageGroups.withIndex()) {
            val enabled = defaultGroupEnabled.getOrNull(i) ?: true
            if (enabled) {
                val g = JSONObject()
                g.put("groupName", "group_$groupIdx")
                g.put("packages", JSONArray(group))
                pkgGroupsArr.put(g)
                enabledMap.put("group_$groupIdx", true)
                groupIdx++
            }
        }
        for ((i, group) in customPackageGroups.withIndex()) {
            val enabled = customGroupEnabled.getOrNull(i) ?: true
            if (enabled) {
                val g = JSONObject()
                g.put("groupName", "group_$groupIdx")
                g.put("packages", JSONArray(group))
                pkgGroupsArr.put(g)
                enabledMap.put("group_$groupIdx", true)
                groupIdx++
            }
        }
        root.put("packageGroups", pkgGroupsArr)
        root.put("groupEnabled", enabledMap)

        // 过滤模式
        val filterModeNum =
            when (filterMode) {
                "white" -> 1
                "black" -> 2
                else -> 0
            }
        root.put("filterMode", filterModeNum)

        // 黑白名单（当前模式对应的名单，仅包含启用条目）
        val activeList =
            when (filterMode) {
                "black" -> blackList
                "white" -> whiteList
                else -> emptyList()
            }
        val activeEnabled =
            when (filterMode) {
                "black" -> blackListEnabled
                "white" -> whiteListEnabled
                else -> emptySet()
            }
        val filterListArr = JSONArray()
        for ((pkg, keyword) in activeList) {
            if (activeEnabled.contains(serializeFilterEntry(pkg, keyword))) {
                filterListArr.put(serializeFilterEntry(pkg, keyword))
            }
        }
        root.put("filterList", filterListArr)

        root.put("enablePeerMode", enablePeerMode)
        root.put("installedPackages", JSONArray(installedPkgs.toList()))

        return root.toString()
    }

    /** 包名映射 — 委托给 Rust Core */
    fun mapToLocalPackage(
        pkg: String,
        installedPkgs: Set<String>,
    ): String {
        val ctx = BackendRemoteFilter.rustContext ?: return pkg
        return NativeCore.mapLocalPackage(ctx, pkg) ?: pkg
    }

    /** 检查过滤模式（含关键词匹配）— 委托给 Rust Core */
    fun checkFilterWithRust(
        pkg: String,
        title: String,
        text: String,
    ): Boolean {
        val ctx = BackendRemoteFilter.rustContext ?: return true
        return NativeCore.checkFilterMode(ctx, pkg, pkg, title, text)
    }
}
