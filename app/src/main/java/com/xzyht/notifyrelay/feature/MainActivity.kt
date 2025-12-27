package com.xzyht.notifyrelay.feature

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.xzyht.notifyrelay.common.NotifyRelayTheme
import com.xzyht.notifyrelay.common.PermissionHelper
import com.xzyht.notifyrelay.common.SetupSystemBars
import com.xzyht.notifyrelay.common.core.repository.AppRepository
import com.xzyht.notifyrelay.common.core.util.IntentUtils
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.core.util.ServiceManager
import com.xzyht.notifyrelay.common.core.util.ToastUtils
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment
import com.xzyht.notifyrelay.feature.device.ui.DeviceListFragment
import com.xzyht.notifyrelay.feature.notification.ui.NotificationHistoryFragment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.Check
import top.yukonga.miuix.kmp.icon.icons.useful.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

class MainActivity : FragmentActivity() {
    internal var showAutoStartBanner = false
    internal var bannerMessage: String? = null

    /**
     * 检查权限并启动服务
     */
    private fun checkPermissionsAndStartServices() {
        showAutoStartBanner = false
        bannerMessage = null

        // 使用 PermissionHelper 检查权限
        if (!PermissionHelper.checkAllPermissions(this)) {
            Logger.w("NotifyRelay", "必要权限未授权，跳转引导页")
            val intent = Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            startActivity(intent)
            finish()
            return
        }

        // 使用 ServiceManager 启动服务
        val result = ServiceManager.startAllServices(this)
        val serviceStarted = result.first
        val errorMessage = result.second as? String
        if (errorMessage != null) {
            showAutoStartBanner = true
            bannerMessage = errorMessage
        }

        // 如果设备服务无法启动，也显示提示
        if (!serviceStarted) {
            showAutoStartBanner = true
            bannerMessage = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStartServices()
    }

    private val guideLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        // 授权页返回后重新检查权限
        recreate()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先设置沉浸式虚拟键和状态栏，然后立即显示UI
        WindowCompat.setDecorFitsSystemWindows(this.window, false)
        this.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // 颜色设置放到 Compose SideEffect 里统一管理

        // 仅使用 Compose 管理主页面和通知历史页面，先显示UI再进行初始化
        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            // 使用统一的主题
            NotifyRelayTheme(darkTheme = isDarkTheme) {
                val colorScheme = MiuixTheme.colorScheme
                // 设置系统栏外观
                SetupSystemBars(isDarkTheme)
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

        // 在UI显示后进行权限检查
        if (!PermissionHelper.checkAllPermissions(this)) {
            ToastUtils.showShortToast(this, "请先授权所有必要权限！")
            val intent = Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            guideLauncher.launch(intent)
            return
        }

        // 在后台线程初始化 NotificationRepository 和启动服务
        GlobalScope.launch {
            // 启动时加载本地历史通知
            NotificationRepository.init(this@MainActivity)
            
            // 预加载应用列表，避免在UI线程中同步加载
            AppRepository.loadApps(this@MainActivity)
            
            // 启动服务并更新Banner状态
            startServicesAndUpdateBanner()
        }
    }
    
    /**
     * 在后台线程启动服务并更新Banner状态
     */
    private suspend fun startServicesAndUpdateBanner() {
        // 使用 ServiceManager 启动服务
        val result = ServiceManager.startAllServices(this)
        val serviceStarted = result.first
        val errorMessage = result.second as? String
        if (errorMessage != null) {
            withContext(Dispatchers.Main) {
                showAutoStartBanner = true
                bannerMessage = errorMessage
            }
        }

        // 如果设备服务无法启动，也显示提示
        if (!serviceStarted) {
            withContext(Dispatchers.Main) {
                showAutoStartBanner = true
                bannerMessage = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
            }
        }
    }
}
@Composable
fun <T : androidx.fragment.app.Fragment> FragmentContainerView(
    fragmentContainerId: Int,
    fragmentTag: String,
    fragmentFactory: () -> T
) {
    val colorScheme = MiuixTheme.colorScheme
    val fragmentManager = (LocalContext.current as? FragmentActivity)?.supportFragmentManager
    Box(modifier = Modifier.fillMaxSize().background(colorScheme.background)) {
        AndroidView(
            factory = { context ->
                val frameLayout = FrameLayout(context)
                frameLayout.id = fragmentContainerId
                fragmentManager?.beginTransaction()
                    ?.replace(frameLayout.id, fragmentFactory(), fragmentTag)
                    ?.commitAllowingStateLoss()
                frameLayout
            },
            update = { }
        )
    }
}

@Composable
fun DeviceListFragmentView(fragmentContainerId: Int) {
    FragmentContainerView(
        fragmentContainerId = fragmentContainerId,
        fragmentTag = "DeviceListFragment",
        fragmentFactory = { DeviceListFragment() }
    )
}

@Composable
fun DeviceForwardFragmentView(fragmentContainerId: Int) {
    FragmentContainerView(
        fragmentContainerId = fragmentContainerId,
        fragmentTag = "DeviceForwardFragment",
        fragmentFactory = { DeviceForwardFragment() }
    )
}

@Composable
fun NotificationHistoryFragmentView(fragmentContainerId: Int) {
    FragmentContainerView(
        fragmentContainerId = fragmentContainerId,
        fragmentTag = "NotificationHistoryFragment",
        fragmentFactory = { NotificationHistoryFragment() }
    )
}

@Composable
fun MainAppFragment(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val fragmentContainerId = remember { View.generateViewId() }
    val items = listOf(
        NavigationItem("设备与转发", MiuixIcons.Useful.Settings),
        NavigationItem("通知历史", MiuixIcons.Basic.Check)
    )
    val colorScheme = MiuixTheme.colorScheme
    
            // 自定义错误颜色常量（用于顶部 banner）
            val errorColor = Color(0xFFD32F2F)
            val onErrorColor = Color.White
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 读取Activity的Banner状态
    val activity = LocalContext.current as? MainActivity
    val showBanner = activity?.showAutoStartBanner == true
    val bannerMsg = activity?.bannerMessage
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        popupHost = { MiuixPopupHost() },
        topBar = {
            if (showBanner && !bannerMsg.isNullOrBlank()) {
                Surface(
                    color = errorColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Useful.Settings,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = bannerMsg,
                            style = MiuixTheme.textStyles.body1,
                            color = onErrorColor,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                // 跳转到本应用系统详情页
                                IntentUtils.startActivity(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null), true)
                            },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("前往设置")
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
}