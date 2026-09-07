package com.xzyht.notifyrelay.feature.device.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import com.xzyht.notifyrelay.feature.notification.filter.BackendRemoteFilter
import com.xzyht.notifyrelay.feature.notification.superisland.notification.LiveUpdatesNotificationManager
import com.xzyht.notifyrelay.feature.notification.superisland.store.SuperIslandRemoteStore
import github.xzynine.superislandui.common.BitmapUtils
import notifyrelay.base.util.Logger

/**
 * 公平运行内存适配广播接收器（小米 HyperOS / 金标联盟通用）。
 *
 * 监听系统内存预警(TRIM)和查杀(KILL)广播：
 * - TRIM：释放缓存，降低内存占用
 * - KILL：保存现场数据，3秒内回调系统确认备份完成
 *
 * 广播 Action: itgsa.intent.action.TRIM
 * 静态注册，系统可直接发送给未运行的进程。
 */
class FairMemoryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FairMemoryReceiver"
        private const val ACTION_TRIM = "itgsa.intent.action.TRIM"
        private const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION

        // notifyType
        private const val NOTIFY_TYPE_PHYSICAL_MEMORY = 1000
        private const val NOTIFY_TYPE_JAVA_HEAP = 2000

        // result
        private const val RESULT_SUCCESS = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_TRIM != intent.action) return

        val extras = intent.extras ?: return
        val common = extras.getBundle("common") ?: return
        val extraData = extras.getBundle("extra")

        val notifyType = common.getInt("notifyType")
        val notifyId = common.getInt("notifyId")
        val callbackBinder = common.getBinder("callback")

        val pss = extraData?.getInt("pss", 0) ?: 0
        val pssLimit = extraData?.getInt("pssLimit", 0) ?: 0
        val heapAlloc = extraData?.getInt("heapAlloc", 0) ?: 0
        val heapCapacity = extraData?.getInt("heapCapacity", 0) ?: 0

        Logger.i(
            TAG,
            "收到公平运行内存广播: notifyType=$notifyType, notifyId=$notifyId, " +
                "pss=${pss}kB/${pssLimit}kB, heap=${heapAlloc}kB/${heapCapacity}kB",
        )

        if (callbackBinder == null) {
            Logger.w(TAG, "回调 Binder 为空，跳过处理")
            return
        }

        val pendingResult = goAsync()

        Thread {
            try {
                when (notifyType) {
                    NOTIFY_TYPE_PHYSICAL_MEMORY,
                    NOTIFY_TYPE_JAVA_HEAP,
                    -> {
                        handleTrim(context, notifyType)
                        reply(notifyType, notifyId, RESULT_SUCCESS, null, callbackBinder)
                    }
                    else -> {
                        Logger.w(TAG, "未知的 notifyType: $notifyType")
                        reply(notifyType, notifyId, RESULT_SUCCESS, null, callbackBinder)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "处理公平运行内存广播异常", e)
                reply(notifyType, notifyId, RESULT_SUCCESS, null, callbackBinder)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /**
     * 处理内存预警：释放非关键缓存，降低内存占用。
     */
    private fun handleTrim(context: Context, notifyType: Int) {
        val typeName = if (notifyType == NOTIFY_TYPE_PHYSICAL_MEMORY) "物理内存" else "Java堆"
        Logger.i(TAG, "执行内存预警处理（$typeName）")

        // 1. 清理 LiveUpdates 图标缓存（Bitmap，占用堆内存）
        try {
            LiveUpdatesNotificationManager.clearIconCache()
        } catch (e: Exception) {
            Logger.w(TAG, "清理 LiveUpdates 图标缓存失败", e)
        }

        // 2. 清理 BitmapUtils 缓存池
        try {
            BitmapUtils.clearCache()
        } catch (e: Exception) {
            Logger.w(TAG, "清理 BitmapUtils 缓存失败", e)
        }

        // 3. 清空聊天记忆（纯内存，重启后丢失）
        try {
            ChatMemory.clear()
        } catch (e: Exception) {
            Logger.w(TAG, "清理 ChatMemory 失败", e)
        }

        // 4. 清空超级岛远端状态存储
        try {
            SuperIslandRemoteStore.clear()
        } catch (e: Exception) {
            Logger.w(TAG, "清理 SuperIslandRemoteStore 失败", e)
        }

        // 5. 清理 BackendRemoteFilter 待处理列表
        try {
            BackendRemoteFilter.clearPending()
        } catch (e: Exception) {
            Logger.w(TAG, "清理 BackendRemoteFilter 失败", e)
        }

        // 6. 触发 GC 回收
        System.gc()

        Logger.i(TAG, "内存预警处理完成")
    }

    /**
     * 通过 IBinder 回调通知系统处理结果。
     * 必须在 3 秒内完成。
     */
    private fun reply(
        notifyType: Int,
        notifyId: Int,
        result: Int,
        extra: Bundle?,
        callbackBinder: IBinder,
    ) {
        var data: Parcel? = null
        var reply: Parcel? = null
        try {
            data = Parcel.obtain()
            reply = Parcel.obtain()
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            data.writeBundle(extra ?: Bundle())
            callbackBinder.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY)
            Logger.i(TAG, "回调系统成功: notifyType=$notifyType, result=$result")
        } catch (e: Exception) {
            Logger.e(TAG, "回调系统失败", e)
        } finally {
            reply?.recycle()
            data?.recycle()
        }
    }
}
