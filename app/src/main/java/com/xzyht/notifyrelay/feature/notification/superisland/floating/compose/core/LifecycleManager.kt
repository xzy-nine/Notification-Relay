package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xzyht.notifyrelay.feature.notification.superisland.floating.FloatingWindowLifecycleOwner

/**
 * 浮窗生命周期管理器
 */
class LifecycleManager {

    private var _lifecycleOwner: FloatingWindowLifecycleOwner? = null

    /**
     * 获取生命周期所有者
     */
    val lifecycleOwner: LifecycleOwner
        get() {
            if (_lifecycleOwner == null) {
                _lifecycleOwner = FloatingWindowLifecycleOwner()
            }
            return _lifecycleOwner!!
        }

    /**
     * 通知浮窗显示
     */
    fun onShow() {
        _lifecycleOwner?.onShow()
    }

    /**
     * 通知浮窗隐藏
     */
    fun onHide() {
        _lifecycleOwner?.onHide()
    }

    /**
     * 通知浮窗销毁
     */
    fun onDestroy() {
        _lifecycleOwner?.onDestroy()
        _lifecycleOwner = null
    }
}

/**
 * 提供生命周期所有者的组合局部
 */
@Composable
fun ProvideLifecycleOwner(
    lifecycleManager: LifecycleManager,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleManager.lifecycleOwner
    ) {
        content()
    }
}