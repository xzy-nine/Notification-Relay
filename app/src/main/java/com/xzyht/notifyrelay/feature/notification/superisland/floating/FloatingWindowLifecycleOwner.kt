package com.xzyht.notifyrelay.feature.notification.superisland.floating

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 浮窗的自定义LifecycleOwner，用于解决ComposeView找不到LifecycleOwner的问题
 */
class FloatingWindowLifecycleOwner : LifecycleOwner {
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    
    init {
        // 初始化为CREATE状态
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    
    /**
     * 处理生命周期事件
     */
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
    
    /**
     * 标记浮窗已显示
     */
    fun onShow() {
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    
    /**
     * 标记浮窗已隐藏
     */
    fun onHide() {
        handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
    
    /**
     * 标记浮窗已销毁
     */
    fun onDestroy() {
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}