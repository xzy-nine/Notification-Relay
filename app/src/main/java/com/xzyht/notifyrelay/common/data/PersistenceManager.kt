package com.xzyht.notifyrelay.common.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.BuildConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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

    // 写入队列相关
    private data class WriteTask<T>(
        val context: Context,
        val device: String,
        val records: List<T>,
        val callback: ((Boolean, Exception?) -> Unit)? = null
    )

    private val writeQueue = LinkedBlockingQueue<WriteTask<*>>(1000) // 容量限制为1000
    private val isProcessing = AtomicBoolean(false)
    private val writeThread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = writeQueue.take() // 阻塞等待任务
                processWriteTask(task)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i("PersistenceManager", "Write thread interrupted")
                break
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Error in write thread: ${e.message}")
            }
        }
    }.apply {
        name = "PersistenceManager-WriteThread"
        isDaemon = true
        start()
    }

    private fun <T> processWriteTask(task: WriteTask<T>) {
        try {
            val file = getNotificationFile(task.context, task.device)
            // 使用原子写入：先写到临时文件，然后重命名
            val tempFile = File(file.parent, "${file.name}.tmp")
            tempFile.writeText(gson.toJson(task.records))
            // 原子移动
            if (tempFile.renameTo(file)) {
                if (BuildConfig.DEBUG) Log.d("PersistenceManager", "Successfully saved notification records for device ${task.device}, size=${task.records.size}")
                task.callback?.invoke(true, null)
            } else {
                val error = Exception("Failed to rename temp file to target file")
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to save notification records for device ${task.device}: ${error.message}")
                task.callback?.invoke(false, error)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to save notification records for device ${task.device}: ${e.message}")
            task.callback?.invoke(false, e)
        }
    }

    // 获取聊天记忆
    fun getChatHistory(context: Context): List<String> {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to read chat memory: ${e.message}")
            emptyList()
        }
    }

    // 保存聊天记忆
    fun saveChatHistory(context: Context, history: List<String>) {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        try {
            file.writeText(gson.toJson(history))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to save chat memory: ${e.message}")
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
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to read notification records for device $device: ${e.message}")
            // 直接删除损坏的文件
            try {
                file.delete()
                if (BuildConfig.DEBUG) Log.w("PersistenceManager", "Deleted corrupted notification file for device $device")
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to delete corrupted file: ${ex.message}")
            }
            emptyList()
        }
    }

    // 保存通知记录（异步队列写入）
    fun <T> saveNotificationRecords(context: Context, device: String, records: List<T>) {
        val task = WriteTask(context, device, records)
        if (!writeQueue.offer(task)) {
            // 队列已满，直接执行（避免阻塞）
            if (BuildConfig.DEBUG) Log.w("PersistenceManager", "Write queue full, executing synchronously for device $device")
            processWriteTask(task)
        }
    }

    // 保存通知记录（同步等待）
    fun <T> saveNotificationRecordsSync(context: Context, device: String, records: List<T>) {
        val result = kotlin.concurrent.thread(start = false) {
            var completed = false
            var exception: Exception? = null
            val task = WriteTask(context, device, records) { success, ex ->
                completed = true
                exception = ex
            }
            writeQueue.put(task) // 阻塞放入队列
            while (!completed) {
                Thread.sleep(1) // 等待完成
            }
            if (exception != null) throw exception!!
        }
        result.start()
        result.join()
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

    // 等待所有写入任务完成（用于应用关闭时）
    fun waitForAllWrites(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (writeQueue.isNotEmpty() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            Thread.sleep(10)
        }
        return writeQueue.isEmpty()
    }

    // 删除通知记录文件
    fun deleteNotificationFile(context: Context, device: String) {
        val file = getNotificationFile(context, device)
        try {
            file.delete()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "Failed to delete notification file for device $device: ${e.message}")
        }
    }

    // 获取队列状态（调试用）
    fun getQueueStatus(): String {
        return "Queue size: ${writeQueue.size}, Processing: ${isProcessing.get()}"
    }
}

