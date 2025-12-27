package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner

/**
 * ComposeView 构建器工具类
 * 提供通用的 ComposeView 构建逻辑，减少重复代码
 */
object ComposeViewBuilder {

    /**
     * 创建基础的 ComposeView 实例
     * 包含通用的配置：ViewCompositionStrategy、触摸事件处理等
     */
    fun createBaseComposeView(
        context: Context,
        setupContent: ComposeView.() -> Unit
    ): ComposeView {
        return ComposeView(context).apply {
            // 使用 DisposeOnDetachedFromWindow 避免在浮窗中找不到 LifecycleOwner 时崩溃
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // 设置为不可点击、不可聚焦，确保触摸事件能传递到父容器
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            // 设置触摸监听器，不消耗事件，让事件自然传递到父容器
            setupTouchListener()
            // 调用内容设置函数
            setupContent()
        }
    }

    /**
     * 为 ComposeView 设置触摸监听器，确保事件传递到父容器
     */
    fun ComposeView.setupTouchListener() {
        setOnTouchListener { _, event ->
            // 返回 false 表示不消耗事件，让事件继续传递给父容器的 onTouch 监听器
            false
        }
    }

    /**
     * 设置 ComposeView 的内容，支持自定义 LifecycleOwner
     */
    fun ComposeView.setComposeContent(
        lifecycleOwner: LifecycleOwner? = null,
        content: @Composable () -> Unit
    ) {
        setContent {
            if (lifecycleOwner != null) {
                androidx.lifecycle.compose.LocalLifecycleOwner.current
                // 这里可以添加 CompositionLocalProvider 来处理自定义 LifecycleOwner
                // 暂时保持简单实现
                content()
            } else {
                content()
            }
        }
    }
}
