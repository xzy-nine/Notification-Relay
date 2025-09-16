package com.xzyht.notifyrelay.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 应用图标缓存管理器
 * 使用文件系统进行持久化存储
 */
object IconCacheManager {
    private const val TAG = "IconCacheManager"
    private const val CACHE_DIR = "app_icons_cache"
    private const val CACHE_VERSION = 1
    private const val MAX_CACHE_SIZE_MB = 50 // 最大缓存大小50MB
    private const val MAX_CACHE_AGE_DAYS = 30 // 缓存最大年龄30天

    private lateinit var cacheDir: File

    /**
     * 初始化缓存管理器
     */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Icon cache initialized at: ${cacheDir.absolutePath}")
    }

    /**
     * 获取缓存文件路径
     */
    private fun getCacheFile(packageName: String): File {
        val hash = packageName.md5()
        return File(cacheDir, "$hash.png")
    }

    /**
     * 获取缓存元数据文件
     */
    private fun getMetadataFile(): File {
        return File(cacheDir, "cache_metadata.txt")
    }

    /**
     * 保存图标到缓存
     */
    suspend fun saveIcon(packageName: String, bitmap: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(packageName)

                // 压缩Bitmap并保存为PNG
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }

                // 更新元数据
                updateMetadata(packageName, System.currentTimeMillis())

                // 检查并清理过期缓存
                cleanupExpiredCacheInternal()

                if (BuildConfig.DEBUG) Log.d(TAG, "Icon saved to cache: $packageName")
                true
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to save icon: $packageName", e)
                false
            }
        }
    }

    /**
     * 从缓存加载图标
     */
    suspend fun loadIcon(packageName: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(packageName)
                if (!cacheFile.exists()) {
                    return@withContext null
                }

                // 检查缓存是否过期
                if (isCacheExpired(packageName)) {
                    cacheFile.delete()
                    removeMetadata(packageName)
                    return@withContext null
                }

                // 加载Bitmap
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) {
                    // 更新访问时间
                    updateMetadata(packageName, System.currentTimeMillis())
                    if (BuildConfig.DEBUG) Log.d(TAG, "Icon loaded from cache: $packageName")
                }
                bitmap
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to load icon: $packageName", e)
                null
            }
        }
    }

    /**
     * 检查图标是否存在于缓存中
     */
    fun hasIcon(packageName: String): Boolean {
        val cacheFile = getCacheFile(packageName)
        return cacheFile.exists() && !isCacheExpired(packageName)
    }

    /**
     * 删除缓存的图标
     */
    suspend fun removeIcon(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(packageName)
                val deleted = cacheFile.delete()
                if (deleted) {
                    removeMetadata(packageName)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Icon removed from cache: $packageName")
                }
                deleted
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to remove icon: $packageName", e)
                false
            }
        }
    }

    /**
     * 清空所有缓存
     */
    suspend fun clearAllCache(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var success = true
                cacheDir.listFiles()?.forEach { file ->
                    if (!file.delete()) {
                        success = false
                    }
                }
                if (success && BuildConfig.DEBUG) {
                    Log.d(TAG, "All icon cache cleared")
                }
                success
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to clear cache", e)
                false
            }
        }
    }

    /**
     * 获取缓存大小（字节）
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 获取缓存大小（MB）
     */
    fun getCacheSizeMB(): Double {
        return getCacheSize() / (1024.0 * 1024.0)
    }

    /**
     * 清理过期缓存（公共方法）
     */
    suspend fun cleanupExpiredCache() {
        cleanupExpiredCacheInternal()
    }

    /**
     * 清理过期缓存（内部方法）
     */
    private suspend fun cleanupExpiredCacheInternal() {
        withContext(Dispatchers.IO) {
            try {
                val currentTime = System.currentTimeMillis()
                val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L

                val metadata = loadMetadata()
                val expiredPackages = metadata.filter { (_, timestamp) ->
                    currentTime - timestamp > maxAgeMillis
                }.keys

                // 删除过期文件
                expiredPackages.forEach { packageName ->
                    getCacheFile(packageName).delete()
                }

                // 更新元数据
                val updatedMetadata = metadata.filterKeys { it !in expiredPackages }
                saveMetadata(updatedMetadata)

                if (BuildConfig.DEBUG && expiredPackages.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${expiredPackages.size} expired cache entries")
                }

                // 检查总缓存大小，如果超过限制，清理最旧的文件
                cleanupBySize()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to cleanup expired cache", e)
            }
        }
    }

    /**
     * 根据大小清理缓存
     */
    private fun cleanupBySize() {
        try {
            val files = cacheDir.listFiles()?.filter { it.isFile && it.name != "cache_metadata.txt" }
                ?.sortedBy { it.lastModified() } ?: return

            var totalSize = getCacheSize()
            val maxSizeBytes = MAX_CACHE_SIZE_MB * 1024 * 1024L

            val iterator = files.iterator()
            while (totalSize > maxSizeBytes && iterator.hasNext()) {
                val file = iterator.next()
                val fileSize = file.length()
                if (file.delete()) {
                    totalSize -= fileSize
                    // 从元数据中移除
                    val packageName = file.nameWithoutExtension
                    removeMetadata(packageName)
                }
            }

            if (BuildConfig.DEBUG && totalSize <= maxSizeBytes) {
                Log.d(TAG, "Cache size cleaned up to ${getCacheSizeMB()}MB")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to cleanup cache by size", e)
        }
    }

    /**
     * 检查缓存是否过期
     */
    private fun isCacheExpired(packageName: String): Boolean {
        val metadata = loadMetadata()
        val timestamp = metadata[packageName] ?: return true
        val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - timestamp > maxAgeMillis
    }

    /**
     * 更新元数据
     */
    private fun updateMetadata(packageName: String, timestamp: Long) {
        val metadata = loadMetadata().toMutableMap()
        metadata[packageName] = timestamp
        saveMetadata(metadata)
    }

    /**
     * 移除元数据
     */
    private fun removeMetadata(packageName: String) {
        val metadata = loadMetadata().toMutableMap()
        metadata.remove(packageName)
        saveMetadata(metadata)
    }

    /**
     * 加载元数据
     */
    private fun loadMetadata(): Map<String, Long> {
        val metadataFile = getMetadataFile()
        if (!metadataFile.exists()) return emptyMap()

        return try {
            metadataFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        val packageName = parts[0]
                        val timestamp = parts[1].toLongOrNull()
                        if (timestamp != null) {
                            packageName to timestamp
                        } else null
                    } else null
                }
                .toMap()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to load metadata", e)
            emptyMap()
        }
    }

    /**
     * 保存元数据
     */
    private fun saveMetadata(metadata: Map<String, Long>) {
        try {
            val metadataFile = getMetadataFile()
            metadataFile.writeText(
                metadata.entries.joinToString("\n") { "${it.key}:${it.value}" }
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to save metadata", e)
        }
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        val metadata = loadMetadata()
        val totalFiles = cacheDir.listFiles()?.count { it.isFile && it.name != "cache_metadata.txt" } ?: 0
        val totalSize = getCacheSize()
        val expiredCount = metadata.count { (_, timestamp) ->
            val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
            System.currentTimeMillis() - timestamp > maxAgeMillis
        }

        return CacheStats(
            totalFiles = totalFiles,
            totalSizeBytes = totalSize,
            totalSizeMB = totalSize / (1024.0 * 1024.0),
            expiredCount = expiredCount,
            cacheDir = cacheDir.absolutePath
        )
    }

    /**
     * 字符串的MD5哈希
     */
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 缓存统计信息
     */
    data class CacheStats(
        val totalFiles: Int,
        val totalSizeBytes: Long,
        val totalSizeMB: Double,
        val expiredCount: Int,
        val cacheDir: String
    )
}