package com.xzyht.notifyrelay

import com.xzyht.notifyrelay.data.Notify.NotificationRepository
import android.content.Intent
import com.xzyht.notifyrelay.service.DeviceConnectionService
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import android.os.Bundle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    private val guideLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        // 授权页返回后重新检查权限
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 权限检查，未授权则跳转引导页，等待返回后再判断
        if (!checkAllPermissions(this)) {
            android.widget.Toast.makeText(this, "请先授权所有必要权限！", android.widget.Toast.LENGTH_SHORT).show()
            val intent = Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            guideLauncher.launch(intent)
            return
        }
        // 权限检查通过后再启动前台服务，保证设备发现线程正常
        DeviceConnectionService.start(this)
        // 启动时加载本地历史通知
        NotificationRepository.init(this)

        // 沉浸式虚拟键和状态栏设置
        val window = this@MainActivity.window
        // 允许内容延伸到状态栏和导航栏区域
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // 先用默认背景色，后续在 Compose SideEffect 里动态同步
        val defaultBg = top.yukonga.miuix.kmp.theme.lightColorScheme().background.toArgb()
        window.statusBarColor = defaultBg
        window.navigationBarColor = defaultBg
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        // 仅使用 Compose 管理主页面和通知历史页面
        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
            MiuixTheme(colors = colors) {
                val colorScheme = MiuixTheme.colorScheme
                // 状态栏/导航栏图标颜色适配+背景色同步
                SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDarkTheme
                    controller.isAppearanceLightNavigationBars = !isDarkTheme
                    // 统一系统栏背景色为主题背景色
                    window.statusBarColor = colorScheme.background.toArgb()
                    window.navigationBarColor = colorScheme.background.toArgb()
                }
                // 根布局加 systemBarsPadding，避免内容被遮挡，强制背景色一致
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .systemBarsPadding()
                ) {
                    MainAppFragment(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    // onActivityResult 已废弃，已迁移到 Activity Result API

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

@Composable
fun MainAppFragment(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val fragmentContainerId = remember { android.view.View.generateViewId() }
    val items = listOf(
        NavigationItem("设备与转发", MiuixIcons.Useful.Settings),
        NavigationItem("通知历史", MiuixIcons.Basic.Check)
    )
    val colorScheme = MiuixTheme.colorScheme
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(
                items = items,
                selected = selectedTab,
                onClick = { index -> selectedTab = index },
                color = colorScheme.background,
                modifier = Modifier
                    .height(58.dp)
                    .navigationBarsPadding() // 防止被系统导航栏遮挡
            )
        },
        containerColor = colorScheme.background // Scaffold 背景色
    ) { paddingValues ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(paddingValues)
            ) {
                // 横屏：设备列表侧边栏
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .background(colorScheme.background)
                ) {
                    DeviceListFragmentView(fragmentContainerId + 100)
                }
                // 内容区
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
        } else {
            // 竖屏：设备列表顶部横排
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .heightIn(max = 90.dp)
                        .background(colorScheme.background)
                ) {
                    DeviceListFragmentView(fragmentContainerId + 100)
                }
                // 内容区
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        0 -> DeviceForwardFragmentView(fragmentContainerId)
                        1 -> NotificationHistoryFragmentView(fragmentContainerId)
                    }
                }
            }
        }
    }
@Preview(showBackground = true)
@Composable
fun MainAppPreview() {
    MiuixTheme {
        MainAppFragment()
     }
  }
}