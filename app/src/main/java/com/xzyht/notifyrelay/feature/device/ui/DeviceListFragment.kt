package com.xzyht.notifyrelay.feature.device.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.xzyht.notifyrelay.feature.device.model.HandshakeRequest
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.device.ui.dialog.ConnectDeviceDialog
import com.xzyht.notifyrelay.feature.device.ui.dialog.HandshakeRequestDialog
import com.xzyht.notifyrelay.feature.device.ui.dialog.RejectedDevicesDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        rememberUpdatedState(_selectedDevice)
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
    ): android.view.View {
        //Logger.d("NotifyRelay", "[UI] DeviceListFragment onCreateView called")
        return ComposeView(requireContext()).apply {
            setContent {
                //Logger.d("NotifyRelay", "[UI] Compose setContent in DeviceListFragment")
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
    val deviceManager = remember { com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton.getDeviceManager(context) }
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
        try {
            val discoveryField = deviceManager.javaClass.getDeclaredField("discoveryManager")
            discoveryField.isAccessible = true
            val discovery = discoveryField.get(deviceManager)
            if (enabled) {
                val startMethod = discovery.javaClass.getDeclaredMethod("startDiscovery")
                startMethod.isAccessible = true
                startMethod.invoke(discovery)
            } else {
                val stopMethod = discovery.javaClass.getDeclaredMethod("stopDiscovery")
                stopMethod.isAccessible = true
                stopMethod.invoke(discovery)
            }
        } catch (_: Exception) {}
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

    //Logger.d("NotifyRelay", "[UI] 设备列表界面 DeviceListScreen 调用")
    //Logger.d("NotifyRelay", "[UI] 设备Map=${deviceMap.keys}，未认证设备=${unauthedDevices.map { it.uuid }}")

    // 认证状态监听，deviceMap/弹窗关闭/恢复操作均会触发刷新
    LaunchedEffect(deviceMap, showRejectedDialog) {
        val authMap = deviceManager.getAuthenticatedDevices()
        authedDeviceUuids = authMap.filter { (_, auth) -> auth.isAccepted }.keys.toSet()
        rejectedDeviceUuids = deviceManager.getRejectedDevices()
    }

    // 注册握手请求处理器，接收到服务端握手时在主线程拉起弹窗
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(deviceManager) {
        val handler = object : DeviceConnectionManager.HandshakeRequestHandler {
            override fun onHandshakeRequest(deviceInfo: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit) {
                mainHandler.post {
                    pendingHandshakeRequest = HandshakeRequest(deviceInfo, publicKey, callback)
                    showHandshakeDialog = true
                }
            }
        }
        deviceManager.handshakeRequestHandler = handler
        onDispose {
            if (deviceManager.handshakeRequestHandler === handler) {
                deviceManager.handshakeRequestHandler = null
            }
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
    Modifier
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
            // UDP广播开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = "主动广播和接收",
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
                                        // 使用 DeviceConnectionManager 提供的安全 API 移除已认证设备
                                        val removed = deviceManager.removeAuthenticatedDevice(device.uuid)
                                        if (removed) {
                                            // 更新本地 UI 用的已认证 uuid 集合
                                            authedDeviceUuids = authedDeviceUuids - device.uuid
                                        }
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
            // UDP广播开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = "主动广播和接收",
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
                                        // 使用公共API移除已认证设备
                                        val removed = deviceManager.removeAuthenticatedDevice(device.uuid)
                                        if (removed) {
                                            // 更新本地UI用的已认证uuid集合
                                            authedDeviceUuids = deviceManager.getAuthenticatedDevices().filter { (_, auth) -> auth.isAccepted }.keys.toSet()
                                        }
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
        val activity = LocalContext.current as? android.app.Activity
        val showDialog = remember { mutableStateOf(true) }
        ConnectDeviceDialog(
            showDialog = showDialog,
            device = pendingConnectDevice,
            onConnect = { device ->
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
                            val notificationDataClass = Class.forName("com.xzyht.notifyrelay.feature.device.model.NotificationData")
                            val getInstance = notificationDataClass.getDeclaredMethod("getInstance", android.content.Context::class.java)
                            val notificationData = getInstance.invoke(null, appContext)
                            val clearDeviceHistory = notificationDataClass.getDeclaredMethod("clearDeviceHistory", String::class.java, android.content.Context::class.java)
                            clearDeviceHistory.invoke(notificationData, uuid, appContext)
                        } catch (_: Exception) {}
                        // 使用Room数据库后，不需要手动删除JSON文件
                    }
                    // 持久化认证状态
                        deviceManager.saveAuthedDevicesPublic()
                        deviceManager.updateDeviceListPublic()
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
                            val rawMap = field.get(deviceManager)
                            @Suppress("UNCHECKED_CAST")
                            val map = if (rawMap is Map<*, *>) rawMap as Map<String, *> else null
                            authedDeviceUuids = map?.filter { entry ->
                                val v = entry.value
                                v?.let {
                                    val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                    isAcceptedField.getBoolean(v)
                                } ?: false
                            }?.keys?.toSet() ?: emptySet()
                        } catch (_: Exception) {}
                    }
                    // 无论成功还是失败，都关闭弹窗
                    showDialog.value = false
                    showConnectDialog = false
                    pendingConnectDevice = null
                }
            },
            onDismiss = {
                showDialog.value = false
                showConnectDialog = false
                pendingConnectDevice = null
            }
        )
    }

    if (showHandshakeDialog && pendingHandshakeRequest != null) {
        val req = pendingHandshakeRequest!!
        val showDialog = remember { mutableStateOf(true) }
        HandshakeRequestDialog(
            showDialog = showDialog,
            handshakeRequest = req,
            onAccept = { handshakeReq ->
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
                    val allUuidsToRemove = findOtherUuidsWithSameIp(handshakeReq.device.uuid, "") + handshakeReq.device.uuid
                    val appContext = context.applicationContext
                    for (uuid in allUuidsToRemove.distinct()) {
                        safeMap.remove(uuid)
                        try {
                            val notificationDataClass = Class.forName("com.xzyht.notifyrelay.feature.device.model.NotificationData")
                            val getInstance = notificationDataClass.getDeclaredMethod("getInstance", android.content.Context::class.java)
                            val notificationData = getInstance.invoke(null, appContext)
                            val clearDeviceHistory = notificationDataClass.getDeclaredMethod("clearDeviceHistory", String::class.java, android.content.Context::class.java)
                            clearDeviceHistory.invoke(notificationData, uuid, appContext)
                        } catch (_: Exception) {}
                        // 持久化认证状态
                        deviceManager.saveAuthedDevicesPublic()
                        deviceManager.updateDeviceListPublic()
                    }
                } catch (_: Exception) {}
                handshakeReq.callback(true)
                // 强制刷新设备列表
                deviceManager.updateDeviceListPublic()
                // 立即刷新UI侧已认证设备列表
                try {
                    val authMap = deviceManager.getAuthenticatedDevices()
                    authedDeviceUuids = authMap.filter { (_, auth) -> auth.isAccepted }.keys.toSet()
                } catch (_: Exception) {}
            },
            onReject = { handshakeReq ->
                handshakeReq.callback(false)
            },
            onDismiss = {
                showDialog.value = false
                showHandshakeDialog = false
                pendingHandshakeRequest = null
            }
        )
    }

    if (showRejectedDialog) {
        val showDialog = remember { mutableStateOf(true) }
        RejectedDevicesDialog(
            showDialog = showDialog,
            rejectedDevices = rejectedDevices,
            onRestoreDevice = { device ->
                // 恢复设备（移除rejected），同IP下所有UUID都移除
                val field = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                field.isAccessible = true
                val rawSet = field.get(deviceManager)
                if (rawSet is MutableSet<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val ms = rawSet as MutableSet<String>
                    val allUuids = findOtherUuidsWithSameIp(device.ip, "") + device.uuid
                    allUuids.distinct().forEach { ms.remove(it) }
                }
                // 触发刷新
                @Suppress("UNCHECKED_CAST")
                rejectedDeviceUuids = if (rawSet is MutableSet<*>) (rawSet as MutableSet<String>).toSet() else emptySet()
            },
            onDismiss = {
                showDialog.value = false
                showRejectedDialog = false
            }
        )
    }
}
