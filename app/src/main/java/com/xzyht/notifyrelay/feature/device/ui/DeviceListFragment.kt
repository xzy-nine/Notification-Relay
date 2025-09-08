package com.xzyht.notifyrelay.feature.device.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.xzyht.notifyrelay.feature.device.ui.dialog.ConnectDeviceDialog
import com.xzyht.notifyrelay.feature.device.ui.dialog.HandshakeRequestDialog
import com.xzyht.notifyrelay.feature.device.ui.dialog.RejectedDevicesDialog
import com.xzyht.notifyrelay.feature.device.model.HandshakeRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xzyht.notifyrelay.feature.device.viewmodel.DeviceListViewModel
import com.xzyht.notifyrelay.feature.device.viewmodel.DeviceConnectionUIHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

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
    val viewModel: DeviceListViewModel = viewModel { DeviceListViewModel(context) }
    val uiHandler = remember { DeviceConnectionUIHandler(viewModel) }

    // Set UI handler
    LaunchedEffect(viewModel) {
        viewModel.setUIHandler(uiHandler)
    }

    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    // State from ViewModel
    val udpDiscoveryEnabled by viewModel::udpDiscoveryEnabled
    val authedDeviceUuids by viewModel::authedDeviceUuids
    val rejectedDeviceUuids by viewModel::rejectedDeviceUuids
    val showRejectedDialog by viewModel::showRejectedDialog
    val showConnectDialog by viewModel::showConnectDialog
    val pendingConnectDevice by viewModel::pendingConnectDevice
    val pendingHandshakeRequest by viewModel::pendingHandshakeRequest
    val showHandshakeDialog by viewModel::showHandshakeDialog
    val selectedDevice by viewModel::selectedDevice

    // Device data
    val deviceMap by viewModel.getDeviceManager().devices.collectAsState(initial = emptyMap())
    val devices: List<DeviceInfo> = deviceMap.values.map { it.first }
    val deviceStates: Map<String, Boolean> = deviceMap.mapValues { it.value.second }

    // Derived data
    val allDevices: List<DeviceInfo?> = listOf<DeviceInfo?>(null) + devices
    val validAuthedDeviceUuids = authedDeviceUuids.intersect(devices.map { it.uuid }.toSet())
    val unauthedDevices = viewModel.getUnauthenticatedDevices()
    val rejectedDevices = viewModel.getRejectedDevices()

    // Update auth states when deviceMap changes
    LaunchedEffect(deviceMap, showRejectedDialog) {
        viewModel.updateAuthStates()
    }

    // Listen to UI handler state changes
    LaunchedEffect(pendingHandshakeRequest, showHandshakeDialog) {
        // Handled in ViewModel
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val onSelectDevice: (DeviceInfo?) -> Unit = { deviceInfo ->
        viewModel.selectDevice(deviceInfo)
        if (deviceInfo == null) {
            GlobalSelectedDeviceHolder.selectedDevice = null
        } else if (viewModel.isAuthenticated(deviceInfo.uuid)) {
            GlobalSelectedDeviceHolder.selectedDevice = deviceInfo
        }
    }

    val buttonMinHeight = 44.dp
    val textScrollModifier = Modifier
        .padding(horizontal = 2.dp, vertical = 4.dp)

    // Landscape layout
    if (isLandscape) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .background(colorScheme.background)
                .padding(12.dp)
        ) {
            // UDP discovery switch
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
                    onCheckedChange = { viewModel.toggleUdpDiscovery(it) }
                )
            }
            // Device list
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
                                    viewModel.removeDevice(device)
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
            // Unauthenticated devices
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
            // Rejected devices button
            Button(
                onClick = { viewModel.showRejectedDevicesDialog() },
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
        // Portrait layout
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.background)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
        ) {
            // UDP discovery switch
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
                    onCheckedChange = { viewModel.toggleUdpDiscovery(it) }
                )
            }
            // Device list
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Local device
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
                // Authenticated devices
                items(viewModel.getAuthenticatedDevices()) { device ->
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
                                    viewModel.removeDevice(device)
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
                // Unauthenticated devices
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
                // Rejected devices button
                item {
                    Button(
                        onClick = { viewModel.showRejectedDevicesDialog() },
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

    // Connect device dialog
    if (showConnectDialog && pendingConnectDevice != null) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        val showDialog = remember { mutableStateOf(true) }
        ConnectDeviceDialog(
            showDialog = showDialog,
            device = pendingConnectDevice,
            onConnect = { device ->
                viewModel.connectToDevice(device) { success, msg ->
                    if (!success && msg != null && activity != null) {
                        activity.runOnUiThread {
                            android.widget.Toast.makeText(activity, "连接失败: $msg", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    showDialog.value = false
                    viewModel.dismissConnectDialog()
                }
            },
            onDismiss = {
                showDialog.value = false
                viewModel.dismissConnectDialog()
            }
        )
    }

    // Handshake request dialog
    if (showHandshakeDialog && pendingHandshakeRequest != null) {
        val req = pendingHandshakeRequest
        val showDialog = remember { mutableStateOf(true) }
        HandshakeRequestDialog(
            showDialog = showDialog,
            handshakeRequest = req,
            onAccept = { handshakeReq ->
                viewModel.acceptHandshakeRequest(handshakeReq)
            },
            onReject = { handshakeReq ->
                viewModel.rejectHandshakeRequest(handshakeReq)
            },
            onDismiss = {
                showDialog.value = false
                viewModel.dismissHandshakeDialog()
            }
        )
    }

    // Rejected devices dialog
    if (showRejectedDialog) {
        val showDialog = remember { mutableStateOf(true) }
        RejectedDevicesDialog(
            showDialog = showDialog,
            rejectedDevices = rejectedDevices,
            onRestoreDevice = { device ->
                viewModel.restoreRejectedDevice(device)
            },
            onDismiss = {
                showDialog.value = false
                viewModel.dismissRejectedDevicesDialog()
            }
        )
    }
}
