package com.xzyht.notifyrelay.common.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.BuildConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 持久化管理器。负责应用内与设备相关的数据持久化读写。*/
object PersistenceManager {

    /** Gson 实例，用于对象与 JSON 间的序列化/反序列化。 */
    private val gson = Gson()

    /** 聊天记忆文件名（存放在 Context.filesDir 下） */
    private const val CHAT_MEMORY_FILE = "chat_memory.json"

    /** 通知记录文件名前缀（实际文件名格式为：前缀 + 安全化设备名 + 后缀） */
    private const val NOTIFICATION_RECORDS_PREFIX = "notification_records_"

    /** 通知记录文件名的后缀（JSON 文件） */
    private const val NOTIFICATION_RECORDS_SUFFIX = ".json"

    /**
     * 写入任务的数据类。
     *
     * @param T 写入记录的类型。通常为数据类或 Map/Json 对象。
     * @property context 用于访问文件系统的 Context（以 filesDir 为根）。
     * @property device 目标设备标识（会被安全化为文件名的一部分）。
     * @property records 要序列化并写入磁盘的记录列表。
     * @property callback 可选回调：写入成功时 success=true、失败时 success=false 且附带异常。
     */
    private data class WriteTask<T>(
        val context: Context,
        val device: String,
        val records: List<T>,
        val callback: ((Boolean, Exception?) -> Unit)? = null
    )

    /**
     * 写入队列：使用有界的阻塞队列避免 OOM 或无限增长。
     * 队列容量为 1000，超过时会回退到同步写入策略。
     */
    private val writeQueue = LinkedBlockingQueue<WriteTask<*>>(1000)

    /** 当前是否有任务正在处理（仅用于调试/状态展示）。 */
    private val isProcessing = AtomicBoolean(false)

