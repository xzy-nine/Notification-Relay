package com.xzyht.notifyrelay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

class DeviceForwardFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val textStyles = MiuixTheme.textStyles
                val colorScheme = MiuixTheme.colorScheme
                Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.background)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "设备与转发设置",
                            style = textStyles.title2.copy(color = colorScheme.onBackground)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "设备发现、连接、转发规则、黑名单管理等功能待实现",
                                style = textStyles.body2.copy(color = colorScheme.outline)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无设备",
                            style = textStyles.body1.copy(color = colorScheme.onBackground)
                        )
                    }
                }
            }
        }
    }
}
