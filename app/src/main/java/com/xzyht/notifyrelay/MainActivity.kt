package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.xzyht.notifyrelay.ui.theme.AppIcons
import com.xzyht.notifyrelay.ui.theme.NotifyRelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotifyRelayTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("设备与转发") },
                    icon = { Icon(AppIcons.Settings, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("通知历史") },
                    icon = { Icon(AppIcons.Notifications, contentDescription = null) }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> DeviceForwardScreen()
            1 -> NotificationHistoryScreen()
        }
    }
}

@Composable
fun DeviceForwardScreen() {
    // TODO: 设备与转发设置界面实现
    Text("设备与转发设置页")
}

@Composable
fun NotificationHistoryScreen() {
    // TODO: 通知历史界面实现
    Text("通知历史页")
}

@Preview(showBackground = true)
@Composable
fun MainAppPreview() {
    NotifyRelayTheme {
        MainApp()
    }
}
