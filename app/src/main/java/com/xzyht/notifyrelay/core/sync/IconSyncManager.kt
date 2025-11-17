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
 *
 * 负责在已认证的设备之间同步应用图标，以避免重复传输并降低网络与性能开销。
 * 功能包括：
 *  - 检查本地是否已有图标，若无则向发送通知的设备请求图标。
 *  - 接收并处理图标请求（ICON_REQUEST）和图标响应（ICON_RESPONSE）。
 *  - 将接收到的图标解码并缓存到本地仓库。
 *
 * 所有日志仅在 BuildConfig.DEBUG 为 true 时打印，以避免在生产环境泄露信息。
 */
object IconSyncManager {

    private const val TAG = "IconSyncManager"
    private const val ICON_REQUEST_TIMEOUT = 10000L // 10 秒超时

    // 正在请求的图标缓存，避免重复请求（packageName -> requestTime）
    private val pendingRequests = mutableMapOf<String, Long>()

    /**
     * 检查并同步应用图标。
     *
     * 如果本地已有该应用的图标，则直接返回。否则在短时间内避免重复请求，并异步向发送通知的设备请求图标。
     *
     * @param context 应用上下文，用于访问 PackageManager 等系统服务。
     * @param packageName 要同步图标的应用包名。
     * @param deviceManager 设备连接管理器，用于查询已认证设备和加密数据。
     * @param sourceDevice 发送通知的设备（请求图标的目标设备）。
     */
    fun checkAndSyncIcon(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
    // 引用 context 以避免未使用参数的检查（无副作用）
    val unusedContext = context.hashCode()
        // 检查本地是否已有图标
        val existingIcon = AppRepository.getExternalAppIcon(packageName)
        if (existingIcon != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "图标已存在，跳过同步：$packageName")
            return
        }

