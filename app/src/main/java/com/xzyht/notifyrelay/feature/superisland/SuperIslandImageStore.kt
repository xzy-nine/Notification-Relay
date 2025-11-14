package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 超级岛图片去重存储（内存 + 简单文件持久化）。
 *
 * 存储策略：将每个图片字符串（data URL 或 HTTP URL）计算 sha256，存为一次性原文并在历史记录里使用 `ref:<sha256>` 作为引用。
 * 渲染时通过 `resolve` 将引用还原为原始字符串。
 */
object SuperIslandImageStore {
    private const val IMAGE_STORE_FILE = "super_island_images.json"
    private val gson = Gson()
    private val lock = Any()

    // hash -> original value
    private val map = mutableMapOf<String, String>()
    // hash -> last seen millis（用于按时间/最近使用回收）
    private val lastSeen = mutableMapOf<String, Long>()
    // 可选的上次引用计数（仅用于 GC 参考/调试）
    private val refCounts = mutableMapOf<String, Int>()
    @Volatile
    private var initialized = false

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            try {
                val file = File(context.filesDir, IMAGE_STORE_FILE)
                if (file.exists()) {
                    val raw = file.readText(Charsets.UTF_8)
                    try {
                        // 先尝试解析为 wrapper { images: {...}, lastSeen: {...} }
                        val wrapperType = object : TypeToken<Map<String, Any>>() {}.type
                        val wrapper: Map<String, Any>? = gson.fromJson(raw, wrapperType)
                        if (wrapper != null && wrapper.containsKey("images")) {
                            val imagesJson = gson.toJson(wrapper["images"])
                            val imagesType = object : TypeToken<Map<String, String>>() {}.type
                            val loadedImages: Map<String, String> = gson.fromJson(imagesJson, imagesType) ?: emptyMap()
                            map.clear(); map.putAll(loadedImages)
                            // load lastSeen if present
                            val lastSeenObj = wrapper["lastSeen"]
                            if (lastSeenObj != null) {
                                val lastSeenJson = gson.toJson(lastSeenObj)
                                val lastSeenType = object : TypeToken<Map<String, Long>>() {}.type
                                val loadedLastSeen: Map<String, Long> = gson.fromJson(lastSeenJson, lastSeenType) ?: emptyMap()
                                lastSeen.clear(); lastSeen.putAll(loadedLastSeen)
                            }
                        } else {
                            // fallback: plain map
                            val type = object : TypeToken<Map<String, String>>() {}.type
                            val loaded: Map<String, String> = gson.fromJson(raw, type) ?: emptyMap()
                            map.clear(); map.putAll(loaded)
                        }
                    } catch (_: Exception) {
                        // best-effort fallback to plain map
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        val loaded: Map<String, String> = gson.fromJson(file.readText(Charsets.UTF_8), type) ?: emptyMap()
                        map.clear(); map.putAll(loaded)
                    }
                }
            } catch (_: Exception) {}
            initialized = true
        }
    }

    private fun persist(context: Context) {
        try {
            val file = File(context.filesDir, IMAGE_STORE_FILE)
            file.parentFile?.mkdirs()
            // 持久化为包含 images 与 meta 的对象以便保存 lastSeen 信息
            val wrapper = mapOf(
                "images" to map,
                "lastSeen" to lastSeen
            )
            file.writeText(gson.toJson(wrapper), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /**
     * 使用历史记录重建引用计数并删除未被引用的图片（一次性回收）。
     * 实现策略：
     * - 遍历历史记录中的所有 picMap 值；
     * - 对于 raw 值（非 ref:），把它 intern 到 store 中（确保在 map 中有原文）；
     * - 统计每个 hash 的引用次数，移除引用计数为0的 map 条目，并持久化。
     *
     * 该方法为“最后引用计数更新式”：先扫描整个历史，更新映射，再清理未被引用的项。
     */
    fun rebuildRefCountsAndPrune(context: Context, historyEntries: List<com.xzyht.notifyrelay.feature.superisland.SuperIslandHistoryEntry>) {
        ensureLoaded(context)
        synchronized(lock) {
            refCounts.clear()
            // ensure all raw values are interned and count refs
            for (entry in historyEntries) {
                val picMap = entry.picMap
                for ((_, v) in picMap) {
                    if (v.isNullOrEmpty()) continue
                    val hash = if (v.startsWith("ref:")) v.removePrefix("ref:") else SuperIslandProtocol.sha256(v)
                    // if raw and not present in map, add original
                    if (!map.containsKey(hash)) {
                        if (!v.startsWith("ref:")) map[hash] = v
                    }
                    refCounts[hash] = (refCounts[hash] ?: 0) + 1
                }
            }

            // remove unreferenced keys
            val toRemove = map.keys.filter { !refCounts.containsKey(it) }
            for (k in toRemove) map.remove(k)
            persist(context)
        }
    }

    /**
     * 获取当前引用计数快照（只供调试/展示）。
     */
    fun getRefCountsSnapshot(): Map<String, Int> {
        synchronized(lock) {
            return refCounts.toMap()
        }
    }

    fun internAll(context: Context, input: Map<String, String>?): Map<String, String> {
        if (input.isNullOrEmpty()) return emptyMap()
        ensureLoaded(context)
        val out = mutableMapOf<String, String>()
        synchronized(lock) {
            for ((k, v) in input) {
                if (v.isNullOrEmpty()) continue
                out[k] = internLocked(context, v)
            }
        }
        return out
    }

    fun intern(context: Context, value: String): String {
        ensureLoaded(context)
        synchronized(lock) {
            return internLocked(context, value)
        }
    }

    private fun internLocked(context: Context, value: String): String {
        if (value.startsWith("ref:") || value.startsWith("REF:")) return value
        val hash = SuperIslandProtocol.sha256(value)
        if (map.containsKey(hash)) return "ref:$hash"
        map[hash] = value
        lastSeen[hash] = System.currentTimeMillis()
        persist(context)
        return "ref:$hash"
    }

    fun resolve(context: Context, refOrValue: String?): String? {
        if (refOrValue == null) return null
        ensureLoaded(context)
        if (!refOrValue.startsWith("ref:")) return refOrValue
        val hash = refOrValue.removePrefix("ref:")
        return map[hash] ?: refOrValue
    }

    fun getRaw(context: Context, hash: String): String? {
        ensureLoaded(context)
        return map[hash]
    }

    /**
     * 清理策略：按时间与最大条目限制清除未被引用的图片记录。
     * @param maxEntries 当映射条目数超过该值时触发按 lastSeen 淘汰
     * @param maxAgeDays 若某条目最后一次见到时间早于该天数且引用计数为0，则可被清理
     */
    fun prune(context: Context, maxEntries: Int = 2000, maxAgeDays: Int = 30) {
        ensureLoaded(context)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val ageThreshold = now - maxAgeDays * 24L * 3600L * 1000L
            // 先移除 refCounts 为0 且超时的项
            val toRemove = mutableListOf<String>()
            for ((k, _) in map) {
                val rc = refCounts[k] ?: 0
                val seen = lastSeen[k] ?: 0L
                if (rc <= 0 && seen < ageThreshold) toRemove += k
            }
            for (k in toRemove) {
                map.remove(k)
                lastSeen.remove(k)
                refCounts.remove(k)
            }

            // 若仍然超出最大条目数，则按 lastSeen 最旧优先移除（仅移除 refCounts==0 的项）
            if (map.size > maxEntries) {
                val candidates = map.keys.filter { (refCounts[it] ?: 0) <= 0 }
                val sorted = candidates.sortedBy { lastSeen[it] ?: 0L }
                var idx = 0
                while (map.size > maxEntries && idx < sorted.size) {
                    val k = sorted[idx++]
                    map.remove(k); lastSeen.remove(k); refCounts.remove(k)
                }
            }

            persist(context)
        }
    }
}
