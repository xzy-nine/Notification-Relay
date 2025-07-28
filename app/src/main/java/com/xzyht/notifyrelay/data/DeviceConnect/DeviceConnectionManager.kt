
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

object DeviceConnectionManagerUtil {
    // 工具：构造 json 格式的通知数据
    fun buildNotificationJson(packageName: String, appName: String?, title: String?, text: String?, time: Long): String {
        val json = org.json.JSONObject()
        json.put("packageName", packageName)
        json.put("appName", appName ?: packageName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("time", time)
        return json.toString()
    }

    // 静态缓存，便于 UI 查询 uuid->displayName
    private val globalDeviceNameCache = mutableMapOf<String, String>()
    fun updateGlobalDeviceName(uuid: String, displayName: String) {
        synchronized(globalDeviceNameCache) {
            globalDeviceNameCache[uuid] = displayName
        }
    }
    fun getDisplayNameByUuid(uuid: String?): String {
        if (uuid == null) return "未知设备"
        if (uuid == "本机") return "本机"
        synchronized(globalDeviceNameCache) {
            return globalDeviceNameCache[uuid] ?: uuid
        }
    }
}


data class AuthInfo(
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean,
    val displayName: String? = null // 新增：持久化设备名
)

class DeviceConnectionManager(private val context: android.content.Context) {
    /**
     * 停止所有后台线程和网络服务，释放资源，供 Service onDestroy 调用
     */
    fun stopAll() {
        try {
            // 停止UDP广播线程
            broadcastThread?.interrupt()
            broadcastThread = null
        } catch (_: Exception) {}
        try {
            // 停止UDP监听线程
            listenThread?.interrupt()
            listenThread = null
        } catch (_: Exception) {}
        try {
            // 关闭TCP服务端口
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        // 其他清理（如定时任务）
        // coroutineScope.cancel() 不建议直接调用，避免影响外部协程
    }
    // 设备信息缓存，解决未认证设备无法显示详细信息问题
    private val deviceInfoCache = mutableMapOf<String, DeviceInfo>()
    //private fun logDeviceCache(tag: String) {
    //    Log.d("NotifyRelay", "[$tag] deviceInfoCache: ${deviceInfoCache.keys}")
    //    Log.d("NotifyRelay", "[$tag] deviceLastSeen: ${deviceLastSeen.keys}")
    //    Log.d("NotifyRelay", "[$tag] authenticatedDevices: ${authenticatedDevices.keys}")
    //    Log.d("NotifyRelay", "[$tag] rejectedDevices: ${rejectedDevices}")
    //    Log.d("NotifyRelay", "[$tag] _devices: ${_devices.value.keys}")
    //}
    // 持久化认证设备表的key
    private val PREFS_AUTHED_DEVICES = "authed_devices_json"

    // 加载已认证设备
    private fun loadAuthedDevices() {
        val json = prefs.getString(PREFS_AUTHED_DEVICES, null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uuid = obj.getString("uuid")
                val publicKey = obj.getString("publicKey")
                val sharedSecret = obj.getString("sharedSecret")
                val isAccepted = obj.optBoolean("isAccepted", true)
                val displayName = obj.optString("displayName").takeIf { it.isNotEmpty() }
                authenticatedDevices[uuid] = AuthInfo(publicKey, sharedSecret, isAccepted, displayName)
                // 新增：恢复设备名到缓存
                if (!displayName.isNullOrEmpty()) {
                    DeviceConnectionManagerUtil.updateGlobalDeviceName(uuid, displayName)
                }
            }
        } catch (_: Exception) {}
    }

