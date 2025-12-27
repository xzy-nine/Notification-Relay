package com.xzyht.notifyrelay.common.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.provider.Settings
import java.io.Serializable

/**
 * Intent工具类，提供通用的Intent处理方法
 */
object IntentUtils {

    /**
     * 启动Activity
     * @param context 上下文
     * @param intent Intent对象
     * @param addNewTaskFlag 是否添加FLAG_ACTIVITY_NEW_TASK标志
     */
    fun startActivity(context: Context, intent: Intent, addNewTaskFlag: Boolean = false) {
        if (addNewTaskFlag) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 启动Activity（简化版）
     * @param context 上下文
     * @param action Intent action
     * @param data 数据URI
     * @param addNewTaskFlag 是否添加FLAG_ACTIVITY_NEW_TASK标志
     */
    fun startActivity(context: Context, action: String, data: Uri? = null, addNewTaskFlag: Boolean = false) {
        val intent = Intent(action)
        if (data != null) {
            intent.data = data
        }
        if (addNewTaskFlag) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 创建显式Intent
     * @param context 上下文
     * @param cls 目标Activity类
     * @return Intent对象
     */
    fun <T> createIntent(context: Context, cls: Class<T>): Intent {
        return Intent(context, cls)
    }

    /**
     * 创建带额外数据的显式Intent
     * @param context 上下文
     * @param cls 目标Activity类
     * @param extras 额外数据
     * @return Intent对象
     */
    fun <T> createIntentWithExtras(context: Context, cls: Class<T>, extras: Map<String, Any>): Intent {
        val intent = Intent(context, cls)
        extras.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Parcelable -> intent.putExtra(key, value)
                is Serializable -> intent.putExtra(key, value)
            }
        }
        return intent
    }

    /**
     * 创建隐式Intent
     * @param action Intent action
     * @return Intent对象
     */
    fun createImplicitIntent(action: String): Intent {
        return Intent(action)
    }

    /**
     * 创建带数据的隐式Intent
     * @param action Intent action
     * @param uri 数据URI
     * @return Intent对象
     */
    fun createImplicitIntentWithData(action: String, uri: Uri): Intent {
        return Intent(action).apply { data = uri }
    }

    /**
     * 添加常用标志到Intent
     * @param intent Intent对象
     * @param flags 标志列表
     * @return 添加标志后的Intent
     */
    fun addFlags(intent: Intent, vararg flags: Int): Intent {
        flags.forEach { intent.addFlags(it) }
        return intent
    }

    /**
     * 设置Intent的数据URI
     * @param intent Intent对象
     * @param uri 数据URI
     * @return 设置数据后的Intent
     */
    fun setData(intent: Intent, uri: Uri): Intent {
        intent.data = uri
        return intent
    }
}


