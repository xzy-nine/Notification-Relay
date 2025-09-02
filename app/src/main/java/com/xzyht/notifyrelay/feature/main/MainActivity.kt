package com.xzyht.notifyrelay

import com.xzyht.notifyrelay.common.PermissionHelper
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import android.content.Intent
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionService
import com.xzyht.notifyrelay.feature.guide.GuideActivity
import com.xzyht.notifyrelay.feature.notification.ui.NotificationHistoryFragment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import android.os.Bundle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.useful.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import android.content.Context
import androidx.compose.ui.graphics.toArgb

class MainActivity : FragmentActivity() {
    internal var showAutoStartBanner = false
    internal var bannerMessage: String? = null

    override fun onResume() {
        super.onResume()
        showAutoStartBanner = false
        bannerMessage = null
        // 检查所有必要权限，未授权则跳转引导页
        if (!checkAllPermissions(this)) {
            android.util.Log.w("NotifyRelay", "必要权限未授权，跳转引导页")
            val intent = Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            startActivity(intent)
            finish()
            return
        }
        // 检查设备连接服务是否存活，未存活则重启
        val serviceStarted = try {
            if (!isServiceRunning("com.xzyht.notifyrelay.feature.device.service.DeviceConnectionService")) {
                DeviceConnectionService.start(this)
            }
            true
        } catch (e: Exception) {
            false
        }
        // 尝试重启通知监听服务
        try {
            val cn = android.content.ComponentName(this, "com.xzyht.notifyrelay.feature.notification.service.NotifyRelayNotificationListenerService")
            val restartIntent = Intent()
            restartIntent.component = cn
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startService(restartIntent)
        } catch (e: Exception) {
            // 服务无法启动，极可能是自启动权限被拒绝
            showAutoStartBanner = true
            bannerMessage = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
        }
        // 设备服务也无法启动时同样提示
        if (!serviceStarted) {
            showAutoStartBanner = true
            bannerMessage = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
        }
    }

    // 判断服务是否存活
    private fun isServiceRunning(serviceClassName: String): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == serviceClassName }
    }
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
        // 先用透明背景，后续在 Compose SideEffect 里动态同步
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        // 仅使用 Compose 管理主页面和通知历史页面
        setContent {
            val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            
            // 自定义错误颜色常量
            val errorColor = Color(0xFFD32F2F)
            val onErrorColor = Color.White
            
            val colors = if (isDarkTheme) darkColorScheme() else lightColorScheme()
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
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                        window.navigationBarDividerColor = colorScheme.background.toArgb()
                    }
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

    // 检查所有必要权限（与 GuideActivity 保持一致）
    private fun checkAllPermissions(context: Context): Boolean {
        return PermissionHelper.checkAllPermissions(context)
    }

    // 检查应用使用情况权限
    @Suppress("DEPRECATION")
    private fun isUsageStatsEnabled(): Boolean {
        return PermissionHelper.isUsageStatsEnabled(this)
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
                        .replace(frameLayout.id, com.xzyht.notifyrelay.feature.device.ui.DeviceListFragment(), fragmentTag)
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
                        .replace(frameLayout.id, com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment(), fragmentTag)
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
                        .replace(frameLayout.id, NotificationHistoryFragment(), fragmentTag)
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
    
    // 自定义错误颜色常量
    val errorColor = Color(0xFFD32F2F)
    val onErrorColor = Color.White
    
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 读取Activity的Banner状态
    val activity = LocalContext.current as? MainActivity
    val showBanner = activity?.showAutoStartBanner == true
    val bannerMsg = activity?.bannerMessage
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        topBar = {
            if (showBanner && !bannerMsg.isNullOrBlank()) {
                top.yukonga.miuix.kmp.basic.Surface(
                    color = errorColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        top.yukonga.miuix.kmp.basic.Icon(
                            imageVector = MiuixIcons.Useful.Settings,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(10.dp))
                        top.yukonga.miuix.kmp.basic.Text(
                            text = bannerMsg,
                            style = MiuixTheme.textStyles.body1,
                            color = onErrorColor,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        top.yukonga.miuix.kmp.basic.Button(
                            onClick = {
                                // 跳转到本应用系统详情页
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = android.net.Uri.fromParts("package", context.packageName, null)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.height(36.dp)
                        ) {
                            top.yukonga.miuix.kmp.basic.Text("前往设置")
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                items = items,
                selected = selectedTab,
                onClick = { index -> selectedTab = index },
                color = colorScheme.background,
                modifier = Modifier
                    .height(58.dp)
                    .navigationBarsPadding()
            )
        },
        containerColor = colorScheme.background
    ) { paddingValues ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .background(colorScheme.background)
                ) {
                    DeviceListFragmentView(fragmentContainerId + 100)
                }
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
    val colors = lightColorScheme()
    MiuixTheme(colors = colors) {
        MainAppFragment()
    }
}
}