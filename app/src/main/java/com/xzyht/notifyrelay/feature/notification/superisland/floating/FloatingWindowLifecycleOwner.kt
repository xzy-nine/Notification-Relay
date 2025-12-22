package com.xzyht.notifyrelay.feature.notification.superisland.floating

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 浮窗的自定义LifecycleOwner + SavedStateRegistryOwner
 * - 提供Lifecycle给Compose的WindowRecomposer
 * - 提供SavedStateRegistry给rememberSaveable等API
 */
class FloatingWindowLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var isRestored = false
    
    init {
        // LifecycleRegistry 默认初始为 INITIALIZED
        // 立即附加并恢复SavedState，使Compose在首次attach就可消费
        try {
            savedStateController.performAttach()
        } catch (_: Exception) {}
        try {
            savedStateController.performRestore(null)
            isRestored = true
        } catch (_: Exception) {}
        // 通过派发 ON_CREATE 事件使生命周期正式进入 CREATED，符合SavedStateRegistry的预期
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    
    /**
     * 处理生命周期事件
     */
    private fun handleLifecycleEvent(event: Lifecycle.Event) {
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