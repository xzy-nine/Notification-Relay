package com.xzyht.notifyrelay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.ui.theme.NotifyRelayTheme

class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotifyRelayTheme {
                GuideScreen(onContinue = {
                    startActivity(Intent(this@GuideActivity, MainActivity::class.java))
                    finish()
                })
            }
        }
    }
}

@Composable
fun GuideScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("欢迎使用通知转发应用", fontSize = 24.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("本应用需要以下权限：\n\n- 通知访问权限\n- 应用列表权限\n- 通知发送权限 (Android 13+)\n\n请在后续页面统一授权，保障功能正常使用。", fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onContinue) {
            Text("同意并继续")
        }
    }
}
