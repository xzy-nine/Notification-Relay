
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

class DeviceConnectionManager(private val context: android.content.Context) {
    /**
     * 通知数据回调。UI 层可自定义赋值，若为 null 则调用默认实现。
     */
    var onNotificationDataReceived: ((String) -> Unit)? = null
    // 设备列表
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    // 只暴露非本机设备列表
    val devices: StateFlow<List<DeviceInfo>> = object : StateFlow<List<DeviceInfo>> {
        override val value: List<DeviceInfo>
            get() = _devices.value.filter { it.uuid != this@DeviceConnectionManager.uuid }
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<DeviceInfo>>) = _devices.collect {
            collector.emit(it.filter { d -> d.uuid != this@DeviceConnectionManager.uuid })
        }
        override val replayCache: List<List<DeviceInfo>>
            get() = _devices.replayCache.map { list -> list.filter { it.uuid != this@DeviceConnectionManager.uuid } }
    }

    private var jmDNS: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private val serviceType = "_notifyrelay._tcp.local."
    private val serviceName: String = Build.MODEL ?: "NotifyRelayDevice"
    private val uuid: String = UUID.randomUUID().toString()
    private val listenPort: Int = 23333 // 可根据实际情况动态分配
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    // 发现设备（JmDNS注册与监听）
    fun startDiscovery() {
        coroutineScope.launch {
            try {
                if (jmDNS == null) {
                    // 绑定到本机局域网IP，避免 0.0.0.0
                    val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
                    if (ip == 0) {
                        // 未连接 WiFi，无法发现设备
                        Log.d("NotifyRelay", "未连接 WiFi，跳过 JmDNS 初始化")
                        return@launch
                    }
                    val ipBytes = byteArrayOf(
                        (ip and 0xff).toByte(),
                        (ip shr 8 and 0xff).toByte(),
                        (ip shr 16 and 0xff).toByte(),
                        (ip shr 24 and 0xff).toByte()
                    )
                    val hostAddress = InetAddress.getByAddress(ipBytes)
                    Log.d("NotifyRelay", "本机IP: ${hostAddress.hostAddress}")
                    jmDNS = JmDNS.create(hostAddress)
                }
                // 注册本机服务，name 只用设备名，不拼接 uuid，uuid 通过属性传递
                serviceInfo = ServiceInfo.create(
                    serviceType,
                    serviceName,
                    listenPort,
                    "uuid=$uuid,displayName=$serviceName"
                )
                jmDNS?.registerService(serviceInfo)

                // 监听其他设备
                jmDNS?.addServiceListener(serviceType, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmDNS?.requestServiceInfo(event.type, event.name, true)
                    }
                    override fun serviceRemoved(event: ServiceEvent) {
                        val name = event.info.getPropertyString("uuid") ?: event.info.name
                        _devices.value = _devices.value.filterNot { it.uuid == name }
                    }
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val uuid = info.getPropertyString("uuid") ?: return // 没有 uuid 属性直接跳过
                        val displayName = info.getPropertyString("displayName") ?: info.name
                        val ip = info.inetAddresses.firstOrNull()?.hostAddress ?: return
                        val port = info.port
                        if (uuid == this@DeviceConnectionManager.uuid) return // 跳过本机
                        val device = DeviceInfo(uuid, displayName, ip, port)
                        // 先过滤掉所有本机和同 uuid 设备，再追加新设备，保证唯一且无本机
                        _devices.value = (_devices.value
                            .filter { it.uuid != this@DeviceConnectionManager.uuid && it.uuid != uuid }
                            + device)
                    }
                })

                // 启动TCP服务监听
                startServer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 连接设备，首次需确认，后续可自动
    fun connectToDevice(device: DeviceInfo) {
        // 实际弹窗确认逻辑应在UI层实现，这里仅建立连接
        coroutineScope.launch {
            try {
                val socket = Socket(device.ip, device.port)
                // 可在此处保存socket以便后续复用
                // 预留加密扩展点
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 发送通知数据
    fun sendNotificationData(device: DeviceInfo, data: String) {
        coroutineScope.launch {
            try {
                val socket = Socket(device.ip, device.port)
                val writer = OutputStreamWriter(socket.getOutputStream())
                // 预留加密扩展点
                writer.write(data + "\n")
                writer.flush()
                writer.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 接收通知数据
    /**
     * 默认通知数据处理。若未设置回调则调用此方法。
     */
    open fun handleNotificationData(data: String) {
        // 这里可将数据分发到通知历史等模块，预留加密扩展点
        // TODO: 实际业务处理
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
                            val data = reader.readLine()
                            if (data != null) {
                                onNotificationDataReceived?.invoke(data) ?: handleNotificationData(data)
                            }
                            reader.close()
                            client.close()
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