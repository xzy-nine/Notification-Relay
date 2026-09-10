package com.xzyht.notifyrelay.feature.appslist.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.sync.ProtocolSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import notifyrelay.base.util.Logger
import notifyrelay.data.database.entity.AppDeviceEntity
import notifyrelay.data.database.repository.DatabaseRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 图标同步管理器
 *
 * 负责在已认证的设备之间同步应用图标，以避免重复传输并降低网络与性能开销。
 * 请求过滤（已缓存/已安装/pending/设备关联）与报文构造由 Rust core（nrc_app_sync_*）处理，
 * 平台端仅负责位图编解码与本地缓存。
 */
object IconSyncManager {
    private const val TAG = "IconSyncManager"
    private const val ICON_REQUEST_TIMEOUT_MS = 10000L

    /**
     * 检查并（必要时）请求单个图标。
     */
    fun checkAndSyncIcon(
        context: Context,
        packageName: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
    ) {
        // 检查本机已安装应用
        val installedPackages = AppRepository.getInstalledPackageNames(context)
        if (installedPackages.contains(packageName)) {
            return
        }
        val appDeviceUuids =
            runBlocking {
                val databaseRepository = DatabaseRepository.getInstance(context)
                val appDevices = databaseRepository.getAppDevicesByPackageName(packageName).first()
                appDevices.map { appDevice -> appDevice.sourceDevice }
            }

        // 已缓存图标来源：与批量路径一致，Rust 据此过滤避免对已缓存图标重复发起 ICON_REQUEST
        val cachedPackages =
            runBlocking {
                val iconMap = AppRepository.getExternalAppIcons(context, listOf(packageName))
                if (iconMap[packageName] != null) listOf(packageName) else emptyList()
            }

        // Rust 内部完成过滤（缓存/已安装/pending/设备关联）并构造请求报文
        val requestJson =
            NativeCore.appSyncPrepareIconRequest(
                NativeCore.getContext(),
                listOf(packageName),
                installedPackages.toList(),
                cachedPackages,
                if (appDeviceUuids.isEmpty()) emptyMap() else mapOf(packageName to appDeviceUuids),
                sourceDevice.uuid,
                System.currentTimeMillis(),
            )
        if (requestJson == null || requestJson == "{}") return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestIconsFromDevice(context, requestJson, deviceManager, sourceDevice)
                // 请求成功，关联应用包名与当前设备
                val databaseRepository = DatabaseRepository.getInstance(context)
                val appDeviceEntity =
                    AppDeviceEntity(
                        packageName = packageName,
                        sourceDevice = sourceDevice.uuid,
                        lastUpdated = System.currentTimeMillis(),
                    )
                databaseRepository.saveAppDeviceAssociations(listOf(appDeviceEntity))
                NativeCore.appSyncClearIconPending(NativeCore.getContext(), listOf(packageName))
            } catch (e: Exception) {
                Logger.e(TAG, "请求图标失败：$packageName", e)
                NativeCore.appSyncClearIconPending(NativeCore.getContext(), listOf(packageName))
            }
        }
    }

    /**
     * 批量请求多个包名图标（自动过滤已存在或正在请求的，过滤逻辑在 Rust 内部）。
     */
    suspend fun requestIconsBatch(
        context: Context,
        packageNames: List<String>,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
    ) {
        if (packageNames.size == 0) return

        val installedPackages = AppRepository.getInstalledPackageNames(context)

        // 预获取已缓存图标与设备关联关系
        val iconMap = AppRepository.getExternalAppIcons(context, packageNames)
        val cachedPackages = packageNames.filter { iconMap[it] != null }

        val databaseRepository = DatabaseRepository.getInstance(context)
        val appDevices = databaseRepository.getAppDevicesByPackageNames(packageNames)
        val appDeviceMap =
            appDevices
                .groupBy { it.packageName }
                .mapValues { (_, entities) -> entities.map { it.sourceDevice } }

        // Rust 内部完成过滤（缓存/已安装/pending/设备关联）并构造请求报文
        val requestJson =
            NativeCore.appSyncPrepareIconRequest(
                NativeCore.getContext(),
                packageNames,
                installedPackages.toList(),
                cachedPackages,
                appDeviceMap,
                sourceDevice.uuid,
                System.currentTimeMillis(),
            )
        if (requestJson == null || requestJson == "{}") return

        val sourceDeviceUuid = sourceDevice.uuid
        try {
            requestIconsFromDevice(context, requestJson, deviceManager, sourceDevice)
            // 请求成功，批量关联应用包名与当前设备
            val need = parseRequestedPackages(requestJson)
            val appDeviceEntities =
                need.map {
                    AppDeviceEntity(
                        packageName = it,
                        sourceDevice = sourceDeviceUuid,
                        lastUpdated = System.currentTimeMillis(),
                    )
                }
            databaseRepository.saveAppDeviceAssociations(appDeviceEntities)
            NativeCore.appSyncClearIconPending(NativeCore.getContext(), need)
        } catch (e: Exception) {
            Logger.e(TAG, "批量请求失败：$packageNames", e)
            NativeCore.appSyncClearIconPending(NativeCore.getContext(), parseRequestedPackages(requestJson))
        }
    }

    private fun parseRequestedPackages(requestJson: String): List<String> =
        try {
            val json = JSONObject(requestJson)
            json.optString("packageName").takeIf { it.isNotEmpty() }?.let { listOf(it) }
                ?: json.optJSONArray("packageNames")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
                }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * 发送由 Rust 构造好的 ICON_REQUEST 请求报文。
     */
    private suspend fun requestIconsFromDevice(
        context: Context,
        requestJson: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
    ) {
        val result =
            ProtocolSender.sendEncrypted(
                deviceManager,
                sourceDevice,
                "DATA_ICON_REQUEST",
                requestJson,
                ICON_REQUEST_TIMEOUT_MS,
            )
        if (result != ProtocolSender.EnqueueResult.SUCCESS) {
            // 入队失败时抛异常，使调用方进入 catch 分支清理 pending 状态，避免后续无法重新请求
            throw IllegalStateException("图标请求入队失败: $result")
        }
    }

    /**
     * 处理 ICON_REQUEST 请求（支持单个或批量）。
     */
    fun handleIconRequest(
        requestData: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
        context: Context,
    ) {
        try {
            val json = JSONObject(requestData)
            val type = json.optString("type")
            Logger.d(TAG, "解析到的 type 字段值：$type")
            if (type != "ICON_REQUEST" && type != "DATA_ICON_REQUEST") return

            val single = json.optString("packageName")
            val multiArray = json.optJSONArray("packageNames")

            if (multiArray != null && multiArray.length() > 0) {
                // 批量
                val resultArr = JSONArray()
                val missingArr = JSONArray()
                runBlocking {
                    for (i in 0 until multiArray.length()) {
                        val pkg = multiArray.optString(i)
                        if (pkg.isNullOrEmpty()) continue
                        val icon = getLocalAppIcon(context, pkg)
                        if (icon != null) {
                            val base64 = bitmapToBase64(icon)
                            val item =
                                JSONObject().apply {
                                    put("packageName", pkg)
                                    put("iconData", base64)
                                }
                            resultArr.put(item)
                        } else {
                            // 记录缺失的图标
                            missingArr.put(pkg)
                        }
                    }
                }

                // 构建响应，包含可用图标和缺失图标信息
                val raw =
                    JSONObject()
                        .apply {
                            put("type", "ICON_RESPONSE")
                            if (resultArr.length() > 0) {
                                put("icons", resultArr)
                            }
                            if (missingArr.length() > 0) {
                                put("missing", missingArr)
                            }
                            put("time", System.currentTimeMillis())
                        }.toString()

                Logger.d(TAG, "批量图标响应准备发送，包含 ${resultArr.length()} 个图标，${missingArr.length()} 个缺失图标")
                ProtocolSender.sendEncrypted(deviceManager, sourceDevice, "DATA_ICON_RESPONSE", raw, ICON_REQUEST_TIMEOUT_MS)
                Logger.d(TAG, "批量图标响应已发送(${resultArr.length()}) -> ${sourceDevice.displayName}")
            } else if (single.isNotEmpty()) {
                val icon =
                    runBlocking {
                        getLocalAppIcon(context, single)
                    }
                val raw =
                    JSONObject()
                        .apply {
                            put("type", "ICON_RESPONSE")
                            put("packageName", single)
                            if (icon != null) {
                                put("iconData", bitmapToBase64(icon))
                            } else {
                                put("missing", true)
                            }
                            put("time", System.currentTimeMillis())
                        }.toString()

                Logger.d(TAG, "单图标响应准备发送，包名：$single，${if (icon != null) "有图标" else "无图标"}")
                ProtocolSender.sendEncrypted(deviceManager, sourceDevice, "DATA_ICON_RESPONSE", raw, ICON_REQUEST_TIMEOUT_MS)
                Logger.d(TAG, "单图标响应已发送：$single -> ${sourceDevice.displayName}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理ICON_REQUEST异常", e)
        }
    }

    /**
     * 处理 ICON_RESPONSE（单个或批量）。解析由 Rust 完成。
     */
    fun handleIconResponse(
        responseData: String,
        context: Context,
    ) {
        try {
            val parsed = NativeCore.appSyncParseIconResponse(responseData) ?: return
            val result = JSONObject(parsed)

            val iconsArray = result.optJSONArray("icons")
            if (iconsArray != null && iconsArray.length() > 0) {
                for (i in 0 until iconsArray.length()) {
                    val item = iconsArray.optJSONObject(i) ?: continue
                    val pkg = item.optString("packageName")
                    val base64 = item.optString("iconData")
                    if (pkg.isNotEmpty() && base64.isNotEmpty()) {
                        cacheDecodedIcon(context, pkg, base64)
                    }
                }
            }

            // 处理缺失图标
            val missingArray = result.optJSONArray("missing")
            if (missingArray != null && missingArray.length() > 0) {
                for (i in 0 until missingArray.length()) {
                    val missingPkg = missingArray.optString(i)
                    if (missingPkg.isNotEmpty()) {
                        runBlocking {
                            val databaseRepository = DatabaseRepository.getInstance(context)
                            databaseRepository.markAppIconAsMissing(missingPkg)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理ICON_RESPONSE异常", e)
        }
    }

    private fun cacheDecodedIcon(
        context: Context,
        packageName: String,
        base64: String,
    ) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            runBlocking {
                AppRepository.cacheExternalAppIcon(context, packageName, bmp, "remote")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "图标解码失败：$packageName", e)
        }
    }

    private fun bitmapToBase64(icon: Bitmap): String {
        val bos = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun getLocalAppIcon(
        context: Context,
        packageName: String,
    ): Bitmap? =
        try {
            AppRepository.getAppIconAsync(context, packageName) ?: run {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val drawable = pm.getApplicationIcon(appInfo)
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
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
