package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlin.math.abs

/**
 * Compose浮窗容器视图，作为传统View系统与Compose之间的桥梁
 */
class FloatingComposeContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {
    
    // 浮窗管理器，用于管理所有浮窗条目
    lateinit var floatingWindowManager: FloatingWindowManager
    
    // 生命周期所有者
    var lifecycleOwner: LifecycleOwner? = null
    
    // 内部LifecycleOwner实现，用于浮窗环境
    private val internalLifecycleOwner = object : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        
        init {
            // 初始化SavedStateRegistry
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            // 设置初始生命周期状态为RESUMED
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    }
    
    // 拖动相关变量
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    
    // WindowManager和LayoutParams引用，用于更新浮窗位置
    var windowManager: WindowManager? = null
    var windowLayoutParams: WindowManager.LayoutParams? = null
    
    init {
        // 设置Compose视图的合成策略，确保在浮窗环境中正确处理生命周期
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    
    override fun onAttachedToWindow() {
        try {
            // 先设置ViewTreeLifecycleOwner，再调用super.onAttachedToWindow()
            // 这样父类方法在调用时就能找到LifecycleOwner
            val viewTreeLifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val viewClass = Class.forName("android.view.View")
            val lifecycleOwnerClass = Class.forName("androidx.lifecycle.LifecycleOwner")
            val setMethod = viewTreeLifecycleOwnerClass.getDeclaredMethod("set", viewClass, lifecycleOwnerClass)
            setMethod.invoke(null, this, internalLifecycleOwner)
            
            // 同时设置SavedStateRegistryOwner
            val viewTreeSavedStateRegistryOwnerClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val savedStateRegistryOwnerClass = Class.forName("androidx.savedstate.SavedStateRegistryOwner")
            val setSavedStateMethod = viewTreeSavedStateRegistryOwnerClass.getDeclaredMethod("set", viewClass, savedStateRegistryOwnerClass)
            setSavedStateMethod.invoke(null, this, internalLifecycleOwner)
        } catch (_: Exception) {
            // 忽略异常，继续执行
        }
        
        // 调用父类方法
        super.onAttachedToWindow()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            // 清理LifecycleOwner和SavedStateRegistryOwner
            val viewTreeLifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val viewClass = Class.forName("android.view.View")
            val lifecycleOwnerClass = Class.forName("androidx.lifecycle.LifecycleOwner")
            val setMethod = viewTreeLifecycleOwnerClass.getDeclaredMethod("set", viewClass, lifecycleOwnerClass)
            setMethod.invoke(null, this, null)
            
            val viewTreeSavedStateRegistryOwnerClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val savedStateRegistryOwnerClass = Class.forName("androidx.savedstate.SavedStateRegistryOwner")
            val setSavedStateMethod = viewTreeSavedStateRegistryOwnerClass.getDeclaredMethod("set", viewClass, savedStateRegistryOwnerClass)
            setSavedStateMethod.invoke(null, this, null)
        } catch (_: Exception) {
            // 忽略异常
        }
    }
    
    // 重写dispatchTouchEvent，确保触摸事件能够被正确传递和处理
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 先处理触摸事件，再调用父类方法
        if (onTouchEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    
    // 处理触摸事件，实现浮窗拖动
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录初始触摸位置
                downX = event.rawX
                downY = event.rawY
                startX = windowLayoutParams?.x ?: 0
                startY = windowLayoutParams?.y ?: 0
                // 拖动状态初始化为false
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 计算移动距离
                val deltaX = abs(event.rawX - downX)
                val deltaY = abs(event.rawY - downY)
                val moveThreshold = 10f // 拖动阈值
                
                if (!isDragging && (deltaX > moveThreshold || deltaY > moveThreshold)) {
                    // 超过拖动阈值，开始拖动
                    isDragging = true
                    // 调用容器拖动开始回调，显示关闭层
                    onContainerDragStart?.invoke()
                }
                
                if (isDragging) {
                    // 计算拖动的偏移量
                    val deltaX = (event.rawX - downX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    
                    // 更新浮窗位置
                    windowLayoutParams?.let { lp ->
                        lp.x = startX + deltaX
                        lp.y = startY + deltaY
                        windowManager?.updateViewLayout(this, lp)
                    }
                    
                    // 调用容器拖动中回调，用于实时检测重叠
                    onContainerDragging?.invoke()
                    
                    // 消费事件，确保能继续拖动
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // 调用容器拖动结束回调
                    onContainerDragEnd?.invoke()
                }
                // 结束拖动
                isDragging = false
            }
        }
        // 只有在拖动时才消费事件，否则让事件传递到Compose内容中
        return isDragging
    }
    
    // 条目点击回调
    var onEntryClick: ((String) -> Unit)? = null
    
    // 容器拖动开始回调
    var onContainerDragStart: (() -> Unit)? = null
    
    // 容器拖动结束回调
    var onContainerDragEnd: (() -> Unit)? = null
    
    // 容器拖动中回调，用于实时检测重叠
    var onContainerDragging: (() -> Unit)? = null
    
    @Composable
    override fun Content() {
        // 只有当浮窗管理器初始化后才渲染内容
        if (::floatingWindowManager.isInitialized) {
            val currentLifecycleOwner = lifecycleOwner
            val contentBlock: @Composable () -> Unit = {
                FloatingWindowContainer(
                    entries = floatingWindowManager.entriesList,
                    onEntryClick = { key -> onEntryClick?.invoke(key) },
                    lifecycleOwner = currentLifecycleOwner
                )
            }
            
            // 使用CompositionLocalProvider为Compose内容提供LifecycleOwner
            if (currentLifecycleOwner != null) {
                CompositionLocalProvider(LocalLifecycleOwner provides currentLifecycleOwner) {
                    contentBlock()
                }
            } else {
                contentBlock()
            }
        }
    }
}