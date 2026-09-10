package com.xzyht.notifyrelay.feature.notification.filter

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import notifyrelay.base.util.Logger
import notifyrelay.data.database.entity.FilterEntryEntity
import notifyrelay.data.database.repository.DatabaseRepository

/**
 * 后端本机通知过滤器
 * 处理本机产生的通知的过滤逻辑
 *
 * 注：本类中所有数据访问方法均为 suspend。`shouldForwardBlocking` 保留 runBlocking
 * 兜底用于通知监听服务的同步回调（NotificationListenerService.onNotificationPosted），
 * 其余调用方（UI/ViewModel）应使用 suspend 版本。
 */
object BackendLocalFilter {
    // 可配置项
    var filterSelf: Boolean = true // 过滤本应用
    var filterOngoing: Boolean = true // 过滤持久化
    var filterNoTitleOrText: Boolean = true // 过滤空标题内容
    var filterImportanceNone: Boolean = true // 过滤IMPORTANCE_NONE

    // 内置过滤条目（包含文本关键词条目和默认包名条目，均为不可删除的默认黑名单）
    private val builtinFilterEntries: Set<FilterEntry> =
        setOf(
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
            FilterEntry("", "com.miui.systemAdSolution"),
        )

    // FilterEntry 表示一条复合过滤规则：keyword 可空，packageName 可空
    data class FilterEntry(
        val keyword: String,
        val packageName: String,
    )

    private val saveLock = Mutex()

    @Volatile
    private var cachedRows: List<FilterEntryEntity>? = null

    /**
     * 获取所有过滤条目（包含内置关键词和默认包名过滤），表为空时初始化内置条目。
     * 单次查询：仅在结果为空时写入内置条目并返回，避免每次调用产生 2 次查询。
     */
    suspend fun getFilterEntries(context: Context): Set<FilterEntry> = rowsCached(context).map { FilterEntry(it.keyword, it.packageName) }.toSet()

    // 获取内置默认关键词集合（兼容旧接口）
    fun getBuiltinKeywords(): Set<String> = builtinFilterEntries.filter { it.keyword.isNotBlank() }.map { it.keyword }.toSet()

    /**
     * 获取启用的过滤条目集合。
     * 单次查询：基于同一份 rows 派生启用集合，不再强制加回内置条目（允许用户禁用内置条目）。
     */
    suspend fun getEnabledFilterEntries(context: Context): Set<FilterEntry> = rowsCached(context).filter { it.enabled }.map { FilterEntry(it.keyword, it.packageName) }.toSet()

    suspend fun setFilterEntryEnabled(
        context: Context,
        entry: FilterEntry,
        enabled: Boolean,
    ) {
        saveLock.withLock {
            val repo = DatabaseRepository.getInstance(context)
            val rows = repo.getLocalFilterEntries().toMutableList()
            val idx = rows.indexOfFirst { it.keyword == entry.keyword && it.packageName == entry.packageName }
            if (idx >= 0) {
                rows[idx] = rows[idx].copy(enabled = enabled)
            } else {
                rows.add(FilterEntryEntity(entry.keyword, entry.packageName, enabled))
            }
            repo.replaceLocalFilterEntries(rows)
            invalidateCache()
        }
    }

    suspend fun addFilterEntry(
        context: Context,
        keyword: String,
        packageName: String,
    ) {
        saveLock.withLock {
            val entry = FilterEntry(keyword.trim(), packageName.trim())
            val repo = DatabaseRepository.getInstance(context)
            val rows = repo.getLocalFilterEntries().toMutableList()
            if (rows.none { it.keyword == entry.keyword && it.packageName == entry.packageName }) {
                rows.add(FilterEntryEntity(entry.keyword, entry.packageName, true))
                repo.replaceLocalFilterEntries(rows)
            }
            invalidateCache()
        }
    }

    suspend fun removeFilterEntry(
        context: Context,
        keyword: String,
        packageName: String,
    ) {
        // 内置条目不可删除（包括内置文本关键词和默认包名）
        if ((packageName.isBlank() && builtinFilterEntries.any { it.keyword == keyword && it.keyword.isNotBlank() }) || (keyword.isBlank() && builtinFilterEntries.any { it.packageName == packageName && it.packageName.isNotBlank() })) return
        saveLock.withLock {
            val repo = DatabaseRepository.getInstance(context)
            val rows = repo.getLocalFilterEntries().filterNot { it.keyword == keyword && it.packageName == packageName }
            repo.replaceLocalFilterEntries(rows)
            invalidateCache()
        }
    }

    private suspend fun rowsCached(context: Context): List<FilterEntryEntity> {
        return saveLock.withLock {
            cachedRows?.let { return@withLock it }
            val repo = DatabaseRepository.getInstance(context)
            val rows = repo.getLocalFilterEntries()
            if (rows.isEmpty()) {
                val builtins = builtinFilterEntries.map { FilterEntryEntity(it.keyword, it.packageName, true) }
                repo.replaceLocalFilterEntries(builtins)
                builtins.also { cachedRows = it }
                return@withLock builtins
            }
            rows.also { cachedRows = it }
            rows
        }
    }

    private fun invalidateCache() {
        cachedRows = null
    }

