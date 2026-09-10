package com.xzyht.notifyrelay.feature.device.service.system

import android.content.Context
import android.util.Base64
import com.xzyht.notifyrelay.nativecore.NativeCore
import notifyrelay.base.util.Logger
import notifyrelay.data.StorageManager
import notifyrelay.data.database.repository.DatabaseRepository

/**
 * 旧平台存储 → Rust 私有库的一次性迁移。
 *
 * 迁移内容：
 * - `device_migration` 表设备行：名称 → `nrc_rename_device`；base64 AES 密钥 → `nrc_migrate_shared_secret`；
 *   旧版明文密钥（与 Rust HKDF 不兼容）→ `nrc_remove_device` 清除配对；
 * - 旧加密状态 blob（SP `rust_core_state`）经 `nrc_import_state` 已导入内存，
 *   由 `nrc_get_local_uuid` 校验落盘成功后清除。
 *
 * 原则：空数据一律不传入 Rust；任一行迁移失败则保留旧存储，下次启动重试。
 */
object LegacyDeviceMigrator {
    private const val TAG = "死神-NotifyRelay"
    private const val PREF_LEGACY_STATE = "rust_core_state"
    private const val AES_KEY_BYTES = 32

    /**
     * 执行迁移与旧存储清理。
     *
     * @param legacyStateEnc 旧加密状态 blob（为空表示无旧数据）
     * @param currentUuid 当前平台侧 uuid（用于判断是否与 Rust 持久化结果一致）
     * @return 以 Rust 持久化为准的 uuid；若 Rust 持久化尚未就绪则返回 null（调用方保持原 uuid 不变）
     */
    suspend fun migrateAndCleanup(
        context: Context,
        legacyStateEnc: String,
        currentUuid: String,
    ): String? {
        val ctx = NativeCore.getContext() ?: return null
        try {
            val rows = DatabaseRepository.getInstance(context).queryDeviceMigrationRows()
            var migratedAny = false
            var migrationFailed = false
            for (row in rows) {
                if (row.uuid.isBlank() || row.uuid == "本机") continue
                if (row.displayName.isNotBlank()) {
                    if (!NativeCore.renameDevice(ctx, row.uuid, row.displayName)) {
                        Logger.w(TAG, "设备名称迁移失败 ${row.uuid}，暂缓清理旧平台存储")
                        migrationFailed = true
                    }
                    migratedAny = true
                }
                if (row.sharedSecret.isNotBlank()) {
                    val keyBytes =
                        try {
                            Base64.decode(row.sharedSecret, Base64.NO_WRAP)
                        } catch (_: Exception) {
                            null
                        }
                    if (keyBytes != null && keyBytes.size == AES_KEY_BYTES) {
                        if (!NativeCore.migrateSharedSecret(ctx, row.uuid, keyBytes)) {
                            Logger.w(TAG, "设备密钥迁移失败 ${row.uuid}，暂缓清理旧平台存储")
                            migrationFailed = true
                        }
                        migratedAny = true
                    } else {
                        // 旧版明文密钥（C#/Kotlin ECDH），与 Rust HKDF 不兼容，清除配对
                        if (!NativeCore.removeDevice(ctx, row.uuid)) {
                            Logger.w(TAG, "旧明文密钥设备清除失败 ${row.uuid}，暂缓清理旧平台存储")
                            migrationFailed = true
                        }
                        migratedAny = true
                    }
                }
                if (row.lastIp.isNotBlank()) {
                    try {
                        NativeCore.addKnownDevice(ctx, row.uuid, row.lastIp)
                    } catch (_: Exception) {
                    }
                }
            }

            // 触发落盘并校验（读取接口前自动 flush；flush 失败返回 null）
            val persistedUuid = NativeCore.getLocalUuid(ctx)
            if (persistedUuid.isNullOrEmpty()) {
                Logger.w(TAG, "Rust 持久化未就绪，暂缓清理旧平台存储")
                return null
            }
            if (persistedUuid != currentUuid) {
                Logger.i(TAG, "UUID 以 Rust 持久化为准: $persistedUuid (原: $currentUuid)")
            }
            // 任一迁移行失败：保留旧存储（SP blob/device_migration 表），下次启动重试
            if (migrationFailed) {
                Logger.w(TAG, "存在迁移失败行，暂缓清理旧平台存储，下次启动重试")
                return persistedUuid
            }
            // 迁移完成：清理旧平台存储（密钥已由 Rust 私有库持有）
            if (legacyStateEnc.isNotEmpty()) {
                StorageManager.remove(context, PREF_LEGACY_STATE)
            }
            if (rows.isNotEmpty()) {
                DatabaseRepository.getInstance(context).dropDeviceMigrationTable()
            }
            if (migratedAny) {
                Logger.i(TAG, "旧设备数据已迁移至 Rust 持久化")
            }
            return persistedUuid
        } catch (e: Exception) {
            Logger.e(TAG, "旧设备数据迁移失败", e)
            return null
        }
    }
}
