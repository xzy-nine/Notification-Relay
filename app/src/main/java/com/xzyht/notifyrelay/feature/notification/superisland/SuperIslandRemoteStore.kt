package com.xzyht.notifyrelay.feature.notification.superisland

import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import org.json.JSONObject

/**
 * 接收端超级岛远端状态存储与差异合并。
 * key使用 sourceId（通常为 "superisland:pkg|featureId"）。
 */
object SuperIslandRemoteStore {
    private val store = mutableMapOf<String, SuperIslandProtocol.State>()

    @Synchronized
    fun applyIncoming(sourceId: String, payload: JSONObject): SuperIslandProtocol.State? {
        // 兼容：不再依赖 payload 内的 type/featureKey 字段，改为根据字段自动推断
        return try {
            // 结束包标识：存在 terminateValue 且等于约定值
            val term = payload.optString("terminateValue", "")
            if (term == SuperIslandProtocol.TERMINATE_VALUE) {
                store.remove(sourceId)
                return null
            }

            // 差异包标识：存在 changes 字段
            if (payload.has("changes")) {
                val old = store[sourceId]
                val merged = applyDelta(old, payload.optJSONObject("changes"))
                if (merged != null) store[sourceId] = merged
                return merged
            }

            // 其它视为全量包（兼容旧包以及首包）
            val state = parseStateFromFull(payload)
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
    fun removeByDeviceAndPkgPrefix(deviceUuid: String, mappedPkg: String): List<String> {
        return try {
            val prefix = listOf(deviceUuid, mappedPkg).joinToString("|")
            val toRemove = store.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { store.remove(it) }
            toRemove
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 根据 featureKey（特征 ID）后缀查找并移除匹配的 sourceId，返回被移除的 sourceId 列表。
     * 兼容只传入 featureKey 的结束包（例如仅包含 featureKeyValue），用于定位完整的 sourceId。
     */
    @Synchronized
    fun removeByFeatureKey(featureKey: String): List<String> {
        return try {
            val suffix = "|$featureKey"
            val toRemove = store.keys.filter { it.endsWith(suffix) || it == featureKey }
            toRemove.forEach { store.remove(it) }
            toRemove
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 精确移除指定的 sourceId（如果存在），返回是否成功移除。
     */
    @Synchronized
    fun removeExact(sourceId: String): Boolean {
        return try {
            store.remove(sourceId) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取指定sourceId的状态，用于外部查询当前状态
     */
    @Synchronized
    fun getState(sourceId: String): SuperIslandProtocol.State? {
        return try {
            store[sourceId]
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStateFromFull(obj: JSONObject): SuperIslandProtocol.State {
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
        return SuperIslandProtocol.State(title, text, p2, picsMap)
    }

    private fun applyDelta(old: SuperIslandProtocol.State?, diffObj: JSONObject?): SuperIslandProtocol.State? {
        if (diffObj == null) return old
        var title = old?.title
        var text = old?.text
        var p2 = old?.paramV2Raw
        val pics = old?.pics?.toMutableMap() ?: mutableMapOf()

        if (diffObj.has("title")) title = diffObj.optString("title", "")
        if (diffObj.has("text")) text = diffObj.optString("text", "")
        if (diffObj.has("param_v2_raw")) p2 = diffObj.optString("param_v2_raw", "")

        val picsChanged = diffObj.optJSONObject("pics")
        if (picsChanged != null) {
            val it = picsChanged.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = picsChanged.optString(k)
                if (!v.isNullOrEmpty()) pics[k] = v
            }
        }
        val removed = diffObj.optJSONArray("pics_removed")
        if (removed != null) {
            for (i in 0 until removed.length()) {
                val k = removed.optString(i)
                if (!k.isNullOrEmpty()) pics.remove(k)
            }
        }
        return SuperIslandProtocol.State(title, text, p2, pics)
    }
}
