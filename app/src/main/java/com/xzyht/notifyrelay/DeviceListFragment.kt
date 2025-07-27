package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Text

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
    val deviceMap by deviceManager.devices.collectAsState()
    val devices = deviceMap.values.map { it.first }
    val deviceStates = deviceMap.mapValues { it.value.second }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    // 本机按钮始终在最前
    val allDevices = listOf<DeviceInfo?>(null) + devices

    // 认证状态监听
    LaunchedEffect(deviceMap) {
        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(deviceManager) as? Map<String, *>
        authedDeviceUuids = map?.filter { (it.value as? Any)?.let { v ->
            val isAccepted = v.javaClass.getDeclaredField("isAccepted").apply { isAccessible = true }.getBoolean(v)
            isAccepted
        } == true }?.keys?.toSet() ?: emptySet()
        val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
        rejField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        rejectedDeviceUuids = rejField.get(deviceManager) as? Set<String> ?: emptySet()
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun isAuthed(uuid: String) = authedDeviceUuids.contains(uuid)

    if (isLandscape) {
        // 横屏：设备列表竖排
        Column(
            modifier = Modifier.fillMaxHeight().width(220.dp).background(colorScheme.background).padding(12.dp)
        ) {
            allDevices.forEach { device ->
                if (device == null) {
                    // 本机按钮
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                    ) {
                        Text("本机", style = textStyles.body2.copy(color = colorScheme.primary))
                    }
                } else if (!rejectedDeviceUuids.contains(device.uuid)) {
                    val authed = isAuthed(device.uuid)
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (authed) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    ) {
                        Text(device.displayName + if (!isOnline && authed) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.primary))
                    }
                }
            }
        }
    } else {
        // 竖屏：设备列表横排
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).background(colorScheme.background).padding(8.dp)
        ) {
            allDevices.forEach { device ->
                if (device == null) {
                    Button(
                        onClick = {},
                        modifier = Modifier.padding(end = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer)
                    ) {
                        Text("本机", style = textStyles.body2.copy(color = colorScheme.primary))
                    }
                } else if (!rejectedDeviceUuids.contains(device.uuid)) {
                    val authed = isAuthed(device.uuid)
                    val isOnline = deviceStates[device.uuid] == true
                    Button(
                        onClick = {},
                        modifier = Modifier.padding(end = 8.dp),
                        colors = if (authed) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer) else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorScheme.surface)
                    ) {
                        Text(device.displayName + if (!isOnline && authed) " (离线)" else "", style = textStyles.body2.copy(color = colorScheme.primary))
                    }
                }
            }
        }
    }
}
