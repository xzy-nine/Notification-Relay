package github.xzynine.superislandui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import github.xzynine.superislandui.model.core.SuperIslandData
import notifyrelay.base.util.Logger
import notifyrelay.core.util.image.ImageUtils
import notifyrelay.data.StorageManager
import org.json.JSONObject

/**
 * 基于小米超级岛文档（miui.focus.param）进行精确解析的实现。
 * 功能：
 *  - 判断系统是否支持岛通知（isSupportIsland）
 *  - 查询焦点通知协议版本（getFocusProtocolVersion）
 *  - 查询应用是否有焦点通知权限（hasFocusPermission）
 *  - 从通知 extras 中提取 miui.focus.param 内容并解析 param_v2 内容
 */
object SuperIslandManager {
    private const val STORAGE_KEY = "superisland_enabled"

    /**
     * 检查用户/配置是否启用了超级岛读取
     */
    private fun isEnabled(context: Context): Boolean =
        try {
            StorageManager.getBoolean(context, STORAGE_KEY, true)
        } catch (_: Exception) {
            true
        }

    /**
     * 获取焦点通知协议版本：Settings.System.getInt(notification_focus_protocol)
     */
    fun getFocusProtocolVersion(context: Context): Int =
        try {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 获取聚焦协议版本失败: ${e.message}")
            0
        }

