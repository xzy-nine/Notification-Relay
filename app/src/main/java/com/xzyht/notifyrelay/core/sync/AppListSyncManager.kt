package com.xzyht.notifyrelay.core.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.core.util.AppListHelper
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        // context 暂未直接使用，仅保留以便未来扩展
        val _ = context.hashCode()

        val req = JSONObject().apply {
            put("type", "APP_LIST_REQUEST")
            put("scope", scope)
            put("time", System.currentTimeMillis())
        }.toString()

        sendAppListRequest(deviceManager, targetDevice, req)
    }

    private fun sendAppListRequest(
        deviceManager: DeviceConnectionManager,
        device: DeviceInfo,
        requestData: String
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[device.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过应用列表请求：${device.displayName}")
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(REQ_TIMEOUT) {
                        val socket = java.net.Socket()
                        try {
                            socket.connect(java.net.InetSocketAddress(device.ip, device.port), 5000)
                            val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                            val encrypted = deviceManager.encryptData(requestData, auth.sharedSecret)
                            val payload = "DATA_APP_LIST_REQUEST:${deviceManager.uuid}:${deviceManager.localPublicKey}:${encrypted}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "应用列表请求已发送 -> ${device.displayName}")
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "应用列表请求发送失败：${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送应用列表请求异常", e)
        }
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
            if (BuildConfig.DEBUG) Log.d(TAG, "已响应应用列表：${sourceDevice.displayName}，共${appArray.length()}项")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理应用列表请求失败", e)
        }
    }

    private fun sendAppListResponse(
        deviceManager: DeviceConnectionManager,
        target: DeviceInfo,
        responseData: String
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[target.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过应用列表响应：${target.displayName}")
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(REQ_TIMEOUT) {
                        val socket = java.net.Socket()
                        try {
                            socket.connect(java.net.InetSocketAddress(target.ip, target.port), 5000)
                            val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                            val encrypted = deviceManager.encryptData(responseData, auth.sharedSecret)
                            val payload = "DATA_APP_LIST_RESPONSE:${deviceManager.uuid}:${deviceManager.localPublicKey}:${encrypted}"
                            writer.write(payload + "\n")
                            writer.flush()
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "应用列表响应发送失败：${target.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送应用列表响应异常", e)
        }
    }

    /**
     * 处理接收到的应用列表响应。
     * 当前实现仅日志输出与占位，留给上层接入展示或缓存。
     */
    fun handleAppListResponse(responseData: String, context: Context) {
        try {
            val _ = context.hashCode()
            val json = JSONObject(responseData)
            if (json.optString("type") != "APP_LIST_RESPONSE") return
            val total = json.optInt("total", -1)
            if (BuildConfig.DEBUG) Log.d(TAG, "收到应用列表响应，共 $total 项")
            // 如需缓存到本地，可在此扩展，例如：
            // StorageManager.putString(context, "remote_app_list_cache", json.toString())
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "处理应用列表响应失败", e)
        }
    }
}
