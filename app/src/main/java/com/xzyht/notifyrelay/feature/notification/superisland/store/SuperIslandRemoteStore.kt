package com.xzyht.notifyrelay.feature.notification.superisland.store

import github.xzynine.superislandui.common.SuperIslandProtocol
import github.xzynine.superislandui.diff.DiffSystem
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收端超级岛远端状态存储与差异合并。
 * key使用 sourceId（通常为 "superisland:pkg|featureId"）。
 */
object SuperIslandRemoteStore {
    private val store = ConcurrentHashMap<String, DiffSystem.State>()

    /**
     * 清空所有远端状态（供公平运行内存回调使用）。
     */
    @Synchronized
    fun clear() {
        store.clear()
    }

    @Synchronized
    fun applyIncoming(
        sourceId: String,
        payload: JSONObject,
    ): DiffSystem.State? {
        return try {
            // 结束包标识：存在 terminateValue 且等于约定值
            val term = payload.optString("terminateValue", "")
            if (term == SuperIslandProtocol.TERMINATE_VALUE) {
                store.remove(sourceId)
                return null
            }

            // 兼容旧设备增量(delta)报文：含 changes 字段时与现有状态合并，而非全量覆盖
            val changes = payload.optJSONObject("changes")
            val state =
                if (changes != null) {
                    mergeDelta(store[sourceId], changes, payload)
                } else {
                    // Rust 合并引擎已输出全量，直接解析存储
                    parseStateFromFull(payload)
                }
            store[sourceId] = state
            state
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 根据 deviceUuid 和 mappedPkg 前缀寻找并移除匹配的 sourceId 条目，返回被移除的 sourceId 列表。
     * 用于在接收到结束包但 featureId 无法可靠重算时，清理存储并告知上层进行浮窗关闭。
     */
    @Synchronized
    fun removeByDeviceAndPkgPrefix(
        deviceUuid: String,
        mappedPkg: String,
    ): List<String> =
        try {
            val prefix = listOf(deviceUuid, mappedPkg).joinToString("|")
            val toRemove = store.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { store.remove(it) }
            toRemove
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * 根据 featureKey（特征 ID）后缀查找并移除匹配的 sourceId，返回被移除的 sourceId 列表。
     * 兼容只传入 featureKey 的结束包（例如仅包含 featureKeyValue），用于定位完整的 sourceId。
     */
    @Synchronized
    fun removeByFeatureKey(featureKey: String): List<String> =
        try {
            val suffix = "|$featureKey"
            val toRemove = store.keys.filter { it.endsWith(suffix) || it == featureKey }
            toRemove.forEach { store.remove(it) }
            toRemove
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * 精确移除指定的 sourceId（如果存在），返回是否成功移除。
     */
    fun removeExact(sourceId: String): Boolean =
        try {
            store.remove(sourceId) != null
        } catch (_: Exception) {
            false
        }

    /**
     * 获取指定sourceId的状态，用于外部查询当前状态
     */
    fun getState(sourceId: String): DiffSystem.State? =
        try {
            store[sourceId]
        } catch (_: Exception) {
            null
        }

    private fun parseStateFromFull(obj: JSONObject): DiffSystem.State {
        val title = obj.optString("title", "").takeIf { it.isNotEmpty() }
        val text = obj.optString("text", "").takeIf { it.isNotEmpty() }
        val p2 = obj.optString("param_v2_raw", "").takeIf { it.isNotEmpty() }
        val picsJson = obj.optJSONObject("pics")
        val picsMap = mutableMapOf<String, String>()
        if (picsJson != null) {
            val it = picsJson.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = picsJson.optString(k)
                if (!v.isNullOrEmpty()) picsMap[k] = v
            }
        }
        return DiffSystem.State(title, text, p2, picsMap)
    }

    /**
     * 增量合并：以 changes 中的字段覆盖现有状态，未涉及的字段保持原值。
     * 缺失 param 时回退读取 payload 顶层的 raw/param_v2_raw 字段。
     */
    private fun mergeDelta(
        current: DiffSystem.State?,
        changes: JSONObject,
        payload: JSONObject,
    ): DiffSystem.State {
        val base = current ?: DiffSystem.State(null, null, null, mutableMapOf())
        val title = if (changes.has("title")) changes.optString("title").takeIf { it.isNotEmpty() } else base.title
        val text = if (changes.has("text")) changes.optString("text").takeIf { it.isNotEmpty() } else base.text
        val p2 =
            if (changes.has("param_v2_raw")) {
                changes.optString("param_v2_raw").takeIf { it.isNotEmpty() }
            } else {
                payload.optString("raw", "").takeIf { it.isNotEmpty() }
                    ?: payload.optString("param_v2_raw", "").takeIf { it.isNotEmpty() }
                    ?: base.paramV2Raw
            }
        val pics = base.pics.toMutableMap()
        changes.optJSONObject("pics")?.let { picsJson ->
            val it = picsJson.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = picsJson.optString(k)
                if (!v.isNullOrEmpty()) pics[k] = v
            }
        }
        changes.optJSONArray("pics_removed")?.let { removed ->
            for (i in 0 until removed.length()) {
                removed.optString(i).takeIf { it.isNotEmpty() }?.let { pics.remove(it) }
            }
        }
        return DiffSystem.State(title, text, p2, pics)
    }
}
