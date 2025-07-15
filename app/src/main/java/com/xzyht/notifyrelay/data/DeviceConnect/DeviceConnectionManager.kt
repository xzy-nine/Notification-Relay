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
    private val wsUrl = "ws://192.168.1.100:8080/notifyrelay" // 占位，实际应为发现的设备地址
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

    /** 初始化连接管理器 */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        loadDeviceInfo()
    }

    /** 启动 WebSocket 长连接服务（前台） */
    fun startConnectionService(pinInput: String) {
        pin = pinInput
        if (context == null) return
        startForegroundService()
        connectWebSocket()
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
        val ctx = context ?: return
        val client = OkHttpClient.Builder()
            .pingInterval(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                lastHeartbeat = System.currentTimeMillis()
                // 交换密钥对
                keyPair = generateKeyPair()
                val pubKeyStr = keyPair?.public?.encoded?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                val handshake = mapOf("type" to "handshake", "pin" to pin, "pubKey" to pubKeyStr)
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
                val record = gson.fromJson(recordJson, NotificationRecord::class.java)
                NotificationRepository.notifications.removeAll { it.key == record.key }
                NotificationRepository.notifications.add(0, record.copy(device = "远程"))
            NotificationRepository.syncToCache(ctx) // Ensure this method is public
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
    /** 获取设备名称，优先 Settings.Secure.DEVICE_NAME，兜底 Build.MODEL */
    fun getDeviceName(ctx: Context): String {
        val name = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "device_name")
        } catch (_: Exception) { null }
        return name ?: android.os.Build.MODEL ?: "Unknown"
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
