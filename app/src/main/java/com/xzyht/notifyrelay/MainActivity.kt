package com.xzyht.notifyrelay

import com.xzyht.notifyrelay.data.Notify.NotificationRepository

import android.content.Intent
import com.xzyht.notifyrelay.service.DeviceConnectionService

import android.os.Bundle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.useful.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 权限检查，未授权则跳转引导页
        if (!checkAllPermissions(this)) {
            android.widget.Toast.makeText(this, "请先授权所有必要权限！", android.widget.Toast.LENGTH_SHORT).show()
            // 检查通知监听权限
            if (!isNotificationListenerEnabled()) {
                android.widget.Toast.makeText(this, "请在系统设置中授权通知访问权限！", android.widget.Toast.LENGTH_LONG).show()
                val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            }
            // 启动前台服务，保持设备在线（推荐统一调用 Service 的静态方法）
            DeviceConnectionService.start(this)
            // 检查通知发送权限（Android 13+）
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
                }
            }
            // 检查应用使用情况权限
            if (!isUsageStatsEnabled()) {
                android.widget.Toast.makeText(this, "请在系统设置中授权应用使用情况访问权限！", android.widget.Toast.LENGTH_LONG).show()
                val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
            val intent = android.content.Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            startActivity(intent)
            finish()
            return
        }
        // 启动时加载本地历史通知
        NotificationRepository.init(this)

        // 使用 Fragment 管理主页面和通知历史页面
        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
            MiuixTheme(colors = colors) {
                val window = this@MainActivity.window
                val colorScheme = MiuixTheme.colorScheme
                // 状态栏背景色与页面背景色完全一致（background）
                SideEffect {
                    @Suppress("DEPRECATION")
                    // TODO: 替换更优的状态栏着色方案，避免使用已废弃API
                    window.statusBarColor = colorScheme.background.toArgb()
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDarkTheme
                }
                MainAppFragment()
            }
        }
    }

    // 检查通知监听权限
    private fun isNotificationListenerEnabled(): Boolean {
        val cn = android.content.ComponentName(this, "com.xzyht.notifyrelay.data.Notify.NotifyRelayNotificationListenerService")
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    // 检查应用使用情况权限
    @Suppress("DEPRECATION")
    private fun isUsageStatsEnabled(): Boolean {
        val appOps = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}

@Composable
fun MainAppFragment() {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val fragmentContainerId = remember { android.view.View.generateViewId() }
    val items = listOf(
        NavigationItem("设备与转发", MiuixIcons.Useful.Settings),
        NavigationItem("通知历史", MiuixIcons.Basic.Check)
    )
    val colorScheme = MiuixTheme.colorScheme
    Scaffold(
        bottomBar = {
            NavigationBar(
                items = items,
                selected = selectedTab,
                onClick = { selectedTab = it }
            )
        },
        containerColor = colorScheme.background
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(paddingValues)
        ) {
            // 设备列表区域，独立Fragment
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(colorScheme.background)
            ) {
                DeviceListFragmentView(fragmentContainerId + 100)
            }
            // 内容区，tab切换
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when (selectedTab) {
                    0 -> DeviceForwardFragmentView(fragmentContainerId)
                    1 -> NotificationHistoryFragmentView(fragmentContainerId)
                }
            }
        }
    }
@Composable
fun DeviceListFragmentView(fragmentContainerId: Int) {
    val colorScheme = MiuixTheme.colorScheme
    val fragmentManager = (LocalContext.current as? FragmentActivity)?.supportFragmentManager
    val fragmentTag = "DeviceListFragment"
    Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        AndroidView(
            factory = { context ->
                val frameLayout = android.widget.FrameLayout(context)
                frameLayout.id = fragmentContainerId
                fragmentManager?.let { fm ->
                    fm.beginTransaction()
                        .replace(frameLayout.id, com.xzyht.notifyrelay.DeviceListFragment(), fragmentTag)
                        .commitAllowingStateLoss()
                }
                frameLayout
            },
            update = { }
        )
    }
}
}

@Composable
fun DeviceForwardFragmentView(fragmentContainerId: Int) {
    val colorScheme = MiuixTheme.colorScheme
    val fragmentManager = (LocalContext.current as? FragmentActivity)?.supportFragmentManager
    val fragmentTag = "DeviceForwardFragment"
    Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        AndroidView(
            factory = { context ->
                val frameLayout = android.widget.FrameLayout(context)
                frameLayout.id = fragmentContainerId
                fragmentManager?.let { fm ->
                    fm.beginTransaction()
                        .replace(frameLayout.id, com.xzyht.notifyrelay.DeviceForwardFragment(), fragmentTag)
                        .commitAllowingStateLoss()
                }
                frameLayout
            },
            update = { }
        )
    }
}

@Composable
fun NotificationHistoryFragmentView(fragmentContainerId: Int) {
    val colorScheme = MiuixTheme.colorScheme
    val fragmentManager = (LocalContext.current as? FragmentActivity)?.supportFragmentManager
    val fragmentTag = "NotificationHistoryFragment"
    Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        AndroidView(
            factory = { context ->
                val frameLayout = android.widget.FrameLayout(context)
                frameLayout.id = fragmentContainerId
                fragmentManager?.let { fm ->
                    // 每次都 replace，保证 fragment attach
                    fm.beginTransaction()
                        .replace(frameLayout.id, com.xzyht.notifyrelay.NotificationHistoryFragment(), fragmentTag)
                        .commitAllowingStateLoss()
                }
                frameLayout
            },
            update = { }
        )
    }
}
@Preview(showBackground = true)
@Composable
fun MainAppPreview() {
    MiuixTheme {
        MainAppFragment()
    }
}