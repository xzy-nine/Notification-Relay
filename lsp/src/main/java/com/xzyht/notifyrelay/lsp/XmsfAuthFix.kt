package com.xzyht.notifyrelay.lsp

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 服务框架侧（com.xiaomi.xmsf）鉴权绕过。
 *
 * XMSF 收到 SystemUI 的焦点通知鉴权请求后，通过 AuthSession 向小米服务器查询应用
 * 权限（scope 20032）；未注册 scope 的应用会返回错误码（-300 scope mismatch / -210
 * 超时），SystemUI 据此撤销已渲染的焦点通知。
 *
 * 本 hook 在 AuthSession.getAuthError(Bundle) 执行前拦截：把 AuthError 的错误码置 0
 * （成功），并把 getAuthSuccess() 的成功 Bundle 作为返回值，使 XMSF 侧鉴权恒为成功。
 *
 * 参考 HyperCeiler UnlockFoucsAuth.kt。
 *
 * 热重载：安装入口 [install] 由 EzXposed.onTargetReady 在「首次加载」与「热重载后」统一调用，
 * 每次都由新世代重新执行 DexKit 查找并重新安装 hook。不使用旧 hook 句柄反推成员，
 * 也不跨代缓存反射对象；hook 走 EzHookTool DSL 以获得稳定物理 ID，供热重载聚合事务
 * 按「executable + ID」原子替换。
 */
object XmsfAuthFix {
    private const val TAG = "NotifyRelay-XmsfAuthFix"

    @Volatile
    private var dexKitLoaded = false
    private val dexKitLock = Any()

    private var getAuthSuccess: Method? = null
    private var getErrorField: Field? = null

    /**
     * 安装 AuthSession 鉴权拦截。由 [XposedInit] 在 onTargetReady 内同步调用，
     * 首次加载与热重载走同一条路径。
     */
    fun install(xposed: XposedInterface) {
        try {
            ensureDexKitLoaded()

            val classLoader = EzXposed.classLoader
            xposed.log(android.util.Log.INFO, TAG, "开始 DexKit 查找 (${EzXposed.processName})")
            val getAuthError = resolveFromDexKit(xposed, classLoader) ?: return

            getAuthError.createBeforeHook { param ->
                val error = param.arg(0) ?: return@createBeforeHook
                val thisObject = param.thisObjectOrNull ?: return@createBeforeHook
                val field = getErrorField ?: return@createBeforeHook
                try {
                    val errorCode = field.get(error)
                    xposed.log(android.util.Log.INFO, TAG, "发现错误分发: $errorCode，正在拦截并强制返回成功")
                    // 对齐 HyperCeiler UnlockFoucsAuth：先无条件将错误码置 0，再返回 getAuthSuccess() 的结果
                    field.set(error, 0)
                    val successMethod = getAuthSuccess ?: return@createBeforeHook
                    param.result = successMethod.invoke(thisObject)
                    xposed.log(android.util.Log.INFO, TAG, "已将鉴权错误强改为成功")
                } catch (e: Throwable) {
                    xposed.log(android.util.Log.ERROR, TAG, "鉴权拦截失败: ${e.message}")
                }
            }
            xposed.log(android.util.Log.INFO, TAG, "AuthSession hook 注入成功 (getAuthError)")
        } catch (e: Throwable) {
            xposed.log(android.util.Log.ERROR, TAG, "hook 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * DexKit 原生库每个世代只加载一次（对齐 HyperCeiler DexKitCacheManager 的做法）。
     * 热重载后新世代由新的 ClassLoader 加载，该标志随世代重置，因此会再加载一次。
     */
    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        synchronized(dexKitLock) {
            if (dexKitLoaded) return
            System.loadLibrary("dexkit")
            dexKitLoaded = true
        }
    }

    private fun resolveFromDexKit(
        xposed: XposedInterface,
        classLoader: ClassLoader,
    ): Method? =
        DexKitBridge.create(classLoader, false).use { bridge ->
            xposed.log(android.util.Log.INFO, TAG, "DexKitBridge 创建成功")

            val authSessionClass =
                bridge.findClass {
                    matcher {
                        className = "com.xiaomi.xms.auth.AuthSession"
                    }
                }
            xposed.log(android.util.Log.INFO, TAG, "AuthSession 类查找结果: ${authSessionClass.size}")
            if (authSessionClass.isEmpty()) {
                xposed.log(android.util.Log.ERROR, TAG, "未找到 AuthSession 类，hook 终止")
                return null
            }

            val authErrorMethods =
                authSessionClass.first().findMethod {
                    matcher {
                        modifiers = Modifier.FINAL
                        paramCount = 1
                        returnType = "android.os.Bundle"
                    }
                }
            xposed.log(android.util.Log.INFO, TAG, "getAuthError 方法查找结果: ${authErrorMethods.size}")
            val authErrorMethod = authErrorMethods.single().getMethodInstance(classLoader)

            val authSuccessMethods =
                authSessionClass.first().findMethod {
                    matcher {
                        modifiers = Modifier.FINAL
                        paramCount = 0
                        returnType = "android.os.Bundle"
                    }
                }
            xposed.log(android.util.Log.INFO, TAG, "getAuthSuccess 方法查找结果: ${authSuccessMethods.size}")
            getAuthSuccess = authSuccessMethods.single().getMethodInstance(classLoader).apply {
                isAccessible = true
            }

            val authErrorClass =
                bridge.findClass {
                    matcher {
                        className = "com.xiaomi.xms.auth.AuthError"
                    }
                }
            xposed.log(android.util.Log.INFO, TAG, "AuthError 类查找结果: ${authErrorClass.size}")
            if (authErrorClass.isEmpty()) {
                xposed.log(android.util.Log.ERROR, TAG, "未找到 AuthError 类，hook 终止")
                return null
            }

            val errorFields =
                authErrorClass.first().findField {
                    matcher {
                        type = "int"
                    }
                }
            xposed.log(android.util.Log.INFO, TAG, "errorCode 字段查找结果: ${errorFields.size}")
            getErrorField = errorFields.single().getFieldInstance(classLoader).apply {
                isAccessible = true
            }

            xposed.log(android.util.Log.INFO, TAG, "DexKit 查找完成")

            authErrorMethod
        }
}
