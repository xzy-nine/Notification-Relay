package com.xzyht.notifyrelay.feature.device.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.HandshakeRequest
import com.xzyht.notifyrelay.common.data.PersistenceManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable

/**
 * ViewModel for DeviceListFragment, handling business logic and state management.
 */
class DeviceListViewModel(private val context: Context) : ViewModel() {

    private val deviceManager: DeviceConnectionManager = DeviceForwardFragment.getDeviceManager(context)

    // UI state
    var udpDiscoveryEnabled by mutableStateOf(deviceManager.udpDiscoveryEnabled)
        private set

    var authedDeviceUuids by mutableStateOf(setOf<String>())
        private set

    var rejectedDeviceUuids by mutableStateOf(setOf<String>())
        private set

    var showRejectedDialog by mutableStateOf(false)
        private set

    var showConnectDialog by mutableStateOf(false)
        private set

    var pendingConnectDevice by mutableStateOf<DeviceInfo?>(null)
        private set

    var pendingHandshakeRequest by mutableStateOf<HandshakeRequest?>(null)
        private set

    var showHandshakeDialog by mutableStateOf(false)
        private set

    var selectedDevice by mutableStateOf<DeviceInfo?>(null)
        private set

    // Device list from manager
    val deviceMap: Map<String, Pair<DeviceInfo, Boolean>> = deviceManager.devices.value
    val devices: List<DeviceInfo> = deviceMap.values.map { it.first }
    val deviceStates: Map<String, Boolean> = deviceMap.mapValues { it.value.second }

    init {
        updateAuthStates()
    }

    /**
     * Toggle UDP discovery
     */
    fun toggleUdpDiscovery(enabled: Boolean) {
        udpDiscoveryEnabled = enabled
        deviceManager.udpDiscoveryEnabled = enabled
        if (enabled) {
            deviceManager.startDiscovery()
        } else {
            stopUdpThreads()
        }
    }

    private fun stopUdpThreads() {
        try {
            val broadcastField = deviceManager.javaClass.getDeclaredField("broadcastThread")
            broadcastField.isAccessible = true
            (broadcastField.get(deviceManager) as? Thread)?.interrupt()
            broadcastField.set(deviceManager, null)
            val listenField = deviceManager.javaClass.getDeclaredField("listenThread")
            listenField.isAccessible = true
            (listenField.get(deviceManager) as? Thread)?.interrupt()
            listenField.set(deviceManager, null)
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error stopping UDP threads: ${e.message}")
        }
    }

