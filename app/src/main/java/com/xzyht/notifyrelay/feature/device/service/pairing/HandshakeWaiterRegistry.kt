package com.xzyht.notifyrelay.feature.device.service.pairing

import kotlinx.coroutines.CompletableDeferred

/**
 * 握手结果等待器登记表。
 *
 * 发起配对/连接时由 UI 层注册一个 [CompletableDeferred]，Rust 回调侧拿到结果后完成它，
 * 使得调用方可以「挂起等待远端响应」而不需要轮询。
 *
 * 同一 uuid 只保留最新的等待器：重复注册会取消上一个（`CompletableDeferred.cancel()`），
 * 避免迟到结果误唤醒旧等待者。
 */
class HandshakeWaiterRegistry {
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /** 注册等待握手结果（同一 uuid 的旧等待器会被取消）。 */
    fun register(uuid: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pending) {
            pending[uuid]?.cancel()
            pending[uuid] = deferred
        }
        return deferred
    }

    /** 解析挂起的握手结果。 */
    fun resolve(
        uuid: String,
        success: Boolean,
    ) {
        synchronized(pending) {
            pending.remove(uuid)?.complete(success)
        }
    }

    /** 按 Deferred 实例清理等待器，防止迟到请求完成或移除其他等待器。 */
    fun cancel(
        uuid: String,
        deferred: CompletableDeferred<Boolean>,
    ) {
        synchronized(pending) {
            if (pending[uuid] === deferred) {
                pending.remove(uuid)
                deferred.cancel()
            }
        }
    }
}
