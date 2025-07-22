
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

data class AuthInfo(
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean
)

class DeviceConnectionManager(private val context: android.content.Context) {
    /**
     * 新增：服务端握手请求回调，UI层应监听此回调并弹窗确认，参数为请求设备uuid/displayName/公钥，回调参数true=同意，false=拒绝
     */
    var onHandshakeRequest: ((DeviceInfo, String, (Boolean) -> Unit) -> Unit)? = null
    /**
     * 设备发现/连接/数据发送/接收，全部本地实现。
     */
    var onNotificationDataReceived: ((String) -> Unit)? = null
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices
    private val uuid: String
    // 认证设备表，key为uuid
    private val authenticatedDevices = mutableMapOf<String, AuthInfo>()
    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()
    // 本地密钥对（简单字符串模拟，实际应用可用RSA/ECDH等）
    private val localPublicKey = UUID.randomUUID().toString().replace("-", "")
    private val localPrivateKey = UUID.randomUUID().toString().replace("-", "")
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
                if (rejectedDevices.contains(device.uuid)) {
                    callback?.invoke(false, "已被对方拒绝")
                    return@launch
                }
                val socket = Socket(device.ip, device.port)
                // 不设置超时，始终等待对方操作
                val writer = OutputStreamWriter(socket.getOutputStream())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                // 发送握手请求，带上本地公钥
                writer.write("HANDSHAKE:${uuid}:${localPublicKey}\n")
                writer.flush()
                // 等待对方确认（无限等待，直到对方操作）
                val resp = reader.readLine()
                if (resp != null && resp.startsWith("ACCEPT:")) {
                    val parts = resp.split(":")
                    if (parts.size >= 3) {
                        val remotePubKey = parts[2]
                        // 简单生成共享密钥（实际应用应用DH等）
                        val sharedSecret = (localPublicKey + remotePubKey).take(32)
                        authenticatedDevices[device.uuid] = AuthInfo(remotePubKey, sharedSecret, true)
                        callback?.invoke(true, null)
                    } else {
                        callback?.invoke(false, "认证响应格式错误")
                    }
                } else if (resp != null && resp.startsWith("REJECT:")) {
                    rejectedDevices.add(device.uuid)
                    callback?.invoke(false, "对方拒绝连接")
                } else {
                    callback?.invoke(false, "认证失败")
                }
                writer.close()
                reader.close()
                socket.close()
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
                val auth = authenticatedDevices[device.uuid]
                if (auth == null || !auth.isAccepted) {
                    Log.d("NotifyRelay", "未认证设备，禁止发送")
                    return@launch
                }
                val socket = Socket(device.ip, device.port)
                val writer = OutputStreamWriter(socket.getOutputStream())
                // 发送数据时带上认证信息
                val payload = "DATA:${uuid}:${auth.publicKey}:${auth.sharedSecret}:${data}"
                writer.write(payload + "\n")
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
                            val line = reader.readLine()
                            if (line != null) {
                                if (line.startsWith("HANDSHAKE:")) {
                                    // 握手请求
                                    val parts = line.split(":")
                                    if (parts.size >= 3) {
                                        val remoteUuid = parts[1]
                                        val remotePubKey = parts[2]
                                        val remoteDevice = _devices.value.find { it.uuid == remoteUuid } ?: DeviceInfo(remoteUuid, "未知设备", client.inetAddress.hostAddress ?: "", client.port)
                                        // 通过回调通知UI弹窗确认
                                        onHandshakeRequest?.invoke(remoteDevice, remotePubKey) { accepted ->
                                            coroutineScope.launch {
                                                val writer = OutputStreamWriter(client.getOutputStream())
                                                if (accepted) {
                                                    val sharedSecret = (localPublicKey + remotePubKey).take(32)
                                                    authenticatedDevices[remoteUuid] = AuthInfo(remotePubKey, sharedSecret, true)
                                                    writer.write("ACCEPT:${uuid}:${localPublicKey}\n")
                                                } else {
                                                    rejectedDevices.add(remoteUuid)
                                                    writer.write("REJECT:${uuid}\n")
                                                }
                                                writer.flush()
                                                writer.close()
                                                reader.close()
                                                client.close()
                                            }
                                        } ?: run {
                                            // 若无回调，默认拒绝
                                            val writer = OutputStreamWriter(client.getOutputStream())
                                            writer.write("REJECT:${uuid}\n")
                                            writer.flush()
                                            writer.close()
                                            reader.close()
                                            client.close()
                                        }
                                    } else {
                                        val writer = OutputStreamWriter(client.getOutputStream())
                                        writer.write("REJECT:${uuid}\n")
                                        writer.flush()
                                        writer.close()
                                        reader.close()
                                        client.close()
                                    }
                                } else if (line.startsWith("DATA:")) {
                                    // 数据包，校验认证
                                    val parts = line.split(":", limit = 5)
                                    if (parts.size >= 5) {
                                        val remoteUuid = parts[1]
                                        val remotePubKey = parts[2]
                                        val sharedSecret = parts[3]
                                        val payload = parts[4]
                                        val auth = authenticatedDevices[remoteUuid]
                                        if (auth != null && auth.publicKey == remotePubKey && auth.sharedSecret == sharedSecret && auth.isAccepted) {
                                            onNotificationDataReceived?.invoke(payload) ?: handleNotificationData(payload)
                                        } else {
                                            Log.d("NotifyRelay", "认证失败，拒绝处理数据")
                                        }
                                    }
                                    reader.close()
                                    client.close()
                                } else {
                                    Log.d("NotifyRelay", "未知请求")
                                    reader.close()
                                    client.close()
                                }
                            }
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