package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper

/**
 * 浮窗条目管理器，负责管理所有浮窗条目的状态
 */
@Stable
class FloatingWindowManager {
    
    // 常量定义
    private val EXPANDED_DURATION_MS = 3000L // 展开态持续时间
    private val AUTO_DISMISS_DURATION_MS = 12000L // 自动移除时间
    
    // 用于处理延迟任务的Handler
    private val handler = Handler(Looper.getMainLooper())
    
    // 条目映射，与原有entries保持一致，记录添加时间
    private val entriesMap = ConcurrentHashMap<String, EntryWithTimestamp>()
    
    // 条目列表，用于Compose渲染
    val entriesList = mutableStateListOf<FloatingEntry>()
    
    // 记录条目的内部数据类
    private data class EntryWithTimestamp(
        val entry: FloatingEntry,
        val initialTimestamp: Long, // 初始创建时间，仅在首次添加时设置
        val timestamp: Long, // 更新时间，用于自动移除等逻辑
        var collapseRunnable: Runnable? = null,
        var removalRunnable: Runnable? = null
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
        business: String?,
        title: String? = null,
        text: String? = null,
        appName: String? = null
    ) {
        // 保留原有条目的isExpanded状态，其他属性使用新传入的值
        // 如果是摘要态，强制设为非展开状态
        val existingEntry = entriesMap[key]?.entry
        val finalIsExpanded = if (summaryOnly) {
            // 如果是摘要态，强制设为非展开状态
            false
        } else if (existingEntry != null) {
            // 如果条目已存在，保留原有展开状态
            existingEntry.isExpanded
        } else {
            // 新条目使用传入的状态
            isExpanded
        }
        
        // 创建新的FloatingEntry，确保所有属性都使用新传入的值，除了isExpanded状态
        val entry = FloatingEntry(
            key = key,
            paramV2 = paramV2,
            paramV2Raw = paramV2Raw,
            picMap = picMap,
            isExpanded = finalIsExpanded,
            summaryOnly = summaryOnly,
            business = business,
            title = title,
            text = text,
            appName = appName
        )
        
        // 取消之前的任务
        cancelAllTasks(key)
        
        // 获取当前时间
        val currentTime = System.currentTimeMillis()
        
        // 记录添加/更新时间
        val existingWithTimestamp = entriesMap[key]
        val entryWithTimestamp = if (existingWithTimestamp != null) {
            // 条目已存在，保留原有初始时间戳，只更新timestamp
            EntryWithTimestamp(
                entry = entry,
                initialTimestamp = existingWithTimestamp.initialTimestamp,
                timestamp = currentTime,
                collapseRunnable = null,
                removalRunnable = null
            )
        } else {
            // 新条目，初始时间戳和timestamp都设置为当前时间
            EntryWithTimestamp(
                entry = entry,
                initialTimestamp = currentTime,
                timestamp = currentTime,
                collapseRunnable = null,
                removalRunnable = null
            )
        }
        entriesMap[key] = entryWithTimestamp
        
        // 如果不是摘要态且处于展开状态，添加自动收起和自动移除任务
        if (!summaryOnly && finalIsExpanded) {
            scheduleCollapse(key, EXPANDED_DURATION_MS)
        }
        
        // 所有条目都添加自动移除任务
        scheduleRemoval(key, AUTO_DISMISS_DURATION_MS)
        
        updateEntriesList()
    }
    
    /**
     * 安排自动收起任务
     */
    private fun scheduleCollapse(key: String, delayMs: Long) {
        val entryWithTimestamp = entriesMap[key] ?: return
        
        val runnable = Runnable {
            val currentEntry = entriesMap[key]?.entry
            if (currentEntry != null && !currentEntry.summaryOnly && currentEntry.isExpanded) {
                // 切换到收起状态
                setEntryExpanded(key, false)
            }
        }
        
        entryWithTimestamp.collapseRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }
    
