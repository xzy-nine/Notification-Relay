package com.xzyht.notifyrelay.feature.device.ui

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig
import com.xzyht.notifyrelay.feature.notification.ui.filter.UILocalFilter
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AddKeywordDialog
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AppPickerDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme


class DeviceForwardFragment : Fragment() {
    // 认证通过设备持久化key
    private val KEY_AUTHED_UUIDS = "authed_device_uuids"

    // 应用安装/卸载监听器
    private val appChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_PACKAGE_ADDED -> {
                    // 应用安装时清除缓存，下次使用时会重新加载最新的应用列表和图标
                    AppRepository.clearCache()
                    if (BuildConfig.DEBUG) Log.d("DeviceForwardFragment", "应用安装，清除缓存")
                }
                android.content.Intent.ACTION_PACKAGE_REMOVED -> {
                    // 应用卸载时清除缓存，下次使用时会重新加载最新的应用列表和图标
                    AppRepository.clearCache()
                    if (BuildConfig.DEBUG) Log.d("DeviceForwardFragment", "应用卸载，清除缓存")
                }
            }
        }
    }

    companion object {
        // 全局单例，保证同一进程内所有页面共享同一个 deviceManager
        @Volatile
        private var sharedDeviceManager: DeviceConnectionManager? = null
        fun getDeviceManager(context: android.content.Context): DeviceConnectionManager {
            return sharedDeviceManager ?: synchronized(this) {
                sharedDeviceManager ?: DeviceConnectionManager(context.applicationContext).also { sharedDeviceManager = it }
            }
        }
    }

    // 加载已认证设备uuid集合
    fun loadAuthedUuids(): Set<String> {
        return StorageManager.getStringSet(requireContext(), KEY_AUTHED_UUIDS, emptySet(), StorageManager.PrefsType.DEVICE)
    }

    // 保存已认证设备uuid集合
    fun saveAuthedUuids(uuids: Set<String>) {
        StorageManager.putStringSet(requireContext(), KEY_AUTHED_UUIDS, uuids, StorageManager.PrefsType.DEVICE)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // 注册应用安装/卸载监听器
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        requireContext().registerReceiver(appChangeReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销监听器
        requireContext().unregisterReceiver(appChangeReceiver)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "onCreateView called")
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    DeviceForwardScreen(
                        deviceManager = getDeviceManager(requireContext())
                    )
                }
            }
        }
    }
@Composable
fun DeviceForwardScreen(
    deviceManager: DeviceConnectionManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 触发一次读取以避免未使用参数提示（保持原有持久化行为）
        // loadAuthedUuids/saveAuthedUuids 留作接口使用，具体操作在需要时调用
    // 确保RemoteFilterConfig已加载
    if (!RemoteFilterConfig.isLoaded) {
        RemoteFilterConfig.load(context)
        RemoteFilterConfig.isLoaded = true
    }
    // 手动发现提示相关状态
    val manualDiscoveryPrompt = remember { mutableStateOf<String?>(null) }
    val snackbarVisible = remember { mutableStateOf(false) }
    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "DeviceForwardScreen Composable launched")
    // TabRow相关状态
    val tabTitles = listOf("远程通知过滤", "聊天测试", "本地通知过滤")
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    // NotificationFilterPager 的状态由其内部组件管理
        // 只监听全局选中设备（订阅由需要的组件进行）
    // 连接弹窗与错误弹窗相关状态（暂不在此处管理具体弹窗）
    // 设备认证、删除等逻辑已交由DeviceListFragment统一管理

    // Miuix风格通知弹窗
    if (snackbarVisible.value && manualDiscoveryPrompt.value != null) {
        Surface(
            color = MiuixTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    manualDiscoveryPrompt.value!!,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "关闭",
                    onClick = { snackbarVisible.value = false }
                )
            }
        }
        // 自动消失
        LaunchedEffect(manualDiscoveryPrompt.value) {
            kotlinx.coroutines.delay(5000)
            snackbarVisible.value = false
        }
    }
    val colorScheme = MiuixTheme.colorScheme
    // textStyles 由各子组件或直接使用 MiuixTheme.textStyles 引用，避免未使用警告
    // 只监听全局选中设备（保持调用以触发任何订阅逻辑）
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    // 读取 value 以保持订阅/避免未使用变量警告
    selectedDeviceState.value
    // 复刻lancomm事件监听风格，Compose事件流监听消息
    // 远程通知过滤与复刻到系统通知中心

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(12.dp)
    ) {
        TabRow(
            tabs = tabTitles,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
            modifier = Modifier.fillMaxWidth()
        )
        // 移除Spacer，改为内容区顶部padding
        when (selectedTabIndex) {
            0 -> {
                // 远程通知过滤 Tab：重构为复用 UIRemoteFilter 组件
                com.xzyht.notifyrelay.feature.notification.ui.filter.UIRemoteFilter()
            }
            1 -> {
                // 聊天测试 Tab：独立到 UIChatTest 组件
                com.xzyht.notifyrelay.feature.notification.ui.filter.UIChatTest(deviceManager = deviceManager)
            }
            2 -> {
                // 本地通知过滤 Tab
                UILocalFilter()
            }
        }
    }
}}

