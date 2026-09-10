package com.xzyht.notifyrelay.lsp

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
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
 * 本 hook 在 AuthSession.getAuthError(Bundle) 返回错误前拦截：把 AuthError 的错误码
 * 置 0（成功），并直接返回 getAuthSuccess() 的成功 Bundle，使 XMSF 侧鉴权恒为成功。
 *
 * 参考 HyperCeiler UnlockFoucsAuth.kt 实现。
 */
object XmsfAuthFix {
    private const val TAG = "NotifyRelay-XmsfAuthFix"

    private var getAuthError: Method? = null
    private var getAuthSuccess: Method? = null
    private var getErrorField: Field? = null

    init {
        System.loadLibrary("dexkit")
    }

    fun hook(
        xposed: XposedInterface,
        param: PackageReadyParam,
    ) {
        val classLoader = param.getClassLoader()

        try {
            xposed.log(android.util.Log.INFO, TAG, "开始 DexKit 查找，useMemoryDexFile=false")

            DexKitBridge.create(classLoader, false).use { bridge ->
                xposed.log(android.util.Log.INFO, TAG, "DexKitBridge 创建成功")

                val authSessionClass = bridge.findClass {
                    matcher {
                        className = "com.xiaomi.xms.auth.AuthSession"
                    }
                }
                xposed.log(android.util.Log.INFO, TAG, "AuthSession 类查找结果: ${authSessionClass.size}")

                if (authSessionClass.isEmpty()) {
                    xposed.log(android.util.Log.ERROR, TAG, "未找到 AuthSession 类，hook 终止")
                    return
                }

                val authErrorMethods = authSessionClass.first().findMethod {
                    matcher {
                        modifiers = Modifier.FINAL
                        paramCount = 1
                        returnType = "android.os.Bundle"
                    }
                }
                xposed.log(android.util.Log.INFO, TAG, "getAuthError 方法查找结果: ${authErrorMethods.size}")
                getAuthError = authErrorMethods.single().getMethodInstance(classLoader)

                val authSuccessMethods = authSessionClass.first().findMethod {
                    matcher {
                        modifiers = Modifier.FINAL
                        paramCount = 0
                        returnType = "android.os.Bundle"
                    }
                }
                xposed.log(android.util.Log.INFO, TAG, "getAuthSuccess 方法查找结果: ${authSuccessMethods.size}")
                getAuthSuccess = authSuccessMethods.single().getMethodInstance(classLoader)

                val authErrorClass = bridge.findClass {
                    matcher {
                        className = "com.xiaomi.xms.auth.AuthError"
                    }
                }
                xposed.log(android.util.Log.INFO, TAG, "AuthError 类查找结果: ${authErrorClass.size}")

                if (authErrorClass.isEmpty()) {
                    xposed.log(android.util.Log.ERROR, TAG, "未找到 AuthError 类，hook 终止")
                    return
                }

                val errorFields = authErrorClass.first().findField {
                    matcher {
                        type = "int"
                    }
                }
                xposed.log(android.util.Log.INFO, TAG, "errorCode 字段查找结果: ${errorFields.size}")
                getErrorField = errorFields.single().getFieldInstance(classLoader)
            }

            xposed.log(android.util.Log.INFO, TAG, "DexKit 查找完成，开始安装 hook")

            val method = getAuthError
            if (method == null) {
                xposed.log(android.util.Log.ERROR, TAG, "getAuthError 为 null，hook 终止")
                return
            }

            xposed.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val error = chain.getArg(0)
                    if (error == null) {
                        xposed.log(android.util.Log.WARN, TAG, "error 参数为 null，跳过拦截")
                        return chain.proceed()
                    }
                    try {
                        val field = getErrorField
                        if (field == null) {
                            xposed.log(android.util.Log.WARN, TAG, "getErrorField 为 null，跳过拦截")
                            return chain.proceed()
                        }
                        val errorCode = field.get(error)
                        xposed.log(android.util.Log.INFO, TAG, "发现错误分发: $errorCode，正在拦截并强制返回成功")
                        field.set(error, 0)
                        val successMethod = getAuthSuccess
                        if (successMethod == null) {
                            xposed.log(android.util.Log.WARN, TAG, "getAuthSuccess 为 null，跳过拦截")
                            return chain.proceed()
                        }
                        val successBundle = successMethod.invoke(chain.getThisObject()) as? Bundle
                        if (successBundle != null) {
                            xposed.log(android.util.Log.INFO, TAG, "已将鉴权错误强改为成功")
                            return successBundle
                        } else {
                            xposed.log(android.util.Log.WARN, TAG, "getAuthSuccess 返回 null")
                        }
                    } catch (e: Throwable) {
                        xposed.log(android.util.Log.ERROR, TAG, "鉴权拦截失败: ${e.message}")
                        e.printStackTrace()
                    }
                    return chain.proceed()
                }
            })
            xposed.log(android.util.Log.INFO, TAG, "AuthSession hook 注入成功 (getAuthError)")
        } catch (e: Throwable) {
            xposed.log(android.util.Log.ERROR, TAG, "hook 整体失败: ${e.message}")
            e.printStackTrace()
        }
    }
}
