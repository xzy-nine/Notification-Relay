package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import android.widget.Toast

/**
 * Toast工具类，提供统一的Toast显示方法
 */
object ToastUtils {

    /**
     * 显示短时间Toast
     * @param context 上下文
     * @param message 要显示的消息
     */
    fun showShortToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示长时间Toast
     * @param context 上下文
     * @param message 要显示的消息
     */
    fun showLongToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
