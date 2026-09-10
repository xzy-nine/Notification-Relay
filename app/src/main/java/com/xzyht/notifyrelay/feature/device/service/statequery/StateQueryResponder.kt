package com.xzyht.notifyrelay.feature.device.service.statequery

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.xzyht.notifyrelay.feature.media.service.MediaSessionMonitorService
import com.xzyht.notifyrelay.feature.notification.service.NotifyRelayNotificationListenerService
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.sync.MessageSender
import github.xzynine.superislandui.common.SuperIslandManager
import notifyrelay.base.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Rust 心跳「状态查询」的响应器。
 *
 * 差分/合并/ACK 与保活全部由 Rust 合并引擎完成（`nrc_push_*_state`），
 * 平台端只负责：按 featureId 找到对应会话 → 组装 full JSON → 以 `isQuery=1` 推回。
 *
 * 返回码约定（必须同步返回，无法异步）：
 * - `0` 平台上不存在该会话；
 * - `1` 存在且无变更（保活，等待下次查询）；
 * - `2` 存在且有变更（已推送）。
 *
 * 性能：先用「轻量比较键」判断内容是否变化，未变则跳过昂贵的 `runBlocking` / fullJson 构造与推送。
 *
 * 注意：本类方法运行在 Rust 心跳线程且锁已释放，回调内可直接调用 `push`。
 */
