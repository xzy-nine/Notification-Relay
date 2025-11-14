package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.common.data.PersistenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 持久化超级岛历史记录并提供实时状态流以便 UI 订阅。
 */
data class SuperIslandHistoryEntry(
    val id: Long,
    val sourceDeviceUuid: String? = null,
    val originalPackage: String? = null,
    val mappedPackage: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val paramV2Raw: String? = null,
    val picMap: Map<String, String> = emptyMap(),
    val rawPayload: String? = null
)

object SuperIslandHistory {
    private const val STORAGE_DEVICE_KEY = "super_island_history"
    private const val MAX_ENTRIES = 600

    private val historyTypeToken = object : TypeToken<List<SuperIslandHistoryEntry>>() {}

    private val historyFlow = MutableStateFlow<List<SuperIslandHistoryEntry>>(emptyList())
    private val lock = Any()
    @Volatile
    private var initialized = false

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val history = try {
                PersistenceManager.readNotificationRecords(context, STORAGE_DEVICE_KEY, historyTypeToken)
            } catch (_: Exception) {
                emptyList()
            }
            historyFlow.value = history
            initialized = true
            // 异步重建图片引用计数并执行 GC（按时间与条目数限制），避免阻塞加载流程
            try {
                Thread {
                    try {
                        SuperIslandImageStore.rebuildRefCountsAndPrune(context, history)
                        // 额外按阈值进行清理（可调整参数）
                        SuperIslandImageStore.prune(context, maxEntries = 2000, maxAgeDays = 30)
                    } catch (_: Exception) {}
                }.start()
            } catch (_: Exception) {}
        }
    }

    fun historyState(context: Context): StateFlow<List<SuperIslandHistoryEntry>> {
        ensureLoaded(context)
        return historyFlow.asStateFlow()
    }

    fun append(context: Context, entry: SuperIslandHistoryEntry) {
        ensureLoaded(context)
        // 将图片字符串 intern 为引用以避免重复存储
        val interned = SuperIslandImageStore.internAll(context, entry.picMap)
        val sanitizedEntry = entry.copy(picMap = interned.toMap())
        val updated = (historyFlow.value + sanitizedEntry).takeLast(MAX_ENTRIES)
        historyFlow.value = updated
        PersistenceManager.saveNotificationRecords(context, STORAGE_DEVICE_KEY, updated)
    }

    fun clear(context: Context) {
        ensureLoaded(context)
        historyFlow.value = emptyList()
        PersistenceManager.saveNotificationRecords(context, STORAGE_DEVICE_KEY, emptyList<SuperIslandHistoryEntry>())
    }
}
