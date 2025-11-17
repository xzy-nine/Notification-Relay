package com.xzyht.notifyrelay.core.sync

import android.content.Context
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.EncryptionManager
import com.xzyht.notifyrelay.feature.device.service.AuthInfo
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerUtil
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * TCP 首行路由器：根据首行内容分发到不同协议处理逻辑。
 *
 * 设计目标：
 * - 让 `DeviceConnectionManager.startServer()` 只负责「接受连接 + 读首行」，
 *   把「这行是 HANDSHAKE / HEARTBEAT / DATA / 其它」的判断与处理挪到这里，
 *   便于后续维护协议演进而不污染连接管理类。
 * - 不直接持有任何全局状态，只通过 `DeviceConnectionManager` 暴露的 internal 访问器
 *   读写设备缓存、认证表、心跳状态等。
 *
 * 注意：
 * - 本类只处理「首行」协议，DATA* 报文的解密与路由仍交给 `ProtocolRouter`，
 *   加密发送仍由 `ProtocolSender` / `HandshakeSender` / `HeartbeatSender` 负责。
 */
object ServerLineRouter {

    private const val TAG = "ServerLineRouter"

    fun handleClientLine(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager,
        context: Context
    ) {
        // 按首行前缀分发：握手 / 心跳 / DATA 通道 / 其它（目前用于手动发现）
        when {
            line.startsWith("HANDSHAKE:") -> handleHandshake(line, client, reader, deviceManager)
            line.startsWith("HEARTBEAT:") -> handleHeartbeat(line, client, reader, deviceManager)
            line.startsWith("DATA") -> handleData(line, client, reader, deviceManager, context)
            else -> handleOther(line, client, reader, deviceManager)
        }
    }