    /**
     * Update authentication states from device manager
     */
    fun updateAuthStates() {
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
            val rejField = deviceManager.javaClass.getDeclaredField("rejectedDevices")
            rejField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            rejectedDeviceUuids = (rejField.get(deviceManager) as? Set<String>)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error updating auth states: ${e.message}")
        }
    }

    /**
     * Select a device
     */
    fun selectDevice(device: DeviceInfo?) {
        if (device == null) {
            selectedDevice = null
        } else if (authedDeviceUuids.contains(device.uuid)) {
            selectedDevice = device
        } else {
            pendingConnectDevice = device
            showConnectDialog = true
        }
    }

    /**
     * Connect to a device
     */
    fun connectToDevice(device: DeviceInfo, onResult: (Boolean, String?) -> Unit) {
        removeOldAuthForSameIp(device)
        deviceManager.connectToDevice(device) { success, msg ->
            if (success) {
                updateAuthStates()
            }
            onResult(success, msg)
        }
    }

    private fun removeOldAuthForSameIp(device: DeviceInfo) {
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
            val oldUuids = findOtherUuidsWithSameIp(device.ip, "")
            val appContext = context.applicationContext
            for (uuid in oldUuids.distinct()) {
                safeMap.remove(uuid)
                clearDeviceHistory(uuid, appContext)
                PersistenceManager.deleteNotificationFile(appContext, uuid)
            }
            saveAuthedDevices()
            updateDeviceList()
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error removing old auth: ${e.message}")
        }
    }

    private fun findOtherUuidsWithSameIp(ip: String, exceptUuid: String): List<String> {
        return deviceMap.values.map { it.first }
            .filter { it.ip == ip && it.uuid != exceptUuid && authedDeviceUuids.contains(it.uuid) }
            .map { it.uuid }
    }

    private fun clearDeviceHistory(uuid: String, appContext: Context) {
        try {
            val notificationDataClass = Class.forName("com.xzyht.notifyrelay.feature.device.model.NotificationData")
            val getInstance = notificationDataClass.getDeclaredMethod("getInstance", Context::class.java)
            val notificationData = getInstance.invoke(null, appContext)
            val clearDeviceHistory = notificationDataClass.getDeclaredMethod("clearDeviceHistory", String::class.java, Context::class.java)
            clearDeviceHistory.invoke(notificationData, uuid, appContext)
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error clearing device history: ${e.message}")
        }
    }

    private fun saveAuthedDevices() {
        try {
            val saveMethod = deviceManager.javaClass.getDeclaredMethod("saveAuthedDevices")
            saveMethod.isAccessible = true
            saveMethod.invoke(deviceManager)
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error saving authed devices: ${e.message}")
        }
    }

    private fun updateDeviceList() {
        try {
            val updateMethod = deviceManager.javaClass.getDeclaredMethod("updateDeviceList")
            updateMethod.isAccessible = true
            updateMethod.invoke(deviceManager)
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error updating device list: ${e.message}")
        }
    }

    /**
     * Remove a device
     */
    fun removeDevice(device: DeviceInfo) {
        try {
            stopHeartbeat(device.uuid)
            removeFromHeartbeatedDevices(device.uuid)
            removeFromAuthenticatedDevices(device.uuid)
            saveAuthedDevices()
            updateDeviceList()
            updateAuthStates()
            selectedDevice = null
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error removing device: ${e.message}")
        }
    }

    private fun stopHeartbeat(uuid: String) {
        val heartbeatJobsField = deviceManager.javaClass.getDeclaredField("heartbeatJobs")
        heartbeatJobsField.isAccessible = true
        val heartbeatJobs = heartbeatJobsField.get(deviceManager) as? MutableMap<String, *>
        heartbeatJobs?.remove(uuid)
    }

    private fun removeFromHeartbeatedDevices(uuid: String) {
        val heartbeatedDevicesField = deviceManager.javaClass.getDeclaredField("heartbeatedDevices")
        heartbeatedDevicesField.isAccessible = true
        val heartbeatedDevices = heartbeatedDevicesField.get(deviceManager) as? MutableSet<String>
        heartbeatedDevices?.remove(uuid)
    }

    private fun removeFromAuthenticatedDevices(uuid: String) {
        val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
        field.isAccessible = true
        val map = field.get(deviceManager)
        if (map is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (map as? MutableMap<String, *>)?.remove(uuid)
        }
    }

    /**
     * Handle handshake request acceptance
     */
    fun acceptHandshakeRequest(request: HandshakeRequest) {
        removeOldAuthForSameIp(request.device)
        request.callback(true)
        updateDeviceList()
        updateAuthStates()
    }

    /**
     * Handle handshake request rejection
     */
    fun rejectHandshakeRequest(request: HandshakeRequest) {
        request.callback(false)
    }

    /**
     * Show rejected devices dialog
     */
    fun showRejectedDevicesDialog() {
        showRejectedDialog = true
    }

    /**
     * Dismiss rejected devices dialog
     */
    fun dismissRejectedDevicesDialog() {
        showRejectedDialog = false
    }

    /**
     * Restore a rejected device
     */
    fun restoreRejectedDevice(device: DeviceInfo) {
        try {
            val field = deviceManager.javaClass.getDeclaredField("rejectedDevices")
            field.isAccessible = true
            val set = field.get(deviceManager)
            if (set is MutableSet<*>) {
                @Suppress("UNCHECKED_CAST")
                val ms = set as? MutableSet<String>
                val allUuids = findOtherUuidsWithSameIp(device.ip, "") + device.uuid
                allUuids.distinct().forEach { ms?.remove(it) }
            }
            rejectedDeviceUuids = (set as? MutableSet<String>)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e("DeviceListViewModel", "Error restoring device: ${e.message}")
        }
    }

    /**
     * Dismiss connect dialog
     */
    fun dismissConnectDialog() {
        showConnectDialog = false
        pendingConnectDevice = null
    }

    /**
     * Dismiss handshake dialog
     */
    fun dismissHandshakeDialog() {
        showHandshakeDialog = false
        pendingHandshakeRequest = null
    }

    /**
     * Get rejected devices
     */
    fun getRejectedDevices(): List<DeviceInfo> {
        return rejectedDeviceUuids.mapNotNull { uuid ->
            devices.find { it.uuid == uuid } ?: DeviceInfo(uuid, "未知设备", "", 0)
        }
    }

    /**
     * Get unauthenticated devices
     */
    fun getUnauthenticatedDevices(): List<DeviceInfo> {
        return devices.filter { d ->
            !authedDeviceUuids.contains(d.uuid) && !rejectedDeviceUuids.contains(d.uuid)
        }
    }

    /**
     * Get authenticated devices
     */
    fun getAuthenticatedDevices(): List<DeviceInfo> {
        return devices.filter { authedDeviceUuids.contains(it.uuid) }
    }

    /**
     * Check if device is authenticated
     */
    fun isAuthenticated(uuid: String): Boolean = authedDeviceUuids.contains(uuid)

    /**
     * Get device manager for UI handler setup
     */
    fun getDeviceManager(): DeviceConnectionManager = deviceManager

    /**
     * Set UI handler for handshake requests
     */
    fun setUIHandler(handler: DeviceConnectionUIHandler) {
        deviceManager.setUIHandler(handler)
    }

    /**
     * Update pending handshake request
     */
    fun updatePendingHandshakeRequest(request: HandshakeRequest?) {
        pendingHandshakeRequest = request
        showHandshakeDialog = request != null
    }
}

/**
 * UI Handler for DeviceListViewModel
 */
class DeviceConnectionUIHandler(private val viewModel: DeviceListViewModel) : com.xzyht.notifyrelay.feature.device.service.DeviceConnectionUIHandler {
    override fun showToast(message: String) {
        // Implement toast showing if needed
    }

    override fun onHandshakeRequest(device: DeviceInfo, publicKey: String, callback: (Boolean) -> Unit) {
        val request = HandshakeRequest(device, publicKey, callback)
        viewModel.updatePendingHandshakeRequest(request)
    }

    override fun onNotificationDataReceived(data: String) {
        // Handle notification data if needed
    }

    override fun onDeviceListUpdated() {
        // Update device list if needed
    }
}
