package notifyrelay.base.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * 权限辅助工具类
 *
 * 提供一组用于检查与请求应用在运行时常用权限的静态方法，包含通知监听、应用列表访问、通知发送、使用情况访问、蓝牙连接、悬浮窗与电池优化等权限的检查与请求逻辑。
 */
object PermissionHelper {
    /**
     * 检查所有必要权限是否已授权。
     *
     * 本方法会检查：
     * 1. 通知监听权限（应用是否列在系统已启用通知监听器中）；
     * 2. 应用列表访问权限（或使用情况访问权限，针对 MIUI/澎湃会优先检查特定权限）；
     * 3. 通知发送权限（Android 13 / API 33 及以上需要 `POST_NOTIFICATIONS`）。
     *
     * @param context 用于访问 PackageManager、Settings 及系统服务的上下文（通常传入 Activity 或 Application 的 Context）。
     * @return 如果所有必要权限均已授予则返回 true，否则返回 false。
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun checkAllPermissions(context: Context): Boolean {
        val enabledListeners =
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: ""
        val hasNotification = isNotificationListenerEnabled(context, enabledListeners)

        // 判断是否为 MIUI/澎湃系统（厂商或系统包识别）
        val isMiui = detectMiuiOrPengpai(context)

        // 检查应用列表权限
        var canQueryApps: Boolean
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            canQueryApps = apps.size > 2
            if (isMiui) {
                canQueryApps = canQueryApps && (ContextCompat.checkSelfPermission(context, "com.android.permission.GET_INSTALLED_APPS") == PackageManager.PERMISSION_GRANTED)
            }
        } catch (e: Exception) {
            canQueryApps = false
        }

        // 检查通知发送权限
        val hasPost =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        // 本地网络权限：仅 Android 17+（API 37）需要；低版本由 INTERNET 隐式授予，
        // checkLocalNetworkPermission 内部已做 SDK 守卫，低版本直接返回 true，不影响必需权限判定。
        val hasLocalNetwork = checkLocalNetworkPermission(context)

        return hasNotification && canQueryApps && hasPost && hasLocalNetwork
    }

    /**
     * 检查敏感通知权限（Android 15+）。
     *
     * @param context 用于执行权限检查的上下文。
     * @return 在 API 35 及以上，返回是否拥有 `RECEIVE_SENSITIVE_NOTIFICATIONS` 权限；在较低版本返回 true（视为不需要该权限）。
     */
    fun checkSensitiveNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 35) {
            context.checkSelfPermission("android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else {
            true // 低版本默认有权限
        }

    /**
     * 请求敏感通知权限（Android 15+）。
     *
     * 说明：Android 15 引入了对敏感通知的更细粒度控制。该方法不会直接弹出系统权限对话框，
     * 而是根据设备厂商引导用户到不同的位置或给出提示：
     * - MIUI：尝试跳转到设置页面以关闭增强型通知（Notification Assistant）以达到目标；
     * - 其他厂商：提示用户使用 ADB 命令授权（因为系统可能未提供 UI 入口）。
     *
     * @param activity 用于启动设置页面或显示提示的 Activity。
     */
    fun requestSensitiveNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 35) {
            val isMiui = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
            if (isMiui) {
                // MIUI 系统跳转关闭增强型通知
                try {
                    val intent = Intent()
                    intent.setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAssistantSettingsActivity")
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    // 跳转失败，提示手动设置
                    ToastUtils.showLongToast(activity, "请手动在设置-通知-增强型通知关闭")
                }
            } else {
                // 其他系统提示使用 ADB
                ToastUtils.showLongToast(activity, "请用adb授权: adb shell appops set ${activity.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow")
            }
        }
    }

    /**
     * 检查应用使用情况访问权限（可选）。
     *
     * 该权限用于获取设备上应用的使用情况统计（Usage Stats），某些功能需要此权限来判断应用是否处于前台等。
     *
     * @param context 用于获取 AppOpsManager 服务的上下文。
     * @return 如果 AppOps 管理器允许 `android:get_usage_stats` 则返回 true，否则返回 false。
     */
    fun isUsageStatsEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName,
            ) == android.app.AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * 检查蓝牙连接权限（Android 12+）。
     *
     * @param context 用于检查权限的上下文。
     * @return 在 API 31+（Android 12）时检查 `BLUETOOTH_CONNECT` 是否已授予，低版本始终返回 true。
     */
    fun checkBluetoothConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 检查应用是否具有悬浮窗（覆盖层）权限。
     *
     * @param context 用于调用 Settings.canDrawOverlays 的上下文。
     * @return 如果系统允许应用在其他应用上层显示窗口则返回 true，异常时返回 false。
     */
    fun checkOverlayPermission(context: Context): Boolean =
        try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }

    /**
     * 请求悬浮窗（覆盖层）权限。
     *
     * 会打开系统悬浮窗权限设置页面，用户需手动在系统设置中允许应用在其他应用之上显示。
     *
     * @param activity 用于启动设置页面的 Activity。
     */
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = "package:${activity.packageName}".toUri()
        activity.startActivity(intent)
    }

    /**
     * 检查应用是否被系统电池优化排除（即具有后台无限制运行权限）。
     *
     * @param context 用于获取 PowerManager 的上下文。
     * @return 如果应用被设置为忽略电池优化（可在后台长期运行）则返回 true，否则返回 false。
     */
    fun checkBackgroundUnlimitedPermission(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 检查通知监听服务是否已被启用（用于间接验证应用是否具有自启动/监听通知的能力）。
     *
     * @param context 用于读取 Settings.Secure 的上下文。
     * @return 当系统已启用通知监听器并包含当前应用包名时返回 true，否则返回 false。
     */
    fun checkNotificationListenerServiceCanStart(context: Context): Boolean {
        val enabledListeners =
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
        return isNotificationListenerEnabled(context, enabledListeners)
    }

    /**
     * 精确判断本应用的通知监听服务是否已在系统中启用。
     *
     * 注意：系统设置 [Settings.Secure.ENABLED_NOTIFICATION_LISTENERS] 的每条记录
     * 格式为「包名/组件类名」（多条目以 `:` 分隔）。仅比对包名会在服务类名发生
     * 历史变更时误判为已授权（旧授权记录仍含旧包名前缀），导致无法引导用户重新授权。
     * 因此这里使用 [ComponentName] 精确匹配完整组件标识。
     *
     * @param context 用于读取包信息与 PackageManager 的上下文。
     * @param enabledListeners [Settings.Secure.ENABLED_NOTIFICATION_LISTENERS] 的原始字符串，可空。
     * @return 当系统已启用本应用声明的某个 NotificationListenerService 组件时返回 true，否则返回 false。
     */
    private fun isNotificationListenerEnabled(context: Context, enabledListeners: String?): Boolean {
        if (enabledListeners.isNullOrEmpty()) return false
        val enabledSet = enabledListeners.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        if (enabledSet.isEmpty()) return false

        // 枚举本应用声明的 NotificationListenerService 组件，构造其完整 ComponentName 标识。
        val pm = context.packageManager
        val myComponentNames =
            runCatching {
                pm
                    .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
                    .services
                    ?.filter { svc ->
                        val cls = runCatching { Class.forName(svc.name) }.getOrNull()
                        cls != null &&
                            android.service.notification.NotificationListenerService::class.java
                                .isAssignableFrom(cls)
                    }?.map { ComponentName(context, it.name).flattenToString() }
                    ?: emptyList()
            }.getOrElse { emptyList() }

        if (myComponentNames.isEmpty()) {
            // 兜底：无法枚举组件时退化为仅比对包名前缀（保持旧行为）。
            return enabledSet.any { entry ->
                val pkg = entry.substringBefore("/")
                pkg == context.packageName
            }
        }

        return enabledSet.any { entry -> myComponentNames.contains(entry) }
    }

    /**
     * 读取本应用在 AndroidManifest 中声明的全部「普通/危险」权限（即 `<uses-permission>` 节点）。
     *
     * 这些权限会随版本升级而增减。通过比对「上次已同意的权限集合」即可判断本次更新是否
     * 新增了需要用户重新阅读并同意的声明式权限。
     *
     * 注意：此方法不包含运行时动态授予的「特殊权限」（如通知监听、使用情况访问、
     * 悬浮窗、所有文件管理、后台无限制等），它们由各自的检查逻辑单独处理。
     *
     * @param context 用于读取包信息的上下文。
     * @return 声明权限名集合（如 `["android.permission.INTERNET", ...]`），读取失败时返回空集。
     */
    fun getDeclaredPermissions(context: Context): Set<String> =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
        }.getOrElse { emptySet() }

    /**
     * 本地网络访问权限名（Android 17 / API 37 新增的运行时权限）。
     * 低版本系统中该权限不存在，因此始终以字符串字面量引用，避免编译/运行期依赖未定义的常量。
     */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * 判断当前设备是否需要本地网络权限。
     *
     * 仅 Android 17 (API 37) 及以上、且应用 targetSdk >= 37 时返回 true。
     * 低版本由 INTERNET 权限隐式授予本地网络访问，无需也不会请求该权限，
     * 从而确保不影响其他旧安卓版本（避免在旧设备上把该权限当作必需权限而卡死引导流程）。
     */
    fun isLocalNetworkPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= 37

    /**
     * 检查本地网络权限是否已授予。
     * 低版本（不需要该权限）直接返回 true，避免影响旧安卓设备的权限判断与引导流程。
     */
    fun checkLocalNetworkPermission(context: Context): Boolean {
        if (!isLocalNetworkPermissionRequired()) return true
        return context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 从声明权限集合中剔除「当前设备版本不需要 / 无法授予」的权限。
     *
     * 例如 ACCESS_LOCAL_NETWORK 仅在 Android 17+ 适用；在旧安卓设备上虽然 Manifest 已声明，
     * 但系统无法授予，若纳入「新增权限需重新同意」比对会导致旧设备误触发同意流程。
     * 因此低版本直接剔除该权限名，从而不影响其他旧安卓版本。
     */
    fun getApplicableDeclaredPermissions(context: Context): Set<String> {
        val declared = getDeclaredPermissions(context)
        return if (isLocalNetworkPermissionRequired()) declared else declared - LOCAL_NETWORK_PERMISSION
    }

    /**
     * 跳转到本应用详情设置页，用于引导用户在系统设置中手动授予被拒绝的权限
     * （例如用户勾选「不再询问」后的本地网络权限）。
     */
    fun openAppDetailsSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /**
     * 检查开发者选项-停用屏幕共享保护是否已开启。
     *
     * @param context 用于读取 Settings.Global 的上下文。
     * @return 如果停用屏幕共享保护已开启则返回 true，否则返回 false。
     */
    fun checkDevScreenShareProtectOff(context: Context): Boolean =
        try {
            val value = Settings.Global.getInt(context.contentResolver, "disable_screen_sharing_protection", 0)
            value == 1
        } catch (_: Exception) {
            false
        }

    /**
     * 检查文件管理权限（MANAGE_EXTERNAL_STORAGE）。
     *
     * @param context 用于检查权限的上下文。
     * @return 在 API 30+（Android 11）时检查是否具有文件管理权限，低版本始终返回 true。
     */
    fun checkManageExternalStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true // 低版本默认有权限
        }

    /**
     * 请求文件管理权限（MANAGE_EXTERNAL_STORAGE）。
     *
     * @param context 用于启动设置页面的上下文。
     */
    fun requestManageExternalStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:${context.packageName}".toUri()
            IntentUtils.startActivity(context, intent, context !is Activity)
        }
    }

    /**
     * 从 FINGERPRINT 中提取详细的 OS 版本信息。
     *
     * @return 详细的 OS 版本字符串，例如 "OS3.0.300.4.WNACNXM"。
     */
    fun getDetailedOsVersion(): String? {
        val fingerprint = Build.FINGERPRINT
        return try {
            // 直接搜索包含 "OS" 开头的版本部分
            // 例如：OS3.0.300.4.WNACNXM
            val osPattern = Regex("OS\\d+(\\.\\d+)*[\\w.]*")
            val matchResult = osPattern.find(fingerprint)

            if (matchResult != null) {
                var osVersion = matchResult.value

                // 清理结果，确保不包含多余字符
                osVersion =
                    osVersion
                        .trim()
                        .trim('/')
                        .trim(':')
                        .trim()

                return osVersion
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 比较两个版本号，判断当前版本是否大于目标版本。
     *
     * @param version 当前版本号，例如 "OS3.0.300"
     * @param target 目标版本号，例如 "OS3.0.200"
     * @return 如果当前版本大于目标版本返回 true，否则返回 false
     */
    fun isVersionGreaterThan(
        version: String?,
        target: String?,
    ): Boolean {
        if (version == null || target == null) return false

        try {
            // 去除OS前缀
            val versionNum = version.replace("OS", "")
            val targetNum = target.replace("OS", "")

            // 分割版本号
            val versionParts = versionNum.split(".").mapNotNull { it.toIntOrNull() }
            val targetParts = targetNum.split(".").mapNotNull { it.toIntOrNull() }

            // 比较版本号
            for (i in 0 until Math.max(versionParts.size, targetParts.size)) {
                val versionPart = versionParts.getOrElse(i) { 0 }
                val targetPart = targetParts.getOrElse(i) { 0 }

                if (versionPart > targetPart) return true
                if (versionPart < targetPart) return false
            }

            return false // 版本相同
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 私有工具：检测设备是否为 MIUI/澎湃（基于厂商名或系统权限信息判断）。
     *
     * @param context 用于访问 PackageManager 的上下文。
     * @return 如果判断为 MIUI/澎湃返回 true，否则返回 false。
     */
    private fun detectMiuiOrPengpai(context: Context): Boolean {
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return true
        return kotlin
            .runCatching {
                context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0).packageName == "com.lbe.security.miui"
            }.getOrElse { false }
    }

    /**
     * 检查设备是否处于锁屏状态。
     *
     * @param context 用于获取 KeyguardManager 服务的上下文。
     * @return 如果设备处于锁屏状态返回 true，否则返回 false。
     */
    fun isDeviceLocked(context: Context): Boolean =
        try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.isKeyguardLocked
        } catch (_: Exception) {
            false
        }

    /**
     * 检查指定的无障碍服务是否已启用。
     *
     * @param context 用于访问 Settings.Secure 的上下文。
     * @param accessibilityServiceName 无障碍服务的完整名称，格式为 "包名/服务类全限定名"。
     * @return 如果该无障碍服务已在系统设置中启用则返回 true，否则返回 false。
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        accessibilityServiceName: String?,
    ): Boolean {
        if (accessibilityServiceName.isNullOrEmpty()) return false
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        return enabledServices?.contains(accessibilityServiceName) == true
    }

    /**
     * 检查应用是否处于前台。
     *
     * 通过 ActivityLifecycleCallbacks 计数法判断应用当前是否在前台运行。
     * 这是目前最可靠且实时的方案，无需任何权限。
     *
     * @param context 用于注册 ActivityLifecycleCallbacks 的上下文。
     * @return 如果应用处于前台则返回 true，否则返回 false。
     */
    fun isAppInForeground(context: Context): Boolean = AppForegroundDetector.isForeground()

    /**
     * 应用前后台检测器
     */
    object AppForegroundDetector {
        @Volatile
        private var isForeground = false
        private var isInitialized = false
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

        @Synchronized
        fun initialize(context: Context) {
            if (isInitialized) return
            isInitialized = true

            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            isForeground = true
                            notifyListeners(true)
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            isForeground = false
                            notifyListeners(false)
                        }
                    },
                )
            } catch (e: Exception) {
                Logger.e("AppForegroundDetector", "初始化失败", e)
            }
        }

        fun isForeground(): Boolean = isForeground

        fun addListener(listener: (Boolean) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (Boolean) -> Unit) {
            listeners.remove(listener)
        }

        private fun notifyListeners(foreground: Boolean) {
            listeners.forEach { it(foreground) }
        }
    }
}
