package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.LifecycleOwner

/**
 * 视图工具类
 * 提供触摸事件处理、LifecycleOwner 处理等通用视图相关工具函数
 */
object ViewUtils {

    /**
     * 创建不消耗触摸事件的监听器
     * 确保触摸事件能传递到父容器
     */
    fun createNonConsumingTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            // 返回 false 表示不消耗事件，让事件继续传递给父容器
            false
        }
    }

    /**
     * 设置视图为不消耗触摸事件
     * 包含：设置触摸监听器、isClickable、isFocusable 等属性
     */
    fun View.setNonConsumingTouch() {
        // 设置为不可点击、不可聚焦
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        // 设置不消耗事件的触摸监听器
        setOnTouchListener(createNonConsumingTouchListener())
    }

    /**
     * 安全处理 LifecycleOwner
     * 提供默认的 LifecycleOwner 处理
     */
    @Composable
    fun withLifecycleOwner(
        lifecycleOwner: LifecycleOwner?,
        content: @Composable () -> Unit
    ) {
        if (lifecycleOwner != null) {
            CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner
            ) {
                content()
            }
        } else {
            content()
        }
    }

    /**
     * 判断触摸事件是否为有效事件
     * 过滤掉一些无效的触摸事件
     */
    fun isTouchEventValid(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }
}
