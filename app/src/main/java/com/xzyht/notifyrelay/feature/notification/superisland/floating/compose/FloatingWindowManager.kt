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
    
    // 条目映射，与原有entries保持一致
    private val entriesMap = ConcurrentHashMap<String, FloatingEntry>()
    
    // 条目列表，用于Compose渲染
    val entriesList = mutableStateListOf<FloatingEntry>()
    
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
        
        entriesMap[key] = entry
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
        val entry = entriesMap[key]
        if (entry != null) {
            entriesMap[key] = entry.copy(isExpanded = !entry.isExpanded)
            updateEntriesList()
        }
    }
    
    /**
     * 设置条目展开状态
     */
    fun setEntryExpanded(key: String, isExpanded: Boolean) {
        val entry = entriesMap[key]
        if (entry != null) {
            entriesMap[key] = entry.copy(isExpanded = isExpanded)
            updateEntriesList()
        }
    }
    
    /**
     * 更新条目列表，确保顺序正确
     */
    private fun updateEntriesList() {
        // 清空列表
        entriesList.clear()
        // TODO 添加所有条目（最新的在顶部，这里暂时按插入顺序）
        entriesList.addAll(entriesMap.values)
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