    /**
     * 后台写入线程：从队列中取出写任务并调用 [processWriteTask] 执行。
     * 以守护线程启动，确保在应用进程退出时不会阻塞 JVM 关闭。
     */
    private val writeThread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = writeQueue.take() // 阻塞等待任务
                processWriteTask(task)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i("PersistenceManager", "写入线程已中断")
                break
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "写入线程异常: ${e.message}")
            }
        }
    }.apply {
        name = "PersistenceManager-WriteThread"
        isDaemon = true
        start()
    }

    /**
     * 处理单个写入任务。
     *
     * 实现细节：
     * - 使用原子写入策略：先写到临时文件（同目录下，扩展名 ".tmp"），再重命名为目标文件。
     * - 写入成功/失败会通过 task.callback 回调通知调用方（如果有）。
     * - 捕获异常并在调试模式下记录日志。
     *
     * @param T 记录类型参数
     * @param task 要处理的写入任务
     */
    private fun <T> processWriteTask(task: WriteTask<T>) {
        try {
            val file = getNotificationFile(task.context, task.device)
            // 使用原子写入：先写到临时文件，然后重命名
            val tempFile = File(file.parent, "${file.name}.tmp")
            tempFile.writeText(gson.toJson(task.records))
            // 原子移动
            if (tempFile.renameTo(file)) {
                if (BuildConfig.DEBUG) Log.d("PersistenceManager", "已成功保存设备 ${task.device} 的通知记录，条数=${task.records.size}")
                task.callback?.invoke(true, null)
            } else {
                val error = Exception("无法将临时文件重命名为目标文件")
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "保存设备 ${task.device} 的通知记录失败: ${error.message}")
                task.callback?.invoke(false, error)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "保存设备 ${task.device} 的通知记录时出错: ${e.message}")
            task.callback?.invoke(false, e)
        }
    }

    /**
     * 获取聊天记忆（按顺序返回消息字符串列表）。
     *
     * 如果文件不存在返回空列表；读取或反序列化失败时也返回空列表并在调试模式下记录错误。
     *
     * @param context 用于访问 filesDir 的 Context
     * @return 聊天消息字符串列表（最新插入在末尾）
     */
    fun getChatHistory(context: Context): List<String> {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "读取聊天记忆失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 保存聊天记忆到磁盘（覆盖写入）。
     *
     * @param context 用于访问 filesDir 的 Context
     * @param history 要保存的消息列表
     */
    fun saveChatHistory(context: Context, history: List<String>) {
        val file = File(context.filesDir, CHAT_MEMORY_FILE)
        try {
            file.writeText(gson.toJson(history))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "保存聊天记忆失败: ${e.message}")
        }
    }

    /**
     * 追加一条聊天消息到记忆中（从磁盘读取、追加、再写回）。
     *
     * 注意：此方法会进行磁盘读写，频繁调用可能影响性能。
     *
     * @param context 用于访问 filesDir 的 Context
     * @param message 要追加的消息文本
     */
    fun appendChatMessage(context: Context, message: String) {
        val history = getChatHistory(context).toMutableList()
        history.add(message)
        saveChatHistory(context, history)
    }

    /**
     * 清空聊天记忆（等效于保存空列表）。
     *
     * @param context 用于访问 filesDir 的 Context
     */
    fun clearChatHistory(context: Context) {
        saveChatHistory(context, emptyList())
    }

    /**
     * 根据设备标识构造用于存储通知记录的文件对象。
     *
     * 设备标识会被安全化为文件名：如果设备为中文描述的“本机”，用 "local" 替换；
     * 其他字符会被替换为下划线以避免文件名非法字符。
     *
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识字符串
     * @return 对应的 File 对象（文件可能不存在）
     */
    private fun getNotificationFile(context: Context, device: String): File {
        val safeDevice = if (device == "本机") "local" else device.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return File(context.filesDir, "$NOTIFICATION_RECORDS_PREFIX${safeDevice}$NOTIFICATION_RECORDS_SUFFIX")
    }

    /**
     * 读取指定设备的通知记录并反序列化为给定类型的列表。
     *
     * 如果文件不存在或反序列化失败会返回空列表；反序列化失败时会尝试删除损坏的文件以避免反复出错。
     *
     * @param T 目标记录类型
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识
     * @param typeToken 用于 Gson 的类型标记（示例： object : TypeToken<List<MyRecord>>() {} ）
     * @return 指定类型的记录列表，出错时返回空列表
     */
    fun <T> readNotificationRecords(context: Context, device: String, typeToken: TypeToken<List<T>>): List<T> {
        val file = getNotificationFile(context, device)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, typeToken.type) ?: emptyList()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "读取设备 $device 的通知记录失败: ${e.message}")
            // 直接删除损坏的文件
            try {
                file.delete()
                if (BuildConfig.DEBUG) Log.w("PersistenceManager", "已删除设备 $device 的损坏通知文件")
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) Log.e("PersistenceManager", "删除损坏文件失败: ${ex.message}")
            }
            emptyList()
        }
    }

    /**
     * 将通知记录保存到磁盘，使用异步队列写入以降低主线程阻塞。
     *
     * 如果队列已满会退化为同步执行（立即在当前线程内写入并记录警告）。
     *
     * @param T 记录类型
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识
     * @param records 要保存的记录列表
     */
    fun <T> saveNotificationRecords(context: Context, device: String, records: List<T>) {
        val task = WriteTask(context, device, records)
        if (!writeQueue.offer(task)) {
            // 队列已满，直接执行（避免阻塞）
            if (BuildConfig.DEBUG) Log.w("PersistenceManager", "写入队列已满，改为同步执行，设备=$device")
            processWriteTask(task)
        }
    }

    /**
     * 同步保存通知记录：此方法会阻塞调用线程直到写入完成或异常抛出。
     *
     * 实现策略：创建一个等待线程将任务放入队列并通过回调标记完成，然后等待该线程 join。
     * 如果写入回调中返回异常，会将该异常重新抛出到调用方。
     *
     * 注意：该实现会阻塞当前线程，请勿在主线程频繁调用。
     *
     * @param T 记录类型
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识
     * @param records 要保存的记录列表
     * @throws Exception 写入过程中发生的异常
     */
    fun <T> saveNotificationRecordsSync(context: Context, device: String, records: List<T>) {
        val result = kotlin.concurrent.thread(start = false) {
            var completed = false
            var exception: Exception? = null
            val task = WriteTask(context, device, records) { _, ex ->
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

    /**
     * 清空指定设备的通知记录（等效于保存空列表）。
     *
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识
     */
    fun clearNotificationRecords(context: Context, device: String) {
        saveNotificationRecords(context, device, emptyList<Any>())
    }

    /**
     * 获取所有存储的通知记录文件（基于文件名前缀和后缀过滤）。
     *
     * @param context 用于访问 filesDir 的 Context
     * @return 匹配前缀/后缀的文件列表，若目录或文件不存在则返回空列表
     */
    fun getAllNotificationFiles(context: Context): List<File> {
        return context.filesDir.listFiles()?.filter {
            it.name.startsWith(NOTIFICATION_RECORDS_PREFIX) && it.name.endsWith(NOTIFICATION_RECORDS_SUFFIX)
        } ?: emptyList()
    }

    /**
     * 等待写入队列清空或达到超时（通常在应用关闭前调用以尽量确保数据已落盘）。
     *
     * @param timeoutMs 最大等待时间（毫秒），默认 5000 ms
     * @return 如果在超时前队列已空则返回 true，否则返回 false
     */
    fun waitForAllWrites(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (writeQueue.isNotEmpty() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            Thread.sleep(10)
        }
        return writeQueue.isEmpty()
    }

    /**
     * 删除指定设备的通知记录文件（如果存在）。
     *
     * @param context 用于访问 filesDir 的 Context
     * @param device 设备标识
     */
    fun deleteNotificationFile(context: Context, device: String) {
        val file = getNotificationFile(context, device)
        try {
            file.delete()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("PersistenceManager", "删除设备 $device 通知文件失败: ${e.message}")
        }
    }

    /**
     * 获取写入队列的当前状态（用于调试展示）。
     *
     * @return 描述队列大小与是否正在处理的简短字符串
     */
    fun getQueueStatus(): String {
        return "队列大小: ${writeQueue.size}, 正在处理: ${isProcessing.get()}"
    }
}

