package com.xzyht.notifyrelay.feature.notification.backend

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.AppListHelper
import com.xzyht.notifyrelay.common.data.StorageManager

/**
 * 后端接收通知过滤器
 * 处理从远程设备接收的通知的过滤逻辑
 */
object BackendRemoteFilter {

    // 延迟去重缓存（10秒内）
    private val dedupCache = mutableListOf<Triple<String, String, Long>>() // title, text, time

    /**
     * 远程通知过滤结果
     */
    data class FilterResult(
        val shouldShow: Boolean,
        val mappedPkg: String,
        val title: String,
        val text: String,
        val rawData: String,
        val needsDelay: Boolean = false
    )

    /**
     * 过滤远程通知
     * 包含包名映射、去重、黑白名单/对等模式
     */
    fun filterRemoteNotification(data: String, context: Context): FilterResult {
        // 确保配置已加载
        RemoteFilterConfig.load(context)
        try {
            val json = org.json.JSONObject(data)
            var pkg = json.optString("packageName")
            val title = json.optString("title")
            val text = json.optString("text")
            val time = System.currentTimeMillis()

            val installedPkgs = AppListHelper.getInstalledApplications(context).map { it.packageName }.toSet()
            val mappedPkg = RemoteFilterConfig.mapToLocalPackage(pkg, installedPkgs)

            // 对等模式过滤
            if (RemoteFilterConfig.filterMode == "peer" || RemoteFilterConfig.enablePeerMode) {
                if (mappedPkg !in installedPkgs) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: peer mode过滤 mappedPkg=$mappedPkg 不在本机已安装应用")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
            }

            // 黑白名单过滤
            if (RemoteFilterConfig.filterMode == "black" || RemoteFilterConfig.filterMode == "white") {
                val match = RemoteFilterConfig.filterList.any { (filterPkg, keyword) ->
                    (mappedPkg == filterPkg || pkg == filterPkg) &&
                    (keyword.isNullOrBlank() || title.contains(keyword) || text.contains(keyword))
                }
                if (RemoteFilterConfig.filterMode == "black" && match) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 命中黑名单 filtered=$match mappedPkg=$mappedPkg title=$title text=$text")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
                if (RemoteFilterConfig.filterMode == "white" && !match) {
                    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 未命中白名单 mappedPkg=$mappedPkg title=$title text=$text")
                    return FilterResult(false, mappedPkg, title, text, data)
                }
            }

            // 去重检查
            if (RemoteFilterConfig.enableDeduplication) {
                val now = System.currentTimeMillis()
                // 1. 先查dedupCache
                synchronized(dedupCache) {
                    dedupCache.removeAll { now - it.third > 10_000 }
                    val dup = dedupCache.any { it.first == title && it.second == text }
                    if (dup) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 去重命中 title=$title text=$text (dedupCache)")
                        return FilterResult(false, mappedPkg, title, text, data)
                    }
                }

                // 2. 再查本机通知历史（10秒内同title+text）
                try {
                    val localList = com.xzyht.notifyrelay.feature.device.model.NotificationRepository.notifications
                    val localDup = localList.any {
                        val match = it.device == "本机" && it.title == title && it.text == text && (now - it.time <= 10_000)
                        if (it.device == "本机" && (now - it.time <= 10_000)) {
                            if (BuildConfig.DEBUG) Log.d(
                                "NotifyRelay(狂鼠)",
                                "remoteNotificationFilter(狂鼠): 本机历史检查 title=$title text=$text vs it.title=${it.title} it.text=${it.text} match=$match"
                            )
                        }
                        match
                    }
                    if (localDup) {
                        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 去重命中 title=$title text=$text (本机历史)")
                        return FilterResult(false, mappedPkg, title, text, data)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "remoteNotificationFilter: 本机历史去重检查异常", e)
                }

                // 需延迟判断
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 需延迟判断 title=$title text=$text")
                return FilterResult(true, mappedPkg, title, text, data, needsDelay = true)
            }

            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 直接通过 mappedPkg=$mappedPkg title=$title text=$text")
            return FilterResult(true, mappedPkg, title, text, data)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "remoteNotificationFilter: 解析异常", e)
            return FilterResult(true, "", "", "", data)
        }
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
     * 检查是否在去重缓存中
     */
    fun isInDedupCache(title: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(dedupCache) {
            dedupCache.removeAll { now - it.third > 10_000 }
            return dedupCache.any { it.first == title && it.second == text }
        }
    }
}

/**
 * 远程过滤配置
 * 包含包名映射、去重、黑白名单/对等模式配置
 */
object RemoteFilterConfig {
    private const val KEY_PACKAGE_GROUPS = "package_groups"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_FILTER_LIST = "filter_list"
    private const val KEY_ENABLE_DEDUP = "enable_dedup"
    private const val KEY_ENABLE_PEER = "enable_peer"

    // 包名等价功能总开关
    var enablePackageGroupMapping: Boolean = true

    // 默认包名等价组及其开关
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

    // 延迟去重开关
    var enableDeduplication: Boolean = true

    // 黑白名单模式："none"=无，"black"=黑名单，"white"=白名单，"peer"=对等
    var filterMode: String = "none"

    // 黑/白名单内容（包名或通用包名+可选文本关键词）
    var filterList: List<Pair<String, String?>> = emptyList() // Pair<包名, 关键词?>

    // 对等模式开关（仅本机存在的应用或通用应用）
    var enablePeerMode: Boolean = false

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
        val filterListStr = StorageManager.getStringSet(context, KEY_FILTER_LIST, emptySet(), StorageManager.PrefsType.FILTER)
        filterList = filterListStr.map {
            val arr = it.split("|", limit=2)
            arr[0] to arr.getOrNull(1)?.takeIf { k->k.isNotBlank() }
        }
    }

    // 保存设置
    fun save(context: Context) {
        StorageManager.putBoolean(context, "enable_package_group_mapping", enablePackageGroupMapping, StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, "default_group_enabled", defaultGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, "custom_group_enabled", customGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
        StorageManager.putStringSet(context, KEY_PACKAGE_GROUPS, customPackageGroups.map { it.joinToString("|") }.toSet(), StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, KEY_FILTER_MODE, filterMode, StorageManager.PrefsType.FILTER)
        StorageManager.putBoolean(context, KEY_ENABLE_DEDUP, enableDeduplication, StorageManager.PrefsType.FILTER)
        StorageManager.putBoolean(context, KEY_ENABLE_PEER, enablePeerMode, StorageManager.PrefsType.FILTER)
        StorageManager.putStringSet(context, KEY_FILTER_LIST, filterList.map { it.first + (it.second?.let { k->"|"+k } ?: "") }.toSet(), StorageManager.PrefsType.FILTER)
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
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "mapToLocalPackage: 无已安装包名，使用组第一个 $group.first()")
                return group.first()
            }
        }
        return pkg
    }
}
