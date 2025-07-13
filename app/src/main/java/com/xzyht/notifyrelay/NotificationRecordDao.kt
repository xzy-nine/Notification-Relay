package com.xzyht.notifyrelay

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class NotificationRecordStore(private val context: Context) {
    private val gson = Gson()
    private val file: File by lazy {
        File(context.filesDir, "notification_records.json")
    }

    private fun readAll(): MutableList<NotificationRecordEntity> {
        if (!file.exists()) return mutableListOf()
        val json = file.readText()
        return gson.fromJson(json, object : TypeToken<MutableList<NotificationRecordEntity>>() {}.type) ?: mutableListOf()
    }

    private fun writeAll(list: List<NotificationRecordEntity>) {
        file.writeText(gson.toJson(list))
    }

    suspend fun insert(record: NotificationRecordEntity) {
        val list = readAll()
        list.removeAll { it.key == record.key }
        list.add(0, record)
        writeAll(list)
    }

    suspend fun getAll(): List<NotificationRecordEntity> {
        return readAll().sortedByDescending { it.time }
    }

    suspend fun deleteByKey(key: String) {
        val list = readAll()
        list.removeAll { it.key == key }
        writeAll(list)
    }

    suspend fun clearByDevice(device: String) {
        val list = readAll()
        list.removeAll { it.device == device }
        writeAll(list)
    }
}