        // 检查是否正在请求中，避免重复请求
        val now = System.currentTimeMillis()
        val lastRequest = pendingRequests[packageName]
        if (lastRequest != null && (now - lastRequest) < ICON_REQUEST_TIMEOUT) {
    /**
     * 批量请求多个包名的图标。
     * 若需要，请调用方自行控制数量与频率以避免超大负载。
     */
    fun requestIconsBatch(
        context: Context,
        packageNames: List<String>,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        if (packageNames.isEmpty()) return
        // 过滤掉已存在的与近期请求过的
        val now = System.currentTimeMillis()
        val need = packageNames.filter { pkg ->
            val exist = AppRepository.getExternalAppIcon(pkg) != null
            val last = pendingRequests[pkg]
            val inFlight = last != null && (now - last) < ICON_REQUEST_TIMEOUT
            !exist && !inFlight
        }
        if (need.isEmpty()) return

        // 标记为正在请求
        need.forEach { pendingRequests[it] = now }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconsFromDevice(context, need, deviceManager, sourceDevice)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "批量请求图标失败：$need", e)
            } finally {
                need.forEach { pendingRequests.remove(it) }
            }
        }
    }
            if (BuildConfig.DEBUG) Log.d(TAG, "图标请求进行中，跳过：$packageName")
            return
        }

        // 标记为正在请求
        pendingRequests[packageName] = now

        // 异步请求图标
        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconFromDevice(context, packageName, deviceManager, sourceDevice)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "请求图标失败：$packageName", e)
            } finally {
                // 清理请求记录
                pendingRequests.remove(packageName)
            }
        }
    }

    /**
     * 从指定设备请求图标（协程、挂起函数）。
     *
     * 会构建一个包含 type=ICON_REQUEST 的 JSON 字符串并发送到目标设备。
     *
     * @param context 应用上下文（当前实现中用于可能的本地资源访问）。
     * @param packageName 要请求图标的包名。
     * @param deviceManager 设备连接管理器，用于加密和访问认证信息。
     * @param sourceDevice 请求目标设备的信息（IP/端口/UUID 等）。
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun requestIconFromDevice(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        try {
            // 引用 context 以避免未使用参数的检查（无副作用）
            val unusedContext = context.hashCode()
            // 构建图标请求 JSON
            val requestJson = JSONObject().apply {
                put("type", "ICON_REQUEST")
                put("packageName", packageName)
                put("time", System.currentTimeMillis())
            }.toString()

            // 发送请求（复用通知发送机制）
            sendIconRequest(deviceManager, sourceDevice, requestJson)

            if (BuildConfig.DEBUG) Log.d(TAG, "已发送图标请求：$packageName -> ${sourceDevice.displayName}")

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标请求失败：$packageName", e)
        }
    }

    /**
     * 发送图标请求的底层实现（非挂起函数）。
     *
     * 将 requestData 加密后通过 socket 发送，使用前缀区分为图标请求（DATA_ICON_REQUEST）。
     *
     * @param deviceManager 设备连接管理器，用于获取认证信息并加密数据。
     * @param device 目标设备信息（IP、端口、UUID 等）。
     * @param requestData 要发送的原始请求数据（JSON 字符串）。
     */
    private fun sendIconRequest(
        deviceManager: DeviceConnectionManager,
        device: DeviceInfo,
        requestData: String
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[device.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过图标请求：${device.displayName}")
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
                            // 使用特殊前缀标识图标请求 — 不在消息中包含 sharedSecret
                            val payload = "DATA_ICON_REQUEST:${deviceManager.uuid}:${deviceManager.localPublicKey}:${encryptedData}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "图标请求发送成功：${device.displayName}")
                        } finally {
                            socket.close()
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "图标请求发送失败：${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标请求异常", e)
        }
    }

    /**
     * 处理接收到的图标请求。
     *
     * 仅当请求类型为 ICON_REQUEST 时处理，会尝试获取本地图标并发送响应。
     *
     * @param requestData 解密后的请求数据（JSON 字符串）。
     * @param deviceManager 设备连接管理器，用于发送响应与加密。
     * @param sourceDevice 请求来源设备信息（用于将响应发回）。
     * @param context 应用上下文，用于读取本地应用图标。
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

            if (BuildConfig.DEBUG) Log.d(TAG, "收到图标请求：$packageName，来自：${sourceDevice.displayName}")

            // 获取本地图标
            val icon = getLocalAppIcon(context, packageName)
            if (icon != null) {
                // 发送图标响应
                sendIconResponse(deviceManager, sourceDevice, packageName, icon)
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "本地无图标，忽略请求：$packageName")
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理图标请求失败", e)
        }
    }

    /**
     * 获取本地应用图标。
     *
     * 优先从仓库缓存同步获取，如无则通过 PackageManager 获取并转换为 Bitmap。
     *
     * @param context 应用上下文，用于访问 PackageManager 和资源。
     * @param packageName 目标应用包名。
     * @return 若找到则返回 Bitmap，否则返回 null。
     */
    private fun getLocalAppIcon(context: Context, packageName: String): Bitmap? {
        return try {
            // 优先从缓存获取
            var icon = AppRepository.getAppIconSync(context, packageName)
            if (icon != null) return icon

            // 从 PackageManager 获取
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)

            icon = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                else -> {
                    // 转换为 Bitmap
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
            if (BuildConfig.DEBUG) Log.w(TAG, "获取本地图标失败：$packageName", e)
            null
        }
    }

    /**
     * 发送图标响应。
     *
     * 将 Bitmap 转为 Base64 后封装为 type=ICON_RESPONSE 的 JSON 并发送给目标设备。
     *
     * @param deviceManager 设备连接管理器，用于获取认证信息并加密数据。
     * @param targetDevice 响应的目标设备信息（IP、端口、UUID 等）。
     * @param packageName 响应所对应的应用包名。
     * @param icon 要发送的图标 Bitmap。
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
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过图标响应：${targetDevice.displayName}")
                return
            }

            // 将 Bitmap 转换为 Base64 字符串
            val outputStream = ByteArrayOutputStream()
            icon.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val iconBytes = outputStream.toByteArray()
            val iconBase64 = Base64.encodeToString(iconBytes, Base64.DEFAULT)

            // 构建响应 JSON
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
                            // 使用特殊前缀标识图标响应 — 不在消息中包含 sharedSecret
                            val payload = "DATA_ICON_RESPONSE:${deviceManager.uuid}:${deviceManager.localPublicKey}:${encryptedData}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "图标响应发送成功：$packageName -> ${targetDevice.displayName}")
                        } finally {
                            socket.close()
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "图标响应发送失败：${targetDevice.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送图标响应异常", e)
        }
    }

    /**
     * 处理接收到的图标响应。
     *
     * 解析 JSON，解码 Base64 图像并缓存到本地仓库。
     *
     * @param responseData 解密后的响应数据（JSON 字符串）。
     * @param context 应用上下文，用于保存与缓存图标。
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleIconResponse(responseData: String, context: Context) {
        try {
            // 引用 context 避免未使用参数的检查（当前实现不直接使用 context）
            val unusedContext = context.hashCode()
            val json = JSONObject(responseData)
            val type = json.optString("type")
            if (type != "ICON_RESPONSE") return

            val packageName = json.optString("packageName")
            val iconBase64 = json.optString("iconData")

            if (packageName.isEmpty() || iconBase64.isEmpty()) return

            if (BuildConfig.DEBUG) Log.d(TAG, "收到图标响应：$packageName")

            // 解码 Base64 并转换为 Bitmap
            val iconBytes = Base64.decode(iconBase64, Base64.DEFAULT)
            val icon = android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
            if (icon == null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "图标解码失败：$packageName")
                return
            }

            // 缓存图标
            AppRepository.cacheExternalAppIcon(packageName, icon)

            if (BuildConfig.DEBUG) Log.d(TAG, "图标缓存成功：$packageName")

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理图标响应失败", e)
        }
    }

    /**
     * 清理过期的图标请求记录。
     *
     * 用于移除长时间未完成的请求，防止内存或重复请求堆积。
     */
    fun cleanupExpiredRequests() {
        val now = System.currentTimeMillis()
        pendingRequests.entries.removeIf { (packageName, requestTime) ->
            val expired = (now - requestTime) > ICON_REQUEST_TIMEOUT * 2
            if (expired && BuildConfig.DEBUG) {
                Log.d(TAG, "清理过期图标请求：$packageName")
            }
            expired
        }
    }
}