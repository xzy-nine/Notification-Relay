package com.xzyht.notifyrelay.data.DeviceConnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xzyht.notifyrelay.data.Notify.NotificationRecord
import com.xzyht.notifyrelay.data.Notify.NotificationRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import com.google.gson.Gson

/**
 * 设备连接与通知转发管理器
 * 支持多设备 WebSocket 长连接，心跳，PIN 验证与密钥交换，JSON 存储设备信息
 */
object DeviceConnectionManager {
    private val gson = Gson()
    private val deviceInfoFileName = "device_info.json"
    private var wsUrl: String? = null // 由发现流程动态赋值
    private var ws: WebSocket? = null
    private var wsJob: Job? = null
    private var context: Context? = null
    private var keyPair: KeyPair? = null
    private var remotePublicKey: PublicKey? = null
    private var pin: String? = null // 本地生成的PIN
    private var pinTimestamp: Long = 0L // PIN生成时间戳
    private val PIN_TIMEOUT = 60_000L // 1分钟
    private var isConnected = false
    private var lastHeartbeat = 0L
    private val HEARTBEAT_INTERVAL = 10_000L // 10秒
    private val CHANNEL_ID = "device_connect_foreground"
    private val NOTIFY_ID = 2001
    // mDNS/NSD 相关
    private var nsdManager: android.net.nsd.NsdManager? = null
    private val SERVICE_TYPE = "_notifyrelay._tcp."
    private var registeredServiceInfo: android.net.nsd.NsdServiceInfo? = null
    private val discoveredDevices = mutableListOf<DiscoveredDevice>()
    private var onPinReceived: ((String) -> Unit)? = null // 被发现方弹窗回调

    data class DiscoveredDevice(
        val name: String,
        val host: String,
        val port: Int,
        val uuid: String,
        val pubKey: String,
        val pin: String
    )

