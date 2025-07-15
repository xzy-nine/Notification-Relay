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
    private var pin: String? = null
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
        val pubKey: String,
        val pin: String
    )

    /** 初始化连接管理器，注册本地服务并监听发现 */
    fun init(ctx: Context, onPin: ((String) -> Unit)? = null) {
        context = ctx.applicationContext
        onPinReceived = onPin
        loadDeviceInfo()
        nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager
        registerLocalService()
        discoverServices()
    }
    /** 注册本地 mDNS 服务，广播设备名、公钥、PIN */
    private fun registerLocalService() {
        val ctx = context ?: return
        val keyPair = generateKeyPair()
        this.keyPair = keyPair
        val pubKeyStr = android.util.Base64.encodeToString(keyPair.public.encoded, android.util.Base64.NO_WRAP)
        val pinCode = (100000..999999).random().toString()
        pin = pinCode
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = getDeviceName(ctx)
            serviceType = SERVICE_TYPE
            port = 8080 // 占位，实际应为 WebSocket 服务端口
            setAttribute("pubKey", pubKeyStr)
            setAttribute("pin", pinCode)
        }
        nsdManager?.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                registeredServiceInfo = serviceInfo
            }
            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
        })
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
                        // Android 官方 API 没有 getAttribute/txtRecords，API 33+ 可用 getAttributes
                        val (pubKey, pinCode) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            val attr = resolved.attributes
                            val pk = attr["pubKey"]?.toString() ?: ""
                            val pin = attr["pin"]?.toString() ?: ""
                            pk to pin
                        } else {
                            "" to ""
                        }
                        // 过滤自身
                        if (name != getDeviceName(context!!)) {
                            discoveredDevices.add(DiscoveredDevice(name, host, port, pubKey, pinCode))
                            // 被发现方弹窗显示 PIN
                            onPinReceived?.invoke(pinCode)
                        }
                    }
                    override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                })
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    /** 启动 WebSocket 长连接服务（前台），连接已发现设备 */
    fun startConnectionService(pinInput: String, device: DiscoveredDevice? = null) {
        pin = pinInput
        if (context == null) return
        // 连接指定设备
        if (device != null) {
            wsUrl = "ws://${device.host}:${device.port}/notifyrelay"
            remotePublicKey = decodePublicKey(device.pubKey)
        }
        startForegroundService()
        connectWebSocket()
    }
    /** 获取已发现设备列表 */
    fun getDiscoveredDevices(): List<DiscoveredDevice> = discoveredDevices.toList()

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
                isConnected = true
                lastHeartbeat = System.currentTimeMillis()
                // 交换密钥对
                keyPair = generateKeyPair()
                val pubKeyStr = keyPair?.public?.encoded?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                val handshake = mapOf("type" to "handshake", "pin" to pin.orEmpty(), "pubKey" to pubKeyStr.orEmpty())
                webSocket.send(gson.toJson(handshake))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
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

    /** 处理收到的消息 */
    private fun handleMessage(text: String) {
        val ctx = context ?: return
        val msg = gson.fromJson(text, Map::class.java)
        when (msg["type"]) {
            "handshake" -> {
                // 对方发来公钥，完成密钥交换
                val remotePubKeyStr = msg["pubKey"] as? String
                if (remotePubKeyStr != null) {
                    remotePublicKey = decodePublicKey(remotePubKeyStr)
                    saveDeviceInfo(msg)
                }
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
