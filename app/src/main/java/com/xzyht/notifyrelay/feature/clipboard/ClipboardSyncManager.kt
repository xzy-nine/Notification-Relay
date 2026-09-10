package com.xzyht.notifyrelay.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.ToastUtils
import notifyrelay.core.util.image.ImageUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剪贴板同步管理器
 * 去重、防循环、频率限制与发送全部由 Rust core 内部闭环（nrc_clipboard_on_changed / nrc_clipboard_on_received），
 * 平台端仅负责系统剪贴板读写与前台/Fcitx 权限判定。
 */
object ClipboardSyncManager {
    private const val TAG = "ClipboardSyncManager"
    private const val CLIPBOARD_TYPE_TEXT = "text"
    private const val CLIPBOARD_TYPE_IMAGE = "image"
    private const val MIME_TEXT = "text/plain"
    private const val MIME_IMAGE = "image/png"

    private var clipboardManager: ClipboardManager? = null
    private var isManualSyncMode = false

    fun isFcitx5Paired(context: Context): Boolean {
        FcitxClipboardManager.restorePairedState(context)
        return FcitxClipboardManager.isPaired
    }

    fun setManualSyncMode(
        context: Context,
        enabled: Boolean,
    ) {
        isManualSyncMode = enabled
        if (enabled) {
            Logger.d(TAG, "已启用手动同步模式，将通过通知点击触发剪贴板同步")
        } else {
            Logger.d(TAG, "已禁用手动同步模式")
        }
    }

    private fun canSyncClipboard(context: Context): Pair<Boolean, String> {
        if (isManualSyncMode) {
            return Pair(true, "手动同步模式")
        }

        if (isFcitx5Paired(context)) {
            return Pair(true, "Fcitx5 已配对")
        }

        if (PermissionHelper.isAppInForeground(context)) {
            return Pair(true, "应用处于前台")
        }

        return Pair(false, "应用不在前台，需要通过透明Activity获取剪贴板")
    }

    fun init(context: Context) {
        Logger.d(TAG, "剪贴板同步管理器已初始化")
        PermissionHelper.AppForegroundDetector.initialize(context)
    }

    private fun getClipboardManager(context: Context): ClipboardManager? {
        if (clipboardManager == null) {
            // 使用 applicationContext 避免间接持有 Activity/Service 导致无法回收
            clipboardManager =
                context.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }
        return clipboardManager
    }

    /**
     * 处理接收到的剪贴板消息。
     * Rust 解析报文、归一化类型并登记防循环时间窗，返回内容供写入系统剪贴板。
     */
    fun handleClipboardMessage(
        jsonData: String,
        context: Context,
    ) {
        try {
            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(context)
            val resultJson =
                NativeCore.clipboardOnReceived(
                    NativeCore.getContext(),
                    jsonData,
                    System.currentTimeMillis(),
                )
            val result = JSONObject(resultJson ?: return)
            val type = result.getString("type")
            val content = result.getString("content")
            if (content.isEmpty()) return

            // 防循环由 Rust 内部闭环处理，平台端直接更新本地剪贴板

            // 更新本地剪贴板
            updateLocalClipboardContent(type, content, context)
        } catch (e: Exception) {
            Logger.e(TAG, "处理剪贴板消息失败", e)
        }
    }

