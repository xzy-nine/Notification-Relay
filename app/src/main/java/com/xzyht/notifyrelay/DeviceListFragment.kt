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
        return ComposeView(requireContext()).apply {
            setContent {
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
    var authedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    // 明确 deviceMap 类型为 Map<String, Pair<DeviceInfo, Boolean>>
    val deviceMap: Map<String, Pair<DeviceInfo, Boolean>> by deviceManager.devices.collectAsState(initial = emptyMap())
    val devices: List<DeviceInfo> = deviceMap.values.map { it.first }
    val deviceStates: Map<String, Boolean> = deviceMap.mapValues { it.value.second }
    var selectedDevice by remember { mutableStateOf(GlobalSelectedDeviceHolder.selectedDevice) }
    // 本机按钮始终在最前
    val allDevices: List<DeviceInfo?> = listOf<DeviceInfo?>(null) + devices

    // 认证状态监听
    LaunchedEffect(deviceMap) {
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
        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun isAuthed(uuid: String) = authedDeviceUuids.contains(uuid)

    val onSelectDevice: (DeviceInfo?) -> Unit = { deviceInfo ->
        selectedDevice = deviceInfo
        GlobalSelectedDeviceHolder.selectedDevice = deviceInfo
    }
    if (isLandscape) {
        // 横屏：设备列表竖排
        Column(
            modifier = Modifier.fillMaxHeight().width(220.dp).background(colorScheme.background).padding(12.dp)
        ) {
            allDevices.forEach { device: DeviceInfo? ->
                if (device == null) {
                    // 本机按钮
                    Button(
                        onClick = { onSelectDevice(null) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (selectedDevice == null) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                    ) {
                        Text("本机", style = textStyles.body2.copy(color = colorScheme.onPrimary.takeIf { selectedDevice == null } ?: colorScheme.primary))
                    }
                } else if (!rejectedDeviceUuids.contains(device.uuid)) {
                    val authed = isAuthed(device.uuid)
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (selectedDevice?.uuid == device.uuid) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary) else if (authed) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    ) {
                        Text(device.displayName + if (!isOnline && authed) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.onPrimary.takeIf { selectedDevice?.uuid == device.uuid } ?: colorScheme.primary))
                    }
                }
            }
        }
    } else {
        // 竖屏：设备列表横排
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).background(colorScheme.background).padding(8.dp)
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
                } else if (!rejectedDeviceUuids.contains(device.uuid)) {
                    val authed = isAuthed(device.uuid)
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = if (selectedDevice?.uuid == device.uuid) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primary) else if (authed) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    ) {
                        Text(device.displayName + if (!isOnline && authed) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.onPrimary.takeIf { selectedDevice?.uuid == device.uuid } ?: colorScheme.primary))
                    }
                }
            }
        }
    }
}
