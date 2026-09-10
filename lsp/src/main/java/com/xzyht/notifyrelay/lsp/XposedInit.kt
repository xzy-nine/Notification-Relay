package com.xzyht.notifyrelay.lsp

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/**
 * Notify-Relay LSPosed 模块入口（EzHookTool 骨架，libxposed API 102）。
 *
 * 只注入服务侧 com.xiaomi.xmsf：AuthSession 鉴权错误强改成功。
 * HyperOS 4 变更的是系统界面（com.android.systemui）组件，服务侧鉴权链路未变，
 * 因此不注入 SystemUI。
 *
 * 热重载采用 EzHookTool 的默认自动流程（对齐 HyperCeiler）：
 * - onModuleLoaded → [EzXposed.initOnModuleLoaded]：记录进程元信息与基础接口；
 * - onPackageReady → 先 [EzXposed.onTargetReady] 注册安装回调，再 [EzXposed.initOnPackageReady]
 *   建立目标 snapshot。**注册必须早于 init**，否则回调不属于默认聚合事务，热重载无法原子替换；
 * - onHotReloading → [EzXposed.handleHotReloading]：拍平 snapshot 交给框架，返回是否允许热重载；
 * - onHotReloaded → [EzXposed.handleHotReloadedWithTargetReady]：新世代重新注册安装回调，
 *   还原 snapshot，并按「executable + hook ID」原子替换旧 hook，最后才清理未再声明的旧 hook。
 *
 * 对应 module.prop：minApiVersion=102 / targetApiVersion=102 / autoHotReload=true。
 * 注意：启用 autoHotReload 要求 minApiVersion=102，即放弃 API 101 框架。
 */
class XposedInit : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName == PKG_XMSF) {
            EzXposed.onTargetReady { XmsfAuthFix.install(this) }
        }
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        runCatching { EzXposed.handleHotReloading(param) }
            .getOrElse {
                log(android.util.Log.WARN, TAG, "热重载 snapshot 保存失败，放弃本次热重载: ${it.message}")
                false
            }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (!param.processName.startsWith(PKG_XMSF)) return
        // 必须用命名参数：尾随 lambda 会绑定到 onExtra 而非 targetReady
        EzXposed.handleHotReloadedWithTargetReady(
            this,
            param,
            targetReady = { XmsfAuthFix.install(this) },
        )
    }

    companion object {
        private const val TAG = "NotifyRelay-LSP"
        private const val PKG_XMSF = "com.xiaomi.xmsf"
    }
}
