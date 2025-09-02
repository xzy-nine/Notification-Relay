package com.xzyht.notifyrelay.feature.notification.data

import android.content.Context
import com.google.gson.Gson
import java.io.File

object ChatMemory {
    private const val CHAT_FILE = "chat_memory.json"
    private var memory: MutableList<String>? = null

    fun getChatHistory(context: Context): List<String> {
        if (memory != null) return memory!!
        val file = File(context.filesDir, CHAT_FILE)
        if (file.exists()) {
            val json = file.readText()
            memory = Gson().fromJson(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        } else {
            memory = mutableListOf()
        }
        return memory!!
    }

    fun append(context: Context, msg: String) {
        val list = getChatHistory(context).toMutableList()
        list.add(msg)
        memory = list
        save(context)
    }

    fun clear(context: Context) {
        memory = mutableListOf()
        save(context)
    }

    private fun save(context: Context) {
        val file = File(context.filesDir, CHAT_FILE)
        file.writeText(Gson().toJson(memory))
    }
}
