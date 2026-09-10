package com.xzyht.notifyrelay.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.superisland.notification.LiveUpdatesNotificationManager
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.feature.appslist.AppRepository
import com.xzyht.notifyrelay.feature.media.service.MediaProjectionForegroundService
import com.xzyht.notifyrelay.feature.appslist.launch.AppLaunchManager
import com.xzyht.notifyrelay.ui.common.NotifyRelayTheme
import com.xzyht.notifyrelay.ui.common.SetupSystemBars
import com.xzyht.notifyrelay.ui.navigation.LocalNavigator
import com.xzyht.notifyrelay.ui.navigation.Route
import com.xzyht.notifyrelay.ui.navigation.rememberNavigator
import com.xzyht.notifyrelay.ui.screen.DeviceForwardScreen
import com.xzyht.notifyrelay.ui.screen.DeviceListScreen
import com.xzyht.notifyrelay.ui.screen.DeviceListScreenState
import com.xzyht.notifyrelay.ui.screen.HistoryScreen
import com.xzyht.notifyrelay.ui.screen.ScrcpyAdvancedScreen
import com.xzyht.notifyrelay.ui.screen.ScrcpyVirtualButtonOrderScreen
import com.xzyht.notifyrelay.ui.screen.SettingsAboutScreen
import com.xzyht.notifyrelay.ui.screen.SettingsAppearanceScreen
import com.xzyht.notifyrelay.ui.screen.SettingsLocalFilterScreen
import com.xzyht.notifyrelay.ui.screen.SettingsRemoteFilterScreen
import com.xzyht.notifyrelay.ui.screen.SettingsScrcpyScreen
import com.xzyht.notifyrelay.ui.screen.SettingsScreen
import com.xzyht.notifyrelay.ui.screen.SettingsSuperIslandScreen
import io.github.miuzarte.scrcpyforandroid.pages.ShortcutLaunchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import notifyrelay.base.util.IntentUtils
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.ThemeSettingsManager
import notifyrelay.base.util.ToastUtils
import notifyrelay.core.util.ServiceManager
import notifyrelay.data.config.DeviceInfoManager
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : FragmentActivity() {
    internal val showAutoStartBanner = mutableStateOf(false)
    internal val bannerMessage = mutableStateOf<String?>(null)

    // 屏幕捕获授权结果，等待本应用前台且媒体投影前台服务就绪后处理
    private var pendingScreenCapture: Pair<Int, Intent>? = null
    private var registeredOnForegroundReady: (() -> Unit)? = null
    private var registeredOnRequestMediaProjection: (() -> Unit)? = null

    private fun processPendingScreenCapture() {
        if (pendingScreenCapture == null) return
        try {
            val onForegroundReady: () -> Unit = { handleScreenCaptureReady() }
            registeredOnForegroundReady = onForegroundReady
            MediaProjectionForegroundService.onForegroundReady = onForegroundReady
            startForegroundService(Intent(this, MediaProjectionForegroundService::class.java))
        } catch (e: Exception) {
            Logger.e("NotifyRelay", "屏幕捕获前台服务启动失败，带回前台重试", e)
            bringMainActivityToFront()
        }
    }

    private fun handleScreenCaptureReady() {
        val pending = pendingScreenCapture ?: return
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(pending.first, pending.second)
            if (projection != null) {
                pendingScreenCapture = null
                val deviceManager = DeviceConnectionManager.getInstance(this)
                deviceManager.audioRelayPlayer.stopSendCapture()
                NativeCore.mediaProjection?.stop()
                NativeCore.mediaProjection = projection
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            // 仅当被停止的投影仍是当前投影时才清理捕获，
                            // 避免主动 stop 旧投影时其 onStop 回调误杀新会话的捕获。
                            if (NativeCore.mediaProjection === projection) {
                                NativeCore.mediaProjection = null
                                deviceManager.audioRelayPlayer.stopSendCapture()
                            }
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                deviceManager.startPendingAudioRelaySend()
            } else {
                pendingScreenCapture = null
                stopService(Intent(this, MediaProjectionForegroundService::class.java))
            }
        } catch (e: Exception) {
            Logger.e("NotifyRelay", "屏幕捕获授权后启动失败", e)
            pendingScreenCapture = null
            stopService(Intent(this, MediaProjectionForegroundService::class.java))
        }
    }

    private fun bringMainActivityToFront() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private suspend fun checkPermissionsAndStartServices() {
        withContext(Dispatchers.Main) {
            showAutoStartBanner.value = false
            bannerMessage.value = null
        }

        val granted =
            withContext(Dispatchers.IO) {
                PermissionHelper.checkAllPermissions(this@MainActivity)
            }
        if (!granted) {
            Logger.w("NotifyRelay", "必要权限未授权，跳转引导页")
            withContext(Dispatchers.Main) {
                val intent = Intent(this@MainActivity, GuideActivity::class.java)
                intent.putExtra("from", "MainActivity")
                intent.putExtra("reauth", true)
                startActivity(intent)
                finish()
            }
            return
        }

        val result =
            withContext(Dispatchers.IO) {
                ServiceManager.startAllServices(this@MainActivity)
            }
        val serviceStarted = result.first
        val errorMessage = result.second
        withContext(Dispatchers.Main) {
            if (errorMessage != null) {
                showAutoStartBanner.value = true
                bannerMessage.value = errorMessage
            }

            if (!serviceStarted) {
                showAutoStartBanner.value = true
                bannerMessage.value = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        processPendingScreenCapture()
        // 后台执行权限检查和服务启动，避免阻塞 UI 线程
        lifecycleScope.launch(Dispatchers.Default) {
            checkPermissionsAndStartServices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (MediaProjectionForegroundService.onForegroundReady === registeredOnForegroundReady) {
            MediaProjectionForegroundService.onForegroundReady = null
        }
        registeredOnForegroundReady = null
        if (DeviceConnectionManager.getInstance(this).onRequestMediaProjection === registeredOnRequestMediaProjection) {
            DeviceConnectionManager.getInstance(this).onRequestMediaProjection = null
        }
        registeredOnRequestMediaProjection = null
        pendingScreenCapture = null
    }

    private val guideLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            recreate()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                pendingScreenCapture = result.resultCode to result.data!!
                processPendingScreenCapture()
            } else {
                stopService(Intent(this, MediaProjectionForegroundService::class.java))
            }
        }

    // 屏幕声音捕获（AudioPlaybackCapture）按官方要求需持有 RECORD_AUDIO 权限，
    // 在发起 MediaProjection 授权前一并请求，拒绝时明确提示而非静默失败。
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchScreenCapture()
            } else {
                ToastUtils.showShortToast(this, "缺少录音权限，无法捕获屏幕声音")
            }
        }

    private fun launchScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ShortcutLaunchActivity.setAppLaunchCallback { deviceIp, packageName, displayId ->
            val deviceManager = DeviceConnectionManager.getInstance(this)
            val devices = deviceManager.getAuthenticatedOnlineDevices()
            val targetDevice = devices.find { it.ip == deviceIp }
            if (targetDevice != null) {
                Logger.d("MainActivity", "发送应用启动请求: $packageName 到 ${targetDevice.displayName}, displayId: $displayId")
                AppLaunchManager.sendAppLaunchRequest(this, deviceManager, targetDevice, packageName, displayId)
            } else {
                Logger.w("MainActivity", "未找到目标设备: $deviceIp")
            }
        }

        DeveloperModeActivity.initLogConfig(this)
        DeveloperModeActivity.initDebugUiConfig(this)

        PermissionHelper.AppForegroundDetector.initialize(this)

        WindowCompat.setDecorFitsSystemWindows(this.window, false)
        this.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        setContent {
            val navigator = rememberNavigator(Route.Main)
            val context = LocalContext.current
            val systemDarkTheme = isSystemInDarkTheme()
            val themeBaseIndex = remember { mutableIntStateOf(ThemeSettingsManager.getThemeBaseIndex(context)) }

            val isDarkTheme =
                when (themeBaseIndex.intValue) {
                    ThemeSettingsManager.THEME_LIGHT -> false
                    ThemeSettingsManager.THEME_DARK -> true
                    else -> systemDarkTheme
                }

            DisposableEffect(context) {
                val listener =
                    ThemeSettingsManager.ThemeChangeListener { newBaseIndex ->
                        themeBaseIndex.intValue = newBaseIndex
                    }
                ThemeSettingsManager.addThemeChangeListener(context, listener)
                onDispose {
                    ThemeSettingsManager.removeThemeChangeListener(context, listener)
                }
            }

            NotifyRelayTheme(darkTheme = isDarkTheme) {
                val colorScheme = MiuixTheme.colorScheme
                SetupSystemBars(isDarkTheme)

                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(colorScheme.background),
                    ) {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator(),
                                ),
                            onBack = {
                                if (navigator.backStackSize() > 1) {
                                    navigator.pop()
                                }
                            },
                            entryProvider =
                                entryProvider {
                                    entry<Route.Main> { MainScreen(navigator) }
                                    entry<Route.History> { HistoryScreen(navigator) }
                                    entry<Route.Settings> { SettingsScreen() }
                                    entry<Route.ScrcpyAdvanced> { ScrcpyAdvancedScreen(navigator) }
                                    entry<Route.ScrcpyVirtualButtonOrder> { ScrcpyVirtualButtonOrderScreen(navigator) }
                                    entry<Route.SettingsRemoteFilter> {
                                        SettingsRemoteFilterScreen()
                                    }
                                    entry<Route.SettingsLocalFilter> {
                                        SettingsLocalFilterScreen()
                                    }
                                    entry<Route.SettingsSuperIsland> {
                                        SettingsSuperIslandScreen()
                                    }
                                    entry<Route.SettingsScrcpy> {
                                        SettingsScrcpyScreen()
                                    }
                                    entry<Route.SettingsAbout> {
                                        SettingsAboutScreen()
                                    }
                                    entry<Route.SettingsAppearance> {
                                        SettingsAppearanceScreen()
                                    }
                                },
                        )
                    }
                }
            }
        }

        if (!PermissionHelper.checkAllPermissions(this)) {
            ToastUtils.showShortToast(this, "请先授权所有必要权限！")
            val intent = Intent(this, GuideActivity::class.java)
            intent.putExtra("from", "MainActivity")
            guideLauncher.launch(intent)
            return
        }

        val projectionRequestCallback: () -> Unit = {
            Handler(Looper.getMainLooper()).post {
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    return@post
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    launchScreenCapture()
                }
            }
        }
        registeredOnRequestMediaProjection = projectionRequestCallback
        DeviceConnectionManager.getInstance(this).onRequestMediaProjection = projectionRequestCallback

        // 后台初始化，避免阻塞 UI 线程
        lifecycleScope.launch(Dispatchers.Default) {
            DeviceConnectionManager.getInstance(this@MainActivity)
            DeviceInfoManager.generateDeviceInfoFile(this@MainActivity)
            LiveUpdatesNotificationManager.initialize(this@MainActivity)
            NotificationRepository.init(this@MainActivity)
            AppRepository.loadApps(this@MainActivity)
            startServicesAndUpdateBanner()
        }
    }

    private suspend fun startServicesAndUpdateBanner() {
        val result = ServiceManager.startAllServices(this)
        val serviceStarted = result.first
        val errorMessage = result.second
        if (errorMessage != null) {
            withContext(Dispatchers.Main) {
                showAutoStartBanner.value = true
                bannerMessage.value = errorMessage
            }
        }

        if (!serviceStarted) {
            withContext(Dispatchers.Main) {
                showAutoStartBanner.value = true
                bannerMessage.value = "服务无法启动，可能因系统自启动/后台运行权限被拒绝。请前往系统设置手动允许自启动、后台运行和电池优化白名单，否则通知转发将无法正常工作。"
            }
        }
    }
}

