package com.xzyht.notifyrelay.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jakewharton.disklrucache.DiskLruCache
import com.xzyht.notifyrelay.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 基于 DiskLruCache 的图标缓存管理器。
 *
 * 使用 DiskLruCache 做大小限制与 LRU 淘汰；同时保留简单元数据用于按年龄清理（过期策略）。
 */
object IconCacheManager {
    private const val TAG = "IconCacheManager"
    private const val CACHE_DIR = "app_icons_cache"
    private const val CACHE_VERSION = 1
    private const val VALUE_COUNT = 1
    private const val MAX_CACHE_SIZE_MB = 50L // 最大缓存大小50MB
    private const val MAX_CACHE_AGE_DAYS = 30 // 缓存最大年龄30天

    private lateinit var cacheDir: File
    private var diskCache: DiskLruCache? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
        try {
            val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024
            diskCache = DiskLruCache.open(cacheDir, CACHE_VERSION, VALUE_COUNT, maxSize)
            Logger.d(TAG, "DiskLruCache 初始化，path=${cacheDir.absolutePath}")
        } catch (e: Exception) {
            Logger.e(TAG, "初始化 DiskLruCache 失败", e)
            diskCache = null
        }
    }

    private fun keyFor(packageName: String): String = packageName.md5()

    suspend fun saveIcon(packageName: String, bitmap: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val key = keyFor(packageName)
                val cache = diskCache
                if (cache != null) {
                    val editor = cache.edit(key) ?: return@withContext false
                    try {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val bytes = baos.toByteArray()
                        editor.newOutputStream(0).use { it.write(bytes) }
                        editor.commit()
                        cache.flush()
                        // 更新文件最后修改时间
                        updateMetadata(packageName, System.currentTimeMillis())
                        Logger.d(TAG, "图标已缓存 (DiskLruCache): $packageName")
                        return@withContext true
                    } catch (e: Exception) {
                        try { editor.abort() } catch (_: Exception) {}
                        throw e
                    }
                } else {
                    // fallback: write directly to file
                    val file = File(cacheDir, "$key.png")
                    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    updateMetadata(packageName, System.currentTimeMillis())
                    Logger.d(TAG, "图标已缓存 (fallback): $packageName")
                    return@withContext true
                }
            } catch (e: Exception) {
                Logger.e(TAG, "保存图标失败: $packageName", e)
                return@withContext false
            }
        }
    }

    suspend fun loadIcon(packageName: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (isCacheExpired(packageName)) {
                    removeIcon(packageName)
                    return@withContext null
                }

                val key = keyFor(packageName)
                val cache = diskCache
                if (cache != null) {
                    val snapshot = cache.get(key)
                    if (snapshot != null) {
                        snapshot.getInputStream(0).use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            if (bmp != null) updateMetadata(packageName, System.currentTimeMillis())
                            return@withContext bmp
                        }
                    }
                }

                // fallback: direct file
                val f = File(cacheDir, "$key.png")
                if (!f.exists()) return@withContext null
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) updateMetadata(packageName, System.currentTimeMillis())
                return@withContext bmp
            } catch (e: Exception) {
                Logger.e(TAG, "加载图标失败: $packageName", e)
                return@withContext null
            }
        }
    }

    fun hasIcon(packageName: String): Boolean {
        if (!::cacheDir.isInitialized) return false
        if (isCacheExpired(packageName)) return false
        val key = keyFor(packageName)
        return try {
            diskCache?.get(key) != null || File(cacheDir, "$key.png").exists()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun removeIcon(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val key = keyFor(packageName)
                try { diskCache?.remove(key) } catch (_: Exception) {}
                val f = File(cacheDir, "$key.png")
                val deleted = if (f.exists()) f.delete() else true
                removeMetadata(packageName)
                Logger.d(TAG, "已从缓存移除图标: $packageName")
                deleted
            } catch (e: Exception) {
                Logger.e(TAG, "移除图标失败: $packageName", e)
                false
            }
        }
    }

    suspend fun clearAllCache(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                try { diskCache?.delete() } catch (_: Exception) {}
                // recreate directory
                cacheDir.listFiles()?.forEach { it.delete() }
                Logger.d(TAG, "已清空所有图标缓存")
                true
            } catch (e: Exception) {
                Logger.e(TAG, "清空缓存失败", e)
                false
            }
        }
    }

    fun getCacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun getCacheSizeMB(): Double = getCacheSize() / (1024.0 * 1024.0)

    suspend fun cleanupExpiredCache() {
        cleanupExpiredCacheInternal()
    }

    private suspend fun cleanupExpiredCacheInternal() {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
                val metadata = loadMetadata()
                val expired = metadata.filter { now - it.value > maxAgeMillis }.keys
                expired.forEach { removeIcon(it) }
                // If over size, rely on DiskLruCache eviction; additionally delete oldest files
                if (getCacheSize() > MAX_CACHE_SIZE_MB * 1024 * 1024) {
                    val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
                    var total = getCacheSize()
                    val limit = MAX_CACHE_SIZE_MB * 1024 * 1024
                    for (f in files) {
                        if (total <= limit) break
                        val len = f.length()
                        if (f.delete()) total -= len
                    }
                }
                Logger.d(TAG, "过期缓存清理完成")
            } catch (e: Exception) {
                Logger.e(TAG, "清理过期缓存失败", e)
            }
        }
    }

    private fun isCacheExpired(packageName: String): Boolean {
        val metadata = loadMetadata()
        val ts = metadata[packageName] ?: return true
        val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - ts > maxAgeMillis
    }

    private fun updateMetadata(packageName: String, timestamp: Long) {
        val m = loadMetadata().toMutableMap()
        m[packageName] = timestamp
        saveMetadata(m)
    }

    private fun removeMetadata(packageName: String) {
        val m = loadMetadata().toMutableMap()
        m.remove(packageName)
        saveMetadata(m)
    }

    private fun getMetadataFile(): File = File(cacheDir, "cache_metadata.txt")

    private fun loadMetadata(): Map<String, Long> {
        val f = getMetadataFile()
        if (!f.exists()) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size == 2) {
                    val name = parts[0]
                    val t = parts[1].toLongOrNull() ?: return@mapNotNull null
                    name to t
                } else null
            }.toMap()
        } catch (e: Exception) {
            Logger.e(TAG, "读取元数据失败", e)
            emptyMap()
        }
    }

    private fun saveMetadata(metadata: Map<String, Long>) {
        try {
            getMetadataFile().writeText(metadata.entries.joinToString("\n") { "${it.key}:${it.value}" })
        } catch (e: Exception) {
            Logger.e(TAG, "保存元数据失败", e)
        }
    }

    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class CacheStats(
        val totalFiles: Int,
        val totalSizeBytes: Long,
        val totalSizeMB: Double,
        val expiredCount: Int,
        val cacheDir: String
    )

    fun getCacheStats(): CacheStats {
        val metadata = loadMetadata()
        val totalFiles = cacheDir.listFiles()?.count { it.isFile && it.name != "cache_metadata.txt" } ?: 0
        val totalSize = getCacheSize()
        val expiredCount = metadata.count { (_, timestamp) -> System.currentTimeMillis() - timestamp > MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L }
        return CacheStats(totalFiles, totalSize, totalSize / (1024.0 * 1024.0), expiredCount, cacheDir.absolutePath)
    }
}