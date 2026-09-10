package com.xzyht.notifyrelay.feature.appslist.sync

import android.content.Context
import com.xzyht.notifyrelay.feature.appslist.AppListHelper
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.sync.ProtocolSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import notifyrelay.data.database.entity.AppDeviceEntity
import notifyrelay.data.database.repository.DatabaseRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用列表同步管理器
 *
 * 通过与图标同步类似的加密通道，按需在设备之间传输“用户应用”的包名与应用名列表。
 *
 * 协议前缀：
 * - 请求：DATA_APP_LIST_REQUEST
 * - 响应：DATA_APP_LIST_RESPONSE
 *
 * 负载明文（加密前）约定：
 * - 请求：{"type":"APP_LIST_REQUEST","scope":"user","time":<ms>}
 * - 响应：{"type":"APP_LIST_RESPONSE","scope":"user","apps":[{"packageName":"...","appName":"..."},...],"total":N,"time":<ms>}
 */
object AppListSyncManager {
    private const val TAG = "AppListSyncManager"
    private const val REQ_TIMEOUT = 15000L
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 主动向目标设备请求其“用户应用”列表。
     */
    fun requestAppListFromDevice(
        context: Context,
        deviceManager: DeviceConnectionManager,
        targetDevice: DeviceInfo,
        scope: String = "user",
    ) {
        context.hashCode()
        val raw =
            NativeCore.appSyncBuildApplistRequest(scope, System.currentTimeMillis())
                ?: return
        ProtocolSender.sendEncrypted(deviceManager, targetDevice, "DATA_APP_LIST_REQUEST", raw, REQ_TIMEOUT)
    }

    /**
     * 处理接收到的应用列表请求。
     * 接收后采集本机“用户应用”列表，并通过响应通道发回。
     */
    fun handleAppListRequest(
        requestData: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
        context: Context,
    ) {
        try {
            val json = JSONObject(requestData)
            val type = json.optString("type")
            Logger.d(TAG, "解析到的 type 字段值：$type")
            if (type != "APP_LIST_REQUEST" && type != "DATA_APP_LIST_REQUEST") return
            val scope = json.optString("scope", "user")

            val apps = AppListHelper.getInstalledApplications(context)
            val userApps =
                when (scope) {
                    "user" -> apps // AppListHelper 已过滤系统/自身
                    else -> apps
                }

            val appArray = JSONArray()
            val pm = context.packageManager
            for (ai in userApps) {
                try {
                    val appName =
                        try {
                            pm.getApplicationLabel(ai).toString()
                        } catch (_: Exception) {
                            ai.packageName
                        }
                    val item = JSONObject()
                    item.put("packageName", ai.packageName)
                    item.put("appName", appName)
                    appArray.put(item)
                } catch (_: Exception) {
                }
            }

            val raw =
                JSONObject()
                    .apply {
                        put("type", "APP_LIST_RESPONSE")
                        put("scope", scope)
                        put("total", appArray.length())
                        put("apps", appArray)
                        put("time", System.currentTimeMillis())
                    }.toString()
            sendAppListResponse(deviceManager, sourceDevice, raw)
            Logger.d(TAG, "已响应应用列表：${sourceDevice.displayName}，共${appArray.length()}项")
        } catch (e: Exception) {
            Logger.e(TAG, "处理应用列表请求失败", e)
        }
    }

    private fun sendAppListResponse(
        deviceManager: DeviceConnectionManager,
        target: DeviceInfo,
        responseData: String,
    ) {
        ProtocolSender.sendEncrypted(deviceManager, target, "DATA_APP_LIST_RESPONSE", responseData, REQ_TIMEOUT)
    }

    /**
     * 处理接收到的应用列表响应。
     * 1. 解析响应数据，将应用列表缓存到 AppRepository
     * 2. 将应用包名与来源设备关联
     * 3. 检查并批量请求缺失的图标
     */
    fun handleAppListResponse(
        responseData: String,
        context: Context,
        deviceUuid: String,
        deviceManager: DeviceConnectionManager,
    ) {
        try {
            // 解析由 Rust 完成
            val parsed = NativeCore.appSyncParseApplistResponse(responseData) ?: return
            val json = JSONObject(parsed)
            val total = json.optInt("total", -1)
            Logger.d(TAG, "收到应用列表响应，共 $total 项，来源设备：$deviceUuid")

            // 解析应用列表
            val appsArray = json.optJSONArray("apps") ?: return
            val appsMap = mutableMapOf<String, String>()
            val packageNames = mutableListOf<String>()
            for (i in 0 until appsArray.length()) {
                val appItem = appsArray.optJSONObject(i) ?: continue
                val packageName = appItem.optString("packageName")
                val appName = appItem.optString("appName")
                if (packageName.isNotEmpty() && appName.isNotEmpty()) {
                    appsMap[packageName] = appName
                    packageNames.add(packageName)
                }
            }

            // 缓存到 AppRepository
            ioScope.launch {
                AppRepository.cacheRemoteAppList(context, appsMap, deviceUuid)

                // 关联应用包名与设备（替代原 associateAppsWithDevice 方法）
                val databaseRepository = DatabaseRepository.getInstance(context)
                val appDeviceEntities =
                    packageNames.map {
                        AppDeviceEntity(
                            packageName = it,
                            sourceDevice = deviceUuid,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    }
                databaseRepository.saveAppDeviceAssociations(appDeviceEntities)

                // 请求缺失的图标
                val sourceDevice = deviceManager.resolveDeviceInfo(deviceUuid, null, 23333)
                sourceDevice?.let { checkAndRequestMissingIcons(context, packageNames, deviceManager, it) }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理应用列表响应失败", e)
        }
    }

    /**
     * 检查并批量请求缺失的图标。
     *
     * @param context 上下文
     * @param packageNames 要检查的包名列表
     * @param deviceManager 设备连接管理器
     * @param sourceDevice 源设备信息
     */
    fun checkAndRequestMissingIcons(
        context: Context,
        packageNames: List<String>,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
    ) {
        ioScope.launch {
            // 检查缺失的图标（替代原 getMissingIconsForPackages 方法）
            val databaseRepository = DatabaseRepository.getInstance(context)
            val missingIcons =
                packageNames.filter { pkg ->
                    val app = databaseRepository.getAppByPackageName(pkg)
                    app?.isIconMissing ?: true
                }
            if (missingIcons.isEmpty()) {
                // Logger.d(TAG, "所有图标已缓存，无需请求")
                return@launch
            }

            // 过滤掉本机已安装的应用（本机已安装的应用图标可直接获取，无需请求）
            val installedPackages = AppRepository.getInstalledPackageNames(context)
            val needRequestIcons = missingIcons.filter { !installedPackages.contains(it) }

            if (needRequestIcons.isEmpty()) {
                // Logger.d(TAG, "所有缺失图标为本机已安装应用，无需请求")
                return@launch
            }

            // 批量请求缺失的图标
            IconSyncManager.requestIconsBatch(context, needRequestIcons, deviceManager, sourceDevice)
            // Logger.d(TAG, "批量请求缺失图标：${needRequestIcons.size} 个")
        }
    }
}
