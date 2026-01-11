package com.xzyht.notifyrelay.common.core.notification

import android.content.Context
import android.util.LruCache
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandRemoteStore
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistory
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistoryEntry

object SuperIslandProcessor {
    private const val TAG = "SuperIslandProcessor"
    private const val DEDUP_CACHE_MAX_SIZE = 1024
    private val superIslandDeduplicationCache = object : LruCache<String, Boolean>(DEDUP_CACHE_MAX_SIZE) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Boolean?, newValue: Boolean?) {
            if (evicted && key != null) {
                try { Logger.i("超级岛", "去重缓存被驱逐: $key") } catch (_: Exception) {}
            }
        }
    }

    fun process(
        context: Context,
        manager: DeviceConnectionManager,
        decrypted: String,
        sharedSecret: String?,
        remoteUuid: String?
    ): Boolean {
        try {
            if (remoteUuid == null) return false
            val json = org.json.JSONObject(decrypted)
            val pkg = json.optString("packageName")
            val appName = json.optString("appName")
            val title = json.optString("title")
            val text = json.optString("text")
            val time = json.optLong("time", System.currentTimeMillis())

            val installedPkgs = com.xzyht.notifyrelay.common.core.repository.AppRepository.getInstalledPackageNamesSync(context)
            val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(pkg.orEmpty(), installedPkgs)

            val siType = try { json.optString("type", "") } catch (_: Exception) { "" }

            // SI_ACK 属于超岛协议的确认包，仅用于可靠性确认，不应进入通知/聊天管线
            if (siType == "SI_ACK") {
                Logger.i("超级岛", "收到超级岛ACK: remoteUuid=$remoteUuid, pkg=$pkg, mappedPkg=$mappedPkg, hash=${try { json.optString("hash", "") } catch (_: Exception) { "" }}")
                return true
            }

            // 由于路由层已经按 DATA_SUPERISLAND 分发，这里不再依赖 JSON 内的 type/featureKey 字段。
            val isLocked = try { json.optBoolean("isLocked", false) } catch (_: Exception) { false }
            val paramV2Raw = try {
                val s = json.optString("param_v2_raw")
                if (s.isNullOrBlank()) null else s
            } catch (_: Exception) { null }
            // 优先使用显式传回的 featureKeyValue（若发送端已计算并包含），保证 full/delta/end 使用相同的 featureId
            val explicitFeatureKeyCandidate = try { json.optString("featureKeyValue", "") } catch (_: Exception) { "" }
            val featureId = if (!explicitFeatureKeyCandidate.isNullOrBlank()) {
                explicitFeatureKeyCandidate
            } else {
                try { SuperIslandProtocol.computeFeatureId(pkg, paramV2Raw, json.optString("title"), json.optString("text")) } catch (_: Exception) { "" }
            }
            val sourceKey = listOfNotNull(remoteUuid, mappedPkg, featureId.takeIf { it.isNotBlank() }).joinToString("|")
            val dedupKey = "${remoteUuid}|${mappedPkg}|${featureId}"

            // 结束包判断：存在 terminateValue 或者显式 featureKeyValue 且 terminateValue 标记
            val termVal = try { json.optString("terminateValue", "") } catch (_: Exception) { "" }
            val explicitFeatureKey = try { json.optString("featureKeyValue", "") } catch (_: Exception) { "" }
            val isEnd = (termVal == SuperIslandProtocol.TERMINATE_VALUE)
            if (isEnd) {
                try {
                    // 优先用显式的 featureKeyValue 进行 dismiss（若有）
                    if (!explicitFeatureKey.isNullOrBlank()) {
                        try {
                            // 如果显式值看起来像完整的 sourceId（包含分隔符），直接移除
                            if (explicitFeatureKey.contains("|")) {
                                FloatingReplicaManager.dismissBySource(explicitFeatureKey)
                                SuperIslandRemoteStore.removeExact(explicitFeatureKey)
                                superIslandDeduplicationCache.remove(dedupKey)
                                Logger.i("超级岛", "收到终止通知(显式完整 sourceId)，移除去重缓存: $dedupKey -> source=$explicitFeatureKey")
                                return true
                            }

                            // 否则将其视为 featureId 后缀，尝试按后缀查找并移除匹配的完整 sourceId
                            val matched = SuperIslandRemoteStore.removeByFeatureKey(explicitFeatureKey)
                            if (matched.isNotEmpty()) {
                                matched.forEach { rid ->
                                    try { FloatingReplicaManager.dismissBySource(rid) } catch (_: Exception) {}
                                    superIslandDeduplicationCache.remove("${remoteUuid}|${mappedPkg}|${rid.substringAfterLast("|")}")
                                    Logger.i("超级岛", "收到终止通知(显式 featureKey 匹配)，移除并关闭浮窗: $rid -> featureKey=$explicitFeatureKey")
                                }
                                return true
                            }
                            // 若未匹配到，再继续落到后续的前缀匹配/兜底逻辑
                        } catch (_: Exception) {}
                    }

                    // 如果没有显式 featureKey，尝试在远端存储中查找可能已存在的 sourceId（按 deviceUuid|mappedPkg 前缀匹配）
                    val removedKeys = SuperIslandRemoteStore.removeByDeviceAndPkgPrefix(remoteUuid, mappedPkg)
                    if (removedKeys.isNotEmpty()) {
                        removedKeys.forEach { rid ->
                            try { FloatingReplicaManager.dismissBySource(rid) } catch (_: Exception) {}
                            // 同步移除去重缓存（若存在）
                            superIslandDeduplicationCache.remove("${remoteUuid}|${mappedPkg}|${rid.substringAfterLast("|")}")
                            Logger.i("超级岛", "收到终止通知，按前缀移除并关闭浮窗: $rid")
                        }
                        return true
                    }

                    // 最后兜底：按照当前计算的 sourceKey 进行移除（可能无对应），以防漏掉
                    try { FloatingReplicaManager.dismissBySource(sourceKey) } catch (_: Exception) {}
                    superIslandDeduplicationCache.remove(dedupKey)
                    Logger.i("超级岛", "收到终止通知(兜底)，尝试移除: $sourceKey")
                    return true
                } catch (e: Exception) {
                    Logger.w("超级岛", "处理结束包时出错: ${e.message}")
                }
            }

            val mTitle = try { json.optString("title", title.orEmpty()) } catch (_: Exception) { title.orEmpty() }
            val mText = try { json.optString("text", text.orEmpty()) } catch (_: Exception) { text.orEmpty() }

            if (isLocked) {
                if (superIslandDeduplicationCache.get(dedupKey) != null) {
                    Logger.i("超级岛", "锁屏重复通知去重: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                    return true
                } else {
                    superIslandDeduplicationCache.put(dedupKey, true)
                    Logger.i("超级岛", "首次处理超级岛通知，添加到去重缓存: $dedupKey, title=${mTitle ?: "无标题"}")
                }
            } else {
                Logger.i("超级岛", "非锁屏状态，正常处理超级岛通知: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
            }

            val merged = SuperIslandRemoteStore.applyIncoming(sourceKey, json)

            val recvHash = try { json.optString("hash", "") } catch (_: Exception) { "" }
            if (!recvHash.isNullOrEmpty()) {
                try { manager.sendSuperIslandAckInternal(remoteUuid, sharedSecret, recvHash, featureId, mappedPkg) } catch (_: Exception) {}
            }

            if (merged != null) {
                val finalTitle = merged.title ?: mTitle
                val finalText = merged.text ?: mText
                val mParam2 = merged.paramV2Raw
                val mPics = merged.pics

                try {
                    // 仅在有实际可展示内容时才创建浮窗，避免只含基础元信息的空白浮窗
                    val hasContent = !finalTitle.isNullOrBlank() || !finalText.isNullOrBlank() || !mParam2.isNullOrBlank() || (mPics.isNotEmpty())
                    if (hasContent) {
                        FloatingReplicaManager.showFloating(context, sourceKey, finalTitle, finalText, mParam2, mPics, appName, isLocked)
                    } else {
                        Logger.i("超级岛", "收到内容为空的超级岛包，跳过创建浮窗: sourceKey=$sourceKey")
                    }
                } catch (e: Exception) {
                    Logger.w("超级岛", "差异复刻悬浮窗失败: ${e.message}")
                }

                val historyEntry = SuperIslandHistoryEntry(
                    id = System.currentTimeMillis(),
                    sourceDeviceUuid = remoteUuid,
                    originalPackage = pkg,
                    mappedPackage = mappedPkg,
                    appName = appName?.takeIf { it.isNotEmpty() },
                    title = finalTitle?.takeIf { it.isNotBlank() },
                    text = finalText?.takeIf { it.isNotBlank() },
                    paramV2Raw = mParam2?.takeIf { it.isNotBlank() },
                    picMap = mPics.toMap(),
                    rawPayload = decrypted,
                    featureId = featureId
                )

                try {
                    SuperIslandHistory.append(context, historyEntry)
                } catch (_: Exception) {
                    SuperIslandHistory.append(
                        context,
                        SuperIslandHistoryEntry(
                            id = System.currentTimeMillis(),
                            sourceDeviceUuid = remoteUuid,
                            originalPackage = pkg,
                            mappedPackage = mappedPkg,
                            rawPayload = decrypted,
                            featureId = featureId
                        )
                    )
                }

                return true
            } else {
                if (isLocked) {
                    superIslandDeduplicationCache.remove(dedupKey)
                    Logger.i("超级岛", "合并失败，移除去重缓存: $dedupKey")
                }
                return true
            }
        } catch (e: Exception) {
            Logger.e(TAG, "SuperIslandProcessor.process 异常: ${e.message}")
            return false
        }
    }
}
