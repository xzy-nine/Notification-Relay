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
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
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
    private var connectionsClient: ConnectionsClient? = null
    private var advertising = false
    private var discovering = false
    private var connectedEndpointId: String? = null
    private var connectionLifecycleCallback: ConnectionLifecycleCallback? = null
    private var payloadCallback: PayloadCallback? = null
    private var context: Context? = null
    private var keyPair: KeyPair? = null
    private var remotePublicKey: PublicKey? = null
    private var pin: String? = null
    private var isConnected = false
    private var lastHeartbeat = 0L
    private val HEARTBEAT_INTERVAL = 10_000L // 10秒
    private val CHANNEL_ID = "device_connect_foreground"
    private val NOTIFY_ID = 2001

    /** 初始化连接管理器 */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        connectionsClient = Nearby.getConnectionsClient(ctx)
        loadDeviceInfo()
    }

    /** 启动设备发现与连接服务（前台） */
    fun startConnectionService(pinInput: String) {
        pin = pinInput
        if (context == null) return
        startForegroundService()
        startAdvertising()
        startDiscovery()
    }

    /** 关闭连接 */
    fun stopConnection() {
        connectionsClient?.stopAdvertising()
        connectionsClient?.stopDiscovery()
        connectedEndpointId?.let { connectionsClient?.disconnectFromEndpoint(it) }
        advertising = false
        discovering = false
        isConnected = false
        stopForegroundService()
    }

    /** Nearby 广播自身，允许其他设备发现 */
    private fun startAdvertising() {
        if (advertising) return
        val ctx = context ?: return
        val deviceName = getDeviceName(ctx)
        connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                // 自动接受连接
                connectionsClient?.acceptConnection(endpointId, getPayloadCallback())
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    isConnected = true
                    connectedEndpointId = endpointId
                    lastHeartbeat = System.currentTimeMillis()
                    keyPair = generateKeyPair()
                    val pubKeyStr = keyPair?.public?.encoded?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    val handshake = mapOf("type" to "handshake", "pin" to pin, "pubKey" to pubKeyStr)
                    sendPayload(endpointId, gson.toJson(handshake))
                }
            }
            override fun onDisconnected(endpointId: String) {
                isConnected = false
                connectedEndpointId = null
            }
        }
        connectionsClient?.startAdvertising(
            deviceName,
            "com.xzyht.notifyrelay",
            connectionLifecycleCallback!!,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )?.addOnSuccessListener { advertising = true }
         ?.addOnFailureListener { advertising = false }
    }

    /** Nearby 发现其他设备 */
    private fun startDiscovery() {
        if (discovering) return
        val ctx = context ?: return
        connectionsClient?.startDiscovery(
            "com.xzyht.notifyrelay",
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    // 自动请求连接
                    connectionsClient?.requestConnection(getDeviceName(ctx), endpointId, getConnectionLifecycleCallback())
                }
                override fun onEndpointLost(endpointId: String) {}
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )?.addOnSuccessListener { discovering = true }
         ?.addOnFailureListener { discovering = false }
    }

    private fun getConnectionLifecycleCallback(): ConnectionLifecycleCallback {
        return connectionLifecycleCallback ?: object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                connectionsClient?.acceptConnection(endpointId, getPayloadCallback())
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    isConnected = true
                    connectedEndpointId = endpointId
                }
            }
            override fun onDisconnected(endpointId: String) {
                isConnected = false
                connectedEndpointId = null
            }
        }
    }

    private fun getPayloadCallback(): PayloadCallback {
        return payloadCallback ?: object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) {
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    handleMessage(text)
                }
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }
    }

    /** 发送数据到远程设备 */
    private fun sendPayload(endpointId: String, text: String) {
        connectionsClient?.sendPayload(endpointId, Payload.fromBytes(text.toByteArray()))
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
        if (!isConnected || remotePublicKey == null || connectedEndpointId == null) return
        val json = gson.toJson(record)
        val encrypted = encryptWithPublicKey(json, remotePublicKey!!)
        val msg = mapOf("type" to "notification", "data" to encrypted)
        sendPayload(connectedEndpointId!!, gson.toJson(msg))
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
            val info = gson.fromJson(json, Map::class.java)
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
        var source = "Unknown"
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
                        result = name
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
                result = name
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
