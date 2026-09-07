package com.xzyht.notifyrelay.sync.notification

import android.content.Context
import android.os.Build
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.filter.RemoteFilterConfig
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.tracker.LocalSuperIslandTracker
import com.xzyht.notifyrelay.feature.notification.superisland.store.SuperIslandRemoteStore
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistoryStore
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistoryStoreEntry
import com.xzyht.notifyrelay.feature.notification.superisland.notification.LiveUpdatesNotificationManager
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import github.xzynine.superislandui.common.SuperIslandProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import notifyrelay.data.FilterConfigDefaults
import notifyrelay.data.StorageManager
import notifyrelay.data.database.repository.DatabaseRepository
import org.json.JSONObject

object SuperIslandProcessor {
    private const val TAG = "SuperIslandProcessor"

    // 锁屏去重 TTL（毫秒）：同一远端岛在锁屏期间的重复包直接丢弃
    private const val SI_DEDUP_TTL_MS = 300_000L

    private val registeredDedupKeys = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private val DEFAULT_MIRROR_PACKAGES: List<String>
        get() = FilterConfigDefaults.defaultMirrorPackages

    private fun dismissBySourceId(sourceId: String) {
        FloatingReplicaManager.dismissBySource(sourceId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            LiveUpdatesNotificationManager.dismissLiveUpdateNotification(sourceId)
        }
    }

    /** 锁屏去重：命中返回 true（应丢弃） */
    private fun dedupCheck(
        manager: DeviceConnectionManager,
        dedupKey: String,
    ): Boolean {
        val ctx = manager.rustContextInternal ?: return false
        return NativeCore.dedup(ctx, 0, dedupKey, SI_DEDUP_TTL_MS, 0L) == 0
    }

    /** 清除锁屏去重登记（结束包 / 合并失败时调用） */
    private fun dedupClear(
        manager: DeviceConnectionManager,
        dedupKey: String,
    ) {
        val ctx = manager.rustContextInternal ?: return
        try {
            NativeCore.dedup(ctx, 2, dedupKey, 0L, 0L)
        } catch (_: Exception) {
        }
    }

    private fun clearDedupBySource(
        manager: DeviceConnectionManager,
        sourceKey: String,
    ) {
        registeredDedupKeys.remove(sourceKey)?.forEach { dedupClear(manager, it) }
    }

    private fun clearDedupBySuffix(
        manager: DeviceConnectionManager,
        remoteUuid: String,
        mappedPkg: String,
        featureSuffix: String,
    ) {
        val matched =
            registeredDedupKeys.entries.filter {
                it.key.startsWith("$remoteUuid|$mappedPkg|") && it.key.substringAfterLast("|") == featureSuffix
            }
        matched.forEach {
            it.value.forEach { key -> dedupClear(manager, key) }
            registeredDedupKeys.remove(it.key)
        }
    }

