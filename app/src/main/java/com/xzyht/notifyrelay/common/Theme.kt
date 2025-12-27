package com.xzyht.notifyrelay.common

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.xzyht.notifyrelay.common.core.util.SystemBarUtils
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun NotifyRelayTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colorScheme, content = content)
}

/**
 * 设置系统栏外观
 * @param isDarkTheme 是否为深色主题
 */
@Composable
fun SetupSystemBars(isDarkTheme: Boolean) {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val barColor = colorScheme.background.toArgb()
    
    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        val win = activity.window
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        
        // 设置状态栏和导航栏图标亮度
        controller.isAppearanceLightStatusBars = !isDarkTheme
        controller.isAppearanceLightNavigationBars = !isDarkTheme
        
        // 设置状态栏和导航栏颜色
        SystemBarUtils.setStatusBarColor(win, barColor, false)
        SystemBarUtils.setNavigationBarColor(win, barColor, false)
        
        // Android 10+ 关闭导航栏对比度强制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
    }
}
