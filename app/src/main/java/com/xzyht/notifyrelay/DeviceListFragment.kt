package com.xzyht.notifyrelay

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
        android.util.Log.d("NotifyRelay", "[UI] DeviceListFragment onCreateView called")
        return ComposeView(requireContext()).apply {
            setContent {
                android.util.Log.d("NotifyRelay", "[UI] Compose setContent in DeviceListFragment")
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
    // 拒绝设备弹窗状态（需提前声明，供下方 LaunchedEffect、rejectedDevices 使用）
    var showRejectedDialog by remember { mutableStateOf(false) }
    var authedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
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
    // 未认证设备（未在authedDeviceUuids和rejectedDeviceUuids中）
    val unauthedDevices = devices.filter { d ->
        !authedDeviceUuids.contains(d.uuid) && !rejectedDeviceUuids.contains(d.uuid)
    }
    // 已拒绝设备（需包含所有被拒绝uuid对应的设备，若deviceMap未包含则手动补全）
    val rejectedDevices = rejectedDeviceUuids.mapNotNull { uuid ->
        devices.find { it.uuid == uuid } ?: run {
            // deviceMap 里没有，尝试构造一个占位DeviceInfo
            DeviceInfo(uuid, "未知设备", "", 0)
        }
    }

    android.util.Log.d("NotifyRelay", "[UI] 设备列表界面 DeviceListScreen 调用")
    android.util.Log.d("NotifyRelay", "[UI] 设备Map=${deviceMap.keys}，未认证设备=${unauthedDevices.map { it.uuid }}")

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

    if (isLandscape) {
        // 横屏：设备列表竖排（侧边栏）
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .background(colorScheme.background)
                .padding(12.dp)
        ) {
            // 认证设备和本机、未认证设备统一按钮逻辑
            allDevices.forEach { device: DeviceInfo? ->
                Button(
                    onClick = { onSelectDevice(device) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = when {
                        device == null && selectedDevice == null -> androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                        device == null -> androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                        selectedDevice?.uuid == device.uuid && authedDeviceUuids.contains(device.uuid) -> androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                        authedDeviceUuids.contains(device.uuid) -> androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                        else -> androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    }
                ) {
                    val isOnline = device?.let { deviceStates[it.uuid] == true } ?: true
                    Text(
                        text = when {
                            device == null -> "本机"
                            !authedDeviceUuids.contains(device.uuid) -> device.displayName + if (!isOnline) " (离线)" else ""
                            else -> device.displayName + if (!isOnline) " (离线)" else ""
                        },
                        style = textStyles.body2.copy(
                            color = when {
                                device == null && selectedDevice == null -> colorScheme.onPrimary
                                device == null -> colorScheme.primary
                                selectedDevice?.uuid == device.uuid && authedDeviceUuids.contains(device.uuid) -> colorScheme.onPrimary
                                authedDeviceUuids.contains(device.uuid) -> colorScheme.primary
                                else -> colorScheme.primary
                            }
                        )
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 拒绝设备按钮
            Button(
                onClick = { showRejectedDialog = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer)
            ) {
                Text(
                    text = "查看已拒绝设备",
                    style = textStyles.body2,
                    color = colorScheme.secondary
                )
            }
        }
    } else {
        // 竖屏：设备列表横排（顶部）
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(colorScheme.background)
                    .padding(8.dp)
            ) {
                allDevices.forEach { device: DeviceInfo? ->
                    if (device == null) {
                        Button(
                            onClick = { onSelectDevice(null) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = if (selectedDevice == null) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                        ) {
                            Text("本机", style = textStyles.body2.copy(color = colorScheme.onPrimary.takeIf { selectedDevice == null } ?: colorScheme.primary))
                        }
                    } else if (authedDeviceUuids.contains(device.uuid)) {
                        val isOnline = deviceStates[device.uuid] == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Button(
                                onClick = { onSelectDevice(device) },
                                modifier = Modifier,
                                colors = if (selectedDevice?.uuid == device.uuid) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                            ) {
                                Text(device.displayName + if (!isOnline) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.onPrimary.takeIf { selectedDevice?.uuid == device.uuid } ?: colorScheme.primary))
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    // 删除已认证设备并持久化
                                    val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                                    field.isAccessible = true
                                    val map = field.get(deviceManager) as? MutableMap<String, *>
                                    map?.remove(device.uuid)
                                    // 持久化
                                    val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
                                    saveMethod.isAccessible = true
                                    saveMethod.invoke(deviceManager)
                                    authedDeviceUuids = map?.filter { entry ->
                                        val v = entry.value
                                        v?.let {
                                            val isAcceptedField = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }
                                            isAcceptedField.getBoolean(v)
                                        } ?: false
                                    }?.keys?.toSet() ?: emptySet()
                                    // 删除后选中本机
                                    selectedDevice = null
                                    GlobalSelectedDeviceHolder.selectedDevice = null
                                },
                                modifier = Modifier,
                            ) {
                                Text("删除", style = textStyles.body2.copy(color = Color.Red))
                            }
                        }
                    }
                }
                // 未认证设备横排
                unauthedDevices.forEach { device ->
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    ) {
                        Text(device.displayName + if (!isOnline) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.primary))
                    }
                }
    // 新增：未认证设备连接弹窗（横竖屏都生效）
    if (showConnectDialog && pendingConnectDevice != null) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = {
                Text(
                    text = "连接设备",
                    style = textStyles.subtitle,
                    color = colorScheme.primary
                )
            },
            text = {
                Text(
                    text = "是否连接设备：${pendingConnectDevice?.displayName ?: ""}？\n对方将收到认证请求。",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConnectDialog = false
                    pendingConnectDevice?.let { device ->
                        deviceManager.connectToDevice(device) { success, msg ->
                            if (!success && msg != null && activity != null) {
                                activity.runOnUiThread {
                                    android.widget.Toast.makeText(activity, "连接失败: $msg", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }) {
                    Text(
                        text = "连接",
                        style = textStyles.body2,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) {
                    Text(
                        text = "取消",
                        style = textStyles.body2,
                        color = colorScheme.primary
                    )
                }
            }
        )
    }

    // 新增：服务端握手请求弹窗（横竖屏都生效）
    if (showHandshakeDialog && pendingHandshakeRequest != null) {
        val req = pendingHandshakeRequest!!
        val device = req.device
        AlertDialog(
            onDismissRequest = {
                showHandshakeDialog = false
                pendingHandshakeRequest = null
                req.callback(false)
            },
            title = {
                Text(
                    text = "新设备连接请求",
                    style = textStyles.subtitle,
                    color = colorScheme.primary
                )
            },
            text = {
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showHandshakeDialog = false
                    pendingHandshakeRequest = null
                    req.callback(true)
                }) {
                    Text(
                        text = "同意",
                        style = textStyles.body2,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showHandshakeDialog = false
                    pendingHandshakeRequest = null
                    req.callback(false)
                }) {
                    Text(
                        text = "拒绝",
                        style = textStyles.body2,
                        color = colorScheme.primary
                    )
                }
            }
        )
    }
            }
            // 拒绝设备按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showRejectedDialog = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer)
                ) {
                    Text("查看已拒绝设备", style = textStyles.body2.copy(color = colorScheme.secondary))
                }
            }
        }
    }

    // 拒绝设备弹窗
    if (showRejectedDialog) {
        AlertDialog(
            onDismissRequest = { showRejectedDialog = false },
            title = {
                Text(
                    text = "已拒绝设备",
                    style = textStyles.subtitle,
                    color = colorScheme.primary
                )
            },
            text = {
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
                                TextButton(onClick = {
                                    // 恢复设备（移除rejected）
                                    val field = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                                    field.isAccessible = true
                                    val set = field.get(deviceManager) as? MutableSet<String>
                                    set?.remove(device.uuid)
                                    // 触发刷新
                                    rejectedDeviceUuids = set?.toSet() ?: emptySet()
                                }) {
                                    Text(
                                        text = "恢复",
                                        style = textStyles.body2,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRejectedDialog = false }) {
                    Text(
                        text = "关闭",
                        style = textStyles.body2,
                        color = colorScheme.primary
                    )
                }
            }
        )
    }
}