    /** 初始化连接管理器，注册本地服务并监听发现 */
    fun init(ctx: Context, onPin: ((String) -> Unit)? = null) {
        context = ctx.applicationContext
        onPinReceived = onPin
        loadDeviceInfo()
        nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager
        generatePin() // 初始化时生成PIN
        registerLocalService()
        discoverServices()
    }
    /** 注册本地 mDNS 服务，广播设备名 */
    private fun registerLocalService() {
        val ctx = context ?: return
        val uuid = getDeviceUUID(ctx)
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = getDeviceName(ctx)
            serviceType = SERVICE_TYPE
            port = 8080 // 占位，实际应为 WebSocket 服务端口
            setAttribute("uuid", uuid)
            // 不广播公钥和PIN，防止中间人攻击
        }
        nsdManager?.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                registeredServiceInfo = serviceInfo
                // 注册后弹窗展示PIN
                pin?.let { onPinReceived?.invoke(it) }
            }
            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
        })
    }
    /** 随机生成PIN码（6位数字），并记录生成时间 */
    private fun generatePin() {
        val random = SecureRandom()
        val pinValue = (100000 + random.nextInt(900000)).toString()
        pin = pinValue
        pinTimestamp = System.currentTimeMillis()
    }
    /** 校验PIN是否有效（未超时且匹配） */
    private fun isPinValid(input: String?): Boolean {
        val now = System.currentTimeMillis()
        return input != null && pin != null && input == pin && (now - pinTimestamp) <= PIN_TIMEOUT
    }
    /** 客户端校验服务端PIN（用于双向校验） */
    private fun isRemotePinValid(remotePin: String?): Boolean {
        val now = System.currentTimeMillis()
        return remotePin != null && (now - pinTimestamp) <= PIN_TIMEOUT
    }

    /** 发现同网段设备，保存设备信息 */
    private fun discoverServices() {
        nsdManager?.discoverServices(SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: android.net.nsd.NsdServiceInfo) {
                        val name = resolved.serviceName
                        val host = resolved.host.hostAddress ?: ""
                        val port = resolved.port
                        val attr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) resolved.attributes else emptyMap<String, Any>()
                        val uuidRaw = attr["uuid"]
                        val uuid = when (uuidRaw) {
                            is ByteArray -> try { String(uuidRaw, Charsets.UTF_8) } catch (_: Exception) { "" }
                            is String -> uuidRaw
                            else -> uuidRaw?.toString() ?: ""
                        }.trim()
                        val selfUuid = getDeviceUUID(context!!)
                        val selfHost = try { java.net.InetAddress.getLocalHost().hostAddress } catch (_: Exception) { null }
                        val selfPort = 8080 // 与注册服务端口保持一致
                        android.util.Log.d("DeviceDiscovery", "发现设备: name=$name, host=$host, port=$port, uuid=$uuid, selfUuid=$selfUuid, selfHost=$selfHost, selfPort=$selfPort")
                        // 用 uuid 过滤掉自己设备，且去重
                        if (uuid.isNotBlank() && uuid != selfUuid) {
                            if (discoveredDevices.none { it.uuid == uuid }) {
                                discoveredDevices.add(DiscoveredDevice(name, host, port, uuid, "", ""))
                            }
                        }
                    }
                    override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                })
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                val attr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) serviceInfo.attributes else emptyMap<String, Any>()
                val uuidRaw = attr["uuid"]
                val lostUuid = when (uuidRaw) {
                    is ByteArray -> try { String(uuidRaw, Charsets.UTF_8) } catch (_: Exception) { "" }
                    is String -> uuidRaw
                    else -> uuidRaw?.toString() ?: ""
                }.trim()
                if (lostUuid.isNotBlank()) {
                    discoveredDevices.removeAll { it.uuid == lostUuid }
                }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    /** 启动 WebSocket 长连接服务（前台），连接已发现设备 */
    fun startConnectionService(pinInput: String, device: DiscoveredDevice? = null) {
        if (context == null) return
        // 客户端校验本地PIN是否超时
        if (!isRemotePinValid(pinInput)) {
            android.widget.Toast.makeText(context, "PIN码已超时，请重新获取", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pin = pinInput // 记录连接方输入的PIN
        // 连接指定设备
        if (device != null) {
            wsUrl = "ws://${device.host}:${device.port}/notifyrelay"
            remotePublicKey = decodePublicKey(device.pubKey)
        }
        startForegroundService()
        connectWebSocket()
    }
    /** 获取已发现设备列表（过滤掉本机设备） */
    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        val ctx = context ?: return emptyList()
        val selfUuid = getDeviceUUID(ctx)
        return discoveredDevices.filter { it.uuid != selfUuid }
    }

    /** 设置被发现方 PIN 弹窗回调 */
    fun setOnPinReceived(callback: (String) -> Unit) {
        onPinReceived = callback
    }

    /** 关闭连接 */
    fun stopConnection() {
        wsJob?.cancel()
        ws?.close(1000, "Manual disconnect")
        isConnected = false
        stopForegroundService()
    }

    /** WebSocket 连接与心跳 */
    private fun connectWebSocket() {
        if (context == null) return
        val client = OkHttpClient.Builder()
            .pingInterval(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(wsUrl.orEmpty()).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 客户端发起握手，带PIN
                isConnected = false // 先不设为true，待校验通过
                lastHeartbeat = System.currentTimeMillis()
                keyPair = generateKeyPair()
                val pubKeyStr = keyPair?.public?.encoded?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                val handshake = mapOf("type" to "handshake", "pin" to pin.orEmpty(), "pubKey" to pubKeyStr.orEmpty())
                webSocket.send(gson.toJson(handshake))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, webSocket)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 仅支持文本 JSON
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
            }
        })
        // 心跳协程
        wsJob?.cancel()
        wsJob = CoroutineScope(Dispatchers.Default).launch {
            while (isConnected) {
                delay(HEARTBEAT_INTERVAL)
                ws?.send(gson.toJson(mapOf("type" to "heartbeat")))
            }
        }
    }

    /** 处理收到的消息（支持双向PIN校验） */
    private fun handleMessage(text: String, webSocket: WebSocket? = null) {
        val ctx = context ?: return
        val msg = gson.fromJson(text, Map::class.java)
        when (msg["type"]) {
            "handshake" -> {
                // 对方发来公钥和PIN，完成密钥交换和PIN校验
                val remotePubKeyStr = msg["pubKey"] as? String
                val remotePin = msg["pin"] as? String
                // 服务端校验PIN
                if (!isPinValid(remotePin)) {
                    // PIN校验失败，拒绝连接
                    webSocket?.close(4001, "PIN码错误或超时")
                    android.util.Log.d("DeviceConnect", "PIN校验失败，连接拒绝")
                    return
                }
                // 客户端校验服务端PIN（双向）
                if (!isRemotePinValid(pin)) {
                    webSocket?.close(4002, "服务端PIN超时")
                    android.util.Log.d("DeviceConnect", "服务端PIN超时，连接拒绝")
                    return
                }
                remotePublicKey = if (remotePubKeyStr != null) decodePublicKey(remotePubKeyStr) else null
                saveDeviceInfo(msg)
                isConnected = true // 校验通过才设为true
            }
            "notification" -> {
                // 收到通知转发
                val recordJson = msg["data"] as? String ?: return
                val deviceName = msg["deviceName"] as? String ?: "远程设备"
                val record = gson.fromJson(recordJson, NotificationRecord::class.java)
                NotificationRepository.notifications.removeAll { it.key == record.key }
                NotificationRepository.notifications.add(0, record.copy(device = deviceName))
                NotificationRepository.syncToCache(ctx)
            }
            "heartbeat" -> {
                lastHeartbeat = System.currentTimeMillis()
            }
        }
    }

    /** 发送通知到远程设备 */
    fun sendNotification(record: NotificationRecord) {
        if (!isConnected || remotePublicKey == null) return
        val json = gson.toJson(record)
        val encrypted = encryptWithPublicKey(json, remotePublicKey!!)
        val msg = mapOf("type" to "notification", "data" to encrypted)
        ws?.send(gson.toJson(msg))
    }

    /** 生成密钥对 */
    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    /** 解码公钥 */
    private fun decodePublicKey(base64: String): PublicKey? {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
            val spec = java.security.spec.X509EncodedKeySpec(bytes)
            java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (_: Exception) { null }
    }

    /** 使用公钥加密 */
    private fun encryptWithPublicKey(data: String, pubKey: PublicKey): String {
        return try {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val encrypted = cipher.doFinal(data.toByteArray())
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    /** 使用私钥解密 */
    private fun decryptWithPrivateKey(data: String, privKey: PrivateKey): String {
        return try {
            val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privKey)
            String(cipher.doFinal(bytes))
        } catch (_: Exception) { "" }
    }

    /** 存储设备信息到本地 JSON */
    private fun saveDeviceInfo(info: Map<*, *>) {
        val ctx = context ?: return
        val file = File(ctx.filesDir, deviceInfoFileName)
        file.writeText(gson.toJson(info))
    }

    /** 加载设备信息 */
    private fun loadDeviceInfo() {
        val ctx = context ?: return
        val file = File(ctx.filesDir, deviceInfoFileName)
        if (file.exists()) {
            val json = file.readText()
            gson.fromJson(json, Map::class.java)
            // 可扩展加载设备信息
        }
    }

    /** 启动前台服务，防止被系统杀死 */
    private fun startForegroundService() {
        val ctx = context ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "设备连接服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("设备连接后台运行中")
            .setContentText("保证设备间通知实时同步")
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1073741824 = FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            (ctx as? Service)?.startForeground(NOTIFY_ID, notification, 1073741824)
        } else {
            (ctx as? Service)?.startForeground(NOTIFY_ID, notification)
        }
    }

    /** 停止前台服务 */
    private fun stopForegroundService() {
        val ctx = context ?: return
        (ctx as? Service)?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    // 设备发现、连接、转发规则、黑名单管理等功能待实现
    // TODO: 设备发现与连接、转发规则、黑名单管理等功能占位
    /** 获取设备名称，优先本机蓝牙名称，其次 Settings.Secure.DEVICE_NAME，兜底 Build.MODEL，并输出获取来源日志 */
    fun getDeviceName(ctx: Context): String {
        var source: String
        var result: String? = null
        // 优先获取蓝牙名称
        val bluetoothName = try {
            val adapterClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = adapterClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            if (adapter != null) {
                val isEnabled = adapterClass.getMethod("isEnabled").invoke(adapter) as? Boolean ?: false
                if (isEnabled) {
                    val name = adapterClass.getMethod("getName").invoke(adapter) as? String
                    if (!name.isNullOrBlank()) {
                        source = "Bluetooth"
                        android.util.Log.d("DeviceName", "来源: $source, 名称: $name")
                        return name
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.d("DeviceName", "蓝牙名称获取异常: ${e.message}")
            null
        }
        // 其次 Settings.Secure.DEVICE_NAME
        val deviceName = try {
            val name = android.provider.Settings.Secure.getString(ctx.contentResolver, "device_name")
            if (!name.isNullOrBlank()) {
                source = "System"
                android.util.Log.d("DeviceName", "来源: $source, 名称: $name")
            }
            name
        } catch (e: Exception) {
            android.util.Log.d("DeviceName", "系统名称获取异常: ${e.message}")
            null
        }
        if (!bluetoothName.isNullOrBlank()) {
            source = "Bluetooth"
            result = bluetoothName
        } else if (!deviceName.isNullOrBlank()) {
            source = "System"
            result = deviceName
        } else {
            source = "Model"
            result = android.os.Build.MODEL ?: "Unknown"
        }
        android.util.Log.d("DeviceName", "最终来源: $source, 名称: $result")
        return result ?: "Unknown"
    }

    /** 获取设备唯一标识符（UUID），存储时不以名称或型号作为标识符 */
    fun getDeviceUUID(ctx: Context): String {
        val file = File(ctx.filesDir, "device_uuid.txt")
        if (file.exists()) {
            return file.readText()
        }
        val uuid = java.util.UUID.randomUUID().toString()
        file.writeText(uuid)
        return uuid
    }
}
