package com.xzyht.notifyrelay.feature.notification.superisland

import org.json.JSONObject

/**
 * 接收端超级岛远端状态存储与差异合并。
 * key使用 sourceId（通常为 "superisland:pkg|featureId"）。
 */
object SuperIslandRemoteStore {
    private val store = mutableMapOf<String, SuperIslandProtocol.State>()

    @Synchronized
    fun applyIncoming(sourceId: String, payload: JSONObject): SuperIslandProtocol.State? {
        val type = payload.optString("type", SuperIslandProtocol.TYPE_FULL)
        return when (type) {
            SuperIslandProtocol.TYPE_FULL -> {
                val state = parseStateFromFull(payload)
                store[sourceId] = state
                state
            }
            SuperIslandProtocol.TYPE_DELTA -> {
                val old = store[sourceId]
                val merged = applyDelta(old, payload.optJSONObject("changes"))
                if (merged != null) store[sourceId] = merged
                merged
            }
            SuperIslandProtocol.TYPE_END -> {
                store.remove(sourceId)
                null
            }
            else -> {
                // 兼容旧包：当无type时视为全量
                val state = parseStateFromFull(payload)
                store[sourceId] = state
                state
            }
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
