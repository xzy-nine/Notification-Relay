package com.xzyht.notifyrelay.ui.MainActivityscreen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.extra.SuperDialog
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Text

// 握手请求数据结构
data class HandshakeRequest(
    val device: DeviceInfo,
    val publicKey: String?,
    val callback: (Boolean) -> Unit
)

// 全局设备选中状态单例
object GlobalSelectedDeviceHolder {
    // null 表示本机
    private var _selectedDevice by mutableStateOf<DeviceInfo?>(null)
    var selectedDevice: DeviceInfo?
        get() = _selectedDevice
        set(value) { _selectedDevice = value }

    /**
     * Compose可组合函数，供其他页面监听选中设备变化。
     */
    @Composable
    fun current(): State<DeviceInfo?> {
        // 这样可在任意Compose页面通过 GlobalSelectedDeviceHolder.current().value 响应式获取
        val state = rememberUpdatedState(_selectedDevice)
        // 由于mutableStateOf已全局可观察，直接返回即可
        return androidx.compose.runtime.remember { object : State<DeviceInfo?> {
            override val value: DeviceInfo? get() = _selectedDevice
        } }
    }
}



/**
 * 统一设备列表Fragment，支持本机按钮，设备名显示逻辑与DeviceForwardFragment一致。
 * 设备列表区域独立，tab切换不影响。
 */
class DeviceListFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        // android.util.Log.d("NotifyRelay", "[UI] DeviceListFragment onCreateView called")
        return ComposeView(requireContext()).apply {
            setContent {
                // android.util.Log.d("NotifyRelay", "[UI] Compose setContent in DeviceListFragment")
                MiuixTheme {
                    DeviceListScreen()
                }
            }
        }
    }
}

