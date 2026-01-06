package com.xzyht.notifyrelay.common.core.sync

import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.common.core.util.BatteryUtils
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
            //Logger.d(TAG, "handshake resp=$resp, target=${target.uuid}@${target.ip}:${target.port}")
            try { writer.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            resp
        } catch (e: Exception) {
            //Logger.d(TAG, "handshake failed: ${e.message}")
            null
        }
    }
}

/** 统一心跳发送器 */
object HeartbeatSender {

    private const val TAG = "HeartbeatSender"
    private const val HEARTBEAT_PORT = 23334 // 与发现端口一致
    
    fun sendHeartbeat(manager: DeviceConnectionManager, target: DeviceInfo): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            // 心跳格式：HEARTBEAT:<deviceUuid><设备电量%>
            val batteryLevel = BatteryUtils.getBatteryLevel(manager.contextInternal)
            val payload = "HEARTBEAT:${manager.uuid}$batteryLevel"
            val buf = payload.toByteArray()
            val address = InetAddress.getByName(target.ip)
            val packet = DatagramPacket(buf, buf.size, address, HEARTBEAT_PORT)
            socket.send(packet)
            true
        } catch (e: Exception) {
            //Logger.d(TAG, "heartbeat failed to ${target.uuid}@${target.ip}:${target.port} - ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
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
            //Logger.d(TAG, "broadcast failed to $targetIp: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
