package com.xzyht.notifyrelay.feature.device.service.callback

import com.sun.jna.Pointer
import com.xzyht.notifyrelay.nativecore.NativeCore

/**
 * Rust/JNA 回调装配器。
 *
 * 把配对、数据、设备事件、状态查询四组回调处理器统一注册到 core 上下文，
 * 并**持有全部回调对象强引用**，防止被 GC 回收导致 native 侧悬空指针。
 *
 * 注意：注册顺序与 core 无关，但日志回调必须在最后接入，
 * 以便前面注册过程中若发生 native 日志也可被捕获。
 */
object RustCallbackInstaller {
    /** 保持 JNA 回调对象强引用，防止被 GC */
    private val callbackRefs = mutableListOf<Any>()

    /**
     * 注册全部回调。
     *
     * @param ctx core 上下文
     * @param host 回调宿主（提供状态与操作能力）
     */
    fun install(
        ctx: Pointer,
        host: DeviceCallbackHost,
    ) {
        val lib = NativeCore.lib

        val pairingCb = PairingCallbackHandler(host).build()
        lib.nrc_set_on_pairing_cb(ctx, pairingCb)
        callbackRefs.add(pairingCb)

        val dataCb = DataCallbackHandler(host).build()
        lib.nrc_set_on_data_cb(ctx, dataCb)
        callbackRefs.add(dataCb)

        val events = DeviceEventCallbackHandler(host)

        val discoveredCb = events.buildDiscovered()
        lib.nrc_set_on_device_discovered_cb(ctx, discoveredCb)
        callbackRefs.add(discoveredCb)

        val timeoutCb = events.buildTimeout()
        lib.nrc_set_on_device_timeout_cb(ctx, timeoutCb)
        callbackRefs.add(timeoutCb)

        val connectedCb = events.buildConnected()
        lib.nrc_set_on_device_connected_cb(ctx, connectedCb)
        callbackRefs.add(connectedCb)

        val disconnectedCb = events.buildDisconnected()
        lib.nrc_set_on_device_disconnected_cb(ctx, disconnectedCb)
        callbackRefs.add(disconnectedCb)

        val stateQueryCb = events.buildStateQuery()
        lib.nrc_set_on_state_query_cb(ctx, stateQueryCb)
        callbackRefs.add(stateQueryCb)

        // ---- 日志回调（接入 Logger.currentLevel 等级控制） ----
        NativeCore.setLogCallback(ctx)
    }
}
