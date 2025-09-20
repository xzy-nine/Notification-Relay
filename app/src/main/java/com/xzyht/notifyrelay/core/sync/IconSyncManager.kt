package com.xzyht.notifyrelay.core.sync

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 图标同步管理器
 * 负责不同设备之间的应用图标同步，避免重复传输以减少网络和性能开销
 */
object IconSyncManager {

    private const val TAG = "IconSyncManager"
    private const val ICON_REQUEST_TIMEOUT = 10000L // 10秒超时

    // 正在请求的图标缓存，避免重复请求
    private val pendingRequests = mutableMapOf<String, Long>() // packageName -> requestTime

    /**
     * 检查并同步应用图标
     * @param context 上下文
     * @param packageName 应用包名
     * @param deviceManager 设备管理器
     * @param sourceDevice 发送通知的设备（用于请求图标）
     */
    fun checkAndSyncIcon(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        // 检查本地是否已有图标
        val existingIcon = AppRepository.getExternalAppIcon(packageName)
        if (existingIcon != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "图标已存在，跳过同步: $packageName")
            return
        }

        // 检查是否正在请求中，避免重复请求
        val now = System.currentTimeMillis()
        val lastRequest = pendingRequests[packageName]
        if (lastRequest != null && (now - lastRequest) < ICON_REQUEST_TIMEOUT) {
            if (BuildConfig.DEBUG) Log.d(TAG, "图标请求进行中，跳过: $packageName")
            return
        }

        // 标记为正在请求
        pendingRequests[packageName] = now

