package com.xzyht.notifyrelay.feature.notification.superisland.notification

/**
 * 超级岛通知列表管理器。
 * 在平板列表模式下维护所有待切换的通知条目，管理激活切换和 media 优先级。
 */
object SuperIslandListManager {
    data class ListEntry(
        val sourceId: String,
        val title: String?,
        val text: String?,
        val paramV2Raw: String?,
        val picMap: Map<String, String>?,
        val appName: String?,
        val isLocked: Boolean,
        val isMedia: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /** 有序列表。media 条目在前，普通条目在后 */
    private val entries = mutableListOf<ListEntry>()

    /** 当前激活条目的索引，-1 表示无激活 */
    private var activeIndex: Int = -1

    /** 本轮是否用户手动切换过。true 时 media 不自动抢占激活 */
    private var manuallyChanged: Boolean = false

    /**
     * 添加或更新条目。
     * @return true 表示激活条目变更，调用方需同步更新系统通知
     */
    @Synchronized
    fun addOrUpdate(entry: ListEntry): Boolean {
        val existingIndex = entries.indexOfFirst { it.sourceId == entry.sourceId }
        val oldActiveSourceId = getActive()?.sourceId

        if (existingIndex >= 0) {
            val oldEntry = entries[existingIndex]
            val mediaChanged = oldEntry.isMedia != entry.isMedia
            entries[existingIndex] = entry
            if (mediaChanged) {
                reorderAndUpdateActive(existingIndex, entry)
            }
        } else {
            insertSorted(entry)
        }

        val newActiveSourceId = getActive()?.sourceId
        return oldActiveSourceId != newActiveSourceId
    }

    private fun insertSorted(entry: ListEntry) {
        val insertIdx =
            if (entry.isMedia) {
                val firstNonMedia = entries.indexOfFirst { !it.isMedia }
                if (firstNonMedia < 0) entries.size else firstNonMedia
            } else {
                entries.size
            }
        entries.add(insertIdx, entry)
        if (activeIndex < 0) {
            activeIndex = insertIdx
            manuallyChanged = false
        } else if (!manuallyChanged && entry.isMedia) {
            activeIndex = insertIdx
            manuallyChanged = false
        } else {
            if (insertIdx <= activeIndex) activeIndex++
        }
    }

    private fun reorderAndUpdateActive(
        oldIndex: Int,
        entry: ListEntry,
    ) {
        entries.removeAt(oldIndex)
        if (oldIndex < activeIndex) {
            activeIndex--
        } else if (oldIndex == activeIndex) {
            activeIndex = -1
        }
        insertSorted(entry)
    }

    /**
     * 移除指定 sourceId 的条目。
     * 如果移除的是当前激活条目，自动切换到下一个。
     * @return 新的激活条目，列表为空则返回 null
     */
    @Synchronized
    fun remove(sourceId: String): ListEntry? {
        val idx = entries.indexOfFirst { it.sourceId == sourceId }
        if (idx < 0) {
            val active = getActive()
            return active
        }

        val wasActive = idx == activeIndex
        entries.removeAt(idx)
        if (idx < activeIndex) {
            activeIndex--
        } else if (wasActive) {
            activeIndex = if (entries.isEmpty()) -1 else activeIndex.coerceAtMost(entries.size - 1)
        }

        if (entries.isEmpty()) {
            manuallyChanged = false
        }
        val result = getActive()
        return result
    }

    /**
     * 切换到列表中下一条条目。
     * 从当前往后找，到末尾后回到开头，跳过自身。
     * 设置 manuallyChanged = true。
     * @return 新的激活条目，如果列表只有当前条目则返回 null
     */
    @Synchronized
    fun switchNext(): ListEntry? {
        if (entries.size <= 1) return null

        val start = (activeIndex + 1) % entries.size
        activeIndex = start
        manuallyChanged = true
        return entries[start]
    }

    /** 获取当前激活条目 */
    @Synchronized
    fun getActive(): ListEntry? = if (activeIndex in entries.indices) entries[activeIndex] else null

    /** 清空所有 */
    @Synchronized
    fun clear() {
        entries.clear()
        activeIndex = -1
        manuallyChanged = false
    }

    /** 指定 sourceId 是否在列表中 */
    @Synchronized
    fun containsSourceId(sourceId: String): Boolean = entries.any { it.sourceId == sourceId }

    /** 列表是否为空 */
    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** 条目数量 */
    @Synchronized
    fun size(): Int = entries.size
}
