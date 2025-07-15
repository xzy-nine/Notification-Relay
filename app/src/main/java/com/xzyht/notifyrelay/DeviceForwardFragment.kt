package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.Text

class DeviceForwardFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    DeviceForwardScreen()
                }
            }
        }
    }
}

@Composable
fun DeviceForwardScreen() {
    val colorScheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "设备转发功能开发中...", style = MiuixTheme.textStyles.title2.copy(color = colorScheme.primary))
    }
}
