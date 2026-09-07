package com.xzyht.notifyrelay.ui.screen

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.R
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.ui.common.DoubleClickConfirmButton
import com.xzyht.notifyrelay.ui.dialog.PairingCodeDialog
import com.xzyht.notifyrelay.ui.dialog.PairingMode
import com.xzyht.notifyrelay.ui.dialog.RejectedDevicesDialog
import com.xzyht.notifyrelay.ui.navigation.Navigator
import notifyrelay.base.util.ToastUtils
import notifyrelay.core.util.BatteryIconConverter
import notifyrelay.core.util.BatteryUtils
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 全局设备选中状态单例
 */
object GlobalSelectedDeviceHolder {
    private var _selectedDevice by mutableStateOf<DeviceInfo?>(null)
    var selectedDevice: DeviceInfo?
        get() = _selectedDevice
        set(value) {
            _selectedDevice = value
        }

    /**
     * Compose可组合函数，供其他页面监听选中设备变化。
     */
    @Composable
    fun current(): State<DeviceInfo?> {
        rememberUpdatedState(_selectedDevice)
        return remember {
            object : State<DeviceInfo?> {
                override val value: DeviceInfo? get() = _selectedDevice
            }
        }
    }
}

/**
 * 设备列表屏幕状态管理类
 * 用于在父组件和子组件之间共享弹窗状态
 */
class DeviceListScreenState {
    var pendingConnectDevice by mutableStateOf<DeviceInfo?>(null)
        internal set
    var showRejectedDialog by mutableStateOf(false)
        internal set
    var showPairingCodeDialog by mutableStateOf(false)
        internal set
    var pairingCodeDialogMode by mutableStateOf(PairingMode.SERVER_MODE)
        internal set
    var serverPairingCode by mutableStateOf("")
        internal set

    /**
     * 检查是否有任何弹窗显示
     */
    fun hasAnyDialogShowing(): Boolean = showRejectedDialog || showPairingCodeDialog

    /**
     * 关闭所有弹窗
     */
    fun dismissAllDialogs() {
        showRejectedDialog = false
        showPairingCodeDialog = false
        pendingConnectDevice = null
    }
}

/**
 * 设备列表屏幕
 * 纯 Compose 实现，弹窗返回逻辑由父组件统一处理
 * 支持横屏（左侧列表）和竖屏（顶部列表）两种布局
 */
