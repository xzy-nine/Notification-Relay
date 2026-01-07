package com.xzyht.notifyrelay.common.core.sync

import android.content.Context
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * 服务端首行协议路由器
 *
 * 设计目标：
 * - 让 `DeviceConnectionManager.startServer()` 只负责「接受连接 + 读首行」，
 *   把「这行是 HANDSHAKE / DATA / 其它」的判断与处理挪到这里，
 *   便于后续维护协议演进而不污染连接管理类。
 * - 不直接持有任何全局状态，只通过 `DeviceConnectionManager` 暴露的 internal 访问器
 *   读写设备缓存、认证表、心跳状态等。
 */
object ServerLineRouter {

    private const val TAG = "ServerLineRouter"

    /**
     * 分发首行协议到对应处理器
     *
     * @param line 首行协议文本
     * @param client 客户端 Socket
     * @param reader 读取流（用于后续数据读取，如需要）
     * @param deviceManager 设备管理器实例
     * @param context 上下文（用于获取系统服务等）
     */
    fun routeLine(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager,
        context: Context
    ) {
        // 按首行前缀分发：握手 / DATA 加密通道 / 其它（目前主要用于手动发现）
        when {
            // HANDSHAKE：连接建立时的认证握手，决定是否建立「受信任设备」关系
            line.startsWith("HANDSHAKE:") -> handleHandshake(line, client, reader, deviceManager)
            // DATA*：加密业务通道（通知 / 图标 / 应用列表等），解密与路由交给 ProtocolRouter
            line.startsWith("DATA") -> handleData(line, client, reader, deviceManager, context)
            // 其他：当前仅用于 NOTIFYRELAY_DISCOVER_MANUAL 等辅助协议（如手动发现）
            else -> handleOther(line, client, reader, deviceManager)
        }
    }

