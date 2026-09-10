package com.xzyht.notifyrelay.feature.device.service.state

import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceNameCache
import com.xzyht.notifyrelay.feature.device.model.DeviceSnapshot
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import org.json.JSONArray

/**
 * 设备状态的**唯一真源消费端**：把 Rust core 的 `nrc_get_device_list` 快照转换为
 * - [devices]：UI 直接订阅的 `Map<uuid, (DeviceInfo, isOnline)>`
 * - 内部只读投影 [snapshot] / [snapshots]：供同步查询（`getDeviceInfo`/`resolveDeviceInfo`）使用
 *
 * 写入约束：**只有本类可以刷新设备状态**；其他模块（心跳、网络变化、UI）只能
 * 调用 [requestRefresh] 触发一次刷新，不得再自行维护设备副本。
 *
 * 兜底策略：仅 [DeviceSnapshot.name] 与 [DeviceSnapshot.deviceType] 使用「上帧非空值」兜底
 * （core 的 `deviceType` 不落库、`name` 重启首帧可能为空）；其余字段一律以 core 为准。
 *
 * 时序：
 * ```mermaid
 * sequenceDiagram
 *     participant T as 触发方（回调/心跳/网络变化/UI）
 *     participant S as DeviceSnapshotStore
 *     participant Core as Rust Core
 *     participant C as OnlineDevicesCache
 *     participant UI as devices StateFlow
 *
 *     T->>S: requestRefresh()（异步）
 *     S->>S: refreshBusy 节流判定（并发触发直接跳过）
 *     S->>Core: nrc_get_device_list(ctx, 0, 0)
 *     Core-->>S: [{uuid,name,ip,port,battery,deviceType,online,paired,...}]
 *     S->>S: 解析 + name/deviceType 兜底 → 更新只读投影
 *     S->>C: update(map, 投影)
 *     S->>UI: _devices.value = 新列表
 * ```
 */
