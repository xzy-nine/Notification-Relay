package com.xzyht.notifyrelay.feature.superisland

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 超级岛差异化同步协议与工具。
 * 目标：
 * - 提供稳定的“特征键名/键值”（si_feature_id），用于标识同一座“岛”的一次会话。
 * - 支持首包全量(SI_FULL)、后续差异包(SI_DELTA)、结束包(SI_END)。
 * - 为每个发送包计算hash，供接收端回执确认。
 */
object SuperIslandProtocol {
    const val FEATURE_KEY_NAME = "si_feature_id"
    const val TYPE_FULL = "SI_FULL"
    const val TYPE_DELTA = "SI_DELTA"
    const val TYPE_END = "SI_END"
    const val TERMINATE_VALUE = "__END__"

    data class State(
        val title: String?,
        val text: String?,
        val paramV2Raw: String?,
        val pics: Map<String, String>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("title", title ?: "")
            put("text", text ?: "")
            if (!paramV2Raw.isNullOrBlank()) put("param_v2_raw", paramV2Raw)
            if (pics.isNotEmpty()) put("pics", JSONObject(pics))
        }
    }

    /**
     * 计算“岛”的特征ID。尽量使用paramV2Raw中稳定字段；若不可得，则退回 title/text。
     * 注意：该ID必须对“同一座岛的一次会话”保持稳定，对不同会话应不同。
     * 为了区分相同内容的通知，加入时间戳确保唯一性。
     */
    /**
     * 计算“岛”的特征ID。
     * - 基于 paramV2/title/text 等稳定内容字段生成特征。
     * - 如果提供了 instanceId（例如接收端的 sbnKey），会把它包含进特征以确保同内容的不同通知能被区分。
     */
    fun computeFeatureId(
        superPkg: String?,
        paramV2Raw: String?,
        title: String?,
        text: String?,
        instanceId: String? = null
    ): String {
        val keyParts = mutableListOf<String>()
        keyParts += (superPkg ?: "")
        // 解析param_v2的稳定字段（如chatInfo.title 或 baseInfo.title/content）
        try {
            if (!paramV2Raw.isNullOrBlank()) {
                val root = JSONObject(paramV2Raw)
                val chatInfo = root.optJSONObject("chatInfo")
                val baseInfo = root.optJSONObject("baseInfo")
                val highlight = root.optJSONObject("highlightInfo")
                when {
                    chatInfo != null -> {
                        val t = chatInfo.optString("title").takeIf { it.isNotBlank() }
                        if (!t.isNullOrBlank()) keyParts += "chat:" + t
                    }
                    baseInfo != null -> {
                        val t = baseInfo.optString("title").takeIf { it.isNotBlank() }
                        val c = baseInfo.optString("content").takeIf { it.isNotBlank() }
                        if (!t.isNullOrBlank()) keyParts += "baseT:" + t
                        if (!c.isNullOrBlank()) keyParts += "baseC:" + c
                    }
                    highlight != null -> {
                        val t = highlight.optString("title").takeIf { it.isNotBlank() }
                        if (!t.isNullOrBlank()) keyParts += "hi:" + t
                    }
                }
            }
        } catch (_: Exception) {}
        if (keyParts.size <= 1) {
            if (!title.isNullOrBlank()) keyParts += ("t:" + title)
            if (!text.isNullOrBlank()) keyParts += ("c:" + text)
        }
        // 如果提供了 instanceId，则把它加入特征，确保能区分同内容的不同实例
        if (!instanceId.isNullOrBlank()) {
            keyParts += "id:" + instanceId
        }
        // 使用SHA-1生成稳定短ID
        val raw = keyParts.joinToString("|")
        return sha1(raw)
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { b -> ((b.toInt() and 0xFF).toString(16)).padStart(2, '0') }
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { b -> ((b.toInt() and 0xFF).toString(16)).padStart(2, '0') }
    }

    data class Diff(
        val title: String? = null,
        val text: String? = null,
        val paramV2Raw: String? = null,
        val picsChanged: Map<String, String> = emptyMap(),
        val picsRemoved: List<String> = emptyList()
    ) {
        fun isEmpty(): Boolean =
            title == null && text == null && paramV2Raw == null && picsChanged.isEmpty() && picsRemoved.isEmpty()

        fun toJson(): JSONObject = JSONObject().apply {
            if (title != null) put("title", title)
            if (text != null) put("text", text)
            if (paramV2Raw != null) put("param_v2_raw", paramV2Raw)
            if (picsChanged.isNotEmpty()) put("pics", JSONObject(picsChanged))
            if (picsRemoved.isNotEmpty()) put("pics_removed", org.json.JSONArray(picsRemoved))
        }
    }

    fun diff(old: State?, new: State): Diff {
        if (old == null) return Diff(
            title = new.title,
            text = new.text,
            paramV2Raw = new.paramV2Raw,
            picsChanged = new.pics
        )
        var t: String? = null
        var c: String? = null
        var p2: String? = null
        if ((old.title ?: "") != (new.title ?: "")) t = new.title ?: ""
        if ((old.text ?: "") != (new.text ?: "")) c = new.text ?: ""
        val oldP2 = old.paramV2Raw ?: ""
        val newP2 = new.paramV2Raw ?: ""
        if (oldP2 != newP2) p2 = new.paramV2Raw
        val changed = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()
        // 变更/新增
        for ((k, v) in new.pics) {
            val ov = old.pics[k]
            if (ov == null || ov != v) changed[k] = v
        }
        // 删除
        for (k in old.pics.keys) {
            if (!new.pics.containsKey(k)) removed += k
        }
        return Diff(t, c, p2, changed, removed)
    }

    fun buildFullPayload(
        superPkg: String,
        appName: String?,
        time: Long,
        isLocked: Boolean,
        featureId: String,
        state: State
    ): JSONObject {
        val obj = JSONObject().apply {
            put("packageName", superPkg)
            put("appName", appName ?: superPkg)
            put("time", time)
            put("isLocked", isLocked)
            put("type", TYPE_FULL)
            put("featureKeyName", FEATURE_KEY_NAME)
            put("featureKeyValue", featureId)
        }
        val data = state.toJson()
        // 合并字段
        for (k in data.keys()) {
            obj.put(k, data.get(k))
        }
        obj.put("hash", sha256(obj.toString()))
        return obj
    }

    fun buildDeltaPayload(
        superPkg: String,
        appName: String?,
        time: Long,
        isLocked: Boolean,
        featureId: String,
        diff: Diff
    ): JSONObject {
        val obj = JSONObject().apply {
            put("packageName", superPkg)
            put("appName", appName ?: superPkg)
            put("time", time)
            put("isLocked", isLocked)
            put("type", TYPE_DELTA)
            put("featureKeyName", FEATURE_KEY_NAME)
            put("featureKeyValue", featureId)
            put("changes", diff.toJson())
        }
        obj.put("hash", sha256(obj.toString()))
        return obj
    }

    fun buildEndPayload(
        superPkg: String,
        appName: String?,
        time: Long,
        isLocked: Boolean,
        featureId: String
    ): JSONObject {
        val obj = JSONObject().apply {
            put("packageName", superPkg)
            put("appName", appName ?: superPkg)
            put("time", time)
            put("isLocked", isLocked)
            put("type", TYPE_END)
            put("featureKeyName", FEATURE_KEY_NAME)
            put("featureKeyValue", featureId)
            put("terminateValue", TERMINATE_VALUE)
        }
        obj.put("hash", sha256(obj.toString()))
        return obj
    }
}