    /**
     * 处理握手请求：
     * - 更新/填充远端设备的 IP 信息到 `deviceInfoCache`
     * - 若已认证则直接回复 ACCEPT
     * - 否则触发 `onHandshakeRequest` 回调给 UI，由用户选择接受/拒绝
     * - 根据结果更新 `authenticatedDevices` / `rejectedDevices`
     * 握手格式：HANDSHAKE:<uuid>:<publicKey>:<ipAddress>:<batteryLevel>:<deviceType>
     */
    private fun handleHandshake(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            val parts = line.split(":")
            if (parts.size >= 6) {
                val remoteUuid = parts[1]
                val remotePubKey = parts[2]
                val remoteIp: String = parts.getOrNull(3) ?: client.inetAddress.hostAddress.orEmpty().ifEmpty { "0.0.0.0" }
                val remoteBattery: String = parts.getOrNull(4) ?: ""
                val remoteDeviceType: String = parts.getOrNull(5) ?: "unknown"

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
                    val localBattery = getLocalBatteryInfo(deviceManager)
                    val localDeviceType = "android"
                    val localIp = getLocalIpAddress(deviceManager)
                    writer.write("ACCEPT:${deviceManager.uuid}:${deviceManager.localPublicKey}:$localIp:$localBattery:$localDeviceType\n")
                    writer.flush()
                    writer.close()
                    reader.close()
                    client.close()
                    
                    // 更新已认证设备的 deviceType 和 lastIp
                    synchronized(deviceManager.authenticatedDevices) {
                        val auth = deviceManager.authenticatedDevices[remoteUuid]
                        if (auth != null) {
                            deviceManager.authenticatedDevices[remoteUuid] = auth.copy(
                                deviceType = remoteDeviceType,
                                lastIp = ip
                            )
                            deviceManager.saveAuthedDevicesInternal()
                            Logger.d(TAG, "已更新已认证设备的 deviceType: $remoteDeviceType, lastIp: $ip")
                        }
                    }
                } else if (deviceManager.handshakeRequestHandler != null) {
                    // 5. 未认证但有回调：交由 UI 确认是否接受连接
                    deviceManager.handshakeRequestHandler!!.onHandshakeRequest(remoteDevice, remotePubKey) { accepted ->
                        if (accepted) {
                            // 用户点击“接受”：生成共享密钥并写入认证表
                            val sharedSecret = com.xzyht.notifyrelay.common.core.util.EncryptionManager.generateSharedSecret(deviceManager.localPublicKey, remotePubKey)
                            synchronized(deviceManager.authenticatedDevices) {
                                deviceManager.authenticatedDevices.remove(remoteUuid)
                                deviceManager.authenticatedDevices[remoteUuid] = com.xzyht.notifyrelay.feature.device.service.AuthInfo(
                                remotePubKey, sharedSecret, true, remoteDevice.displayName,
                                deviceType = remoteDeviceType, battery = remoteBattery
                            )
                                deviceManager.saveAuthedDevicesInternal()
                            }
                            try { deviceManager.updateDeviceListInternal() } catch (_: Exception) {}
                        } else {
                            // 用户拒绝：记录到本地拒绝名单，避免反复打扰
                            synchronized(deviceManager.rejectedDevicesInternal) {
                                deviceManager.rejectedDevicesInternal.add(remoteUuid)
                            }
                        }
                        // 6. 通过 TCP 回写 ACCEPT/REJECT 结果给对端
                        deviceManager.coroutineScopeInternal.launch {
                            val writer = OutputStreamWriter(client.getOutputStream())
                            val localBattery = getLocalBatteryInfo(deviceManager)
                            val localDeviceType = "android"
                            val localIp = getLocalIpAddress(deviceManager)
                            if (accepted) {
                                writer.write("ACCEPT:${deviceManager.uuid}:${deviceManager.localPublicKey}:$localIp:$localBattery:$localDeviceType\n")
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
            Logger.e(TAG, "handleHandshake error: ${e.message}")
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 处理 DATA* 加密通道：
     * - 这里不做具体业务解析，只负责把首行和 client IP 交给 ProtocolRouter
     * - ProtocolRouter 再统一完成解密和按 DATA_* 头进行的业务路由
     */
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

    /**
     * 处理其它首行协议：
     * - 当前 only 用于处理手动发现 NOTIFYRELAY_DISCOVER_MANUAL（加密文本）
     * - 尝试用每个已认证设备的 sharedSecret 解密首行
     * - 匹配成功后更新 deviceInfoCache / authenticatedDevices 的 IP / 端口等信息
     */
    private fun handleOther(
        line: String,
        client: Socket,
        reader: BufferedReader,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            if (line.startsWith("NOTIFYRELAY_DISCOVER_MANUAL:")) {
                val encryptedPart = line.substringAfter("NOTIFYRELAY_DISCOVER_MANUAL:")
                val clientIp = client.inetAddress.hostAddress.orEmpty()

                // 尝试用每个已认证设备的 sharedSecret 解密
                synchronized(deviceManager.authenticatedDevices) {
                    for ((uuid, auth) in deviceManager.authenticatedDevices) {
                        try {
                            val decrypted = deviceManager.decryptDataInternal(encryptedPart, auth.sharedSecret)
                            if (decrypted.startsWith("NOTIFYRELAY_DISCOVER:")) {
                                // 解密成功：更新设备缓存
                                val parts = decrypted.split(":")
                                if (parts.size >= 4) {
                                    val remoteUuid = parts[1]
                                    val rawDisplay = parts[2]
                                    val displayName = try {
                                        deviceManager.decodeDisplayNameFromTransportInternal(rawDisplay)
                                    } catch (_: Exception) {
                                        rawDisplay
                                    }
                                    val port = parts[3].toIntOrNull() ?: deviceManager.listenPort
                                    if (remoteUuid == uuid && !clientIp.isNullOrEmpty() && uuid != deviceManager.uuid) {
                                        val device = DeviceInfo(uuid, displayName, clientIp, port)
                                        synchronized(deviceManager.deviceInfoCacheInternal) {
                                            deviceManager.deviceInfoCacheInternal[uuid] = device
                                        }
                                        //Logger.d(TAG, "收到手动发现UDP: $decrypted, ip=$ip, uuid=$remoteUuid")
                                    }
                                }
                                break
                            }
                        } catch (_: Exception) {
                            // 解密失败，继续尝试下一个密钥
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun getLocalBatteryInfo(deviceManager: DeviceConnectionManager): String {
        return try {
            val batteryLevel = com.xzyht.notifyrelay.common.core.util.BatteryUtils.getBatteryLevel(deviceManager.contextInternal)
            val isCharging = com.xzyht.notifyrelay.common.core.util.BatteryUtils.isCharging(deviceManager.contextInternal)
            if (isCharging) "$batteryLevel+" else "$batteryLevel"
        } catch (_: Exception) {
            ""
        }
    }

    private fun getLocalIpAddress(deviceManager: DeviceConnectionManager): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }
}