    /**
     * 安排自动移除任务
     */
    private fun scheduleRemoval(key: String, delayMs: Long) {
        val entryWithTimestamp = entriesMap[key] ?: return
        
        val runnable = Runnable {
            removeEntry(key)
        }
        
        entryWithTimestamp.removalRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }
    
    /**
     * 取消所有任务
     */
    private fun cancelAllTasks(key: String) {
        val entryWithTimestamp = entriesMap[key] ?: return
        
        // 取消自动收起任务
        entryWithTimestamp.collapseRunnable?.let {
            handler.removeCallbacks(it)
            entryWithTimestamp.collapseRunnable = null
        }
        
        // 取消自动移除任务
        entryWithTimestamp.removalRunnable?.let {
            handler.removeCallbacks(it)
            entryWithTimestamp.removalRunnable = null
        }
    }
    
    /**
     * 移除浮窗条目
     */
    fun removeEntry(key: String) {
        // 取消所有相关任务
        cancelAllTasks(key)
        entriesMap.remove(key)
        updateEntriesList()
    }
    
    /**
     * 切换条目展开/收起状态
     */
    fun toggleEntryExpanded(key: String) {
        val entryWithTimestamp = entriesMap[key]
        if (entryWithTimestamp != null) {
            val currentEntry = entryWithTimestamp.entry
            
            // 如果是摘要态，不允许切换展开状态
            if (currentEntry.summaryOnly) {
                return
            }
            
            val isExpanded = !currentEntry.isExpanded
            
            // 取消之前的任务
            cancelAllTasks(key)
            
            val updatedEntry = currentEntry.copy(isExpanded = isExpanded)
            // 保留初始时间戳，只更新entry和timestamp
            val updatedWithTimestamp = entryWithTimestamp.copy(
                entry = updatedEntry,
                timestamp = System.currentTimeMillis()
            )
            entriesMap[key] = updatedWithTimestamp
            
            // 如果切换到展开状态，添加自动收起和自动移除任务
            if (isExpanded) {
                scheduleCollapse(key, EXPANDED_DURATION_MS)
            }
            
            // 重新添加自动移除任务
            scheduleRemoval(key, AUTO_DISMISS_DURATION_MS)
            
            updateEntriesList()
        }
    }
    
    /**
     * 设置条目展开状态
     */
    fun setEntryExpanded(key: String, isExpanded: Boolean) {
        val entryWithTimestamp = entriesMap[key]
        if (entryWithTimestamp != null) {
            val currentEntry = entryWithTimestamp.entry
            
            // 如果是摘要态，不允许设置为展开状态
            val finalIsExpanded = if (currentEntry.summaryOnly) false else isExpanded
            
            // 取消之前的任务
            cancelAllTasks(key)
            
            val updatedEntry = currentEntry.copy(isExpanded = finalIsExpanded)
            // 保留初始时间戳，只更新entry和timestamp
            val updatedWithTimestamp = entryWithTimestamp.copy(
                entry = updatedEntry,
                timestamp = System.currentTimeMillis()
            )
            entriesMap[key] = updatedWithTimestamp
            
            // 如果切换到展开状态，添加自动收起任务
            if (finalIsExpanded) {
                scheduleCollapse(key, EXPANDED_DURATION_MS)
            }
            
            // 重新添加自动移除任务
            scheduleRemoval(key, AUTO_DISMISS_DURATION_MS)
            
            updateEntriesList()
        }
    }
    
    /**
     * 更新条目列表，确保顺序正确（最新的在底部）
     */
    private fun updateEntriesList() {
        // 清空列表
        entriesList.clear()
        // 按初始时间戳升序排序，最新的在底部
        val sortedEntries = entriesMap.values
            .sortedBy { it.initialTimestamp }
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
        // 取消所有任务
        entriesMap.keys.forEach {
            cancelAllTasks(it)
        }
        entriesMap.clear()
        entriesList.clear()
    }
}