@Composable
fun DeviceListScreen() {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val deviceManager = remember { DeviceForwardFragment.getDeviceManager(context) }
    // 手动发现/心跳等提示完全交由后端处理，前端不再注册回调
    // 拒绝设备弹窗状态（需提前声明，供下方 LaunchedEffect、rejectedDevices 使用）
    var showRejectedDialog by remember { mutableStateOf(false) }
    var authedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    // 新增：UDP发现开关状态与切换方法
    var udpDiscoveryEnabled by remember { mutableStateOf(deviceManager.udpDiscoveryEnabled) }
    fun toggleUdpDiscovery(enabled: Boolean) {
        udpDiscoveryEnabled = enabled
        deviceManager.udpDiscoveryEnabled = enabled
        if (enabled) {
            deviceManager.startDiscovery()
        } else {
            // 只停止UDP相关线程，避免影响TCP服务
            try {
                val broadcastField = deviceManager.javaClass.getDeclaredField("broadcastThread")
                broadcastField.isAccessible = true
                (broadcastField.get(deviceManager) as? Thread)?.interrupt()
                broadcastField.set(deviceManager, null)
                val listenField = deviceManager.javaClass.getDeclaredField("listenThread")
                listenField.isAccessible = true
                (listenField.get(deviceManager) as? Thread)?.interrupt()
                listenField.set(deviceManager, null)
            } catch (_: Exception) {}
        }
    }
    // 新增：未认证设备连接弹窗状态
    var showConnectDialog by remember { mutableStateOf(false) }
    var pendingConnectDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    // 新增：服务端握手请求弹窗状态
    var pendingHandshakeRequest by remember { mutableStateOf<HandshakeRequest?>(null) }
    var showHandshakeDialog by remember { mutableStateOf(false) }
    // 明确 deviceMap 类型为 Map<String, Pair<DeviceInfo, Boolean>>
    val deviceMap: Map<String, Pair<DeviceInfo, Boolean>> by deviceManager.devices.collectAsState(initial = emptyMap())
    val devices: List<DeviceInfo> = deviceMap.values.map { it.first }
    val deviceStates: Map<String, Boolean> = deviceMap.mapValues { it.value.second }
    var selectedDevice by remember { mutableStateOf(GlobalSelectedDeviceHolder.selectedDevice) }
    // 本机按钮始终在最前
    val allDevices: List<DeviceInfo?> = listOf<DeviceInfo?>(null) + devices
    // 只展示deviceMap中存在的认证设备，彻底移除已无效的旧UUID设备
    val validAuthedDeviceUuids = authedDeviceUuids.intersect(devices.map { it.uuid }.toSet())
    // 未认证设备
    val unauthedDevices = devices.filter { d ->
        !validAuthedDeviceUuids.contains(d.uuid) && !rejectedDeviceUuids.contains(d.uuid)
    }
    // 已拒绝设备（需包含所有被拒绝uuid对应的设备，若deviceMap未包含则手动补全）
    val rejectedDevices = rejectedDeviceUuids.mapNotNull { uuid ->
        devices.find { it.uuid == uuid } ?: run {
            // deviceMap 里没有，尝试构造一个占位DeviceInfo
            DeviceInfo(uuid, "未知设备", "", 0)
        }
    }
    // 辅助：根据IP查找所有同IP的已认证设备UUID（排除当前UUID）
    fun findOtherUuidsWithSameIp(ip: String, exceptUuid: String): List<String> {
        return deviceMap.values.map { it.first }
            .filter { it.ip == ip && it.uuid != exceptUuid && authedDeviceUuids.contains(it.uuid) }
            .map { it.uuid }
    }

    // android.util.Log.d("NotifyRelay", "[UI] 设备列表界面 DeviceListScreen 调用")
    // android.util.Log.d("NotifyRelay", "[UI] 设备Map=${deviceMap.keys}，未认证设备=${unauthedDevices.map { it.uuid }}")

    // 认证状态监听，deviceMap/弹窗关闭/恢复操作均会触发刷新
    LaunchedEffect(deviceMap, showRejectedDialog) {
        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(deviceManager) as? Map<String, *>
        authedDeviceUuids = map?.filter { entry ->
            val v = entry.value
            v?.let {
                val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                isAcceptedField.getBoolean(v)
            } ?: false
        }?.keys?.toSet() ?: emptySet()
        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
        rejField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        rejectedDeviceUuids = (rejField.get(deviceManager) as? Set<String>)?.toSet() ?: emptySet()
    }

    // 监听 deviceManager 的 onHandshakeRequest 回调
    LaunchedEffect(Unit) {
        try {
            val field = deviceManager.javaClass.getDeclaredField("onHandshakeRequest")
            field.isAccessible = true
            field.set(deviceManager) { device: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit ->
                pendingHandshakeRequest = HandshakeRequest(device, publicKey, callback)
                showHandshakeDialog = true
            }
        } catch (_: Exception) {
            // 兼容未实现的情况
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun isAuthed(uuid: String) = authedDeviceUuids.contains(uuid)

    val onSelectDevice: (DeviceInfo?) -> Unit = { deviceInfo ->
        // 本机或已认证设备直接选中
        if (deviceInfo == null) {
            selectedDevice = null
            GlobalSelectedDeviceHolder.selectedDevice = null
        } else if (authedDeviceUuids.contains(deviceInfo.uuid)) {
            selectedDevice = deviceInfo
            GlobalSelectedDeviceHolder.selectedDevice = deviceInfo
        } else {
            // 未认证设备，弹出连接请求弹窗
            pendingConnectDevice = deviceInfo
            showConnectDialog = true
        }
    }

    val buttonMinHeight = 44.dp // 更适合内容自适应，防止裁剪
    val textScrollModifier = Modifier
        .padding(horizontal = 2.dp, vertical = 4.dp)

    // 横竖屏都显示UDP发现开关
    if (isLandscape) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .background(colorScheme.background)
                .padding(12.dp)
        ) {
            // UDP发现开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = "UDP发现(未认证设备)",
                    style = textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                top.yukonga.miuix.kmp.basic.Switch(
                    checked = udpDiscoveryEnabled,
                    onCheckedChange = { toggleUdpDiscovery(it) }
                )
            }
            // 设备列表竖排
            allDevices.forEach { device: DeviceInfo? ->
                if (device == null) {
                    Button(
                        onClick = { onSelectDevice(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = buttonMinHeight)
                            .padding(vertical = 2.dp),
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = if (selectedDevice == null) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            text = "本机",
                            style = textStyles.body2.copy(color = if (selectedDevice == null) colorScheme.onPrimary else colorScheme.primary),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                } else if (authedDeviceUuids.contains(device.uuid)) {
                    val isOnline = deviceStates[device.uuid] == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Button(
                            onClick = { onSelectDevice(device) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = buttonMinHeight),
                            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            colors = if (selectedDevice?.uuid == device.uuid) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                        ) {
                            Text(
                                device.displayName + if (!isOnline) " (离线)" else "",
                                style = textStyles.body2.copy(color = if (selectedDevice?.uuid == device.uuid) colorScheme.onPrimary else colorScheme.primary),
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        if (selectedDevice?.uuid == device.uuid) {
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    try {
                                        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                        field.isAccessible = true
                                        val map = field.get(deviceManager)
                                        if (map is MutableMap<*, *>) {
                                            @Suppress("UNCHECKED_CAST")
                                            (map as? MutableMap<String, *>)?.remove(device.uuid)
                                        }
                                        val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
                                        saveMethod.isAccessible = true
                                        saveMethod.invoke(deviceManager)
                                        val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
                                        updateMethod.isAccessible = true
                                        updateMethod.invoke(deviceManager)
                                        @Suppress("UNCHECKED_CAST")
                                        authedDeviceUuids = (map as? MutableMap<String, *>)?.filter { entry: Map.Entry<String, *> ->
                                            val v = entry.value
                                            v?.let {
                                                val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                                isAcceptedField.getBoolean(v)
                                            } ?: false
                                        }?.keys?.toSet() ?: emptySet()
                                    } catch (_: Exception) {}
                                    selectedDevice = null
                                    GlobalSelectedDeviceHolder.selectedDevice = null
                                },
                                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = buttonMinHeight)
                                    .heightIn(min = buttonMinHeight),
                                colors = ButtonDefaults.buttonColors(color = Color.Red)
                            ) {
                                Text(
                                    text = "删除",
                                    style = textStyles.body2.copy(color = Color.White),
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            // 未认证设备
            unauthedDevices.forEach { device ->
                val isOnline = deviceStates[device.uuid] == true
                Button(
                    onClick = { onSelectDevice(device) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = buttonMinHeight)
                        .padding(vertical = 2.dp),
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(color = colorScheme.surface)
                ) {
                    Text(
                        device.displayName + if (!isOnline) " (离线)" else "",
                        style = textStyles.body2.copy(color = colorScheme.primary),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            // 已拒绝设备按钮
            Button(
                onClick = { showRejectedDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = buttonMinHeight)
                    .padding(vertical = 2.dp),
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(color = colorScheme.secondaryContainer)
            ) {
                Text(
                    "查看已拒绝设备",
                    style = textStyles.body2.copy(color = colorScheme.secondary),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.background)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
        ) {
            // UDP发现开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = "UDP发现(未认证设备)",
                    style = textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                top.yukonga.miuix.kmp.basic.Switch(
                    checked = udpDiscoveryEnabled,
                    onCheckedChange = { toggleUdpDiscovery(it) }
                )
            }
            // 设备列表横排，顺序：本机、已认证、未认证、已拒绝
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 本机按钮
                item {
                    Button(
                        onClick = { onSelectDevice(null) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = buttonMinHeight)
                            .padding(end = 6.dp),
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = if (selectedDevice == null) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            "本机",
                            style = textStyles.body2.copy(color = if (selectedDevice == null) colorScheme.onPrimary else colorScheme.primary),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                // 已认证设备
                items(allDevices.filterNotNull().filter { authedDeviceUuids.contains(it.uuid) }) { device ->
                    val isOnline = deviceStates[device.uuid] == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Button(
                            onClick = { onSelectDevice(device) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = buttonMinHeight),
                            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            colors = if (selectedDevice?.uuid == device.uuid) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                        ) {
                            Text(
                                device.displayName + if (!isOnline) " (离线)" else "",
                                style = textStyles.body2.copy(color = if (selectedDevice?.uuid == device.uuid) colorScheme.onPrimary else colorScheme.primary),
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        if (selectedDevice?.uuid == device.uuid) {
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    try {
                                        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                        field.isAccessible = true
                                        val map = field.get(deviceManager)
                                        if (map is MutableMap<*, *>) {
                                            @Suppress("UNCHECKED_CAST")
                                            (map as? MutableMap<String, *>)?.remove(device.uuid)
                                        }
                                        val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
                                        saveMethod.isAccessible = true
                                        saveMethod.invoke(deviceManager)
                                        val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
                                        updateMethod.isAccessible = true
                                        updateMethod.invoke(deviceManager)
                                        @Suppress("UNCHECKED_CAST")
                                        authedDeviceUuids = (map as? MutableMap<String, *>)?.filter { entry: Map.Entry<String, *> ->
                                            val v = entry.value
                                            v?.let {
                                                val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                                isAcceptedField.getBoolean(v)
                                            } ?: false
                                        }?.keys?.toSet() ?: emptySet()
                                    } catch (_: Exception) {}
                                    selectedDevice = null
                                    GlobalSelectedDeviceHolder.selectedDevice = null
                                },
                                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = buttonMinHeight)
                                    .heightIn(min = buttonMinHeight),
                                colors = ButtonDefaults.buttonColors(color = Color.Red)
                            ) {
                                Text(
                                    text = "删除",
                                    style = textStyles.body2.copy(color = Color.White),
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                // 未认证设备
                items(unauthedDevices) { device ->
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = buttonMinHeight)
                            .padding(end = 6.dp),
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(color = colorScheme.surface)
                    ) {
                        Text(
                            device.displayName + if (!isOnline) " (离线)" else "",
                            style = textStyles.body2.copy(color = colorScheme.primary),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                // 已拒绝设备按钮
                item {
                    Button(
                        onClick = { showRejectedDialog = true },
                        modifier = Modifier
                            .defaultMinSize(minHeight = buttonMinHeight)
                            .padding(end = 6.dp),
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(color = colorScheme.secondaryContainer)
                    ) {
                        Text(
                            "查看已拒绝设备",
                            style = textStyles.body2.copy(color = colorScheme.secondary),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 连接设备弹窗
    if (showConnectDialog && pendingConnectDevice != null) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        SuperDialog(
            show = remember { mutableStateOf(true) },
            title = "连接设备",
            summary = "是否连接设备：${pendingConnectDevice?.displayName ?: ""}？\n对方将收到认证请求。",
            onDismissRequest = { showConnectDialog = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "连接",
                    onClick = {
                        showConnectDialog = false
                        pendingConnectDevice?.let { device ->
                            // 连接前，先移除同IP的所有旧UUID认证（防止多UUID）
                            try {
                                val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                field.isAccessible = true
                                val rawMap = field.get(deviceManager)
                                val safeMap = if (rawMap is MutableMap<*, *>) {
                                    val m = mutableMapOf<String, Any?>()
                                    for ((k, v) in rawMap) {
                                        if (k is String) m[k] = v
                                    }
                                    m
                                } else mutableMapOf()
                                val oldUuids = findOtherUuidsWithSameIp(device.ip, "") + device.uuid
                                val appContext = context.applicationContext
                                for (uuid in oldUuids.distinct()) {
                                    safeMap.remove(uuid)
                                    try {
                                        val notificationDataClass = Class.forName("com.xzyht.notifyrelay.data.Notify.NotificationData")
                                        val getInstance = notificationDataClass.getDeclaredMethod("getInstance", android.content.Context::class.java)
                                        val notificationData = getInstance.invoke(null, appContext)
                                        val clearDeviceHistory = notificationDataClass.getDeclaredMethod("clearDeviceHistory", String::class.java, android.content.Context::class.java)
                                        clearDeviceHistory.invoke(notificationData, uuid, appContext)
                                    } catch (_: Exception) {}
                                    try {
                                        val file = java.io.File(appContext.filesDir, "notification_records_${uuid}.json")
                                        if (file.exists()) file.delete()
                                    } catch (_: Exception) {}
                                }
                                // 持久化认证状态
                                val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
                                saveMethod.isAccessible = true
                                saveMethod.invoke(deviceManager)
                                val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
                                updateMethod.isAccessible = true
                                updateMethod.invoke(deviceManager)
                            } catch (_: Exception) {}
                            deviceManager.connectToDevice(device) { success, msg ->
                                if (!success && msg != null && activity != null) {
                                    activity.runOnUiThread {
                                        android.widget.Toast.makeText(activity, "连接失败: $msg", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else if (success) {
                                    // 认证成功，立即刷新UI侧已认证设备列表
                                    try {
                                        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                        field.isAccessible = true
                                        @Suppress("UNCHECKED_CAST")
                                        val map = field.get(deviceManager) as? Map<String, *>
                                        authedDeviceUuids = map?.filter { entry ->
                                            val v = entry.value
                                            v?.let {
                                                val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                                isAcceptedField.getBoolean(v)
                                            } ?: false
                                        }?.keys?.toSet() ?: emptySet()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                )
                TextButton(
                    text = "取消",
                    onClick = { showConnectDialog = false }
                )
            }
        }
    }

    if (showHandshakeDialog && pendingHandshakeRequest != null) {
        val req = pendingHandshakeRequest!!
        val device = req.device
        SuperDialog(
            show = remember { mutableStateOf(true) },
            title = "新设备连接请求",
            onDismissRequest = {
                // 先回调，后关闭弹窗，避免回调丢失
                req.callback(false)
                showHandshakeDialog = false
                pendingHandshakeRequest = null
            }
        ) {
            Column {
                Text(
                    text = "设备名: ${device.displayName}",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
                Text(
                    text = "UUID: ${device.uuid}",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
                Text(
                    text = "IP: ${device.ip}  端口: ${device.port}",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
                if (!req.publicKey.isNullOrBlank()) {
                    Text(
                        text = "公钥: ${req.publicKey}",
                        style = textStyles.body2,
                        color = colorScheme.onSurfaceContainerVariant
                    )
                }
                Text(
                    text = "是否允许该设备连接？",
                    style = textStyles.body2,
                    color = colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "同意",
                        onClick = {
                            // 先回调，后关闭弹窗，避免回调丢失
                            // 认证前，批量移除同IP下所有旧UUID认证和缓存
                            try {
                                val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                field.isAccessible = true
                                val rawMap = field.get(deviceManager)
                                val safeMap = if (rawMap is MutableMap<*, *>) {
                                    val m = mutableMapOf<String, Any?>()
                                    for ((k, v) in rawMap) {
                                        if (k is String) m[k] = v
                                    }
                                    m
                                } else mutableMapOf()
                                val allUuidsToRemove = findOtherUuidsWithSameIp(device.uuid, "") + device.uuid
                                val appContext = context.applicationContext
                                for (uuid in allUuidsToRemove.distinct()) {
                                    safeMap.remove(uuid)
                                    try {
                                        val notificationDataClass = Class.forName("com.xzyht.notifyrelay.data.Notify.NotificationData")
                                        val getInstance = notificationDataClass.getDeclaredMethod("getInstance", android.content.Context::class.java)
                                        val notificationData = getInstance.invoke(null, appContext)
                                        val clearDeviceHistory = notificationDataClass.getDeclaredMethod("clearDeviceHistory", String::class.java, android.content.Context::class.java)
                                        clearDeviceHistory.invoke(notificationData, uuid, appContext)
                                    } catch (_: Exception) {}
                                    try {
                                        val file = java.io.File(appContext.filesDir, "notification_records_${uuid}.json")
                                        if (file.exists()) file.delete()
                                    } catch (_: Exception) {}
                                }
                                val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
                                saveMethod.isAccessible = true
                                saveMethod.invoke(deviceManager)
                                val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
                                updateMethod.isAccessible = true
                                updateMethod.invoke(deviceManager)
                            } catch (_: Exception) {}
                            req.callback(true)
                            showHandshakeDialog = false
                            pendingHandshakeRequest = null
                            // 强制刷新设备列表
                            val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
                            updateMethod.isAccessible = true
                            updateMethod.invoke(deviceManager)
                            // 立即刷新UI侧已认证设备列表
                            try {
                                val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                field.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val map = field.get(deviceManager) as? Map<String, *>
                                authedDeviceUuids = map?.filter { entry ->
                                    val v = entry.value
                                    v?.let {
                                        val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                        isAcceptedField.getBoolean(v)
                                    } ?: false
                                }?.keys?.toSet() ?: emptySet()
                            } catch (_: Exception) {}
                        }
                    )
                    TextButton(
                        text = "拒绝",
                        onClick = {
                            req.callback(false)
                            showHandshakeDialog = false
                            pendingHandshakeRequest = null
                        }
                    )
                }
            }
        }
    }

    if (showRejectedDialog) {
        SuperDialog(
            show = remember { mutableStateOf(true) },
            title = "已拒绝设备",
            onDismissRequest = { showRejectedDialog = false }
        ) {
            if (rejectedDevices.isEmpty()) {
                Text(
                    text = "暂无已拒绝设备",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
            } else {
                Column {
                    rejectedDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = device.displayName,
                                style = textStyles.body2,
                                color = colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "恢复",
                                onClick = {
                                // 恢复设备（移除rejected），同IP下所有UUID都移除
                                val field = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                                field.isAccessible = true
                                val set = field.get(deviceManager)
                                if (set is MutableSet<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val ms = set as? MutableSet<String>
                                    val allUuids = findOtherUuidsWithSameIp(device.ip, "") + device.uuid
                                    allUuids.distinct().forEach { ms?.remove(it) }
                                }
                                // 触发刷新
                                @Suppress("UNCHECKED_CAST")
                                rejectedDeviceUuids = (set as? MutableSet<String>)?.toSet() ?: emptySet()
                            }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "关闭",
                    onClick = { showRejectedDialog = false }
                )
            }
        }
    }
}
