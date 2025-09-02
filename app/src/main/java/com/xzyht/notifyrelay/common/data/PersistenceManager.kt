package com.xzyht.notifyrelay.common.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 持久化管理器，负责文件存储的统一管理
 */
object PersistenceManager {

    private val gson = Gson()

    // 聊天记忆文件
    private const val CHAT_MEMORY_FILE = "chat_memory.json"

    // 通知记录文件前缀
    private const val NOTIFICATION_RECORDS_PREFIX = "notification_records_"
    private const val NOTIFICATION_RECORDS_SUFFIX = ".json"

    // 获取聊天记忆
    fun getChatHistory(context: Context): List<String> {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("PersistenceManager", "Failed to read chat memory: ${e.message}")
            emptyList()
        }
    }

    // 保存聊天记忆
    fun saveChatHistory(context: Context, history: List<String>) {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        try {
            file.writeText(gson.toJson(history))
        } catch (e: Exception) {
            android.util.Log.e("PersistenceManager", "Failed to save chat memory: ${e.message}")
        }
    }

    // 追加聊天消息
    fun appendChatMessage(context: Context, message: String) {
        val history = getChatHistory(context).toMutableList()
        history.add(message)
        saveChatHistory(context, history)
    }

    // 清空聊天记忆
    fun clearChatHistory(context: Context) {
        saveChatHistory(context, emptyList())
    }

    // 获取通知记录文件
    private fun getNotificationFile(context: Context, device: String): File {
        val safeDevice = if (device == "本机") "local" else device.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return File(context.filesDir, "$NOTIFICATION_RECORDS_PREFIX${safeDevice}$NOTIFICATION_RECORDS_SUFFIX")
    }

    // 读取通知记录
    fun <T> readNotificationRecords(context: Context, device: String, typeToken: TypeToken<List<T>>): List<T> {
        val file = getNotificationFile(context, device)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, typeToken.type) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("PersistenceManager", "Failed to read notification records for device $device: ${e.message}")
            // 尝试删除损坏的文件
            try {
                file.delete()
            } catch (ex: Exception) {
                android.util.Log.e("PersistenceManager", "Failed to delete corrupted file: ${ex.message}")
            }
            emptyList()
        }
    }

    // 保存通知记录
    fun <T> saveNotificationRecords(context: Context, device: String, records: List<T>) {
        val file = getNotificationFile(context, device)
        try {
            file.writeText(gson.toJson(records))
        } catch (e: Exception) {
            android.util.Log.e("PersistenceManager", "Failed to save notification records for device $device: ${e.message}")
            throw e
        }
    }

    // 清空通知记录
    fun clearNotificationRecords(context: Context, device: String) {
        saveNotificationRecords(context, device, emptyList<Any>())
    }

    // 获取所有通知记录文件
    fun getAllNotificationFiles(context: Context): List<File> {
        return context.filesDir.listFiles()?.filter {
            it.name.startsWith(NOTIFICATION_RECORDS_PREFIX) && it.name.endsWith(NOTIFICATION_RECORDS_SUFFIX)
        } ?: emptyList()
    }

    // 删除通知记录文件
    fun deleteNotificationFile(context: Context, device: String) {
        val file = getNotificationFile(context, device)
        try {
            file.delete()
        } catch (e: Exception) {
            android.util.Log.e("PersistenceManager", "Failed to delete notification file for device $device: ${e.message}")
        }
    }
}