    fun process(
        context: Context,
        manager: DeviceConnectionManager,
        decrypted: String,
        remoteUuid: String?,
    ): Boolean {
        try {
            if (remoteUuid == null) return false
            val json = JSONObject(decrypted)
            val pkg = json.optString("packageName")
            val appName = json.optString("appName")
            val title = json.optString("title").takeIf { it.isNotEmpty() }
            val text = json.optString("text").takeIf { it.isNotEmpty() }
            val time = json.optLong("time", System.currentTimeMillis())

            val installedPkgs = AppRepository.getInstalledPackageNamesSync(context)
            val mappedPkg = RemoteFilterConfig.mapToLocalPackage(pkg.orEmpty(), installedPkgs)

            val siType =
                try {
                    json.optString("type", "")
                } catch (_: Exception) {
                    ""
                }
            val termVal =
                try {
                    json.optString("terminateValue", "")
                } catch (_: Exception) {
                    ""
                }
            val isEnd = (termVal == SuperIslandProtocol.TERMINATE_VALUE)

            val mirrorFilterEnabled = StorageManager.getBoolean(context, "super_island_mirror_filter_enabled", true)
            if (mirrorFilterEnabled) {
                val isMirrorEnabled =
                    runBlocking(Dispatchers.IO) {
                        val repo = DatabaseRepository.getInstance(context)
                        val all = repo.getAllMirrorFilterPackages()
                        val entry = all.find { it.packageName == mappedPkg }
                        when {
                            entry != null -> entry.enabled
                            // 默认包名：表中未初始化行时默认启用（与旧 disabled_defaults 语义一致）
                            DEFAULT_MIRROR_PACKAGES.contains(mappedPkg) -> true
                            else -> false
                        }
                    }
                if (isMirrorEnabled && LocalSuperIslandTracker.isActive(mappedPkg) && !isEnd) {
                    Logger.i("超级岛", "镜像应用过滤(对称)：跳过远程复刻, pkg=$mappedPkg, remoteUuid=$remoteUuid")
                    return true
                }
            }

            // SI_ACK 属于超级岛协议的确认包，仅用于可靠性确认，不应进入通知/聊天管线
            if (siType == "SI_ACK") {
                Logger.i(
                    "超级岛",
                    "收到超级岛ACK: remoteUuid=$remoteUuid, pkg=$pkg, mappedPkg=$mappedPkg, hash=${try {
                        json.optString("hash", "")
                    } catch (_: Exception) {
                        ""
                    }}",
                )
                return true
            }

