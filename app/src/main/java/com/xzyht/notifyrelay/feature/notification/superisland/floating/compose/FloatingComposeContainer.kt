package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.LifecycleOwner

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
    
    // 条目点击回调
    var onEntryClick: ((String) -> Unit)? = null
    
    // 条目拖拽回调
    var onEntryDrag: ((String, androidx.compose.ui.geometry.Offset) -> Unit)? = null
    
    @Composable
    override fun Content() {
        // 只有当浮窗管理器初始化后才渲染内容
        if (::floatingWindowManager.isInitialized) {
            FloatingWindowContainer(
                entries = floatingWindowManager.entriesList,
                onEntryClick = { key -> onEntryClick?.invoke(key) },
                onEntryDrag = { key, offset -> onEntryDrag?.invoke(key, offset) },
                lifecycleOwner = lifecycleOwner
            )
        }
    }
}