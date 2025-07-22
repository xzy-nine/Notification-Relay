
package com.xzyht.notifyrelay.data.deviceconnect

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import android.util.Log


data class DeviceInfo(
    val uuid: String,
    val displayName: String, // 前端显示名，优先蓝牙名，其次型号
    val ip: String,
    val port: Int
)

class DeviceConnectionManager(private val context: android.content.Context) {
    /**
     * 设备发现/连接/数据发送/接收，全部本地实现。
     */
    var onNotificationDataReceived: ((String) -> Unit)? = null
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices
    private val uuid: String
    private val listenPort: Int = 23333
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val prefs: SharedPreferences = context.getSharedPreferences("notifyrelay_prefs", android.content.Context.MODE_PRIVATE)
    private val deviceLastSeen = mutableMapOf<String, Long>()
    // 广播发现线程
    private var broadcastThread: Thread? = null
    // 监听线程
    private var listenThread: Thread? = null

    init {
        val saved = prefs.getString("device_uuid", null)
        if (saved != null) {
            uuid = saved
        } else {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", newUuid).apply()
            uuid = newUuid
        }
        startOfflineDeviceCleaner()
    }
    fun startDiscovery() {
        // 启动UDP广播线程，定期广播本机信息
        if (broadcastThread == null) {
            broadcastThread = Thread {
                try {
                    val socket = java.net.DatagramSocket()
                    val buf = ("NOTIFYRELAY_DISCOVER:${uuid}:${android.os.Build.MODEL}:${listenPort}").toByteArray()
                    val group = java.net.InetAddress.getByName("255.255.255.255")
                    while (true) {
                        val packet = java.net.DatagramPacket(buf, buf.size, group, 23334)
                        socket.send(packet)
                        Thread.sleep(3000)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            broadcastThread?.isDaemon = true
            broadcastThread?.start()
        }
        // 启动UDP监听线程，发现其他设备
        if (listenThread == null) {
            listenThread = Thread {
                try {
                    val socket = java.net.DatagramSocket(23334)
                    val buf = ByteArray(256)
                    while (true) {
                        val packet = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg.startsWith("NOTIFYRELAY_DISCOVER:")) {
                            val parts = msg.split(":")
                            if (parts.size >= 4) {
                                val uuid = parts[1]
                                val displayName = parts[2]
                                val port = parts[3].toIntOrNull() ?: 23333
                                val ip = packet.address.hostAddress
                                if (!uuid.isNullOrEmpty() && uuid != this@DeviceConnectionManager.uuid) {
                                    val device = DeviceInfo(uuid, displayName, ip, port)
                                    _devices.value = (_devices.value.filter { it.uuid != uuid } + device)
                                    deviceLastSeen[uuid] = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            listenThread?.isDaemon = true
            listenThread?.start()
        }
        // 启动TCP服务监听
        startServer()
    }

    // 定时清理离线设备
    private fun startOfflineDeviceCleaner() {
        coroutineScope.launch {
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val timeout = 30_000
                val toRemove = deviceLastSeen.filter { now - it.value > timeout }.keys
                if (toRemove.isNotEmpty()) {
                    _devices.value = _devices.value.filterNot { it.uuid in toRemove }
                    toRemove.forEach { deviceLastSeen.remove(it) }
                }
            }
        }
    }

    // 连接设备
    fun connectToDevice(device: DeviceInfo, callback: ((Boolean, String?) -> Unit)? = null) {
        coroutineScope.launch {
            try {
                val socket = Socket(device.ip, device.port)
                // 可扩展握手/认证协议
                socket.close()
                callback?.invoke(true, null)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(false, e.message)
            }
        }
    }

    // 发送通知数据
    fun sendNotificationData(device: DeviceInfo, data: String) {
        coroutineScope.launch {
            try {
                val socket = Socket(device.ip, device.port)
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write(data + "\n")
                writer.flush()
                writer.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 接收通知数据
    fun handleNotificationData(data: String) {
        Log.d("NotifyRelay", "收到通知数据: $data")
    }

    // 启动TCP服务监听，接收其他设备的通知
    private fun startServer() {
        coroutineScope.launch {
            try {
                serverSocket = ServerSocket(listenPort)
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    coroutineScope.launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val data = reader.readLine()
                            if (data != null) {
                                onNotificationDataReceived?.invoke(data) ?: handleNotificationData(data)
                            }
                            reader.close()
                            client.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}