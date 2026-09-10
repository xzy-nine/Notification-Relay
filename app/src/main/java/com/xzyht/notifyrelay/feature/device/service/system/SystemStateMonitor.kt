package com.xzyht.notifyrelay.feature.device.service.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.xzyht.notifyrelay.nativecore.NativeCore
import notifyrelay.core.util.BatteryUtils

/**
 * 系统状态广播监听（锁屏状态 + 电池状态），把平台侧才能采集到的状态推送给 Rust core。
 *
 * - 锁屏：[Intent.ACTION_SCREEN_OFF] / [Intent.ACTION_SCREEN_ON] / [Intent.ACTION_USER_PRESENT]
 *   → 回调 [onLockStateChanged]（由装配方同步心跳模式）；
 * - 电池：[Intent.ACTION_BATTERY_CHANGED] 为粘性广播，注册后立即回调一次当前状态，
 *   电量或充电状态变化时调用 `nrc_update_heartbeat_scheduler_params`，
 *   使心跳报文携带实时电量（正=充电，负=放电）。相同值被防抖跳过。
 *
 * core 侧没有主动拉取本机电量的接口，因此这里必须由平台推送。
 */
class SystemStateMonitor(
    private val context: Context,
    private val localDisplayName: () -> String,
    private val onLockStateChanged: () -> Unit,
) {
    companion object {
        /** 带符号电量：正=充电，负=放电（与 core 约定一致）。 */
        fun signedBatteryLevel(context: Context): Int {
            val level = BatteryUtils.getBatteryLevel(context)
            return if (BatteryUtils.isCharging(context)) level else -level
        }
    }

    // 上次同步给 Rust 心跳调度器的带符号电量，用于防抖
    @Volatile
    private var lastSentSignedBattery: Int = Int.MIN_VALUE

    private var batteryReceiver: BroadcastReceiver? = null

    // 锁屏状态变化监听：SCREEN_OFF/ON + USER_PRESENT 覆盖锁屏/解锁切换
    private var lockStateReceiver: BroadcastReceiver? = null

    /**
     * 记录启动时已上报给 core 的带符号电量，避免首次电池广播重复上报。
     */
    fun seedSignedBattery(signed: Int) {
        lastSentSignedBattery = signed
    }

    /** 注册锁屏与电池广播监听（幂等）。 */
    fun register() {
        registerLockStateReceiver()
        registerBatteryChangeReceiver()
    }

    /** 注销全部广播监听。 */
    fun unregister() {
        lockStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        lockStateReceiver = null
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        batteryReceiver = null
    }

    private fun registerLockStateReceiver() {
        if (lockStateReceiver != null) return
        lockStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF,
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT,
                        -> onLockStateChanged()
                    }
                }
            }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            // RECEIVER_NOT_EXPORTED 语义仅 API 33+ 生效，API 31/32 上等效 exported。
            // 三参数重载 API 26+ 已存在，但显式分支可避免在低版本上误传未定义 flag。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(lockStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(lockStateReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun registerBatteryChangeReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                    try {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (level < 0 || scale <= 0) return
                        val batteryLevel = (level * 100 / scale).coerceIn(0, 100)
                        val charging =
                            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        val signed = if (charging) batteryLevel else -batteryLevel
                        if (signed == lastSentSignedBattery) return
                        // 仅在上下文可用且上报调用未抛异常时才更新去重值；否则保留原值以便后续相同电量重试
                        val ctx = NativeCore.getContext() ?: return
                        NativeCore.updateHeartbeatSchedulerParams(ctx, localDisplayName(), signed, "android")
                        lastSentSignedBattery = signed
                    } catch (_: Exception) {
                    }
                }
            }
        try {
            // 同锁屏监听：RECEIVER_NOT_EXPORTED 仅 API 33+ 生效。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                context.registerReceiver(
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
            }
        } catch (_: Exception) {
        }
    }
}
