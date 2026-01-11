package com.xzyht.notifyrelay.common.core.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

object MediaStoreHelper {
    
    fun indexFile(context: Context, file: File) {
        try {
            val contentResolver = context.contentResolver
            
            when {
                file.isImage() -> {
                    indexImage(contentResolver, file)
                }
                file.isVideo() -> {
                    indexVideo(contentResolver, file)
                }
                file.isAudio() -> {
                    indexAudio(contentResolver, file)
                }
                else -> {
                    // 其他类型文件，发送广播通知
                    sendMediaScanBroadcast(context, file)
                }
            }
        } catch (e: Exception) {
            Logger.e("MediaStoreHelper", "Failed to index file: ${file.absolutePath}", e)
        }
    }
    
    private fun indexImage(contentResolver: ContentResolver, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/${file.extension}")
            put(MediaStore.Images.Media.DATA, file.absolutePath)
            put(MediaStore.Images.Media.SIZE, file.length())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
        }
        
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }
    
    private fun indexVideo(contentResolver: ContentResolver, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/${file.extension}")
            put(MediaStore.Video.Media.DATA, file.absolutePath)
            put(MediaStore.Video.Media.SIZE, file.length())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies")
        }
        
        contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }
    
    private fun indexAudio(contentResolver: ContentResolver, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/${file.extension}")
            put(MediaStore.Audio.Media.DATA, file.absolutePath)
            put(MediaStore.Audio.Media.SIZE, file.length())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music")
        }
        
        contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    }
    
    private fun sendMediaScanBroadcast(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val contentUri = Uri.fromFile(file)
            mediaScanIntent.data = contentUri
            context.sendBroadcast(mediaScanIntent)
        } else {
            context.sendBroadcast(
                android.content.Intent(android.content.Intent.ACTION_MEDIA_MOUNTED,
                Uri.parse("file://" + file.parent))
            )
        }
    }
    
    private fun File.isImage(): Boolean {
        val imageExtensions = arrayOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        return extension.lowercase() in imageExtensions
    }
    
    private fun File.isVideo(): Boolean {
        val videoExtensions = arrayOf("mp4", "avi", "mov", "wmv", "flv", "mkv")
        return extension.lowercase() in videoExtensions
    }
    
    private fun File.isAudio(): Boolean {
        val audioExtensions = arrayOf("mp3", "wav", "ogg", "flac", "aac", "m4a")
        return extension.lowercase() in audioExtensions
    }
}