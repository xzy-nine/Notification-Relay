package com.xzyht.notifyrelay.lsp

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Notify-Relay LSPosed 模块入口。
 *
 * 通过 libxposed API 102 在目标进程注入超岛鉴权绕过：
 * - com.xiaomi.xmsf：AuthSession 鉴权错误强改成功（服务框架侧）
 */
class XposedInit : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 模块自身加载，无额外初始化
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            PKG_XMSF -> runCatching { XmsfAuthFix.hook(this, param) }
                .onFailure { log(android.util.Log.WARN, TAG, "XmsfAuthFix 注入失败: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "NotifyRelay-LSP"
        private const val PKG_XMSF = "com.xiaomi.xmsf"
    }
}
