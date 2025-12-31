package com.xzyht.notifyrelay.common.core.sync

import android.content.Context
import com.xzyht.notifyrelay.common.core.repository.AppListHelper
import com.xzyht.notifyrelay.common.core.repository.AppRepository
import com.xzyht.notifyrelay.common.core.sync.IconSyncManager
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
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

    /**
     * 主动向目标设备请求其“用户应用”列表。
     */
    fun requestAppListFromDevice(
        context: Context,
        deviceManager: DeviceConnectionManager,
        targetDevice: DeviceInfo,
        scope: String = "user"
    ) {
        context.hashCode()
        val req = JSONObject().apply {
            put("type", "APP_LIST_REQUEST")
            put("scope", scope)
            put("time", System.currentTimeMillis())
        }.toString()
        ProtocolSender.sendEncrypted(deviceManager, targetDevice, "DATA_APP_LIST_REQUEST", req, REQ_TIMEOUT)
    }

    /**
     * 处理接收到的应用列表请求。
     * 接收后采集本机“用户应用”列表，并通过响应通道发回。
     */
    fun handleAppListRequest(
        requestData: String,
        deviceManager: DeviceConnectionManager,
        sourceDevice: DeviceInfo,
        context: Context
    ) {
        try {
            val json = JSONObject(requestData)
            if (json.optString("type") != "APP_LIST_REQUEST") return
            val scope = json.optString("scope", "user")

            val apps = AppListHelper.getInstalledApplications(context)
            val userApps = when (scope) {
                "user" -> apps // AppListHelper 已过滤系统/自身
                else -> apps
            }

            val appArray = JSONArray()
            val pm = context.packageManager
            for (ai in userApps) {
                try {
                    val appName = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName }
                    val item = JSONObject()
                    item.put("packageName", ai.packageName)
                    item.put("appName", appName)
                    appArray.put(item)
                } catch (_: Exception) {}
            }

            val resp = JSONObject().apply {
                put("type", "APP_LIST_RESPONSE")
                put("scope", scope)
                put("total", appArray.length())
                put("apps", appArray)
                put("time", System.currentTimeMillis())
            }.toString()

            sendAppListResponse(deviceManager, sourceDevice, resp)
            //Logger.d(TAG, "已响应应用列表：${sourceDevice.displayName}，共${appArray.length()}项")
        } catch (e: Exception) {
            Logger.e(TAG, "处理应用列表请求失败", e)
        }
    }

    private fun sendAppListResponse(
        deviceManager: DeviceConnectionManager,
        target: DeviceInfo,
        responseData: String
    ) {
        ProtocolSender.sendEncrypted(deviceManager, target, "DATA_APP_LIST_RESPONSE", responseData, REQ_TIMEOUT)
    }

    /**
     * 处理接收到的应用列表响应。
     * 1. 解析响应数据，将应用列表缓存到 AppRepository
     * 2. 将应用包名与来源设备关联
     * 3. 检查并批量请求缺失的图标
     */
    fun handleAppListResponse(responseData: String, context: Context, deviceUuid: String) {
        try {
            val json = JSONObject(responseData)
            if (json.optString("type") != "APP_LIST_RESPONSE") return
            val total = json.optInt("total", -1)
            //Logger.d(TAG, "收到应用列表响应，共 $total 项，来源设备：$deviceUuid")
            
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
            AppRepository.cacheRemoteAppList(appsMap)
            
            // 将应用包名与来源设备关联
            AppRepository.associateAppsWithDevice(packageNames, deviceUuid)
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
        sourceDevice: DeviceInfo
    ) {
        // 检查缺失的图标
        val missingIcons = AppRepository.getMissingIconsForPackages(packageNames)
        if (missingIcons.isEmpty()) {
            //Logger.d(TAG, "所有图标已缓存，无需请求")
            return
        }
        
        // 过滤掉本机已安装的应用（本机已安装的应用图标可直接获取，无需请求）
        val installedPackages = AppRepository.getInstalledPackageNames(context)
        val needRequestIcons = missingIcons.filter { !installedPackages.contains(it) }
        
        if (needRequestIcons.isEmpty()) {
            //Logger.d(TAG, "所有缺失图标为本机已安装应用，无需请求")
            return
        }
        
        // 批量请求缺失的图标
        IconSyncManager.requestIconsBatch(context, needRequestIcons, deviceManager, sourceDevice)
        //Logger.d(TAG, "批量请求缺失图标：${needRequestIcons.size} 个")
    }
}