@Composable
fun DeviceListScreen(
    navigator: Navigator,
    state: DeviceListScreenState = remember { DeviceListScreenState() },
) {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val deviceManager = remember { DeviceConnectionManagerSingleton.getDeviceManager(context) }

    var authedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    var rejectedDeviceUuids by rememberSaveable { mutableStateOf(setOf<String>()) }
    var udpDiscoveryEnabled by remember { mutableStateOf(true) }

    val deviceMap: Map<String, Pair<DeviceInfo, Boolean>> by deviceManager.devices.collectAsState(initial = emptyMap())
    val devices: List<DeviceInfo> = deviceMap.values.map { it.first }
    val deviceStates: Map<String, Boolean> = deviceMap.mapValues { it.value.second }
    var selectedDevice by remember { mutableStateOf(GlobalSelectedDeviceHolder.selectedDevice) }
    var showDeleteHistoryDialog by remember { mutableStateOf(false) }
    var pendingDeleteDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    val localBatteryLevel =
        remember {
            BatteryUtils.getBatteryLevel(context)
        }

    val allDevices: List<DeviceInfo?> = listOf<DeviceInfo?>(null) + devices
    val validAuthedDeviceUuids = authedDeviceUuids.intersect(devices.map { it.uuid }.toSet())
    val unauthedDevices =
        if (udpDiscoveryEnabled) {
            devices.filter { d ->
                !validAuthedDeviceUuids.contains(d.uuid) && !rejectedDeviceUuids.contains(d.uuid)
            }
        } else {
            emptyList()
        }
    val rejectedDevices =
        rejectedDeviceUuids.mapNotNull { uuid ->
            devices.find { it.uuid == uuid } ?: DeviceInfo(uuid, "未知设备", "", 0)
        }

    fun findOtherUuidsWithSameIp(
        ip: String,
        exceptUuid: String,
    ): List<String> =
        deviceMap.values
            .map { it.first }
            .filter { it.ip == ip && it.uuid != exceptUuid && authedDeviceUuids.contains(it.uuid) }
            .map { it.uuid }

    LaunchedEffect(deviceMap, state.showRejectedDialog) {
        val authMap = deviceManager.getAuthenticatedDevices()
        authedDeviceUuids = authMap.filter { (_, auth) -> auth.isAccepted }.keys.toSet()
        rejectedDeviceUuids = deviceManager.getRejectedDevices()
    }

    // 有未认证设备连接时：生成配对码并弹出显示
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(deviceManager) {
        val handler =
            object : DeviceConnectionManager.HandshakeRequestHandler {
                override fun onPairingInitRequest(
                    deviceInfo: DeviceInfo,
                    tmpPublicKey: String,
                ) {
                    mainHandler.post {
                        state.pendingConnectDevice = deviceInfo
                        state.pairingCodeDialogMode = PairingMode.SERVER_MODE
                        state.showPairingCodeDialog = true
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun isAuthed(uuid: String) = authedDeviceUuids.contains(uuid)

    val onSelectDevice: (DeviceInfo?) -> Unit = { deviceInfo ->
        if (deviceInfo == null) {
            selectedDevice = null
            GlobalSelectedDeviceHolder.selectedDevice = null
        } else if (authedDeviceUuids.contains(deviceInfo.uuid)) {
            selectedDevice = deviceInfo
            GlobalSelectedDeviceHolder.selectedDevice = deviceInfo
        } else {
            state.pendingConnectDevice = deviceInfo
            state.pairingCodeDialogMode = PairingMode.CLIENT_MODE
            state.showPairingCodeDialog = true
        }
    }

    val buttonMinHeight = 44.dp

    @Composable
    fun UdpDiscoverySwitch() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = "显示未认证设备",
                style = textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = udpDiscoveryEnabled,
                onCheckedChange = { udpDiscoveryEnabled = it },
            )
        }
    }

    @Composable
    fun LocalDeviceButton() {
        val batteryLevel = localBatteryLevel
        val isCharging =
            remember {
                mutableStateOf(if (BatteryUtils.isCharging(context)) '1' else '0')
            }
        val batteryIcon = BatteryIconConverter.getBatteryIcon(batteryLevel, isCharging.value)

        val buttonColors =
            if (selectedDevice == null) {
                ButtonDefaults.buttonColorsPrimary()
            } else {
                ButtonDefaults.buttonColors()
            }

        val buttonModifier =
            if (isLandscape) {
                Modifier
                    .defaultMinSize(minHeight = buttonMinHeight)
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            } else {
                Modifier
                    .defaultMinSize(minHeight = buttonMinHeight)
                    .wrapContentWidth()
                    .padding(end = 6.dp)
            }

        Column(
            horizontalAlignment = if (isLandscape) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Button(
                onClick = { onSelectDevice(null) },
                modifier = buttonModifier,
                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = buttonColors,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "本机",
                        style = textStyles.body2.copy(color = if (selectedDevice == null) colorScheme.onPrimary else colorScheme.primary),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    fun AuthenticatedDeviceButton(device: DeviceInfo) {
        val isOnline = deviceStates[device.uuid] == true
        val context = LocalContext.current

        val batteryLevel = remember { mutableIntStateOf(100) }
        val isCharging = remember { mutableStateOf(device.chargingStatus) }

        LaunchedEffect(device.batteryLevel) {
            // 未知电量（超出 [-100,100]）不更新显示
            val level = kotlin.math.abs(device.batteryLevel)
            if (level <= 100 && level != batteryLevel.intValue) {
                batteryLevel.intValue = level
            }
        }

        LaunchedEffect(device.chargingStatus) {
            if (device.chargingStatus != '*' && device.chargingStatus != isCharging.value) {
                isCharging.value = device.chargingStatus
            }
        }

        val batteryIcon = BatteryIconConverter.getBatteryIcon(batteryLevel.intValue, isCharging.value)

        val buttonColors =
            if (selectedDevice?.uuid == device.uuid) {
                ButtonDefaults.buttonColorsPrimary()
            } else {
                ButtonDefaults.buttonColors()
            }

        Row(
            verticalAlignment = Alignment.Top,
            modifier = if (isLandscape) Modifier.padding(bottom = 4.dp) else Modifier.padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val buttonModifier =
                if (isLandscape) {
                    Modifier
                        .defaultMinSize(minHeight = buttonMinHeight)
                        .fillMaxWidth()
                } else {
                    Modifier
                        .defaultMinSize(minHeight = buttonMinHeight)
                        .wrapContentWidth()
                }

            Column(
                modifier = if (isLandscape) Modifier.weight(1f) else Modifier,
                horizontalAlignment = if (isLandscape) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Button(
                    onClick = { onSelectDevice(device) },
                    modifier = buttonModifier,
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = buttonColors,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (isOnline) {
                            Text(
                                text = batteryIcon,
                                fontFamily = FontFamily(Font(resId = R.font.segsmdl2)),
                                fontSize = 16.sp,
                                color = BatteryIconConverter.getBatteryColor(batteryLevel.intValue),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Text(
                            text = device.displayName + if (!isOnline) " (离线)" else "",
                            style = textStyles.body2.copy(color = if (selectedDevice?.uuid == device.uuid) colorScheme.onPrimary else colorScheme.primary),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (selectedDevice?.uuid == device.uuid) {
                Spacer(Modifier.width(4.dp))
                DoubleClickConfirmButton(
                    text = "删除",
                    confirmText = "确认?",
                    onClick = {},
                    onConfirm = {
                        pendingDeleteDevice = device
                        showDeleteHistoryDialog = true
                    },
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = buttonMinHeight, minWidth = 60.dp)
                            .heightIn(min = buttonMinHeight)
                            .widthIn(min = 60.dp),
                    colors = ButtonDefaults.buttonColors(color = colorScheme.error),
                    confirmColors = ButtonDefaults.buttonColors(color = colorScheme.error),
                    textColor = colorScheme.onError,
                    confirmTextColor = colorScheme.onError,
                )
            }
        }
    }

    @Composable
    fun UnauthenticatedDeviceButton(device: DeviceInfo) {
        val isOnline = deviceStates[device.uuid] == true
        Button(
            onClick = { onSelectDevice(device) },
            modifier =
                Modifier
                    .then(if (isLandscape) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = buttonMinHeight)
                    .then(if (isLandscape) Modifier.padding(vertical = 2.dp) else Modifier.padding(end = 6.dp)),
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(color = colorScheme.surface),
        ) {
            Text(
                device.displayName + if (!isOnline) " (离线)" else "",
                style = textStyles.body2.copy(color = colorScheme.primary),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    fun RejectedDevicesButton() {
        Button(
            onClick = { state.showRejectedDialog = true },
            modifier =
                Modifier
                    .then(if (isLandscape) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = buttonMinHeight)
                    .then(if (isLandscape) Modifier.padding(vertical = 2.dp) else Modifier.padding(end = 6.dp)),
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(color = colorScheme.secondaryContainer),
        ) {
            Text(
                "查看已拒绝设备",
                style = textStyles.body2.copy(color = colorScheme.secondary),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (isLandscape) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(colorScheme.background)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            UdpDiscoverySwitch()
            LocalDeviceButton()

            allDevices.forEach { device: DeviceInfo? ->
                if (device != null && authedDeviceUuids.contains(device.uuid)) {
                    AuthenticatedDeviceButton(device)
                }
            }

            unauthedDevices.forEach {
                UnauthenticatedDeviceButton(it)
            }

            RejectedDevicesButton()
        }
    } else {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colorScheme.background)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        ) {
            UdpDiscoverySwitch()
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                item { LocalDeviceButton() }

                items(allDevices.filterNotNull().filter { authedDeviceUuids.contains(it.uuid) }) {
                    AuthenticatedDeviceButton(it)
                }

                items(unauthedDevices) {
                    UnauthenticatedDeviceButton(it)
                }

                item { RejectedDevicesButton() }
            }
        }
    }

    // 配对码对话框 - 服务端模式
    if (state.showPairingCodeDialog && state.pairingCodeDialogMode == PairingMode.SERVER_MODE) {
        PairingCodeDialog(
            mode = PairingMode.SERVER_MODE,
            deviceManager = deviceManager,
            pairingCode = state.serverPairingCode,
            show = state.showPairingCodeDialog,
            onDismiss = {
                deviceManager.cancelPendingPairing()
                state.showPairingCodeDialog = false
            },
            onPairingComplete = { success, _ ->
                if (success) {
                    state.showPairingCodeDialog = false
                    try {
                        deviceManager.updateDeviceListInternal()
                        val authMap = deviceManager.getAuthenticatedDevices()
                        authedDeviceUuids = authMap.filter { (_, auth) -> auth.isAccepted }.keys.toSet()
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }

    // 配对码对话框 - 客户端模式
    if (state.showPairingCodeDialog && state.pairingCodeDialogMode == PairingMode.CLIENT_MODE && state.pendingConnectDevice != null) {
        PairingCodeDialog(
            mode = PairingMode.CLIENT_MODE,
            deviceManager = deviceManager,
            targetDevice = state.pendingConnectDevice,
            show = state.showPairingCodeDialog,
            onDismiss = {
                state.showPairingCodeDialog = false
                state.pendingConnectDevice = null
            },
            onPairingComplete = { success: Boolean, _: String ->
                if (success) {
                    state.showPairingCodeDialog = false
                    state.pendingConnectDevice = null
                    try {
                        deviceManager.updateDeviceListInternal()
                        val authMap = deviceManager.getAuthenticatedDevices()
                        authedDeviceUuids = authMap.filter { (_, auth) -> auth.isAccepted }.keys.toSet()
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }

    if (state.showRejectedDialog) {
        val showDialog = remember { mutableStateOf(true) }
        RejectedDevicesDialog(
            showDialog = showDialog,
            rejectedDevices = rejectedDevices,
            onRestoreDevice = { device ->
                val field = deviceManager.javaClass.getDeclaredField("rejectedDevices")
                field.isAccessible = true
                val rawSet = field.get(deviceManager)
                if (rawSet is MutableSet<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val ms = rawSet as MutableSet<String>
                    val allUuids = findOtherUuidsWithSameIp(device.ip, "") + device.uuid
                    allUuids.distinct().forEach { ms.remove(it) }
                }
                @Suppress("UNCHECKED_CAST")
                rejectedDeviceUuids = if (rawSet is MutableSet<*>) (rawSet as MutableSet<String>).toSet() else emptySet()
            },
            onDismiss = {
                showDialog.value = false
                state.showRejectedDialog = false
            },
        )
    }

    // 删除设备历史确认弹窗
    if (showDeleteHistoryDialog && pendingDeleteDevice != null) {
        val deviceToDelete = pendingDeleteDevice!!

        WindowDialog(
            show = showDeleteHistoryDialog,
            title = "删除设备",
            summary = "是否同时删除「${deviceToDelete.displayName}」的通知历史？",
            titleColor = DialogDefaults.titleColor(),
            summaryColor = DialogDefaults.summaryColor(),
            backgroundColor = DialogDefaults.backgroundColor(),
            enableWindowDim = true,
            onDismissRequest = {
                selectedDevice = null
                GlobalSelectedDeviceHolder.selectedDevice = null
                showDeleteHistoryDialog = false
                pendingDeleteDevice = null
            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "仅删除设备",
                            onClick = {
                                try {
                                    val removed = deviceManager.removeAuthenticatedDevice(deviceToDelete.uuid, deleteHistory = false)
                                    if (removed) {
                                        authedDeviceUuids = authedDeviceUuids - deviceToDelete.uuid
                                    } else {
                                        ToastUtils.showShortToast(context, "删除设备失败: 设备不存在或持久化删除未完成，请重试")
                                    }
                                } catch (e: Exception) {
                                    ToastUtils.showShortToast(context, "删除设备失败: ${e.message ?: "未知错误"}")
                                }
                                selectedDevice = null
                                GlobalSelectedDeviceHolder.selectedDevice = null
                                showDeleteHistoryDialog = false
                                pendingDeleteDevice = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "删除并清除历史",
                            onClick = {
                                try {
                                    val removed = deviceManager.removeAuthenticatedDevice(deviceToDelete.uuid, deleteHistory = true)
                                    if (removed) {
                                        authedDeviceUuids = authedDeviceUuids - deviceToDelete.uuid
                                    } else {
                                        ToastUtils.showShortToast(context, "删除设备失败: 设备不存在或持久化删除未完成，请重试")
                                    }
                                } catch (e: Exception) {
                                    ToastUtils.showShortToast(context, "删除设备失败: ${e.message ?: "未知错误"}")
                                }
                                selectedDevice = null
                                GlobalSelectedDeviceHolder.selectedDevice = null
                                showDeleteHistoryDialog = false
                                pendingDeleteDevice = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }
}