class DeviceSnapshotStore(
    private val scope: CoroutineScope,
    private val localUuidProvider: () -> String,
    private val defaultPort: Int,
    private val onlineDevicesCache: OnlineDevicesCache,
) {
    companion object {
        private const val TAG = "死神-NotifyRelay"

        /** name/deviceType 兜底缓存容量上限 */
        private const val FALLBACK_MAX_ENTRIES = 500
    }

    private val _devices = MutableStateFlow<Map<String, Pair<DeviceInfo, Boolean>>>(emptyMap())

    /**
     * 设备状态流：key 为 uuid，value 为 (DeviceInfo, isOnline)。
     * 由 core 决定设备是否进入列表以及是否在线，平台端只做展示。
     */
    val devices: StateFlow<Map<String, Pair<DeviceInfo, Boolean>>> = _devices

    /** core 快照的只读投影（`@Volatile`：刷新在 IO 线程写，查询可能在任意线程读）。 */
    @Volatile
    private var projection: Map<String, DeviceSnapshot> = emptyMap()

    /** name/deviceType 的上帧兜底（core 重启首帧可能为空）。 */
    private val displayFallback = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** 刷新节流：心跳/连接回调并发触发时跳过重复刷新，避免同一时刻多个协程并发进入 JNA。 */
    @Volatile
    private var refreshBusy = false

    // ==================== 刷新入口 ====================

    /** 异步触发一次刷新（回调线程内安全）。 */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    /** 同步刷新（沿用既有回调语义：超时/连接/断开回调直接刷新）。 */
    fun refresh() {
        if (refreshBusy) return
        refreshBusy = true
        try {
            doRefresh()
        } finally {
            refreshBusy = false
        }
    }

    // ==================== 只读查询 ====================

    fun snapshot(uuid: String): DeviceSnapshot? = projection[uuid]

    fun snapshots(): Map<String, DeviceSnapshot> = projection

    /** 已配对设备快照（含离线）。 */
    fun pairedSnapshots(): List<DeviceSnapshot> = projection.values.filter { it.paired }

    /** 指定设备是否已配对（core 判定）。 */
    fun isPaired(uuid: String): Boolean = projection[uuid]?.paired == true

    /** 指定设备的设备类型（core 不落库，可能为空）。 */
    fun deviceType(uuid: String): String? = projection[uuid]?.deviceType?.takeIf { it.isNotBlank() && it != DeviceSnapshot.UNKNOWN_DEVICE_TYPE }

    /** 指定设备的名称（core 快照优先，其次上帧兜底，最后 uuid→名称缓存）。 */
    fun displayName(uuid: String): String? = projection[uuid]?.name?.takeIf { it.isNotBlank() } ?: DeviceNameCache.getDisplayNameByUuid(uuid)

    /** 当前 devices 流中的展示模型。 */
    fun currentInfo(uuid: String): DeviceInfo? = _devices.value[uuid]?.first

    /** 清除某设备的兜底缓存（设备被移除时调用）。 */
    fun forget(uuid: String) {
        displayFallback.remove(uuid)
        projection = projection - uuid
        _devices.value = _devices.value - uuid
    }

    // ==================== 内部实现 ====================

    private fun doRefresh() {
        val ctx = NativeCore.getContext() ?: return
        val localUuid = localUuidProvider()
        val json =
            try {
                // 传 0/0：使用 Rust core 内建在线阈值（已认证 12s / 未认证 20s），判定完全归 core
                NativeCore.getDeviceList(ctx, 0L, 0L)
            } catch (_: Exception) {
                null
            } ?: return

        try {
            val arr = JSONArray(json)
            val newMap = mutableMapOf<String, Pair<DeviceInfo, Boolean>>()
            val newProjection = mutableMapOf<String, DeviceSnapshot>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uuid = obj.optString("uuid")
                if (uuid.isEmpty() || uuid == localUuid) continue
                val parsed = parseSnapshot(obj, uuid)
                newProjection[uuid] = parsed
                newMap[uuid] =
                    parsed.toDeviceInfo(DeviceNameCache.getDisplayNameByUuid(uuid)) to parsed.online
            }
            projection = newProjection
            _devices.value = newMap
            onlineDevicesCache.update(newMap, newProjection)
            Logger.d(
                TAG,
                "[DeviceSnapshotStore] 列表: ${newMap.size} 台, 在线: ${newMap.count { it.value.second }}, 已配对: ${newProjection.count { it.value.paired }}",
            )
        } catch (e: Exception) {
            Logger.e(TAG, "[DeviceSnapshotStore] 解析设备快照失败", e)
        }
    }

    /** 解析单条快照，并对 name/deviceType 做上帧兜底。 */
    private fun parseSnapshot(
        obj: org.json.JSONObject,
        uuid: String,
    ): DeviceSnapshot {
        val rawBattery = obj.optInt("battery", -101)
        val old = projection[uuid]
        val battery = if (kotlin.math.abs(rawBattery) > DeviceSnapshot.BATTERY_UNKNOWN_THRESHOLD) (old?.battery ?: rawBattery) else rawBattery

        val rawType = obj.optString("deviceType", DeviceSnapshot.UNKNOWN_DEVICE_TYPE)
        val rawName = obj.optString("name")
        val fallback = displayFallback[uuid]
        val deviceType = if (rawType.isBlank() || rawType == DeviceSnapshot.UNKNOWN_DEVICE_TYPE) (fallback?.second ?: rawType) else rawType
        val name = if (rawName.isBlank()) (fallback?.first ?: "") else rawName

        if (name.isNotBlank() || deviceType.isNotBlank()) {
            displayFallback[uuid] = name to deviceType
            if (displayFallback.size > FALLBACK_MAX_ENTRIES) {
                displayFallback.remove(displayFallback.keys.first())
            }
        }

        return DeviceSnapshot(
            uuid = uuid,
            name = name,
            ip = obj.optString("ip"),
            port = obj.optInt("port", defaultPort).takeIf { it > 0 } ?: defaultPort,
            battery = battery,
            deviceType = deviceType,
            lastSeen = obj.optLong("lastSeen", 0L),
            connected = obj.optBoolean("connected"),
            paired = obj.optBoolean("paired"),
            online = obj.optBoolean("online"),
        )
    }
}
