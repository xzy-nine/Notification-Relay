package com.xzyht.notifyrelay.common.core.sync

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.xzyht.notifyrelay.common.core.repository.AppRepository
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
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
    private const val ICON_REQUEST_TIMEOUT = 10000L

    // 正在请求的图标缓存，避免重复请求（packageName -> requestTime）
    private val pendingRequests = mutableMapOf<String, Long>()

    /**
     * 检查并（必要时）请求单个图标。
     */
    fun checkAndSyncIcon(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        val exist = AppRepository.getExternalAppIcon(packageName)
        if (exist != null) {
            Logger.d(TAG, "图标已存在，跳过：$packageName")
            return
        }
        val now = System.currentTimeMillis()
        val last = pendingRequests[packageName]
        if (last != null && (now - last) < ICON_REQUEST_TIMEOUT) {
            Logger.d(TAG, "单图标请求进行中，跳过：$packageName")
            return
        }
        pendingRequests[packageName] = now
        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconsFromDevice(context, listOf(packageName), deviceManager, sourceDevice)
            } catch (e: Exception) {
                Logger.e(TAG, "请求图标失败：$packageName", e)
            } finally {
                pendingRequests.remove(packageName)
            }
        }
    }

    /**
     * 批量请求多个包名图标（自动过滤已存在或正在请求的）。
     */
    fun requestIconsBatch(
        context: Context,
        packageNames: List<String>,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        Logger.d(TAG, "批量请求图标：$packageNames")
        if (packageNames.isEmpty()) return
        val now = System.currentTimeMillis()
        val need = packageNames.filter { pkg ->
            val exist = AppRepository.getExternalAppIcon(pkg) != null
            val last = pendingRequests[pkg]
            val inFlight = last != null && (now - last) < ICON_REQUEST_TIMEOUT
            !exist && !inFlight
        }
        if (need.isEmpty()) return
        need.forEach { pendingRequests[it] = now }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconsFromDevice(context, need, deviceManager, sourceDevice)
            } catch (e: Exception) {
                Logger.e(TAG, "批量请求失败：$need", e)
            } finally {
                need.forEach { pendingRequests.remove(it) }
            }
        }
    }

    /**
     * 构建并发送（单包或多包） ICON_REQUEST 请求。
     */
    private suspend fun requestIconsFromDevice(
        context: Context,
        packages: List<String>,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo
    ) {
        if (packages.isEmpty()) return
        val json = JSONObject().apply {
            put("type", "ICON_REQUEST")
            if (packages.size == 1) {
                put("packageName", packages.first())
            } else {
                put("packageNames", JSONArray(packages))
            }
            put("time", System.currentTimeMillis())
        }.toString()
        ProtocolSender.sendEncrypted(deviceManager, sourceDevice, "DATA_ICON_REQUEST", json, ICON_REQUEST_TIMEOUT)
        Logger.d(TAG, "发送ICON_REQUEST(${packages.size}) -> ${sourceDevice.displayName}")
    }

    /**
     * 处理 ICON_REQUEST 请求（支持单个或批量）。
     */
    fun handleIconRequest(
        requestData: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
        context: Context
    ) {
        try {
            val json = JSONObject(requestData)
            if (json.optString("type") != "ICON_REQUEST") return

            val single = json.optString("packageName")
            val multiArray = json.optJSONArray("packageNames")

            if (multiArray != null && multiArray.length() > 0) {
                // 批量
                val resultArr = JSONArray()
                for (i in 0 until multiArray.length()) {
                    val pkg = multiArray.optString(i)
                    if (pkg.isNullOrEmpty()) continue
                    val icon = getLocalAppIcon(context, pkg) ?: continue
                    val base64 = bitmapToBase64(icon)
                    val item = JSONObject().apply {
                        put("packageName", pkg)
                        put("iconData", base64)
                    }
                    resultArr.put(item)
                }
                if (resultArr.length() == 0) {
                    Logger.d(TAG, "批量请求均无可用图标，忽略")
                    return
                }
                val resp = JSONObject().apply {
                    put("type", "ICON_RESPONSE")
                    put("icons", resultArr)
                    put("time", System.currentTimeMillis())
                }.toString()
                ProtocolSender.sendEncrypted(deviceManager, sourceDevice, "DATA_ICON_RESPONSE", resp, ICON_REQUEST_TIMEOUT)
                Logger.d(TAG, "批量图标响应发送(${resultArr.length()}) -> ${sourceDevice.displayName}")
            } else if (single.isNotEmpty()) {
                val icon = getLocalAppIcon(context, single)
                if (icon == null) {
                    Logger.d(TAG, "本地无图标：$single")
                    return
                }
                val resp = JSONObject().apply {
                    put("type", "ICON_RESPONSE")
                    put("packageName", single)
                    put("iconData", bitmapToBase64(icon))
                    put("time", System.currentTimeMillis())
                }.toString()
                ProtocolSender.sendEncrypted(deviceManager, sourceDevice, "DATA_ICON_RESPONSE", resp, ICON_REQUEST_TIMEOUT)
                Logger.d(TAG, "单图标响应发送：$single -> ${sourceDevice.displayName}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理ICON_REQUEST异常", e)
        }
    }

    /**
     * 处理 ICON_RESPONSE（单个或批量）。
     */
    fun handleIconResponse(responseData: String, context: Context) {
        try {
            val json = JSONObject(responseData)
            if (json.optString("type") != "ICON_RESPONSE") return

            val iconsArray = json.optJSONArray("icons")
            if (iconsArray != null && iconsArray.length() > 0) {
                for (i in 0 until iconsArray.length()) {
                    val item = iconsArray.optJSONObject(i) ?: continue
                    val pkg = item.optString("packageName")
                    val base64 = item.optString("iconData")
                    cacheDecodedIcon(pkg, base64)
                }
                Logger.d(TAG, "批量图标接收完成：${iconsArray.length()}")
                return
            }

            val pkg = json.optString("packageName")
            val base64 = json.optString("iconData")
            if (pkg.isNotEmpty() && base64.isNotEmpty()) {
                cacheDecodedIcon(pkg, base64)
                Logger.d(TAG, "单图标接收：$pkg")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理ICON_RESPONSE异常", e)
        }
    }

    private fun cacheDecodedIcon(packageName: String, base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            AppRepository.cacheExternalAppIcon(packageName, bmp)
        } catch (e: Exception) {
            Logger.w(TAG, "图标解码失败：$packageName", e)
        }
    }

    private fun bitmapToBase64(icon: Bitmap): String {
        val bos = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT)
    }

    private fun getLocalAppIcon(context: Context, packageName: String): Bitmap? {
        return try {
            AppRepository.getAppIconSync(context, packageName) ?: run {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val drawable = pm.getApplicationIcon(appInfo)
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "获取本地图标失败：$packageName", e)
            null
        }
    }

    fun cleanupExpiredRequests() {
        val now = System.currentTimeMillis()
        pendingRequests.entries.removeIf { (pkg, t) ->
            val expired = (now - t) > ICON_REQUEST_TIMEOUT * 2
            if (expired) Logger.d(TAG, "清理过期请求：$pkg")
            expired
        }
    }
}