            // 由于路由层已经按 DATA_SUPERISLAND 分发，这里不再依赖 JSON 内的 type/featureKey 字段。
            val isLocked =
                try {
                    json.optBoolean("isLocked", false)
                } catch (_: Exception) {
                    false
                }
            val paramV2Raw =
                try {
                    val s = json.optString("param_v2_raw")
                    if (s.isNullOrBlank()) null else s
                } catch (_: Exception) {
                    null
                }
            // 优先使用显式传回的 featureKeyValue（若发送端已计算并包含），保证 full/delta/end 使用相同的 featureId
            val explicitFeatureKeyCandidate =
                try {
                    json.optString("featureKeyValue", "")
                } catch (_: Exception) {
                    ""
                }
            val featureId =
                if (!explicitFeatureKeyCandidate.isNullOrBlank()) {
                    explicitFeatureKeyCandidate
                } else {
                    try {
                        NativeCore.computeFeatureId(pkg, paramV2Raw ?: "", json.optString("title"), json.optString("text"), "") ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
            val sourceKey = listOfNotNull(remoteUuid, mappedPkg, featureId.takeIf { it.isNotBlank() }).joinToString("|")
            val contentHash = (title.orEmpty() + text.orEmpty() + (paramV2Raw ?: "")).hashCode()
            val dedupKey = "$remoteUuid|$mappedPkg|$featureId|$contentHash"

            // 结束包判断：存在 terminateValue 或者显式 featureKeyValue 且 terminateValue 标记
            val explicitFeatureKey =
                try {
                    json.optString("featureKeyValue", "")
                } catch (_: Exception) {
                    ""
                }
            if (isEnd) {
                manager.removeStateQueryKey(remoteUuid, featureId)
                try {
                    // 优先用显式的 featureKeyValue 进行 dismiss（若有）
                    if (!explicitFeatureKey.isNullOrBlank()) {
                        try {
                            // 如果显式值看起来像完整的 sourceId（包含分隔符），直接移除
                            if (explicitFeatureKey.contains("|")) {
                                dismissBySourceId(explicitFeatureKey)
                                SuperIslandRemoteStore.removeExact(explicitFeatureKey)
                                clearDedupBySource(manager, sourceKey)
                                Logger.i("超级岛", "收到终止通知(显式完整 sourceId)，移除去重缓存: $dedupKey -> source=$explicitFeatureKey")
                                return true
                            }

                            // 否则将其视为 featureId 后缀，尝试按后缀查找并移除匹配的完整 sourceId
                            val matched = SuperIslandRemoteStore.removeByFeatureKey(explicitFeatureKey)
                            if (matched.isNotEmpty()) {
                                matched.forEach { rid ->
                                    try {
                                        dismissBySourceId(rid)
                                    } catch (_: Exception) {
                                    }
                                    clearDedupBySuffix(manager, remoteUuid, mappedPkg, rid.substringAfterLast("|"))
                                    Logger.i("超级岛", "收到终止通知(显式 featureKey 匹配)，移除并关闭通知: $rid -> featureKey=$explicitFeatureKey")
                                }
                                return true
                            }
                            // 若未匹配到，再继续落到后续的前缀匹配/兜底逻辑
                        } catch (_: Exception) {
                        }
                    }

                    // 如果没有显式 featureKey，尝试在远端存储中查找可能已存在的 sourceId（按 deviceUuid|mappedPkg 前缀匹配）
                    val removedKeys = SuperIslandRemoteStore.removeByDeviceAndPkgPrefix(remoteUuid, mappedPkg)
                    if (removedKeys.isNotEmpty()) {
                        removedKeys.forEach { rid ->
                            try {
                                dismissBySourceId(rid)
                            } catch (_: Exception) {
                            }
                            // 同步移除去重缓存（若存在）
                            clearDedupBySuffix(manager, remoteUuid, mappedPkg, rid.substringAfterLast("|"))
                            Logger.i("超级岛", "收到终止通知，按前缀移除并关闭通知: $rid")
                        }
                        return true
                    }

                    // 最后兜底：按照当前计算的 sourceKey 进行移除（可能无对应），以防漏掉
                    try {
                        dismissBySourceId(sourceKey)
                    } catch (_: Exception) {
                    }
                    clearDedupBySource(manager, sourceKey)
                    Logger.i("超级岛", "收到终止通知(兜底)，尝试移除: $sourceKey")
                    return true
                } catch (e: Exception) {
                    Logger.w("超级岛", "处理结束包时出错: ${e.message}")
                }
            }

            val mTitle =
                try {
                    json.optString("title", title.orEmpty())
                } catch (_: Exception) {
                    title.orEmpty()
                }
            val mText =
                try {
                    json.optString("text", text.orEmpty())
                } catch (_: Exception) {
                    text.orEmpty()
                }

            if (isLocked) {
                if (featureId.isBlank()) {
                    // 缺失 featureId 时共享去重键会误伤同设备同应用的其他通知，跳过锁屏去重正常展示
                    Logger.w("超级岛", "featureId 缺失，跳过锁屏去重: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                } else {
                    val osVersion = PermissionHelper.getDetailedOsVersion()
                    val shouldSkipDedup = PermissionHelper.isVersionGreaterThan(osVersion, "OS3.0.200")

                    if (shouldSkipDedup) {
                        Logger.i("超级岛", "澎湃系统版本高于OS3.0.200，跳过锁屏去重: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                    } else if (dedupCheck(manager, dedupKey)) {
                        Logger.i("超级岛", "锁屏重复通知去重: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                        return true
                    } else {
                        registeredDedupKeys.computeIfAbsent(sourceKey) { mutableSetOf() }.add(dedupKey)
                        Logger.i("超级岛", "首次处理超级岛通知，添加到去重缓存: $dedupKey, title=${mTitle ?: "无标题"}")
                    }
                }
            } else {
                Logger.i("超级岛", "非锁屏状态，正常处理超级岛通知: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
            }

            val merged = SuperIslandRemoteStore.applyIncoming(sourceKey, json)

            val mParam2 = merged?.paramV2Raw ?: paramV2Raw

            // 解析 title/text 的优先级：merged > 顶层包字段 > paramV2Raw.iconTextInfo
            val finalTitle =
                merged?.title?.takeIf { it.isNotBlank() }
                    ?: mTitle.takeIf { it.isNotBlank() }
                    ?: if (!mParam2.isNullOrBlank()) {
                        try {
                            val paramJson = JSONObject(mParam2)
                            paramJson
                                .optJSONObject("iconTextInfo")
                                ?.optString("title", "")
                                ?.takeIf { it.isNotBlank() }
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    } ?: "未知"

            val finalText =
                merged?.text?.takeIf { it.isNotBlank() }
                    ?: mText.takeIf { it.isNotBlank() }
                    ?: if (!mParam2.isNullOrBlank()) {
                        try {
                            val paramJson = JSONObject(mParam2)
                            paramJson
                                .optJSONObject("iconTextInfo")
                                ?.optString("content", "")
                                ?.takeIf { it.isNotBlank() }
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    } ?: "未知"

            val rawPics = merged?.pics ?: emptyMap()
            val mPics = if (rawPics.isEmpty()) rawPics else rawPics.filterKeys { it != "miui.focus.pics" }

            // 初始化 Live Updates 通知管理器（仅第一次调用时初始化）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    LiveUpdatesNotificationManager.initialize(context)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "初始化 Live Updates 通知管理器失败: ${e.message}")
            }

            try {
                // 仅在有实际可展示内容时才创建浮窗
                val hasContent = !finalTitle.isNullOrBlank() || !finalText.isNullOrBlank() || !mParam2.isNullOrBlank() || (mPics.isNotEmpty())
                if (hasContent) {
                    // 对于所有类型，都显示传统浮窗
                    // 复合通知将在浮窗创建时由FloatingReplicaManager处理
                    FloatingReplicaManager.showFloating(context, sourceKey, finalTitle, finalText, mParam2, mPics, appName, isLocked)
                    Logger.i("超级岛", "使用浮窗显示通知: sourceKey=$sourceKey")
                } else {
                    Logger.i("超级岛", "收到内容为空的超级岛包，跳过创建通知: sourceKey=$sourceKey")
                }
            } catch (e: Exception) {
                Logger.w("超级岛", "显示超级岛通知失败: ${e.message}")
            }

            // 检查是否为测试数据，如果是则跳过保存到历史记录
            if (!pkg.startsWith("test_")) {
                val historyEntry =
                    SuperIslandHistoryStoreEntry(
                        id = System.currentTimeMillis(),
                        sourceDeviceUuid = remoteUuid,
                        originalPackage = pkg,
                        mappedPackage = mappedPkg,
                        appName = appName.takeIf { it.isNotEmpty() },
                        title = finalTitle?.takeIf { it.isNotBlank() },
                        text = finalText?.takeIf { it.isNotBlank() },
                        paramV2Raw = mParam2?.takeIf { it.isNotBlank() },
                        picMap = mPics.toMap(),
                        rawPayload = decrypted,
                        featureId = featureId,
                    )

                try {
                    SuperIslandHistoryStore.append(context, historyEntry)
                } catch (_: Exception) {
                    SuperIslandHistoryStore.append(
                        context,
                        SuperIslandHistoryStoreEntry(
                            id = System.currentTimeMillis(),
                            sourceDeviceUuid = remoteUuid,
                            originalPackage = pkg,
                            mappedPackage = mappedPkg,
                            rawPayload = decrypted,
                            featureId = featureId,
                        ),
                    )
                }
            } else {
                Logger.i("超级岛", "跳过保存测试数据到历史记录: pkg=$pkg")
            }

            if (merged == null && isLocked) {
                clearDedupBySource(manager, sourceKey)
                Logger.i("超级岛", "合并失败，移除去重缓存: $dedupKey")
            }

            return true
        } catch (e: Exception) {
            Logger.e(TAG, "SuperIslandProcessor.process 异常: ${e.message}")
            return false
        }
    }
}
