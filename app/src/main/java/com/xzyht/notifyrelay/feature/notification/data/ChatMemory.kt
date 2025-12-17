package com.xzyht.notifyrelay.feature.notification.data

import android.content.Context

/**
 * 聊天记忆管理类
 * 聊天历史是未UI转化的通知数据的json结构，不需要单独的数据维护
 * 仅在内存中存储，应用重启后会丢失
 */
object ChatMemory {
    private var memory: MutableList<String>? = null
    private const val MAX_MEMORY_SIZE = 1000 // 最多保存1000条记录

    /**
     * 获取聊天历史记录
     */
    fun getChatHistory(context: Context): List<String> {
        if (memory == null) {
            memory = mutableListOf()
        }
        return memory!!
    }

    /**
     * 添加聊天记录
     */
    fun append(context: Context, msg: String) {
        val list = getChatHistory(context).toMutableList()
        list.add(msg)
        // 限制历史记录数量，避免内存占用过大
        if (list.size > MAX_MEMORY_SIZE) {
            memory = list.subList(list.size - MAX_MEMORY_SIZE, list.size)
        } else {
            memory = list
        }
    }

    /**
     * 清除聊天记忆
     */
    fun clear(context: Context) {
        memory = mutableListOf()
    }

    /**
     * 清除所有聊天记忆，并释放内存缓存。
     * 通常用于设置页里的“清空对话记录”按钮。
     */
    fun clearAll(context: Context) {
        memory = null
    }
}