    /**
     * 处理握手请求：
     * - 更新/填充远端设备的 IP 信息到 `deviceInfoCache`
     * - 若已认证则直接回复 ACCEPT
     * - 否则触发 `onHandshakeRequest` 回调给 UI，由用户选择接受/拒绝
     * - 根据结果更新 `authenticatedDevices` / `rejectedDevices`
     */
    private fun handleHandshake(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            val parts = line.split(":")
            if (parts.size >= 3) {
                val remoteUuid = parts[1]
                val remotePubKey = parts[2]
                val ip: String = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }

                // 1. 同步更新设备 IP 缓存，端口保持原有或默认
                synchronized(deviceManager.deviceInfoCacheInternal) {
                    val old = deviceManager.deviceInfoCacheInternal[remoteUuid]
                    val displayName = old?.displayName ?: "未知设备"
                    deviceManager.deviceInfoCacheInternal[remoteUuid] = DeviceInfo(
                        remoteUuid,
                        displayName,
                        ip,
                        old?.port ?: 23333
                    )
                }

                // 2. 若认证表中已有该设备，只更新 lastIp（不改端口）
                synchronized(deviceManager.authenticatedDevices) {
                    val auth = deviceManager.authenticatedDevices[remoteUuid]
                    if (auth != null) {
                        deviceManager.authenticatedDevices[remoteUuid] = auth.copy(lastIp = ip)
                        deviceManager.saveAuthedDevicesInternal()
                    }
                }

                // 3. 基于缓存构造远端设备信息，用于 UI 显示
                val remoteDevice = deviceManager.deviceInfoCacheInternal[remoteUuid]!!
                val alreadyAuthed = synchronized(deviceManager.authenticatedDevices) {
                    deviceManager.authenticatedDevices[remoteUuid]?.isAccepted == true
                }

                // 4. 已认证设备：自动 ACCEPT（静默建立信任链）
                if (alreadyAuthed) {
                    val writer = OutputStreamWriter(client.getOutputStream())
                    writer.write("ACCEPT:${deviceManager.uuid}:${deviceManager.localPublicKey}\n")
                    writer.flush()
                    writer.close()
                    reader.close()
                    client.close()
                } else if (deviceManager.onHandshakeRequest != null) {
                    // 5. 未认证但有回调：交由 UI 确认是否接受连接
                    deviceManager.onHandshakeRequest!!.invoke(remoteDevice, remotePubKey) { accepted ->
                        if (accepted) {
                            // 用户点击“接受”：生成共享密钥并写入认证表
                            val sharedSecret = EncryptionManager.generateSharedSecret(deviceManager.localPublicKey, remotePubKey)
                            synchronized(deviceManager.authenticatedDevices) {
                                deviceManager.authenticatedDevices.remove(remoteUuid)
                                deviceManager.authenticatedDevices[remoteUuid] = AuthInfo(remotePubKey, sharedSecret, true, remoteDevice.displayName)
                                deviceManager.saveAuthedDevicesInternal()
                            }
                            try { deviceManager.launchUpdateDeviceList() } catch (_: Exception) {}
                        } else {
                            // 用户拒绝：记录到本地拒绝名单，避免反复打扰
                            synchronized(deviceManager.rejectedDevicesInternal) {
                                deviceManager.rejectedDevicesInternal.add(remoteUuid)
                            }
                        }
                        // 6. 通过 TCP 回写 ACCEPT/REJECT 结果给对端
                        deviceManager.coroutineScopeInternal.launch {
                            val writer = OutputStreamWriter(client.getOutputStream())
                            if (accepted) {
                                writer.write("ACCEPT:${deviceManager.uuid}:${deviceManager.localPublicKey}\n")
                            } else {
                                writer.write("REJECT:${deviceManager.uuid}\n")
                            }
                            writer.flush()
                            writer.close()
                            reader.close()
                            client.close()
                        }
                    }
                } else {
                    // 无 UI 回调时，保守起见一律 REJECT
                    val writer = OutputStreamWriter(client.getOutputStream())
                    writer.write("REJECT:${deviceManager.uuid}\n")
                    writer.flush()
                    writer.close()
                    reader.close()
                    client.close()
                }
            } else {
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("REJECT:${deviceManager.uuid}\n")
                writer.flush()
                writer.close()
                reader.close()
                client.close()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "handleHandshake error: ${e.message}")
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleHeartbeat(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            val parts = line.split(":")
            if (parts.size >= 3) {
                val remoteUuid = parts[1]
                // 仅已在认证表中的设备才接受心跳
                val isAuthed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.containsKey(remoteUuid) }
                if (BuildConfig.DEBUG) Log.d(TAG, "收到HEARTBEAT: remoteUuid=$remoteUuid, isAuthed=$isAuthed, authedKeys=${deviceManager.authenticatedDevices.keys}")
                if (isAuthed) {
                    val ip: String = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                    // 1. 用最新 IP 更新设备缓存（端口只在其它渠道更新）
                    synchronized(deviceManager.deviceInfoCacheInternal) {
                        val old = deviceManager.deviceInfoCacheInternal[remoteUuid]
                        val displayName = old?.displayName ?: "未知设备"
                        deviceManager.deviceInfoCacheInternal[remoteUuid] = DeviceInfo(
                            remoteUuid,
                            displayName,
                            ip,
                            old?.port ?: 23333
                        )
                    }
                    // 2. 更新认证信息中的 lastIp，便于后续重连
                    synchronized(deviceManager.authenticatedDevices) {
                        val auth = deviceManager.authenticatedDevices[remoteUuid]
                        if (auth != null) {
                            deviceManager.authenticatedDevices[remoteUuid] = auth.copy(lastIp = ip)
                            deviceManager.saveAuthedDevicesInternal()
                        }
                    }
                    // 3. 用心跳驱动在线状态：刷新 lastSeen + 标记已建立心跳
                    deviceManager.deviceLastSeenInternal[remoteUuid] = System.currentTimeMillis()
                    deviceManager.heartbeatedDevicesInternal.add(remoteUuid)
                    deviceManager.coroutineScopeInternal.launch { deviceManager.updateDeviceListInternal() }

                    // 4. 若本端尚未给对方发心跳，则自动反向 connectToDevice，确保双向链路
                    if (remoteUuid != deviceManager.uuid && !deviceManager.heartbeatJobsInternal.containsKey(remoteUuid)) {
                        val info = deviceManager.getDeviceInfoInternal(remoteUuid)
                        if (info != null && info.ip.isNotEmpty() && info.ip != "0.0.0.0") {
                            if (BuildConfig.DEBUG) Log.d(TAG, "收到HEARTBEAT后自动反向connectToDevice: $info")
                            deviceManager.connectToDevice(info)
                        }
                    }
                } else {
                    // 未认证设备发来的心跳仅记录日志，不做任何状态变更
                    if (BuildConfig.DEBUG) Log.w(TAG, "收到HEARTBEAT但未认证: remoteUuid=$remoteUuid, authedKeys=${deviceManager.authenticatedDevices.keys}")
                }
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "收到HEARTBEAT格式异常: $line")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "handleHeartbeat error: ${e.message}")
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleData(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager,
        context: Context
    ) {
        try {
            val clientIp = client.inetAddress?.hostAddress ?: "0.0.0.0"
            // DATA* 加密通道统一交给 ProtocolRouter 处理（解密+业务路由）
            ProtocolRouter.handleEncryptedDataLine(line, clientIp, deviceManager, context)
        } catch (_: Exception) {
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleOther(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            // 当前 only 用于处理手动发现 NOTIFYRELAY_DISCOVER_MANUAL（加密文本）
            try {
                val authed = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices.toMap() }
                for ((uuid, auth) in authed) {
                    if (uuid == deviceManager.uuid) continue
                    // 尝试用每个已认证设备的 sharedSecret 解密首行
                    val decrypted = try { deviceManager.decryptDataInternal(line, auth.sharedSecret) } catch (_: Exception) { null }
                    if (decrypted != null && decrypted.startsWith("NOTIFYRELAY_DISCOVER_MANUAL:")) {
                        val parts = decrypted.split(":")
                        if (parts.size >= 5) {
                            val remoteUuid = parts[1]
                            val displayName = parts[2]
                            val port = parts[3].toIntOrNull() ?: 23333
                            val sharedSecret = parts[4]
                            // 只有 sharedSecret 完全匹配时才认为是该设备的手动发现包
                            if (auth.sharedSecret == sharedSecret) {
                                val ip = client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                                val device = DeviceInfo(remoteUuid, displayName, ip, port)
                                deviceManager.deviceLastSeenInternal[remoteUuid] = System.currentTimeMillis()
                                synchronized(deviceManager.deviceInfoCacheInternal) { deviceManager.deviceInfoCacheInternal[remoteUuid] = device }
                                synchronized(deviceManager.authenticatedDevices) {
                                    val a = deviceManager.authenticatedDevices[remoteUuid]
                                    if (a != null) {
                                        deviceManager.authenticatedDevices[remoteUuid] = a.copy(lastIp = ip, lastPort = port)
                                        deviceManager.saveAuthedDevicesInternal()
                                    }
                                }
                                // 同步更新全局设备名缓存，以便 UI 显示
                                DeviceConnectionManagerUtil.updateGlobalDeviceName(remoteUuid, displayName)
                                deviceManager.coroutineScopeInternal.launch { deviceManager.updateDeviceListInternal() }
                                if (BuildConfig.DEBUG) Log.d(TAG, "收到手动发现UDP: $decrypted, ip=$ip, uuid=$remoteUuid")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "未知请求: $line")
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }
}