    /**
     * 调用 content://miui.statusbar.notification.public canShowFocus 判断应用是否有焦点通知权限
     */
    fun hasFocusPermission(context: Context): Boolean =
        try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val extras = Bundle()
            extras.putString("package", context.packageName)
            val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
            bundle?.getBoolean("canShowFocus", false) ?: false
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 查询应用聚焦权限失败: ${e.message}")
            false
        }

    /**
     * 解析 miui.focus.param（JSON string），返回结构化 SuperIslandData；不存在则返回 null。
     * 按文档，miui.focus.param 中包含 param_v2 字段，内部可能含 param_island / baseInfo / aodTitle 等字段。
     */
    fun extractSuperIslandData(
        sbn: StatusBarNotification,
        context: Context,
    ): SuperIslandData? {
        try {
            if (!isEnabled(context)) return null

            val extras = sbn.notification.extras ?: return null

            // 首先检查 miui.focus.param（文档中明确使用此键）
            val islandParamStr = extras.getString("miui.focus.param") ?: extras.getString("miui_focus_param")
            val pkg = sbn.packageName

            // 若不存在 miui.focus.param，则尝试检查 mipush extra 名称或图片键
            if (islandParamStr.isNullOrEmpty()) {
                // 检查是否有 miui.focus.pics 或 miui.focus.pic_xxx 等图片键
                if (extras.getBundle("miui.focus.pics") == null) {
                    // 也支持 MIPUSH 通过 extra("miui.focus.param", params) 的上报
                    // 如果都没有，则非超级岛通知
                    return null
                }
            }

            // 解析 JSON（如果有）
            var title: String? = null
            var text: String? = null
            var appName: String? = null
            // 系统短信App在锁屏状态下会把真实验证码放在 verify_code 字段
            val verifyCode = extras.getString("verify_code")
            val rawExtras = mutableMapOf<String, Any?>()

            if (!islandParamStr.isNullOrEmpty()) {
                try {
                    val root = JSONObject(islandParamStr)
                    val pv = if (root.has("param_v2")) root.getJSONObject("param_v2") else root

                    // 锁屏态验证码在 param_v2 中会被系统替换为 ******，这里用 verify_code 的真实值回填 raw，
                    // 使接收端（PC/其他设备）直接按 raw 渲染即可显示真实验证码，而不依赖各自本地替换
                    if (!verifyCode.isNullOrEmpty()) {
                        replaceVerifyCodePlaceholder(pv, verifyCode)
                    }

                    // 提取 baseInfo (焦点通知数据) 中的 title/content
                    if (pv.has("baseInfo")) {
                        val base = pv.getJSONObject("baseInfo")
                        val tBaseTitle = base.optString("title", "")
                        if (tBaseTitle.isNotEmpty()) title = tBaseTitle
                        val tBaseContent = base.optString("content", "")
                        if (tBaseContent.isNotEmpty()) text = tBaseContent
                    }

                    // aodTitle 优先用于息屏场景的摘要
                    if (pv.has("aodTitle") && (title == null || title.isEmpty())) {
                        val tAod = pv.optString("aodTitle", "")
                        if (tAod.isNotEmpty()) title = tAod
                    }

                    // param_island 中可能包含更丰富的摘要数据
                    if (pv.has("param_island")) {
                        val island = pv.getJSONObject("param_island")
                        rawExtras["param_island"] = island.toString()
                        // 尝试从 island 的 summary 区或 smallIslandArea 提取简单文本
                        if (island.has("smallIslandArea")) {
                            val small = island.getJSONObject("smallIslandArea")
                            if (small.has("title") && title.isNullOrEmpty()) {
                                val tSmallTitle = small.optString("title", "")
                                if (tSmallTitle.isNotEmpty()) title = tSmallTitle
                            }
                            if (small.has("content") && text.isNullOrEmpty()) {
                                val tSmallContent = small.optString("content", "")
                                if (tSmallContent.isNotEmpty()) text = tSmallContent
                            }
                        }
                        if (island.has("bigIslandArea")) rawExtras["bigIslandArea"] = island.getJSONObject("bigIslandArea").toString()
                    }

                    // 记录 param_v2 原始内容
                    rawExtras["param_v2"] = pv.toString()
                    // 保留 param_v2 原始字符串以便发送
                    rawExtras["param_v2_raw"] = pv.toString()
                } catch (e: Exception) {
                    Logger.w("超级岛", "超级岛: 解析 miui.focus.param 失败: ${e.message}")
                }
            }

            // 其次：尝试直接从 android 标准 title/text 补全
            // 优先使用 verify_code 字段（系统短信App在锁屏状态下也会暴露实际验证码）
            if (text == null && !verifyCode.isNullOrEmpty()) {
                text = verifyCode
                Logger.i("超级岛", "超级岛: 读取到 verify_code 字段: $verifyCode")
            }
            if (title == null) title = extras.getString("android.title") ?: extras.getCharSequence("android.title")?.toString()
            if (text == null) text = extras.getString("android.text") ?: extras.getCharSequence("android.text")?.toString()

            // 图片信息：支持 miui.focus.pics bundle 或者多个 miui.focus.pic_xxx string/url
            val picMap = mutableMapOf<String, String>()
            try {
                val picsBundle = extras.getBundle("miui.focus.pics")
                if (picsBundle != null) {
                    rawExtras["miui.focus.pics"] = picsBundle.toString()
                    // 将 bundle 中的图片项解析到 picMap，key/值均转换为字符串
                    try {
                        for (bk in picsBundle.keySet()) {
                            try {
                                // 优先尝试字符串类型
                                val s = picsBundle.getString(bk)
                                if (!s.isNullOrEmpty()) {
                                    picMap[bk] = s
                                    continue
                                }
                                // 其次尝试 Parcelable（Bitmap / Drawable / Icon / ByteArray）
                                @Suppress("DEPRECATION")
                                val obj = picsBundle.get(bk)
                                if (obj is Bitmap) {
                                    picMap[bk] = ImageUtils.bitmapToDataUri(obj)
                                    continue
                                }
                                if (obj is Drawable) {
                                    try {
                                        val bmp = obj.toBitmap()
                                        picMap[bk] = ImageUtils.bitmapToDataUri(bmp)
                                        continue
                                    } catch (_: Exception) {
                                    }
                                }
                                if (obj is Icon) {
                                    try {
                                        val drawable = obj.loadDrawable(context)
                                        if (drawable != null) {
                                            val bmp = drawable.toBitmap()
                                            picMap[bk] = ImageUtils.bitmapToDataUri(bmp)
                                            continue
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                                if (obj is ByteArray) {
                                    try {
                                        val b64 = Base64.encodeToString(obj, Base64.NO_WRAP)
                                        picMap[bk] = "data:image/png;base64,$b64"
                                        continue
                                    } catch (_: Exception) {
                                    }
                                }
                                // 回退到字符串表示
                                val fallback = obj?.toString()
                                if (!fallback.isNullOrEmpty()) picMap[bk] = fallback
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            // 支持单独的 pic keys
            for (k in extras.keySet()) {
                if (k == "miui.focus.pics") continue
                if (k.startsWith("miui.focus.pic_") || k.startsWith("miui.focus.pic")) {
                    try {
                        // 优先使用 getString
                        val s = extras.getString(k)
                        if (!s.isNullOrEmpty()) {
                            rawExtras[k] = s
                            picMap[k] = s
                            continue
                        }
                        // 其次尝试 CharSequence
                        val cs = extras.getCharSequence(k)
                        if (!cs.isNullOrEmpty()) {
                            rawExtras[k] = cs.toString()
                            picMap[k] = cs.toString()
                            continue
                        }
                        // 再尝试 Parcelable（Bitmap / Drawable / Icon / ByteArray）
                        @Suppress("DEPRECATION")
                        val p =
                            try {
                                extras.get(k)
                            } catch (_: Exception) {
                                null
                            }
                        if (p is Bitmap) {
                            picMap[k] = ImageUtils.bitmapToDataUri(p)
                            continue
                        }
                        if (p is Drawable) {
                            try {
                                picMap[k] = ImageUtils.bitmapToDataUri(p.toBitmap())
                                continue
                            } catch (_: Exception) {
                            }
                        }
                        if (p is Icon) {
                            try {
                                val drawable = p.loadDrawable(context)
                                if (drawable != null) {
                                    picMap[k] = ImageUtils.bitmapToDataUri(drawable.toBitmap())
                                    continue
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (p is ByteArray) {
                            try {
                                val b64 = Base64.encodeToString(p, Base64.NO_WRAP)
                                picMap[k] = "data:image/png;base64,$b64"
                                continue
                            } catch (_: Exception) {
                            }
                        }
                        // 最后回退到 toString()
                        @Suppress("DEPRECATION")
                        val fallback = extras.get(k)?.toString()
                        if (!fallback.isNullOrEmpty()) {
                            rawExtras[k] = fallback
                            picMap[k] = fallback
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // 若通知未提供应用图标，但业务侧使用了 miui.focus.pic_app_icon，我们在获取端注入应用图标的 data URL
            try {
                val appIconKey = "miui.focus.pic_app_icon"
                if (!picMap.containsKey(appIconKey)) {
                    val pm = context.packageManager
                    val appIconDrawable = pm.getApplicationIcon(pkg)
                    val appIconBitmap = appIconDrawable.toBitmap()
                    val dataUrl = ImageUtils.bitmapToDataUri(appIconBitmap)
                    picMap[appIconKey] = dataUrl
                    // Logger.d("超级岛", "超级岛: 注入应用图标到 picMap => $appIconKey")
                }
            } catch (e: Exception) {
                Logger.w("超级岛", "超级岛: 注入应用图标失败: ${e.message}")
            }

            // data URL / bitmap helpers moved to DataUrlUtils

            // 将 picMap 放入 rawExtras 以便上层读取，同时返回到 SuperIslandData
            rawExtras["pic_map"] = picMap

            // appName 尝试从包管理器获取
            try {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                appName = pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
            }

            Logger.i("超级岛", "超级岛: 提取数据 pkg=$pkg, title=$title, text=$text, keys=${extras.keySet()}")
            try {
                picMap.entries.take(6).joinToString(",") { (k, v) -> "$k=${v?.take(80)}" }
                // Logger.d("超级岛", "超级岛: pic_map keys=${picMap.keys.size}, sample={$sample}")
            } catch (_: Exception) {
            }

            return SuperIslandData(
                sourcePackage = pkg,
                appName = appName,
                title = title,
                text = text,
                rawExtras = rawExtras,
                paramV2Raw = rawExtras["param_v2_raw"] as? String,
                picMap = picMap.toMap(),
            )
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 提取超级岛数据时发生错误: ${e.message}")
            return null
        }
    }

    /**
     * 将 param_v2 中的 ****** 占位符替换为真实验证码。
     * 锁屏态系统短信App会把验证码显示为 ******，真实值位于 verify_code 字段；
     * 回填后 raw 直接携带真实验证码，接收端无需再做替换。
     */
    private fun replaceVerifyCodePlaceholder(
        paramV2: JSONObject,
        verifyCode: String,
    ) {
        try {
            // iconTextInfo.title
            paramV2.optJSONObject("iconTextInfo")?.let { iconTextInfo ->
                val title = iconTextInfo.optString("title", "")
                if (title.contains("******") || title.contains("****")) {
                    val replaced = title.replace("******", verifyCode).replace("****", verifyCode)
                    iconTextInfo.put("title", replaced)
                    Logger.i("超级岛", "超级岛: 验证码回填 iconTextInfo.title: $title -> $replaced")
                }
            }

            // param_island.bigIslandArea.textInfo.title
            paramV2
                .optJSONObject("param_island")
                ?.optJSONObject("bigIslandArea")
                ?.optJSONObject("textInfo")
                ?.let { textInfo ->
                    val title = textInfo.optString("title", "")
                    if (title.contains("******") || title.contains("****")) {
                        val replaced = title.replace("******", verifyCode).replace("****", verifyCode)
                        textInfo.put("title", replaced)
                        Logger.i("超级岛", "超级岛: 验证码回填 bigIslandArea.textInfo.title: $title -> $replaced")
                    }
                }
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 验证码回填失败: ${e.message}")
        }
    }
}
