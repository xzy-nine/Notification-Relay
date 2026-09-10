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
    /**
     * 投影更新后的回调（在刷新线程内同步执行）。
     *
     * 供装配方把快照中的寻址/展示元数据回填到自身派生表（如认证设备表的 lastIp/deviceType），
     * 避免这些字段各自再维护一条刷新链路。回调内不得再次触发刷新（会造成递归）。
     */
    private val onSnapshotRefreshed: (Map<String, DeviceSnapshot>) -> Unit = {},
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

    /** 保护 [projection] 的读-改-写，避免 [forget] 与 [doRefresh] 的整体替换互相覆盖。 */
    private val projectionLock = Any()

    /** name/deviceType 的上帧兜底（core 重启首帧可能为空）；按插入顺序淘汰最旧条目。 */
    private val displayFallback = LinkedHashMap<String, Pair<String, String>>()

    /** 刷新节流：心跳/连接回调并发触发时跳过重复刷新，避免同一时刻多个协程并发进入 JNA。 */
    private val refreshBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    // ==================== 刷新入口 ====================

    /** 异步触发一次刷新（回调线程内安全）。 */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    /** 同步刷新（沿用既有回调语义：超时/连接/断开回调直接刷新）。 */
    fun refresh() {
        if (!refreshBusy.compareAndSet(false, true)) return
        try {
            doRefresh()
        } finally {
            refreshBusy.set(false)
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
        synchronized(displayFallback) { displayFallback.remove(uuid) }
        synchronized(projectionLock) { projection = projection - uuid }
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
            synchronized(projectionLock) { projection = newProjection }
            _devices.value = newMap
            onlineDevicesCache.update(newMap, newProjection)
            // 回填装配方的派生表（如认证设备的 lastIp/deviceType）；回调失败不得影响本次刷新
            runCatching { onSnapshotRefreshed(newProjection) }
                .onFailure { Logger.e(TAG, "[DeviceSnapshotStore] 快照刷新回调失败", it) }
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
        // 电量一律以 core 为准；未知值（|v|>100）不沿用上帧，交由 batteryPercent 统一转为 -1
        val battery = obj.optInt("battery", -101)

        val rawType = obj.optString("deviceType", DeviceSnapshot.UNKNOWN_DEVICE_TYPE)
        val rawName = obj.optString("name")
        val fallback = synchronized(displayFallback) { displayFallback[uuid] }
        val deviceType = if (rawType.isBlank() || rawType == DeviceSnapshot.UNKNOWN_DEVICE_TYPE) (fallback?.second ?: rawType) else rawType
        val name = if (rawName.isBlank()) (fallback?.first ?: "") else rawName

        if (name.isNotBlank() || deviceType.isNotBlank()) {
            synchronized(displayFallback) {
                // 先移除再插入：LinkedHashMap 的重复 put 不更新既有键的插入顺序，
                // 否则「淘汰最旧」可能删掉刚写入的这一条
                displayFallback.remove(uuid)
                displayFallback[uuid] = name to deviceType
                if (displayFallback.size > FALLBACK_MAX_ENTRIES) {
                    displayFallback.keys.firstOrNull()?.let { displayFallback.remove(it) }
                }
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
