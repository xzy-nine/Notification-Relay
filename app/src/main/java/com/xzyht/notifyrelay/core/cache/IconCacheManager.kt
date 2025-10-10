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
     * 初始化缓存管理器。
     *
     * @param context 应用上下文，用于获取缓存目录（Context.cacheDir）。
     *
     * 该方法会在应用的缓存目录下创建一个用于存放应用图标的子目录。
     * 仅在 Debug 模式下打印初始化路径的日志。
     */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "图标缓存已初始化，路径: ${cacheDir.absolutePath}")
        }
    }

    /**
     * 获取给定包名对应的缓存文件对象。
     *
     * @param packageName 应用包名（用于生成缓存文件名）。
     * @return 对应的缓存文件（File），以包名的 MD5 哈希作为文件名，后缀为 .png。
     */
    private fun getCacheFile(packageName: String): File {
        val hash = packageName.md5()
        return File(cacheDir, "$hash.png")
    }

    /**
     * 获取用于保存元数据的文件对象。
     *
     * @return 元数据文件（File），文件名为 cache_metadata.txt。
     */
    private fun getMetadataFile(): File {
        return File(cacheDir, "cache_metadata.txt")
    }

    /**
     * 将图标 Bitmap 保存到缓存。
     *
     * @param packageName 应用包名（用于关联缓存条目）。
     * @param bitmap 要保存的图标 Bitmap 对象。
     * @return 保存成功返回 true，失败返回 false。
     *
     * 该方法在 IO 线程（Dispatchers.IO）执行：会把 Bitmap 压缩为 PNG 并写入文件，
     * 然后更新对应的元数据（最近访问/保存时间），并触发过期及大小检查清理。
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

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "图标已缓存: $packageName")
                }
                true
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "保存图标失败: $packageName", e)
                }
                false
            }
        }
    }

    /**
     * 从缓存加载图标。
     *
     * @param packageName 应用包名（用于定位缓存文件）。
     * @return 若缓存存在且未过期则返回 Bitmap，否则返回 null。
     *
     * 该方法在 IO 线程（Dispatchers.IO）执行：会检测文件是否存在与是否过期，
     * 若存在则解码 Bitmap 并更新访问时间戳。
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
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "缓存已过期并已删除: $packageName")
                    }
                    return@withContext null
                }

                // 加载Bitmap
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) {
                    // 更新访问时间
                    updateMetadata(packageName, System.currentTimeMillis())
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "从缓存加载图标: $packageName")
                    }
                }
                bitmap
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "加载图标失败: $packageName", e)
                }
                null
            }
        }
    }

    /**
     * 检查指定包名的图标是否存在于缓存且未过期。
     *
     * @param packageName 应用包名。
     * @return 若存在且未过期返回 true，否则返回 false。
     */
    fun hasIcon(packageName: String): Boolean {
        val cacheFile = getCacheFile(packageName)
        return cacheFile.exists() && !isCacheExpired(packageName)
    }

    /**
     * 删除指定包名的缓存图标。
     *
     * @param packageName 应用包名。
     * @return 删除成功返回 true，否则返回 false。
     */
    suspend fun removeIcon(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(packageName)
                val deleted = cacheFile.delete()
                if (deleted) {
                    removeMetadata(packageName)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "已从缓存移除图标: $packageName")
                    }
                }
                deleted
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "移除图标失败: $packageName", e)
                }
                false
            }
        }
    }

    /**
     * 清空所有图标缓存。
     *
     * @return 若所有文件均成功删除返回 true，若有文件删除失败则返回 false。
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
                    Log.d(TAG, "已清空所有图标缓存")
                }
                success
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "清空缓存失败", e)
                }
                false
            }
        }
    }

    /**
     * 获取缓存总大小（字节）。
     *
     * @return 缓存目录下所有文件的总字节数（Long）。
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 获取缓存总大小（MB）。
     *
     * @return 缓存目录大小，单位为 MB（Double）。
     */
    fun getCacheSizeMB(): Double {
        return getCacheSize() / (1024.0 * 1024.0)
    }

    /**
     * 公共接口：触发一次过期缓存清理。
     */
    suspend fun cleanupExpiredCache() {
        cleanupExpiredCacheInternal()
    }

    /**
     * 内部实现：清理超过年龄限制的缓存，并在总大小超出限制时按最旧先清理。
     *
     * 该方法在 IO 线程执行。
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
                    Log.d(TAG, "已清理 ${expiredPackages.size} 个过期缓存条目")
                }

                // 检查总缓存大小，如果超过限制，清理最旧的文件
                cleanupBySize()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "清理过期缓存失败", e)
                }
            }
        }
    }

    /**
     * 根据总大小清理缓存，直到总大小低于限制（按文件最后修改时间，从最旧开始删除）。
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
                Log.d(TAG, "缓存大小已清理至 ${getCacheSizeMB()} MB")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "按大小清理缓存失败", e)
            }
        }
    }

    /**
     * 检查指定包名的缓存是否已过期。
     *
     * @param packageName 应用包名。
     * @return 若元数据中未找到时间戳或已超过最大允许年龄则返回 true，表示已过期。
     */
    private fun isCacheExpired(packageName: String): Boolean {
        val metadata = loadMetadata()
        val timestamp = metadata[packageName] ?: return true
        val maxAgeMillis = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - timestamp > maxAgeMillis
    }

    /**
     * 更新单个包名的元数据（时间戳）。
     *
     * @param packageName 应用包名。
     * @param timestamp 时间戳（毫秒），通常使用 System.currentTimeMillis()。
     */
    private fun updateMetadata(packageName: String, timestamp: Long) {
        val metadata = loadMetadata().toMutableMap()
        metadata[packageName] = timestamp
        saveMetadata(metadata)
    }

    /**
     * 从元数据中移除指定包名的记录。
     *
     * @param packageName 应用包名。
     */
    private fun removeMetadata(packageName: String) {
        val metadata = loadMetadata().toMutableMap()
        metadata.remove(packageName)
        saveMetadata(metadata)
    }

    /**
     * 读取并解析元数据文件，返回包名到时间戳的映射。
     *
     * 元数据文件格式为每行一条记录："<packageName>:<timestamp>"。
     * 若文件不存在或解析出错则返回空映射。
     *
     * @return Map<String, Long> 包名 -> 时间戳（毫秒）。
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
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "读取元数据失败", e)
            }
            emptyMap()
        }
    }

    /**
     * 将元数据写入到磁盘（覆盖写入）。
     *
     * @param metadata 包名 -> 时间戳 的映射，将被序列化为每行一条记录的文本文件。
     */
    private fun saveMetadata(metadata: Map<String, Long>) {
        try {
            val metadataFile = getMetadataFile()
            metadataFile.writeText(
                metadata.entries.joinToString("\n") { "${it.key}:${it.value}" }
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "保存元数据失败", e)
            }
        }
    }

    /**
     * 获取缓存统计信息：文件数、总字节数、总 MB、过期条目数、缓存目录路径。
     *
     * @return CacheStats 包含统计数据的不可变数据类。
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
     * 计算字符串的 MD5 哈希值（小写十六进制表示）。
     *
     * @receiver 要进行哈希的字符串。
     * @return 哈希字符串（小写十六进制）。
     */
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 缓存统计信息数据类。
     *
     * @param totalFiles 缓存中图标文件的数量（不包括元数据文件）。
     * @param totalSizeBytes 缓存总大小（字节）。
     * @param totalSizeMB 缓存总大小（MB）。
     * @param expiredCount 元数据中标记为过期的条目数量。
     * @param cacheDir 缓存目录的绝对路径。
     */
    data class CacheStats(
        val totalFiles: Int,
        val totalSizeBytes: Long,
        val totalSizeMB: Double,
        val expiredCount: Int,
        val cacheDir: String
    )
}