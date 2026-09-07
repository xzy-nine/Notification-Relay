package notifyrelay.base.util

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import notifyrelay.base.util.PermissionHelper
import notifyrelay.base.util.ToastUtils

/**
 * 引导页运行时权限请求委托（单文件实现，由 [GuideActivity] 以组合方式持有，不通过接口继承）。
 *
 * 通过 [androidx.activity.result.ActivityResultLauncher] 触发系统权限弹窗，
 * 统一处理「拒绝后引导至设置页」的逻辑。涵盖引导流程中所有真正的运行时权限：
 * - [requestPostNotifications]：Android 13+（API 33）通知发送权限；
 * - [requestQueryApps]：MIUI/澎湃系统的应用列表权限（com.android.permission.GET_INSTALLED_APPS）；
 * - [requestLocalNetwork]：Android 17+（API 37）本地网络权限（实现内部有 SDK 守卫）；
 * - [requestBluetoothConnect]：Android 12+（API 31）蓝牙连接权限（可选权限页使用）。
 *
 * 全部使用现代 Activity Result API，避免与旧 [androidx.core.app.ActivityCompat.requestPermissions] 混用。
 *
 * @param activity 宿主 Activity（需在初始化期构造本类，以便注册 launcher）。
 * @param onPermissionResult 每次权限请求结果返回后的回调，用于通知 UI 重新读取权限状态
 *   （运行时权限弹窗不会触发 onResume，必须主动刷新，否则授权后 UI 不更新）。
 */
class GuidePermissionRequester(
    private val activity: ComponentActivity,
    private val onPermissionResult: () -> Unit = {},
) {
    private val postNotificationsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ToastUtils.showShortToast(activity, "已获得通知发送权限")
            } else {
                ToastUtils.showShortToast(activity, "需要通知发送权限才能发送本地通知")
            }
            onPermissionResult()
        }

    private val queryAppsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ToastUtils.showShortToast(activity, "已获得应用列表权限")
            } else {
                ToastUtils.showShortToast(activity, "需要应用列表权限才能识别本机应用")
                PermissionHelper.openAppDetailsSettings(activity)
            }
            onPermissionResult()
        }

    private val localNetworkLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ToastUtils.showShortToast(activity, "已获得本地网络权限，可用于局域网设备发现")
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, PermissionHelper.LOCAL_NETWORK_PERMISSION)) {
                    ToastUtils.showShortToast(activity, "本地网络权限被拒绝，请到设置中开启以发现局域网设备")
                    PermissionHelper.openAppDetailsSettings(activity)
                } else {
                    ToastUtils.showShortToast(activity, "需要本地网络权限才能发现局域网设备")
                }
            }
            onPermissionResult()
        }

    private val bluetoothLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ToastUtils.showShortToast(activity, "已获得蓝牙连接权限，可优化设备发现")
            } else {
                ToastUtils.showShortToast(activity, "需要蓝牙权限才能优化设备发现")
            }
            onPermissionResult()
        }

    fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestQueryApps() {
        queryAppsLauncher.launch("com.android.permission.GET_INSTALLED_APPS")
    }

    fun requestLocalNetwork() {
        if (!PermissionHelper.isLocalNetworkPermissionRequired()) return
        if (PermissionHelper.checkLocalNetworkPermission(activity)) return
        localNetworkLauncher.launch(PermissionHelper.LOCAL_NETWORK_PERMISSION)
    }

    fun requestBluetoothConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