@Composable
fun MainScreen(navigator: com.xzyht.notifyrelay.ui.navigation.Navigator) {
    val colorScheme = MiuixTheme.colorScheme

    val errorColor = MiuixTheme.colorScheme.error
    val onErrorColor = MiuixTheme.colorScheme.onError

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val activity = LocalActivity.current as? MainActivity
    val showBanner = activity?.showAutoStartBanner?.value == true
    val bannerMsg = activity?.bannerMessage?.value
    val context = LocalContext.current

    val deviceListState = remember { DeviceListScreenState() }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    MainScreenBackHandler(selectedTab, pagerState, navigator, deviceListState)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (showBanner && !bannerMsg.isNullOrBlank()) {
                    Surface(
                        color = errorColor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = bannerMsg,
                                style = MiuixTheme.textStyles.body1,
                                color = onErrorColor,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    IntentUtils.startActivity(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null), true)
                                },
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("前往设置")
                            }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(
                    color = colorScheme.background,
                    modifier =
                        Modifier
                            .height(75.dp)
                            .navigationBarsPadding(),
                ) {
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selectedTab == 0,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        icon = MiuixIcons.Community,
                        label = "历史",
                    )
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selectedTab == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        icon = MiuixIcons.Settings,
                        label = "设备互联与增强",
                    )
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selectedTab == 2,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2)
                            }
                        },
                        icon = MiuixIcons.Tune,
                        label = "设置",
                    )
                }
            },
            containerColor = colorScheme.background,
        ) { paddingValues ->
            if (isLandscape) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.background)
                            .padding(paddingValues),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(220.dp)
                                .fillMaxHeight()
                                .background(colorScheme.background),
                    ) {
                        DeviceListScreen(navigator, deviceListState)
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        userScrollEnabled = false,
                    ) { page ->
                        when (page) {
                            0 -> HistoryScreen(navigator)
                            1 -> DeviceForwardScreen()
                            2 -> SettingsScreen()
                        }
                    }
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.background)
                            .padding(paddingValues),
                ) {
                    DeviceListScreen(navigator, deviceListState)
                    HorizontalPager(
                        state = pagerState,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        userScrollEnabled = false,
                    ) { page ->
                        when (page) {
                            0 -> HistoryScreen(navigator)
                            1 -> DeviceForwardScreen()
                            2 -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    selectedTab: Int,
    pagerState: PagerState,
    navigator: com.xzyht.notifyrelay.ui.navigation.Navigator,
    deviceListState: DeviceListScreenState,
) {
    val activity = LocalActivity.current as? MainActivity
    var backPressedTime by remember { mutableLongStateOf(0L) }
    val exitInterval = 2000L
    val coroutineScope = rememberCoroutineScope()

    val isBackHandlerEnabled by remember {
        derivedStateOf {
            navigator.current() is Route.Main &&
                navigator.backStackSize() == 1
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isBackHandlerEnabled,
        onBackCompleted = {
            if (deviceListState.hasAnyDialogShowing()) {
                deviceListState.dismissAllDialogs()
            } else if (selectedTab != 0) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(0)
                }
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < exitInterval) {
                    activity?.finish()
                } else {
                    ToastUtils.showShortToast(activity ?: return@NavigationBackHandler, "再次返回以退出应用")
                    backPressedTime = currentTime
                }
            }
        },
    )
}
