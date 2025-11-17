package com.xzyht.notifyrelay.core.sync

import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与协议相关的底层传输封装：握手 / 心跳 / 发现广播。
 *
 * 这里仅负责文本报文的拼装与发送，不做业务状态变更，
 * 由上层 `DeviceConnectionManager` 根据返回结果更新认证、心跳等状态。
 */

/** 统一握手发送器 */
object HandshakeSender {

    private const val TAG = "HandshakeSender"

    /**
     * 主动向指定设备发起握手请求，并返回服务端的响应首行。
     * 不做认证表更新等副作用，由调用方处理。
     */
    fun sendHandshake(
        manager: DeviceConnectionManager,
        target: DeviceInfo,
        connectTimeoutMs: Int = 3000
    ): String? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(target.ip, target.port), connectTimeoutMs)
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.write("HANDSHAKE:${manager.uuid}:${manager.localPublicKey}\n")
            writer.flush()
            val resp = reader.readLine()
            if (BuildConfig.DEBUG) Log.d(TAG, "handshake resp=$resp, target=${target.uuid}@${target.ip}:${target.port}")
            try { writer.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            resp
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "handshake failed: ${e.message}")
            null
        }
    }
}

/** 统一心跳发送器 */
object HeartbeatSender {

    private const val TAG = "HeartbeatSender"

    fun sendHeartbeat(manager: DeviceConnectionManager, target: DeviceInfo): Boolean {
        return try {
            val socket = Socket(target.ip, target.port)
            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write("HEARTBEAT:${manager.uuid}:${manager.localPublicKey}\n")
            writer.flush()
            try { writer.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "heartbeat failed to ${target.uuid}@${target.ip}:${target.port} - ${e.message}")
            false
        }
    }
}

/** 统一发现广播发送器 */
object DiscoveryBroadcaster {

    private const val TAG = "DiscoveryBroadcaster"

    fun sendBroadcast(manager: DeviceConnectionManager, encodedDisplayName: String, targetIp: String = "255.255.255.255") {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val group = InetAddress.getByName(targetIp)
            val payload = "NOTIFYRELAY_DISCOVER:${manager.uuid}:${encodedDisplayName}:${manager.listenPort}"
            val buf = payload.toByteArray()
            val packet = DatagramPacket(buf, buf.size, group, 23334)
            socket.send(packet)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "broadcast failed to $targetIp: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
