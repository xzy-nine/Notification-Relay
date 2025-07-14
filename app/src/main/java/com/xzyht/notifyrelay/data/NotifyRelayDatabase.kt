package com.xzyht.notifyrelay.data

import com.xzyht.notifyrelay.data.NotificationRecordStore

import android.content.Context

object NotifyRelayStoreProvider {
    @Volatile
    private var INSTANCE: NotificationRecordStore? = null

    fun getInstance(context: Context): NotificationRecordStore {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: NotificationRecordStore(context.applicationContext).also { INSTANCE = it }
        }
    }
}
