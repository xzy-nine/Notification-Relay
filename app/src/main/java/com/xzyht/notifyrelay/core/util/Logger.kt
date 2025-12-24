package com.xzyht.notifyrelay.core.util

import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
//TODO 记得修改其他文件的替换为Logger
/**
 * 日志工具类
 * 用于统一管理日志输出，支持按级别控制是否输出到控制台
 * 支持i/d/w/e的级别控制
 */
object Logger {
    // 日志级别枚举
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        NONE
    }

    // 当前日志级别，可根据需求调整
    private val CURRENT_LEVEL = if (BuildConfig.DEBUG) {
        Level.VERBOSE
    } else {
        Level.ERROR
    }

    /**
     * 详细日志（最低级别）
     */
    fun v(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.VERBOSE) {
            Log.v(tag, message)
        }
    }

    /**
     * 详细日志带异常
     */
    fun v(tag: String, message: String, throwable: Throwable) {
        if (CURRENT_LEVEL <= Level.VERBOSE) {
            Log.v(tag, message, throwable)
        }
    }

    /**
     * 调试日志
     */
    fun d(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * 调试日志带异常
     */
    fun d(tag: String, message: String, throwable: Throwable) {
        if (CURRENT_LEVEL <= Level.DEBUG) {
            Log.d(tag, message, throwable)
        }
    }

    /**
     * 信息日志
     */
    fun i(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.INFO) {
            Log.i(tag, message)
        }
    }

    /**
     * 信息日志带异常
     */
    fun i(tag: String, message: String, throwable: Throwable) {
        if (CURRENT_LEVEL <= Level.INFO) {
            Log.i(tag, message, throwable)
        }
    }

    /**
     * 警告日志
     */
    fun w(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.WARN) {
            Log.w(tag, message)
        }
    }

    /**
     * 警告日志带异常
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        if (CURRENT_LEVEL <= Level.WARN) {
            Log.w(tag, message, throwable)
        }
    }

    /**
     * 错误日志
     */
    fun e(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.ERROR) {
            Log.e(tag, message)
        }
    }

    /**
     * 错误日志带异常
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        if (CURRENT_LEVEL <= Level.ERROR) {
            Log.e(tag, message, throwable)
        }
    }

    /**
     * 输出SBN详情（用于替换logSbnDetail）
     */
    fun logSbnDetail(tag: String, message: String) {
        if (CURRENT_LEVEL <= Level.DEBUG) {
            Log.d(tag, message)
        }
    }
}