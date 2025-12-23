package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import java.util.concurrent.ConcurrentHashMap

/**
 * 浮窗条目管理器，负责管理所有浮窗条目的状态
 */
@Stable
class FloatingWindowManager {
    
    // 条目映射，与原有entries保持一致，记录添加时间
    private val entriesMap = ConcurrentHashMap<String, EntryWithTimestamp>()
    
    // 条目列表，用于Compose渲染
    val entriesList = mutableStateListOf<FloatingEntry>()
    
    // 记录条目的内部数据类
    private data class EntryWithTimestamp(
        val entry: FloatingEntry,
        val timestamp: Long
    )
    
    // 获取条目
    fun getEntry(key: String): FloatingEntry? {
        return entriesMap[key]?.entry
    }
    
    /**
     * 添加或更新浮窗条目
     */
    fun addOrUpdateEntry(
        key: String,
        paramV2: ParamV2?,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        isExpanded: Boolean = false,
        summaryOnly: Boolean = false,
        business: String?
    ) {
        val entry = FloatingEntry(
            key = key,
            paramV2 = paramV2,
            paramV2Raw = paramV2Raw,
            picMap = picMap,
            isExpanded = isExpanded,
            summaryOnly = summaryOnly,
            business = business
        )
        
        // 记录添加/更新时间
        entriesMap[key] = EntryWithTimestamp(
            entry = entry,
            timestamp = System.currentTimeMillis()
        )
        updateEntriesList()
    }
    
    /**
     * 移除浮窗条目
     */
    fun removeEntry(key: String) {
        entriesMap.remove(key)
        updateEntriesList()
    }
    
    /**
     * 切换条目展开/收起状态
     */
    fun toggleEntryExpanded(key: String) {
        val entryWithTimestamp = entriesMap[key]
        if (entryWithTimestamp != null) {
            val updatedEntry = entryWithTimestamp.entry.copy(isExpanded = !entryWithTimestamp.entry.isExpanded)
            entriesMap[key] = entryWithTimestamp.copy(entry = updatedEntry)
            updateEntriesList()
        }
    }
    
    /**
     * 设置条目展开状态
     */
    fun setEntryExpanded(key: String, isExpanded: Boolean) {
        val entryWithTimestamp = entriesMap[key]
        if (entryWithTimestamp != null) {
            val updatedEntry = entryWithTimestamp.entry.copy(isExpanded = isExpanded)
            entriesMap[key] = entryWithTimestamp.copy(entry = updatedEntry)
            updateEntriesList()
        }
    }
    
    /**
     * 更新条目列表，确保顺序正确（最新的在顶部）
     */
    private fun updateEntriesList() {
        // 清空列表
        entriesList.clear()
        // 按时间戳倒序排序，最新的在顶部
        val sortedEntries = entriesMap.values
            .sortedByDescending { it.timestamp }
            .map { it.entry }
        // 添加到列表
        entriesList.addAll(sortedEntries)
    }
    
    /**
     * 获取条目数量
     */
    fun getEntryCount(): Int {
        return entriesMap.size
    }
    
    /**
     * 清空所有条目
     */
    fun clearAllEntries() {
        entriesMap.clear()
        entriesList.clear()
    }
}
