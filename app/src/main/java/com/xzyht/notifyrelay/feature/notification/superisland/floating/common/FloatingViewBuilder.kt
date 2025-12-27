package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner

/**
 * 浮动视图构建器接口
 * 定义公共的构建器模式，用于抽象 BigIsland 和 SmallIsland 的构建逻辑
 */
interface FloatingViewBuilder {

    /**
     * 构建浮动视图的 ComposeView
     * @param context 上下文
     * @param picMap 图片映射
     * @param lifecycleOwner 生命周期所有者
     * @return ComposeView 实例
     */
    fun buildView(
        context: Context,
        picMap: Map<String, String>? = null,
        lifecycleOwner: LifecycleOwner? = null
    ): ComposeView

    /**
     * 异步构建浮动视图的 ComposeView
     * 用于需要异步加载数据的场景
     * @param context 上下文
     * @param picMap 图片映射
     * @param lifecycleOwner 生命周期所有者
     * @return ComposeView 实例
     */
    suspend fun buildViewAsync(
        context: Context,
        picMap: Map<String, String>? = null,
        lifecycleOwner: LifecycleOwner? = null
    ): ComposeView
}

/**
 * 构建器工厂接口
 * 用于创建不同类型的浮动视图构建器
 */
interface FloatingViewBuilderFactory {

    /**
     * 创建 BigIsland 构建器
     * @param data BigIsland 数据
     * @return FloatingViewBuilder 实例
     */
    fun createBigIslandBuilder(data: Any): FloatingViewBuilder

    /**
     * 创建 SmallIsland 构建器
     * @param data SmallIsland 数据
     * @return FloatingViewBuilder 实例
     */
    fun createSmallIslandBuilder(data: Any): FloatingViewBuilder
}

/**
 * 浮动视图构建器工具类
 * 提供通用的构建器功能
 */
object FloatingViewBuilderUtil {

    /**
     * 构建视图的通用参数
     * 封装构建视图所需的所有参数
     */
    data class BuildParams(
        val context: Context,
        val picMap: Map<String, String>? = null,
        val lifecycleOwner: LifecycleOwner? = null,
        val business: String? = null,
        val fallbackTitle: String? = null,
        val fallbackContent: String? = null
    )
}