    // 保存已认证设备
    private fun saveAuthedDevices() {
        try {
            val arr = org.json.JSONArray()
            for ((uuid, auth) in authenticatedDevices) {
                if (auth.isAccepted) {
                    val obj = org.json.JSONObject()
                    obj.put("uuid", uuid)
                    obj.put("publicKey", auth.publicKey)
                    obj.put("sharedSecret", auth.sharedSecret)
                    obj.put("isAccepted", auth.isAccepted)
                    // 新增：持久化 displayName
                    val name = auth.displayName ?: deviceInfoCache[uuid]?.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
                    obj.put("displayName", name)
                    arr.put(obj)
                }
            }
            prefs.edit().putString(PREFS_AUTHED_DEVICES, arr.toString()).apply()
        } catch (_: Exception) {}
    }
    /**
     * 新增：服务端握手请求回调，UI层应监听此回调并弹窗确认，参数为请求设备uuid/displayName/公钥，回调参数true=同意，false=拒绝
     */
    var onHandshakeRequest: ((DeviceInfo, String, (Boolean) -> Unit) -> Unit)? = null
    /**
     * 设备发现/连接/数据发送/接收，全部本地实现。
     */
    var onNotificationDataReceived: ((String) -> Unit)? = null
    private val _devices = MutableStateFlow<Map<String, Pair<DeviceInfo, Boolean>>>(emptyMap())
    /**
     * 设备状态流：key为uuid，value为(DeviceInfo, isOnline)
     * 只要认证过的设备会一直保留，未认证设备3秒未发现则消失
     */
    val devices: StateFlow<Map<String, Pair<DeviceInfo, Boolean>>> = _devices
    private val uuid: String
    // 认证设备表，key为uuid
    private val authenticatedDevices = mutableMapOf<String, AuthInfo>()
    // 被拒绝设备表
    private val rejectedDevices = mutableSetOf<String>()
    // 本地密钥对（简单字符串模拟，实际应用可用RSA/ECDH等）
    private val localPublicKey: String
    private val localPrivateKey: String
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
        val savedUuid = prefs.getString("device_uuid", null)
        if (savedUuid != null) {
            uuid = savedUuid
        } else {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", newUuid).apply()
            uuid = newUuid
        }
        // 公钥持久化
        val savedPub = prefs.getString("device_pubkey", null)
        if (savedPub != null) {
            localPublicKey = savedPub
        } else {
            val newPub = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_pubkey", newPub).apply()
            localPublicKey = newPub
        }
        // 私钥可临时
        localPrivateKey = UUID.randomUUID().toString().replace("-", "")
        loadAuthedDevices()
        startOfflineDeviceCleaner()
    }
    fun startDiscovery() {
        // 优先获取蓝牙设备名，获取不到则用型号
        fun getLocalDisplayName(): String {
            return try {
                val name = android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                if (!name.isNullOrEmpty()) name else android.os.Build.MODEL
            } catch (_: Exception) {
                android.os.Build.MODEL
            }
        }
        // 启动UDP广播线程，定期广播本机信息
        if (broadcastThread == null) {
            //android.util.Log.d("NotifyRelay", "广播线程即将启动")
            broadcastThread = Thread {
                //android.util.Log.d("NotifyRelay", "广播线程已启动")
                try {
                    val socket = java.net.DatagramSocket()
                    val displayName = getLocalDisplayName()
                    val group = java.net.InetAddress.getByName("255.255.255.255")
                    while (true) {
                        val buf = ("NOTIFYRELAY_DISCOVER:${uuid}:${displayName}:${listenPort}").toByteArray()
                        val packet = java.net.DatagramPacket(buf, buf.size, group, 23334)
                        socket.send(packet)
                        //android.util.Log.d("NotifyRelay", "已发送广播: NOTIFYRELAY_DISCOVER:${uuid}:${displayName}:${listenPort}")
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
            //android.util.Log.d("NotifyRelay", "监听线程即将启动")
            listenThread = Thread {
                //android.util.Log.d("NotifyRelay", "监听线程已启动")
                try {
                    val socket = java.net.DatagramSocket(23334)
                    val buf = ByteArray(256)
                    while (true) {
                        val packet = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        //android.util.Log.d("NotifyRelay", "收到广播: $msg, ip=${packet.address.hostAddress}")
                        // logDeviceCache("before_broadcast_handle")
                        if (msg.startsWith("NOTIFYRELAY_DISCOVER:")) {
                            val parts = msg.split(":")
                            if (parts.size >= 4) {
                                val uuid = parts[1]
                                val displayName = parts[2]
                                val port = parts[3].toIntOrNull() ?: 23333
                                val ip = packet.address.hostAddress
                                if (!uuid.isNullOrEmpty() && uuid != this@DeviceConnectionManager.uuid && !ip.isNullOrEmpty()) {
                                val device = DeviceInfo(uuid, displayName, ip, port)
                                deviceLastSeen[uuid] = System.currentTimeMillis()
                                synchronized(deviceInfoCache) {
                                    deviceInfoCache[uuid] = device
                                }
                                // 更新全局缓存
                                DeviceConnectionManagerUtil.updateGlobalDeviceName(uuid, displayName)
                                // Log.d("NotifyRelay", "[broadcast_handle] 新增/更新设备: $device")
                                // logDeviceCache("after_broadcast_handle")
                                coroutineScope.launch {
                                    updateDeviceList()
                                }
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

    // 统一设备状态管理：3秒未发现未认证设备直接移除，已认证设备置灰
    private fun startOfflineDeviceCleaner() {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                updateDeviceList()
            }
        }
    }

    private fun updateDeviceList() {
        val now = System.currentTimeMillis()
        val authed = synchronized(authenticatedDevices) { authenticatedDevices.keys.toSet() }
        val allUuids = (deviceLastSeen.keys + authed).toSet()
        val newMap = mutableMapOf<String, Pair<DeviceInfo, Boolean>>()
        val unauthedTimeout = 30_000L // 未认证设备保留30秒
        val authedOfflineTimeout = 9_000L // 已认证设备离线阈值，放宽到9秒
        for (uuid in allUuids) {
            val lastSeen = deviceLastSeen[uuid]
            val auth = synchronized(authenticatedDevices) { authenticatedDevices[uuid] }
            if (auth != null) {
                // 已认证设备，离线也保留
                val isOnline = lastSeen != null && now - lastSeen <= authedOfflineTimeout
                val info = getDeviceInfo(uuid) ?: DeviceInfo(uuid, auth.displayName ?: "已认证设备", "", listenPort)
                newMap[uuid] = info to isOnline
            } else {
                // 未认证设备，30秒未发现则移除
                val isOnline = lastSeen != null && now - lastSeen <= unauthedTimeout
                if (isOnline) {
                    val info = getDeviceInfo(uuid)
                    if (info != null) newMap[uuid] = info to true
                } else {
                    deviceLastSeen.remove(uuid)
                }
            }
        }
        _devices.value = newMap
    }

    private fun getDeviceInfo(uuid: String): DeviceInfo? {
        // 优先从缓存取，取不到再从已展示的设备流取
        synchronized(deviceInfoCache) {
            deviceInfoCache[uuid]?.let { return it }
        }
        // 新增：若为已认证设备，优先用认证表中的 displayName
        val auth = authenticatedDevices[uuid]
        if (auth != null) {
            val name = auth.displayName ?: DeviceConnectionManagerUtil.getDisplayNameByUuid(uuid)
            return DeviceInfo(uuid, name, "", listenPort)
        }
        return _devices.value[uuid]?.first
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
                        val sharedSecret = if (localPublicKey < remotePubKey)
                            (localPublicKey + remotePubKey).take(32)
                        else
                            (remotePubKey + localPublicKey).take(32)
                        // 先移除旧的认证表项
                        synchronized(authenticatedDevices) {
                            authenticatedDevices.remove(device.uuid)
                            authenticatedDevices[device.uuid] = AuthInfo(remotePubKey, sharedSecret, true, device.displayName)
                            saveAuthedDevices()
                        }
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

    // 简单对称加密（XOR），仅用于示例，实际应用请替换为安全算法
    private fun xorEncryptDecrypt(input: String, key: String): String {
        val keyBytes = key.toByteArray()
        val inputBytes = input.toByteArray()
        val output = ByteArray(inputBytes.size)
        for (i in inputBytes.indices) {
            output[i] = (inputBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return android.util.Base64.encodeToString(output, android.util.Base64.NO_WRAP)
    }

    private fun xorDecrypt(input: String, key: String): String {
        val keyBytes = key.toByteArray()
        val inputBytes = android.util.Base64.decode(input, android.util.Base64.NO_WRAP)
        val output = ByteArray(inputBytes.size)
        for (i in inputBytes.indices) {
            output[i] = (inputBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(output)
    }

    // 发送通知数据（加密）
    fun sendNotificationData(device: DeviceInfo, data: String) {
        // data 必须为 json 字符串，包含 packageName, title, text, time
        coroutineScope.launch {
            try {
                val auth = authenticatedDevices[device.uuid]
                if (auth == null || !auth.isAccepted) {
                    Log.d("NotifyRelay", "未认证设备，禁止发送")
                    return@launch
                }
                val socket = Socket(device.ip, device.port)
                val writer = OutputStreamWriter(socket.getOutputStream())
                // 加密数据
                val encryptedData = xorEncryptDecrypt(data, auth.sharedSecret)
                // 标记 json
                val payload = "DATA_JSON:${uuid}:${localPublicKey}:${auth.sharedSecret}:${encryptedData}"
                writer.write(payload + "\n")
                writer.flush()
                writer.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 接收通知数据（解密）
    fun handleNotificationData(data: String, sharedSecret: String? = null, remoteUuid: String? = null) {
        Log.d("NotifyRelay", "收到通知数据: $data")
        val decrypted = if (sharedSecret != null) {
            try {
                xorDecrypt(data, sharedSecret)
            } catch (e: Exception) {
                Log.d("NotifyRelay", "解密失败: ${e.message}")
                data
            }
        } else data
        // 只处理 json 格式
        try {
            if (remoteUuid != null) {
                val json = org.json.JSONObject(decrypted)
                val pkg = json.optString("packageName")
                val title = json.optString("title")
                val text = json.optString("text")
                val time = json.optLong("time", System.currentTimeMillis())
                // 尝试获取设备名并更新缓存（如有）
                val displayName = DeviceConnectionManagerUtil.getDisplayNameByUuid(remoteUuid)
                if (!displayName.isNullOrEmpty() && displayName != remoteUuid) {
                    DeviceConnectionManagerUtil.updateGlobalDeviceName(remoteUuid, displayName)
                }
                val repoClass = Class.forName("com.xzyht.notifyrelay.data.Notify.NotificationRepository")
                val addMethod = repoClass.getDeclaredMethod("addRemoteNotification", String::class.java, String::class.java, String::class.java, Long::class.java, String::class.java, android.content.Context::class.java)
                addMethod.invoke(null, pkg, title, text, time, remoteUuid, context)
                // 强制刷新设备列表和UI
                val scanMethod = repoClass.getDeclaredMethod("scanDeviceList", android.content.Context::class.java)
                scanMethod.invoke(null, context)
            }
        } catch (e: Exception) {
            Log.e("NotifyRelay", "存储远程通知失败: ${e.message}")
        }
        // 不再直接发系统通知，由 UI 层渲染
        onNotificationDataReceived?.invoke(decrypted)
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
                                        val ip: String = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                                        val remoteDevice = _devices.value[remoteUuid]?.first ?: DeviceInfo(
                                            remoteUuid,
                                            "未知设备",
                                            ip,
                                            client.port
                                        )
                                        // 通过回调通知UI弹窗确认
                                        val handshakeHandler = onHandshakeRequest
                                        if (handshakeHandler != null) {
                                            handshakeHandler.invoke(remoteDevice, remotePubKey) { accepted ->
                                                // 关键修复：无论回调在何线程，先同步写入认证表
                                                if (accepted) {
                                                    val sharedSecret = if (localPublicKey < remotePubKey)
                                                        (localPublicKey + remotePubKey).take(32)
                                                    else
                                                        (remotePubKey + localPublicKey).take(32)
                                                    synchronized(authenticatedDevices) {
                                                        authenticatedDevices.remove(remoteUuid)
                                                        authenticatedDevices[remoteUuid] = AuthInfo(remotePubKey, sharedSecret, true, remoteDevice.displayName)
                                                        saveAuthedDevices()
                                                    }
                                                } else {
                                                    synchronized(rejectedDevices) {
                                                        rejectedDevices.add(remoteUuid)
                                                    }
                                                }
                                                coroutineScope.launch {
                                                    val writer = OutputStreamWriter(client.getOutputStream())
                                                    if (accepted) {
                                                        writer.write("ACCEPT:${uuid}:${localPublicKey}\n")
                                                    } else {
                                                        writer.write("REJECT:${uuid}\n")
                                                    }
                                                    writer.flush()
                                                    writer.close()
                                                    reader.close()
                                                    client.close()
                                                }
                                            }
                                        } else {
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
                                } else if (line.startsWith("DATA:") || line.startsWith("DATA_JSON:")) {
                                    // 数据包，校验认证
                                    val isJson = line.startsWith("DATA_JSON:")
                                    val parts = line.split(":", limit = 5)
                                    if (parts.size >= 5) {
                                        val remoteUuid = parts[1]
                                        val remotePubKey = parts[2]
                                        val sharedSecret = parts[3]
                                        val payload = parts[4]
                                        val auth = authenticatedDevices[remoteUuid]
                                        if (auth != null && auth.sharedSecret == sharedSecret && auth.isAccepted) {
                                            // 解密数据并存储，传递 remoteUuid
                                            handleNotificationData(payload, sharedSecret, remoteUuid)
                                        } else {
                                            if (auth == null) {
                                                Log.d("NotifyRelay", "认证失败：无此uuid(${remoteUuid})的认证记录")
                                            } else {
                                                val reason = buildString {
                                                    if (auth.sharedSecret != sharedSecret) append("sharedSecret不匹配; ")
                                                    if (!auth.isAccepted) append("isAccepted=false; ")
                                                }
                                                Log.d("NotifyRelay", "认证失败，拒绝处理数据，uuid=${remoteUuid}, 本地sharedSecret=${auth.sharedSecret}, 对方sharedSecret=${sharedSecret}, isAccepted=${auth.isAccepted}，原因: $reason")
                                            }
                                        }
                                    }
                                    reader.close()
                                    client.close()
                                } else {
                                    Log.d("NotifyRelay", "未知请求: $line")
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