    // 兼容的旧 API：返回仅 keyword（package 为空）的集合
    suspend fun getForegroundKeywords(context: Context): Set<String> = getFilterEntries(context).filter { it.packageName.isBlank() }.map { it.keyword }.toSet()

    // 兼容的旧 API：返回仅 package 的集合（包含默认）
    suspend fun getPackageFilterList(context: Context): Set<String> {
        val pkgs = getFilterEntries(context).filter { it.keyword.isBlank() && it.packageName.isNotBlank() }.map { it.packageName }.toMutableSet()
        // 添加内置默认包名
        pkgs.addAll(builtinFilterEntries.filter { it.packageName.isNotBlank() }.map { it.packageName })
        return pkgs
    }

    suspend fun addPackageFilter(
        context: Context,
        packageName: String,
    ) {
        if (packageName.isBlank()) return
        addFilterEntry(context, "", packageName)
    }

    suspend fun removePackageFilter(
        context: Context,
        packageName: String,
    ) {
        // 默认包名不可删除
        if (builtinFilterEntries.any { it.packageName == packageName && it.packageName.isNotBlank() }) return
        removeFilterEntry(context, "", packageName)
    }

    suspend fun getEnabledPackageFilters(context: Context): Set<String> = getEnabledFilterEntries(context).filter { it.keyword.isBlank() && it.packageName.isNotBlank() }.map { it.packageName }.toSet()

    // 获取默认包名过滤集合（从内置条目提取）
    fun getDefaultPackageFilters(): Set<String> = builtinFilterEntries.filter { it.packageName.isNotBlank() }.map { it.packageName }.toSet()

    suspend fun setPackageEnabled(
        context: Context,
        packageName: String,
        enabled: Boolean,
    ) {
        setFilterEntryEnabled(context, FilterEntry("", packageName), enabled)
    }

    /**
     * 判断本机通知是否应该被转发（suspend 版本，热路径仅一次查询）。
     * @param isFromPeriodicCheck 是否来自定时检查，避免调试日志刷屏
     */
    suspend fun shouldForward(
        sbn: StatusBarNotification,
        context: Context,
        isFromPeriodicCheck: Boolean = false,
    ): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false

        // 单次查询：基于同一份 rows 派生 enabled 与 all，避免热路径上重复查询
        val rows = rowsCached(context)

        // 包名过滤：仅使用 enabled 集合
        val enabledEntries = rows.filter { it.enabled }.map { FilterEntry(it.keyword, it.packageName) }.toSet()
        val enabledPackageFilters = enabledEntries.filter { it.keyword.isBlank() && it.packageName.isNotBlank() }.map { it.packageName }.toSet()
        if (enabledPackageFilters.contains(sbn.packageName)) return false

        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        // 使用 getNotificationTextWithVerifyCode 读取文本，优先读取 verify_code 字段
        val text = NotificationRepository.getNotificationTextWithVerifyCode(sbn) ?: ""
        if (!isFromPeriodicCheck) {
            val titlePreview = if (title.length > 10) "${title.take(10)}..." else title
            Logger.v("NotifyRelay-Filter", "shouldForward: packageName='${sbn.packageName}', titlePreview='$titlePreview'")
        }

        // 持久化/前台服务过滤，包含服务相关关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            if (isOngoing) return false
        }

        // 使用统一的启用过滤条目进行匹配：
        // - entry.keyword 非空 且 packageName 为空 -> 只匹配文本（任意应用）
        // - entry.keyword 为空 且 packageName 非空 -> 只匹配包名
        // - 两者均非空 -> 同时匹配才命中
        // 新的匹配逻辑：支持关键字按空格分词后跨标题/内容匹配。
        // 例如 keyword = "米家 手表" 时，如果 title 包含 "米家" 且 text 包含 "手表" 也应视为命中。
        fun keywordMatchesAcrossFields(
            keyword: String,
            title: String,
            text: String,
        ): Boolean {
            val kw = keyword.trim()
            if (kw.isEmpty()) return false
            // 如果 keyword 没有空白，则使用原来的包含匹配
            if (!kw.contains(' ')) return title.contains(kw, true) || text.contains(kw, true)

            // 含空格：把 keyword 按空白分词，要求所有 token 都能在 title 或 text 中找到（任意分布）
            val tokens = kw.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false

            // 对于每个 token，只要在 title 或 text 中存在即可；所有 token 都必须满足
            for (t in tokens) {
                if (!title.contains(t, true) && !text.contains(t, true)) return false
            }
            return true
        }

        for (entry in enabledEntries) {
            val kw = entry.keyword.trim()
            val pkg = entry.packageName.trim()

            val kwMatches = kw.isNotEmpty() && keywordMatchesAcrossFields(kw, title, text)
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

    /**
     * 同步兜底版本：供 NotificationListenerService 的非挂起回调使用。
     * 内部通过 runBlocking 调用 suspend 版本，保留服务回调语义不变。
     * 注意：仅在无法改造为协程的同步入口使用，新代码应优先调用 suspend 版本。
     */
    fun shouldForwardBlocking(
        sbn: StatusBarNotification,
        context: Context,
        isFromPeriodicCheck: Boolean = false,
    ): Boolean = runBlocking { shouldForward(sbn, context, isFromPeriodicCheck) }
}
