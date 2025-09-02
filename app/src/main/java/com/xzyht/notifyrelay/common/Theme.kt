package com.xzyht.notifyrelay.common

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun NotifyRelayTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colorScheme, content = content)
}