class StateQueryResponder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "CoreCb"

        /** 与 Rust MEDIA_KEY 一致 */
        private const val MEDIA_FEATURE_ID = "media_global"
        private const val MAX_CACHE_ENTRIES = 200
    }

    // 轻量比较键缓存：键 = "$remoteUuid|$featureId"，值 = 轻量内容键
    private val stateQueryKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var cachedMediaBitmap: Bitmap? = null
    private var cachedMediaCoverUrl: String? = null
    private val mediaCoverCacheLock = Any()

    /** 会话结束时清除该 featureId 的比较键。 */
    fun removeKey(
        remoteUuid: String,
        featureId: String,
    ) {
        stateQueryKeys.remove("$remoteUuid|$featureId")
    }

    /** 设备被移除时清除其全部比较键。 */
    fun removeKeysForDevice(uuid: String) {
        stateQueryKeys.keys.removeIf { it.startsWith("$uuid|") }
    }

    /**
     * 处理一次状态查询回调。
     *
     * @param isMedia true=媒体会话查询，false=超级岛查询
     * @return 0=不存在 / 1=存在无变更 / 2=存在有变更
     */
    fun handle(
        remoteUuid: String,
        featureId: String,
        isMedia: Boolean,
    ): Int =
        try {
            if (isMedia) {
                handleMediaQuery(remoteUuid, featureId)
            } else {
                handleSuperIslandQuery(remoteUuid, featureId)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "on_state_query error: ${e.message}")
            1 // 异常保守保活，等待下一次查询
        }

    private fun trimKeys(remoteUuid: String) {
        if (stateQueryKeys.size <= MAX_CACHE_ENTRIES) return
        stateQueryKeys.keys.removeIf { !it.startsWith("$remoteUuid|") }
        if (stateQueryKeys.size > MAX_CACHE_ENTRIES) stateQueryKeys.clear()
    }

    /** 超级岛查询：扫描活跃通知，重算 featureId（与 Rust 算法一致，iid 传空）匹配后对比推送 */
    private fun handleSuperIslandQuery(
        remoteUuid: String,
        featureId: String,
    ): Int {
        val listener = NotifyRelayNotificationListenerService.instance ?: return 0
        val actives = listener.activeNotifications ?: return 0
        for (sbn in actives) {
            if (sbn.packageName == context.packageName) continue
            val superData =
                try {
                    SuperIslandManager.extractSuperIslandData(sbn, context)
                } catch (_: Exception) {
                    null
                } ?: continue
            val superPkg = superData.sourcePackage ?: continue
            // 重算 featureId：与 Rust 会话 key 及发送端严格一致（sbn.key 稳定值）
            val computedId = listener.getNotificationKey(sbn, "")
            if (computedId != featureId) continue
            // 轻量比较键（不含图片资源）：未变化时跳过昂贵的 runBlocking/fullJson 构造与推送
            val lightKey = "$superPkg|${superData.appName}|${superData.title}|${superData.text}|${sbn.postTime}|${superData.paramV2Raw}"
            val cacheKey = "$remoteUuid|$featureId"
            if (stateQueryKeys[cacheKey] == lightKey) return 1 // 存在无变更，保活等待下次查询
            // 匹配：组装 full（与 sendSuperIslandData 同格式）并对比推送。
            // 该回调运行在 Rust 心跳线程且必须同步返回 0/1/2，无法异步处理；
            // 查询路径的 picMap 来自已提取的活跃通知（多为 data URI/http，无 IO），
            // 仅本地 file/content URI 时才会发生实际文件读取，故在此包装 runBlocking 是必要且可接受的。
            val content =
                kotlinx.coroutines.runBlocking {
                    MessageSender.buildSuperIslandFullContent(
                        context,
                        superPkg,
                        superData.appName ?: "超级岛",
                        superData.title,
                        superData.text,
                        sbn.postTime,
                        superData.paramV2Raw,
                        superData.picMap ?: emptyMap(),
                        featureId,
                    )
                }
            // 仅当原生入队成功才写入比较键并返回“有变更”，否则保留原键以便下次查询重试
            if (!pushState(remoteUuid, featureId, content, isMedia = false)) return 1
            trimKeys(remoteUuid)
            stateQueryKeys[cacheKey] = lightKey
            return 2
        }
        // 无匹配：平台上不存在该会话
        Logger.w(TAG, "状态查询: 超级岛会话不存在, fid=$featureId")
        return 0
    }

    /** 媒体查询：读取当前媒体会话组装 full 并对比推送；无媒体会话返回 0 */
    private fun handleMediaQuery(
        remoteUuid: String,
        featureId: String,
    ): Int {
        if (featureId != MEDIA_FEATURE_ID) return 0
        val monitor = MediaSessionMonitorService.instance ?: return 0
        val primary = monitor.getPrimaryController() ?: return 0
        val pkg = primary.packageName
        val mediaData =
            NotifyRelayNotificationListenerService.getMediaSessionData(pkg)
                ?: return 1 // 有媒体会话但数据未就绪，保守保活等待下次查询
        val coverUrl =
            synchronized(mediaCoverCacheLock) {
                val bmp = mediaData.artBitmap
                if (bmp !== cachedMediaBitmap) {
                    cachedMediaBitmap = bmp
                    cachedMediaCoverUrl =
                        if (bmp == null) {
                            null
                        } else {
                            try {
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            } catch (_: Exception) {
                                null
                            }
                        }
                }
                cachedMediaCoverUrl
            }
        // 轻量比较键：仅标题/艺术家/封面变化才重新构造 fullJson 并推送（isPlaying 由 Rust 媒体心跳处理）
        val lightKey = "$pkg|${mediaData.title}|${mediaData.artist}|$coverUrl"
        val cacheKey = "$remoteUuid|$featureId"
        if (stateQueryKeys[cacheKey] == lightKey) return 1 // 存在无变更，保活等待下次查询
        val content =
            MessageSender.buildMediaFullContent(
                context,
                pkg,
                pkg,
                mediaData.title,
                mediaData.artist,
                coverUrl,
                System.currentTimeMillis(),
            )
        // 仅当原生入队成功才写入比较键并返回“有变更”，否则保留原键以便下次查询重试
        if (!pushState(remoteUuid, featureId, content, isMedia = true)) return 1
        trimKeys(remoteUuid)
        stateQueryKeys[cacheKey] = lightKey
        return 2
    }

    /**
     * 查询响应推送：仅推给查询对应的远端设备（isQuery=1），差异/合并/保活由 Rust 负责。
     *
     * @return true=原生入队成功；false=上下文/队列不可用、入队失败或异常（调用方可重试）
     */
    private fun pushState(
        remoteUuid: String,
        featureId: String,
        fullJson: String,
        isMedia: Boolean,
    ): Boolean =
        try {
            val ctx = NativeCore.getContext() ?: return false
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return false
            val ok =
                if (isMedia) {
                    NativeCore.pushMediaState(ctx, queuePtr, remoteUuid, fullJson, false, true)
                } else {
                    NativeCore.pushSuperislandState(ctx, queuePtr, remoteUuid, fullJson, false, true)
                }
            if (!ok) Logger.w(TAG, "查询响应入队失败: $remoteUuid fid=$featureId")
            ok
        } catch (e: Exception) {
            Logger.w(TAG, "查询响应推送失败: $remoteUuid fid=$featureId", e)
            false
        }
}
