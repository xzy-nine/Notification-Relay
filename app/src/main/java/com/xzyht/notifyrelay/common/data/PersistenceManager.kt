package com.xzyht.notifyrelay.common.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.common.core.util.Logger
import java.io.File

/** 持久化管理器。负责应用内与设备相关的数据持久化读写。*/
object PersistenceManager {

    /** Gson 实例，用于对象与 JSON 间的序列化/反序列化。 */
    private val gson = Gson()

    /** 通知记录文件名前缀（实际文件名格式为：前缀 + 安全化设备名 + 后缀） */
    private const val NOTIFICATION_RECORDS_PREFIX = "notification_records_"

    /** 通知记录文件名的后缀（JSON 文件） */
    private const val NOTIFICATION_RECORDS_SUFFIX = ".json"

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
            file.reader(Charsets.UTF_8).use { reader ->
                @Suppress("UNCHECKED_CAST")
                val data = gson.fromJson<Any?>(reader, typeToken.type) as? List<T>
                data ?: emptyList()
            }
        } catch (e: Exception) {
            Logger.e("PersistenceManager", "读取设备 $device 的通知记录失败: ${e.message}")
            // 直接删除损坏的文件
            try {
                file.delete()
                Logger.w("PersistenceManager", "已删除设备 $device 的损坏通知文件")
            } catch (ex: Exception) {
                Logger.e("PersistenceManager", "删除损坏文件失败: ${ex.message}")
            }
            emptyList()
        }
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

}