    /**
     * 获取当前剪贴板数据
     */
    private fun getCurrentClipboardData(context: Context): Pair<String, String>? {
        // 先检查是否可以访问剪贴板
        val (canSync, _) = canSyncClipboard(context)
        if (!canSync) {
            return null
        }

        try {
            getClipboardManager(context)?.let { cm ->
                // 尝试获取剪贴板内容，捕获可能的权限异常
                val clip = cm.primaryClip
                if (clip == null) {
                    return null
                }

                val clipDescription = clip.description
                val item = clip.getItemAt(0)

                if (clipDescription != null && item != null) {
                    // 处理文本类型剪贴板内容
                    if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                        clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                    ) {
                        try {
                            val text = item.text?.toString()
                            if (!text.isNullOrEmpty()) {
                                // 检查是否为图片的data URL格式
                                if (text.startsWith("data:image/") && text.contains(",")) {
                                    // 从data URL中提取纯base64部分
                                    val commaIndex = text.indexOf(',')
                                    if (commaIndex > 0) {
                                        val base64Image = text.substring(commaIndex + 1)
                                        return Pair(CLIPBOARD_TYPE_IMAGE, base64Image)
                                    }
                                }
                                return Pair(CLIPBOARD_TYPE_TEXT, text)
                            }
                        } catch (e: SecurityException) {
                            // 忽略权限异常，直接返回null
                            return null
                        }
                    }

                    // 处理图片类型剪贴板内容
                    // 检查是否支持图片类型
                    var hasImageType = false
                    for (i in 0 until clipDescription.mimeTypeCount) {
                        if (clipDescription.getMimeType(i).startsWith("image/")) {
                            hasImageType = true
                            break
                        }
                    }

                    if (hasImageType) {
                        try {
                            // 尝试获取Bitmap
                            val imageBitmap: Bitmap? =
                                item.uri?.let { uri ->
                                    try {
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        inputStream?.use {
                                            BitmapFactory.decodeStream(it)
                                        }
                                    } catch (e: Exception) {
                                        // 忽略异常，返回null
                                        null
                                    }
                                }
                            if (imageBitmap != null) {
                                val dataUrl = ImageUtils.bitmapToDataUri(imageBitmap)
                                // 从data URI中提取纯base64部分
                                val commaIndex = dataUrl.indexOf(',')
                                if (commaIndex > 0) {
                                    val base64Image = dataUrl.substring(commaIndex + 1)
                                    return Pair(CLIPBOARD_TYPE_IMAGE, base64Image)
                                }
                            }
                        } catch (e: SecurityException) {
                            // 忽略权限异常，直接返回null
                            return null
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // 忽略权限异常，直接返回null
        } catch (e: Exception) {
            // 忽略其他异常，直接返回null
        }
        return null
    }

    /**
     * 更新本地剪贴板
     */
    private fun updateLocalClipboardContent(
        type: String,
        content: String,
        context: Context,
    ) {
        try {
            getClipboardManager(context)?.let { cm ->
                when (type) {
                    CLIPBOARD_TYPE_TEXT -> {
                        // 使用便捷方法创建文本剪贴板内容
                        val clip = ClipData.newPlainText("synced_text", content)
                        cm.setPrimaryClip(clip)
                        Logger.d(TAG, "已更新本地剪贴板为文本内容")
                    }
                    CLIPBOARD_TYPE_IMAGE -> {
                        val dataUrl = "data:image/png;base64,$content"
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val bitmap = ImageUtils.decodeDataUrlToBitmap(context, dataUrl)
                                if (bitmap != null) {
                                    val clipItem = ClipData.Item(dataUrl)
                                    val mimeTypes = arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
                                    val clip = ClipData("synced_image", mimeTypes, clipItem)
                                    cm.setPrimaryClip(clip)
                                    Logger.d(TAG, "已更新剪贴板，包含图片数据URL")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "更新剪贴板图片失败", e)
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "更新剪贴板失败：访问被拒绝。应用可能未处于前台状态。", e)
        } catch (e: Exception) {
            Logger.e(TAG, "更新剪贴板失败", e)
        }
    }

    /**
     * 手动触发剪贴板同步（通过通知点击调用）
     * 此方法忽略前台检测，直接获取并发送当前剪贴板内容（force=true 跳过内容未变检查）
     */
    fun manualSyncClipboard(
        deviceManager: DeviceConnectionManager,
        context: Context,
    ) {
        Logger.d(TAG, "手动触发剪贴板同步")

        // 临时启用手动同步模式
        val previousMode = isManualSyncMode
        isManualSyncMode = true

        // 先在UI线程获取剪贴板数据，然后再在后台线程调用 Rust
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. 在UI线程获取剪贴板数据
                val clipboardData = getCurrentClipboardData(context)

                if (clipboardData != null) {
                    Logger.d(TAG, "手动同步：剪贴板读取成功")

                    val (type, content) = clipboardData

                    // 2. 切换到后台线程调用 Rust 发送
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val devices = deviceManager.getAuthenticatedOnlineDevices()
                            if (devices.isEmpty()) {
                                Logger.d(TAG, "没有可用于发送剪贴板内容的在线设备")
                                return@launch
                            }

                            handleSendResult(
                                context,
                                NativeCore.clipboardOnChanged(
                                    NativeCore.getContext(),
                                    NativeCore.senderQueuePtr,
                                    buildTargetsJson(devices.map { it.uuid }),
                                    if (type == CLIPBOARD_TYPE_IMAGE) MIME_IMAGE else MIME_TEXT,
                                    content,
                                    System.currentTimeMillis(),
                                    force = true,
                                ),
                                type,
                            )
                        } catch (e: Exception) {
                            Logger.e(TAG, "手动同步：发送剪贴板失败", e)
                        } finally {
                            isManualSyncMode = previousMode
                        }
                    }
                } else {
                    Logger.d(TAG, "手动同步：剪贴板为空或无法获取")
                    isManualSyncMode = previousMode
                }
            } catch (e: Exception) {
                Logger.e(TAG, "手动同步：获取剪贴板失败", e)
                isManualSyncMode = previousMode
            }
        }
    }

    /**
     * 直接同步文本内容到其他设备
     * 不触发系统剪贴板读取，不检查前台状态，用于特定场景（如验证码复制）
     */
    fun syncTextDirectly(
        deviceManager: DeviceConnectionManager,
        text: String,
        context: Context,
    ) {
        if (text.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = deviceManager.getAuthenticatedOnlineDevices()
                if (devices.isEmpty()) {
                    Logger.d(TAG, "直接同步：没有在线设备")
                    return@launch
                }

                handleSendResult(
                    context,
                    NativeCore.clipboardOnChanged(
                        NativeCore.getContext(),
                        NativeCore.senderQueuePtr,
                        buildTargetsJson(devices.map { it.uuid }),
                        MIME_TEXT,
                        text,
                        System.currentTimeMillis(),
                        force = false,
                    ),
                    CLIPBOARD_TYPE_TEXT,
                )
            } catch (e: Exception) {
                Logger.e(TAG, "直接同步失败", e)
            }
        }
    }

    private fun buildTargetsJson(deviceUuids: List<String>): String {
        val arr = JSONArray()
        deviceUuids.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * 处理 Rust 返回的发送结果：file_transfer 动作在 Android 侧尚未实现文件传输通道，
     * 大内容剪贴板会被丢弃，需向用户提示同步失败。
     */
    private fun handleSendResult(
        context: Context,
        resultJson: String?,
        type: String,
    ) {
        val action =
            try {
                JSONObject(resultJson ?: return).optString("action", "skipped")
            } catch (e: Exception) {
                return
            }
        when (action) {
            "sent" -> Logger.d(TAG, "剪贴板已发送（Rust 处理）：$type")
            "skipped" -> Logger.d(TAG, "剪贴板已跳过（Rust 处理）")
            "file_transfer" -> {
                Logger.w(TAG, "剪贴板大内容需走文件传输通道，同步失败")
                Handler(Looper.getMainLooper()).post {
                    ToastUtils.showShortToast(context, "剪贴板内容过大（超过 2MB），同步失败")
                }
            }
            else -> Logger.d(TAG, "剪贴板结果未知：$action")
        }
    }
}
