package com.xzyht.notifyrelay.feature.notification.superisland.replica

import android.content.Context
import android.os.Build
import android.view.View
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingEntry
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowManager
import com.xzyht.notifyrelay.feature.notification.superisland.notification.LiveUpdatesNotificationManager
import kotlinx.coroutines.Job
import notifyrelay.base.util.Logger
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.iterator

object FloatingReplicaMappingManager {
    private const val TAG = "超级岛映射管理"

    private var appContextRef: Context? = null

    fun setAppContext(context: Context?) {
        appContextRef = context?.applicationContext
    }

    fun getAppContext(): Context? = appContextRef

    private val sourceIdToEntryKeyMap = ConcurrentHashMap<String, MutableSet<String>>()

    private val entryKeyToNotificationId = ConcurrentHashMap<String, Int>()

    private val sourceIdToNotificationIds = ConcurrentHashMap<String, MutableSet<Int>>()

    private val closedSourceIds = ConcurrentHashMap<String, Long>()
    private val closedSourceVersions = ConcurrentHashMap<String, Long>()

    private val timeoutJobs = ConcurrentHashMap<String, Job>()

    private val hiddenEntries = ConcurrentHashMap<String, FloatingEntry>()

    private val sourceVersions = ConcurrentHashMap<String, AtomicLong>()

    private val blockedInstanceIds = ConcurrentHashMap<String, Long>()
    private const val BLOCK_EXPIRE_MS = 15_000L

    // 上次成功发出的系统通知内容指纹（sourceId → 指纹）。
    // 保活包内容无变更时跳过 notify()，仅重置内部撤回计时器；通知撤回时同步清理，保证撤回后会重新发出
    private val lastNotificationFingerprints = ConcurrentHashMap<String, String>()

    private var overlayViewRef: WeakReference<View>? = null

    fun setOverlayView(view: View?) {
        overlayViewRef = if (view != null) WeakReference(view) else null
    }

    fun addSourceIdMapping(
        sourceId: String,
        entryKey: String,
        notificationId: Int? = null,
    ) {
        if (sourceId.isNotBlank()) {
            sourceIdToEntryKeyMap.computeIfAbsent(sourceId) { ConcurrentHashMap.newKeySet() }.add(entryKey)
            if (notificationId != null) {
                sourceIdToNotificationIds.computeIfAbsent(sourceId) { ConcurrentHashMap.newKeySet() }.add(notificationId)
            }
        }
    }

    fun removeSourceIdMapping(key: String): List<String>? {
        val sourceIdsToRemove = mutableListOf<String>()

        sourceIdToEntryKeyMap.forEach { (sourceId, keys) ->
            if (keys.contains(key)) {
                sourceIdToEntryKeyMap.compute(sourceId) { _, currentKeys ->
                    if (currentKeys != null) {
                        currentKeys.remove(key)
                        if (currentKeys.isEmpty()) {
                            sourceIdsToRemove.add(sourceId)
                            null
                        } else {
                            currentKeys
                        }
                    } else {
                        null
                    }
                }
            }
        }

        sourceIdsToRemove.forEach {
            sourceIdToNotificationIds.remove(it)
            lastNotificationFingerprints.remove(it)
        }

        return if (sourceIdsToRemove.isNotEmpty()) {
            Logger.i(TAG, "removeSourceIdMapping: 成功移除 sourceIds=$sourceIdsToRemove, key=$key")
            sourceIdsToRemove
        } else {
            Logger.i(TAG, "removeSourceIdMapping: 未找到匹配的 sourceId，key=$key")
            null
        }
    }

    fun getSourceIdEntryKeys(sourceId: String): List<String>? = sourceIdToEntryKeyMap[sourceId]?.toList()

    fun getNotificationId(entryKey: String): Int? = entryKeyToNotificationId[entryKey]

    fun putNotificationId(
        entryKey: String,
        notificationId: Int,
    ) {
        entryKeyToNotificationId[entryKey] = notificationId
    }

    fun removeNotificationId(entryKey: String): Int? = entryKeyToNotificationId.remove(entryKey)

    fun getAllNotificationIds(): Map<String, Int> = HashMap(entryKeyToNotificationId)

    fun clearAllNotificationIds() {
        entryKeyToNotificationId.clear()
    }

    fun getNotificationIdsBySourceId(sourceId: String): List<Int>? = sourceIdToNotificationIds[sourceId]?.toList()

    fun removeNotificationIdsBySourceId(sourceId: String): List<Int>? {
        lastNotificationFingerprints.remove(sourceId)
        return sourceIdToNotificationIds.remove(sourceId)?.toList()
    }

    fun removeSourceIdMappings(sourceId: String) {
        sourceIdToNotificationIds.remove(sourceId)
        val entryKeys = sourceIdToEntryKeyMap.remove(sourceId)
        entryKeys?.forEach { entryKey ->
            entryKeyToNotificationId.remove(entryKey)
        }
        sourceVersions.remove(sourceId)
        lastNotificationFingerprints.remove(sourceId)
    }

