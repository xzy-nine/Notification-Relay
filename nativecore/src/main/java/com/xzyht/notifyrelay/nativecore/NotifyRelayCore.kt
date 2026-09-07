package com.xzyht.notifyrelay.nativecore

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface NotifyRelayCore : Library {
    // ======== Lifecycle ========
    fun nrc_init(): Pointer

    fun nrc_get_git_hash(): Pointer

    // ======== ECDH key management ========
    fun nrc_ecdh_generate_keypair(ctx: Pointer): Int

    fun nrc_ecdh_get_public_key(ctx: Pointer): Pointer

    fun nrc_ecdh_has_keypair(ctx: Pointer): Int

    fun nrc_ecdh_derive_shared_secret(
        ctx: Pointer,
        peerUuid: String,
        peerPubKeyB64: String,
    ): Int

    fun nrc_migrate_shared_secret(
        ctx: Pointer,
        deviceUuid: String,
        secret: ByteArray,
        len: Int,
    ): Int

    fun nrc_remove_device(
        ctx: Pointer,
        deviceUuid: String,
    ): Int

    // ======== Callback interfaces ========
    interface OnPairingCb : Callback {
        fun invoke(
            uuid: Pointer?,
            messageType: Pointer?,
            data: Pointer?,
            intValue: Int,
            extra: Pointer?,
            userData: Pointer?,
        )
    }

    interface OnDataCb : Callback {
        fun invoke(
            uuid: Pointer?,
            messageType: Pointer?,
            plaintext: Pointer?,
            userData: Pointer?,
        )
    }

    // 状态查询回调（Rust 心跳线程锁外调用）：
    // 参数 (uuid, featureId, isMedia, userData)，返回 0=不存在 / 1=存在无变更 / 2=存在有变更
    interface OnStateQueryCb : Callback {
        fun invoke(
            uuid: Pointer?,
            featureId: Pointer?,
            isMedia: Int,
            userData: Pointer?,
        ): Int
    }

    interface OnLogCb : Callback {
        fun invoke(
            level: Int,
            message: Pointer?,
        )
    }

    interface OnHeartbeatUdpCb : Callback {
        fun invoke(
            uuid: Pointer?,
            name: Pointer?,
            port: Short,
            battery: Int,
            deviceType: Pointer?,
            ip: Pointer?,
            userData: Pointer?,
        )
    }

    interface OnMdnsDiscoveredCb : Callback {
        fun invoke(
            uuid: Pointer?,
            name: Pointer?,
            ip: Pointer?,
            port: Short,
            battery: Int,
            deviceType: Pointer?,
            userData: Pointer?,
        )
    }

    interface OnDeviceTimeoutCb : Callback {
        fun invoke(
            uuid: Pointer?,
            userData: Pointer?,
        )
    }

    // ======== Audio stream ========
    interface OnAudioDataCb : Callback {
        fun invoke(
            deviceUuid: Pointer?,
            pcmData: Pointer?,
            pcmLen: Int,
            sampleRate: Int,
            channels: Int,
            userData: Pointer?,
        )
    }

    interface OnAudioEventCb : Callback {
        fun invoke(
            deviceUuid: Pointer?,
            event: Pointer?,
            errorMsg: Pointer?,
            userData: Pointer?,
        )
    }

    fun nrc_audio_start(
        ctx: Pointer,
        direction: String,
        port: Int,
        sampleRate: Int,
        channels: Int,
        remoteUuid: String,
    ): Int

    fun nrc_audio_write_frame(
        ctx: Pointer,
        pcmData: ByteArray,
        pcmLen: Int,
    ): Int

    fun nrc_audio_stop(ctx: Pointer): Int

    fun nrc_register_audio_data_cb(
        ctx: Pointer,
        cb: OnAudioDataCb?,
    )

    fun nrc_register_audio_event_cb(
        ctx: Pointer,
        cb: OnAudioEventCb?,
    )

    fun nrc_audio_is_active(ctx: Pointer): Int

    // ======== Device timeout ========
    fun nrc_set_on_device_timeout_cb(
        ctx: Pointer,
        cb: OnDeviceTimeoutCb?,
    )

    // ======== Dedup engine ========
    fun nrc_dedup(
        ctx: Pointer,
        action: Int,
        dedupKey: String,
        arg1Ms: Long,
        arg2Ms: Long,
    ): Int

    // ======== Utility functions ========
    fun nrc_compute_dedup_key(
        deviceUuid: String,
        data: String,
    ): Pointer

    fun nrc_compute_feature_id(
        superPkg: String,
        paramV2Raw: String,
        title: String,
        text: String,
        instanceId: String,
    ): Pointer

    // ======== Text similarity & dedup ========
    fun nrc_should_deduplicate(
        newTitle: String,
        newText: String,
        oldTitle: String,
        oldText: String,
    ): Int

    // ======== Filter ========
    fun nrc_set_filter_config(
        ctx: Pointer,
        configJson: String,
    ): Int

    fun nrc_map_local_package(
        ctx: Pointer,
        pkg: String,
    ): Pointer

    fun nrc_check_filter_mode(
        ctx: Pointer,
        mappedPkg: String,
        originalPkg: String,
        title: String,
        text: String,
    ): Int

    // ======== FTP credential derivation ========
    fun nrc_derive_ftp_credentials(sharedSecretB64: String): Pointer

    fun nrc_derive_password_hash(password: String): Pointer

    fun nrc_generate_random_password(): Pointer

    // ======== Callback setters ========
    fun nrc_set_log_callback(cb: OnLogCb?)

    fun nrc_set_on_pairing_cb(
        ctx: Pointer,
        cb: OnPairingCb?,
    )

    fun nrc_set_on_data_cb(
        ctx: Pointer,
        cb: OnDataCb?,
    )

    fun nrc_set_on_state_query_cb(
        ctx: Pointer,
        cb: OnStateQueryCb?,
    )

    fun nrc_set_on_heartbeat_udp_cb(
        ctx: Pointer,
        cb: OnHeartbeatUdpCb?,
    )

    // ======== Send functions ========
    fun nrc_send_handshake(
        ctx: Pointer,
        uuid: String,
        pubKey: String,
        localIp: String,
        targetIp: String,
        battery: Int,
        deviceType: String,
    ): Int

    fun nrc_connect_device(
        ctx: Pointer,
        uuid: String,
        targetIp: String,
        battery: Int,
        deviceType: String,
    ): Int

    fun nrc_send_pairing_init(
        ctx: Pointer,
        localUuid: String,
        targetUuid: String,
        expectedCode: String,
        battery: Int,
        deviceType: String,
    ): Int

    fun nrc_send_pairing_resp(
        ctx: Pointer,
        uuid: String,
        ltPub: String,
        pairingCode: String,
        ip: String,
        battery: Int,
        deviceType: String,
    ): Int

    fun nrc_send_accept(
        ctx: Pointer,
        uuid: String,
        ltPubKey: String,
        ip: String,
        battery: Int,
        deviceType: String,
    )

    // ======== Pairing code management (Rust-generated) ========
    fun nrc_generate_pairing_code(
        ctx: Pointer,
        ttlSecs: Int,
    ): Pointer

    fun nrc_clear_pairing_code(ctx: Pointer)

    fun nrc_send_reject(
        ctx: Pointer,
        uuid: String,
    )

    // ======== Periodic broadcast ========
    fun nrc_periodic_broadcast(
        ctx: Pointer,
        action: Int,
        uuid: String?,
        name: String?,
        battery: Int,
        deviceType: String?,
    ): Int

    // ======== State persistence ========
    fun nrc_export_state(ctx: Pointer): Pointer

    fun nrc_import_state(
        ctx: Pointer,
        json: String,
    ): Int

    fun nrc_encrypt_local_state(
        ctx: Pointer,
        plaintext: String,
        deviceUuid: String,
    ): Pointer

    fun nrc_decrypt_local_state(
        ctx: Pointer,
        encryptedB64: String,
        deviceUuid: String,
    ): Pointer

    fun nrc_export_device_key(
        ctx: Pointer,
        deviceUuid: String,
    ): Pointer

    fun nrc_get_local_uuid(ctx: Pointer): Pointer

    fun nrc_rename_device(
        ctx: Pointer,
        deviceUuid: String,
        name: String,
    ): Int

    fun nrc_free_string(s: Pointer)

    // ======== Network layer ========
    fun nrc_remove_device_session(
        ctx: Pointer,
        uuid: String,
    ): Int

    // ======== Core start (统一启动 TCP/UDP、心跳、离线检测、发送队列、扫描、重连、mDNS) ========
    fun nrc_start_core(
        ctx: Pointer,
        uuid: String,
        name: String,
        battery: Int,
        deviceType: String,
        tcpPort: Short,
        pubkey: String,
        heartbeatIntervalMs: Long,
        offlineTimeoutSec: Long,
        offlineCheckIntervalMs: Long,
        reconnectIntervalSecs: Long,
        reconnectMaxRetries: Int,
    ): Long

    fun nrc_update_heartbeat_scheduler_params(
        ctx: Pointer,
        name: String,
        battery: Int,
        deviceType: String,
    )

    // 心跳模式切换：1=TCP 备用（锁屏/WLAN直连），0=广播主用（默认）
    fun nrc_set_heartbeat_tcp_backup(
        ctx: Pointer,
        enabled: Int,
    ): Int

    // ======== Device state snapshot ========
    fun nrc_get_device_list(
        ctx: Pointer,
        authedTimeoutMs: Long,
        unauthedTimeoutMs: Long,
    ): Pointer

    // ======== Sender queue ========
    fun nrc_enqueue_message(
        ctx: Pointer,
        queuePtr: Long,
        deviceUuid: String,
        header: String,
        plaintext: String,
        dedupKey: String?,
    )

    // ======== Clipboard ========
    fun nrc_clipboard_on_changed(
        ctx: Pointer,
        queuePtr: Long,
        targetsJson: String,
        mime: String,
        content: String,
        nowMs: Long,
        force: Int,
    ): Pointer

    fun nrc_clipboard_on_received(
        ctx: Pointer,
        payloadJson: String,
        nowMs: Long,
    ): Pointer

    // ======== App sync (app list & icons) ========
    fun nrc_app_sync_prepare_icon_request(
        ctx: Pointer,
        packagesJson: String,
        installedJson: String,
        cachedJson: String,
        appDeviceJson: String,
        sourceDeviceUuid: String,
        nowMs: Long,
    ): Pointer

    fun nrc_app_sync_clear_icon_pending(
        ctx: Pointer,
        packagesJson: String,
    )

    fun nrc_app_sync_parse_icon_response(payloadJson: String): Pointer

    fun nrc_app_sync_build_applist_request(
        scope: String,
        nowMs: Long,
    ): Pointer

    fun nrc_app_sync_parse_applist_response(payloadJson: String): Pointer

    // 推送「全量」超级岛/媒体状态（Rust 内部计算差异、合并、ACK 与心跳）；接收端经 on_data 回传全量。
    // isQuery：1=查询回调响应推送（心跳查询发现变更后由平台推送），0=正常主动推送。
    fun nrc_push_superisland_state(
        ctx: Pointer?,
        queuePtr: Long,
        deviceUuid: String,
        fullJson: String,
        isEnd: Int,
        isQuery: Int,
    )

    fun nrc_push_media_state(
        ctx: Pointer?,
        queuePtr: Long,
        deviceUuid: String,
        fullJson: String,
        isEnd: Int,
        isQuery: Int,
    )

    // ======== Network change ========
    fun nrc_on_network_changed(
        ctx: Pointer,
        localIp: String?,
    )

    // ======== Local IP ========
    fun nrc_get_local_ip(): Pointer

    // ======== Discovery ========
    fun nrc_add_known_device(
        ctx: Pointer,
        uuid: String,
        ip: String,
    )

    fun nrc_remove_known_device(
        ctx: Pointer,
        uuid: String,
    )

    // ======== Reconnect ========
    fun nrc_reconnect_add_target(
        ctx: Pointer,
        uuid: String,
        ip: String,
    )

    fun nrc_reconnect_remove_target(
        ctx: Pointer,
        uuid: String,
    )

    // ======== Network callbacks ========
    interface OnDeviceConnectedCb : Callback {
        fun invoke(
            uuid: Pointer?,
            ip: Pointer?,
            userData: Pointer?,
        )
    }

    interface OnDeviceDisconnectedCb : Callback {
        fun invoke(
            uuid: Pointer?,
            userData: Pointer?,
        )
    }

    interface OnTcpErrorCb : Callback {
        fun invoke(
            error: Pointer?,
            userData: Pointer?,
        )
    }

    fun nrc_set_on_device_connected_cb(
        ctx: Pointer,
        cb: OnDeviceConnectedCb?,
    )

    fun nrc_set_on_device_disconnected_cb(
        ctx: Pointer,
        cb: OnDeviceDisconnectedCb?,
    )

    fun nrc_set_on_tcp_error_cb(
        ctx: Pointer,
        cb: OnTcpErrorCb?,
    )

    fun nrc_set_on_mdns_discovered_cb(
        ctx: Pointer,
        cb: OnMdnsDiscoveredCb?,
    )

    // ======== mDNS ========
    fun nrc_stop_mdns_advertiser(ctx: Pointer): Int

    fun nrc_stop_mdns_discovery(ctx: Pointer): Int

    companion object {
        private val _instance: NotifyRelayCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Native.load("notify_relay_core", NotifyRelayCore::class.java)
        }

        fun instance(): NotifyRelayCore = _instance

        fun ptrToStringAndFree(ptr: Pointer?): String? {
            if (ptr == null || Pointer.nativeValue(ptr) == 0L) return null
            return try {
                ptr.getString(0, "UTF-8")
            } finally {
                instance().nrc_free_string(ptr)
            }
        }

        fun ptrToString(ptr: Pointer?): String? {
            if (ptr == null || Pointer.nativeValue(ptr) == 0L) return null
            return ptr.getString(0, "UTF-8")
        }
    }
}