        // 异步请求图标
        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconFromDevice(context, packageName, deviceManager, sourceDevice)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "请求图标失败: $packageName", e)
            } finally {
                // 清理过期请求
                pendingRequests.remove(packageName)
            }
        }
    }

    /**
     * 从指定设备请求图标
     */
    private suspend fun requestIconFromDevice(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        try {
            // 构建图标请求JSON
            val requestJson = JSONObject().apply {
                put("type", "ICON_REQUEST")
                put("packageName", packageName)
                put("time", System.currentTimeMillis())
            }.toString()

            // 发送请求（复用通知发送机制）
            sendIconRequest(deviceManager, sourceDevice, requestJson)

            if (BuildConfig.DEBUG) Log.d(TAG, "已发送图标请求: $packageName -> ${sourceDevice.displayName}")

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标请求失败: $packageName", e)
        }
    }

    /**
     * 发送图标请求
     * 复用现有的通知发送机制，但使用特殊标识
     */
    private fun sendIconRequest(
        deviceManager: DeviceConnectionManager,
        device: DeviceInfo,
        requestData: String
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[device.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过图标请求: ${device.displayName}")
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(ICON_REQUEST_TIMEOUT) {
                        val socket = java.net.Socket()
                        try {
                            socket.connect(java.net.InetSocketAddress(device.ip, device.port), 5000)
                            val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                            val encryptedData = deviceManager.encryptData(requestData, auth.sharedSecret)
                            // 使用特殊前缀标识图标请求
                            val payload = "DATA_ICON_REQUEST:${deviceManager.uuid}:${deviceManager.localPublicKey}:${auth.sharedSecret}:${encryptedData}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "图标请求发送成功: ${device.displayName}")
                        } finally {
                            socket.close()
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "图标请求发送失败: ${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标请求异常", e)
        }
    }

    /**
     * 处理接收到的图标请求
     * @param requestData 解密后的请求数据
     * @param deviceManager 设备管理器
     * @param sourceDevice 请求来源设备
     * @param context 上下文
     */
    fun handleIconRequest(
        requestData: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
        context: Context
    ) {
        try {
            val json = JSONObject(requestData)
            val type = json.optString("type")
            if (type != "ICON_REQUEST") return

            val packageName = json.optString("packageName")
            if (packageName.isEmpty()) return

            if (BuildConfig.DEBUG) Log.d(TAG, "收到图标请求: $packageName from ${sourceDevice.displayName}")

            // 获取本地图标
            val icon = getLocalAppIcon(context, packageName)
            if (icon != null) {
                // 发送图标响应
                sendIconResponse(deviceManager, sourceDevice, packageName, icon)
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "本地无图标，忽略请求: $packageName")
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理图标请求失败", e)
        }
    }

    /**
     * 获取本地应用图标
     */
    private fun getLocalAppIcon(context: Context, packageName: String): Bitmap? {
        return try {
            // 优先从缓存获取
            var icon = AppRepository.getAppIconSync(context, packageName)
            if (icon != null) return icon

            // 从PackageManager获取
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)

            icon = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                else -> {
                    // 转换为Bitmap
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                    bitmap
                }
            }
            icon
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "获取本地图标失败: $packageName", e)
            null
        }
    }

    /**
     * 发送图标响应
     */
    private fun sendIconResponse(
        deviceManager: DeviceConnectionManager,
        targetDevice: DeviceInfo,
        packageName: String,
        icon: Bitmap
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[targetDevice.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过图标响应: ${targetDevice.displayName}")
                return
            }

            // 将Bitmap转换为Base64字符串
            val outputStream = ByteArrayOutputStream()
            icon.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val iconBytes = outputStream.toByteArray()
            val iconBase64 = Base64.encodeToString(iconBytes, Base64.DEFAULT)

            // 构建响应JSON
            val responseJson = JSONObject().apply {
                put("type", "ICON_RESPONSE")
                put("packageName", packageName)
                put("iconData", iconBase64)
                put("time", System.currentTimeMillis())
            }.toString()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(ICON_REQUEST_TIMEOUT) {
                        val socket = java.net.Socket()
                        try {
                            socket.connect(java.net.InetSocketAddress(targetDevice.ip, targetDevice.port), 5000)
                            val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                            val encryptedData = deviceManager.encryptData(responseJson, auth.sharedSecret)
                            // 使用特殊前缀标识图标响应
                            val payload = "DATA_ICON_RESPONSE:${deviceManager.uuid}:${deviceManager.localPublicKey}:${auth.sharedSecret}:${encryptedData}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "图标响应发送成功: $packageName -> ${targetDevice.displayName}")
                        } finally {
                            socket.close()
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "图标响应发送失败: ${targetDevice.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标响应异常", e)
        }
    }

    /**
     * 处理接收到的图标响应
     * @param responseData 解密后的响应数据
     * @param context 上下文
     */
    fun handleIconResponse(responseData: String, context: Context) {
        try {
            val json = JSONObject(responseData)
            val type = json.optString("type")
            if (type != "ICON_RESPONSE") return

            val packageName = json.optString("packageName")
            val iconBase64 = json.optString("iconData")

            if (packageName.isEmpty() || iconBase64.isEmpty()) return

            if (BuildConfig.DEBUG) Log.d(TAG, "收到图标响应: $packageName")

            // 解码Base64并转换为Bitmap
            val iconBytes = Base64.decode(iconBase64, Base64.DEFAULT)
            val icon = android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
            if (icon == null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "图标解码失败: $packageName")
                return
            }

            // 缓存图标
            AppRepository.cacheExternalAppIcon(packageName, icon)

            if (BuildConfig.DEBUG) Log.d(TAG, "图标缓存成功: $packageName")

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理图标响应失败", e)
        }
    }

    /**
     * 清理过期请求
     */
    fun cleanupExpiredRequests() {
        val now = System.currentTimeMillis()
        pendingRequests.entries.removeIf { (packageName, requestTime) ->
            val expired = (now - requestTime) > ICON_REQUEST_TIMEOUT * 2
            if (expired && BuildConfig.DEBUG) {
                Log.d(TAG, "清理过期图标请求: $packageName")
            }
            expired
        }
    }
}