    /**
     * 计算通知内容指纹：标题 + 正文 + paramV2Raw + 图片映射。
     * 用于判断保活包是否与上次成功发出的系统通知内容一致
     */
    fun computeNotificationFingerprint(
        title: String?,
        text: String?,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
    ): String {
        val pics =
            picMap
                ?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { "${it.key}=${it.value}" }
                .orEmpty()
        val input = title.orEmpty() + "\u0001" + text.orEmpty() + "\u0001" + paramV2Raw.orEmpty() + "\u0001" + pics
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getNotificationFingerprint(sourceId: String): String? = lastNotificationFingerprints[sourceId]

    fun setNotificationFingerprint(
        sourceId: String,
        fingerprint: String,
    ) {
        lastNotificationFingerprints[sourceId] = fingerprint
    }

    fun removeNotificationFingerprint(sourceId: String) {
        lastNotificationFingerprints.remove(sourceId)
    }

    fun clearAllNotificationFingerprints() {
        lastNotificationFingerprints.clear()
    }

    fun nextVersion(sourceId: String): Long = sourceVersions.computeIfAbsent(sourceId) { AtomicLong(0) }.incrementAndGet()

    fun isLatestVersion(
        sourceId: String,
        version: Long,
    ): Boolean = sourceVersions[sourceId]?.get() == version

    fun handleRemovalReason(
        sourceId: String,
        reason: FloatingWindowManager.RemovalReason,
    ) {
        if (reason == FloatingWindowManager.RemovalReason.MANUAL || reason == FloatingWindowManager.RemovalReason.HIDDEN) {
            blockInstance(sourceId)
        }

        if (reason != FloatingWindowManager.RemovalReason.HIDDEN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            runWithErrorHandling("关闭Live Updates复合通知") {
                val context = overlayViewRef?.get()?.context
                if (context != null) {
                    LiveUpdatesNotificationManager.initialize(context)
                    LiveUpdatesNotificationManager.dismissLiveUpdateNotification(sourceId)
                    Logger.i(TAG, "关闭Live Updates复合通知: sourceId=$sourceId")
                } else {
                    Logger.w(TAG, "无法关闭Live Updates复合通知，上下文为空")
                }
            }
        }
    }

    fun isInstanceBlocked(instanceId: String?): Boolean {
        if (instanceId.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val ts = blockedInstanceIds[instanceId] ?: return false
        if (now - ts > BLOCK_EXPIRE_MS) {
            blockedInstanceIds.remove(instanceId)
            Logger.i(TAG, "超级岛: 屏蔽过期，自动移除 instanceId=$instanceId")
            return false
        }
        blockedInstanceIds[instanceId] = now
        return true
    }

    fun blockInstance(instanceId: String?) {
        if (instanceId.isNullOrBlank()) return
        blockedInstanceIds[instanceId] = System.currentTimeMillis()
        Logger.i(TAG, "超级岛: 会话级屏蔽 instanceId=$instanceId")
    }

    fun removeBlockedInstance(instanceId: String) {
        blockedInstanceIds.remove(instanceId)
    }

    fun isSourceRecentlyClosed(sourceId: String): Boolean {
        val lastClosed = closedSourceIds[sourceId]
        return lastClosed != null && (System.currentTimeMillis() - lastClosed) < 30_000L
    }

    fun markSourceClosed(sourceId: String) {
        closedSourceIds[sourceId] = System.currentTimeMillis()
        sourceVersions[sourceId]?.get()?.let { closedSourceVersions[sourceId] = it }
    }

    fun removeClosedSource(sourceId: String) {
        closedSourceIds.remove(sourceId)
    }

    fun isSourceRecentlyClosedWithinMinute(sourceId: String): Boolean {
        val lastClosed = closedSourceIds[sourceId] ?: return false
        if (System.currentTimeMillis() - lastClosed >= 60_000L) return false
        val closedVersion = closedSourceVersions[sourceId] ?: return false
        val currentVersion = sourceVersions[sourceId]?.get() ?: return false
        return currentVersion == closedVersion
    }

    fun cancelTimeoutJob(sourceId: String) {
        timeoutJobs.remove(sourceId)?.cancel()
    }

    fun setTimeoutJob(
        sourceId: String,
        job: Job,
    ) {
        timeoutJobs[sourceId] = job
    }

    fun saveHiddenEntry(
        sourceId: String,
        entry: FloatingEntry,
    ) {
        hiddenEntries[sourceId] = entry
    }

    fun getHiddenEntry(sourceId: String): FloatingEntry? = hiddenEntries[sourceId]

    fun removeHiddenEntry(sourceId: String) {
        hiddenEntries.remove(sourceId)
    }

    fun getEntryKeyByNotificationId(notificationId: Int): String? = entryKeyToNotificationId.entries.find { it.value == notificationId }?.key

    fun findSourceIdByNotificationId(notificationId: Int): String? {
        for ((sourceId, notificationIds) in sourceIdToNotificationIds) {
            if (notificationIds.contains(notificationId)) {
                return sourceId
            }
        }
        val entryKey = getEntryKeyByNotificationId(notificationId)
        if (entryKey != null) {
            for ((sourceId, keys) in sourceIdToEntryKeyMap) {
                if (keys.contains(entryKey)) {
                    return sourceId
                }
            }
        }
        return null
    }

    fun findSourceIdByEntryKey(entryKey: String): String? {
        for ((sourceId, keys) in sourceIdToEntryKeyMap) {
            if (keys.contains(entryKey)) {
                return sourceId
            }
        }
        return null
    }

    private inline fun runWithErrorHandling(
        actionName: String,
        crossinline block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            Logger.w(TAG, "超级岛: $actionName 失败: ${e.message}")
        }
    }
}
