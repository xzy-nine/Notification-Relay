package com.xzyht.notifyrelay.feature.notification.data

import android.content.Context
import com.google.gson.Gson
import com.xzyht.notifyrelay.common.data.PersistenceManager

object ChatMemory {
    private var memory: MutableList<String>? = null

    fun getChatHistory(context: Context): List<String> {
        if (memory != null) return memory!!
        memory = PersistenceManager.getChatHistory(context).toMutableList()
        return memory!!
    }

    fun append(context: Context, msg: String) {
        val list = getChatHistory(context).toMutableList()
        list.add(msg)
        memory = list
        PersistenceManager.saveChatHistory(context, list)
    }

    fun clear(context: Context) {
        memory = mutableListOf()
        PersistenceManager.clearChatHistory(context)
    }
}
