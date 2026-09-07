package com.xzyht.notifyrelay.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.xzyht.notifyrelay.ui.common.NotifyRelayTheme
import com.xzyht.notifyrelay.ui.common.ProvideNavigationEventDispatcherOwner
import com.xzyht.notifyrelay.ui.common.SetupSystemBars
import com.xzyht.notifyrelay.ui.guide.GuideScreen
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.GuidePermissionRequester
import notifyrelay.base.util.ThemeSettingsManager
import notifyrelay.data.StorageManager

class GuideActivity : ComponentActivity() {
    private val permissionRequester =
        GuidePermissionRequester(this) {
            // 运行时权限弹窗不会触发 onResume，必须主动刷新权限状态，否则授权后 UI 不更新
            GuideScreen.refreshTrigger++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isFirstLaunch = StorageManager.getBoolean(this, "isFirstLaunch", true, StorageManager.PrefsType.GENERAL)
        val fromInternal = intent.getBooleanExtra("fromInternal", false)
        val fromftp = intent.getBooleanExtra("fromftp", false)
        val reauthExtra = intent.getBooleanExtra("reauth", false)
        // 调试用：强制指定引导分支（full=完整流程 / reauth=重授权 / consent=需重新同意），优先级高于自动判定。
        val forceBranch = intent.getStringExtra("forceBranch")

        // 声明式权限（AndroidManifest 中 <uses-permission> 声明的普通/危险权限）比对：
        // 读取上次已同意的权限集合，与当前声明的权限集合做差集，得到「本次更新新增、需重新同意」的权限。
        val declaredPermissions = PermissionHelper.getApplicableDeclaredPermissions(this)
        val agreedPermissions =
            StorageManager.getStringSet(
                this,
                "agreedManifestPermissions",
                emptySet(),
                StorageManager.PrefsType.GENERAL,
            )
        val newPermissions = declaredPermissions - agreedPermissions

        // 流程模式判定（优先级从高到低）：
        // 1) 首次启动（isFirstLaunch）-> 完整 6 步（含使用须知与授权说明页）；
        // 2) 运行时权限掉落回弹（reauthExtra）-> 重授权 3 步，不重复同意；
        // 3) 非首次启动且本次更新新增了声明式权限（newPermissions 非空）-> 同意 3 步，必须重新阅读并同意；
        // 4) 非首次启动但权限检测未通过（包名变更等授权失效）-> 重授权 3 步，不重复同意；
        // 5) 其余（非首次且权限齐全）-> 直接进入主界面。
        val reauth = reauthExtra || (!isFirstLaunch && !PermissionHelper.checkAllPermissions(this) && newPermissions.isEmpty())
        val needConsent = !isFirstLaunch && newPermissions.isNotEmpty() && !reauthExtra

        // 调试分支覆盖：forceBranch 存在时直接采用指定分支，绕过真实状态判定。
        val debugReauth = when (forceBranch) {
            "reauth" -> true
            "consent" -> false
            else -> reauth
        }
        val debugNeedConsent = when (forceBranch) {
            "consent" -> true
            "reauth" -> false
            else -> needConsent
        }

        // 仅冷启动、已首次启动过、且权限满足、且无需重新同意时自动跳主界面；
        // 其余情况（首次启动 / 应用内跳转 / 重授权 / 需重新同意）均渲染引导页。
        // 流程仿照 HyperCeiler：欢迎页 -> 使用须知 -> 权限设置 -> 基础设置（设置总览 + 多个设置页）-> 完成页。
        if (!fromInternal && !debugReauth && !debugNeedConsent && PermissionHelper.checkAllPermissions(this) && !isFirstLaunch) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // 沉浸式虚拟键，内容延伸到手势提示线区域
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ProvideNavigationEventDispatcherOwner {
                val appContext = LocalContext.current
                val systemDarkTheme = isSystemInDarkTheme()
                var themeBaseIndex by remember {
                    mutableIntStateOf(ThemeSettingsManager.getThemeBaseIndex(appContext))
                }
                val isDarkTheme =
                    when (themeBaseIndex) {
                        ThemeSettingsManager.THEME_LIGHT -> false
                        ThemeSettingsManager.THEME_DARK -> true
                        else -> systemDarkTheme
                    }

                NotifyRelayTheme(darkTheme = isDarkTheme) {
                    SetupSystemBars(isDarkTheme)
                    GuideScreen(
                        permissionRequester = permissionRequester,
                        themeBaseIndex = themeBaseIndex,
                        reauth = debugReauth,
                        needConsent = debugNeedConsent,
                        onThemeChanged = { newIndex ->
                            ThemeSettingsManager.setThemeBaseIndex(appContext, newIndex)
                            themeBaseIndex = newIndex
                        },
                        onContinue = {
                            // 首次启动后标记为已启动
                            StorageManager.putBoolean(this@GuideActivity, "isFirstLaunch", false, StorageManager.PrefsType.GENERAL)
                            // 记录当前已声明的权限集合，作为「已同意」基线，供后续版本更新比对新增权限。
                            StorageManager.putStringSet(
                                this@GuideActivity,
                                "agreedManifestPermissions",
                                declaredPermissions,
                                StorageManager.PrefsType.GENERAL,
                            )

                            if (fromftp) {
                                // 如果是从 FTP 请求跳转过来的，尝试重新启动 FTP 服务
                                Logger.d("GuideActivity", "从 FTP 请求跳转，尝试重新启动 FTP 服务")
                            }

                            startActivity(Intent(this@GuideActivity, MainActivity::class.java))
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 当从系统设置页面返回时，刷新权限状态
        GuideScreen.refreshTrigger++

        // 检查是否从 FTP 请求跳转过来，并且已经获取了文件管理权限
        val fromftp = intent.getBooleanExtra("fromftp", false)
        if (fromftp && PermissionHelper.checkManageExternalStoragePermission(this)) {
            Logger.d("GuideActivity", "从 FTP 请求跳转，已经获取文件管理权限，尝试重新启动 FTP 服务")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    object GuideScreen {
        // 用于触发刷新
        var refreshTrigger by mutableIntStateOf(0)
    }
}
