package com.xzyht.notifyrelay.feature.superisland

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.common.data.StorageManager
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
    private fun isEnabled(context: Context): Boolean {
        return try { StorageManager.getBoolean(context, STORAGE_KEY, true) } catch (_: Exception) { true }
    }

    /**
     * 判断系统属性是否支持岛功能（反射 SystemProperties.getBoolean）
     */
    fun isSupportIsland(defaultValue: Boolean = false): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            val res = method.invoke(null, "persist.sys.feature.island", defaultValue) as? Boolean
            res ?: defaultValue
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("SuperIsland", "isSupportIsland check failed: ${e.message}")
            defaultValue
        }
    }

    /**
     * 获取焦点通知协议版本：Settings.System.getInt(notification_focus_protocol)
     */
    fun getFocusProtocolVersion(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("SuperIsland", "getFocusProtocolVersion failed: ${e.message}")
            0
        }
    }

    /**
     * 调用 content://miui.statusbar.notification.public canShowFocus 判断应用是否有焦点通知权限
     */
    fun hasFocusPermission(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val extras = Bundle()
            extras.putString("package", context.packageName)
            val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
            bundle?.getBoolean("canShowFocus", false) ?: false
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("SuperIsland", "hasFocusPermission failed: ${e.message}")
            false
        }
    }

    /**
     * 解析 miui.focus.param（JSON string），返回结构化 SuperIslandData；不存在则返回 null。
     * 按文档，miui.focus.param 中包含 param_v2 字段，内部可能含 param_island / baseInfo / aodTitle 等字段。
     */
    fun extractSuperIslandData(sbn: StatusBarNotification, context: Context): SuperIslandData? {
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
            val rawExtras = mutableMapOf<String, Any?>()

            if (!islandParamStr.isNullOrEmpty()) {
                try {
                    val root = JSONObject(islandParamStr)
                    val pv = if (root.has("param_v2")) root.getJSONObject("param_v2") else root

                    // 提取 baseInfo (焦点通知数据) 中的 title/content
                    if (pv.has("baseInfo")) {
                        val base = pv.getJSONObject("baseInfo")
                        title = base.optString("title", null)
                        text = base.optString("content", text)
                    }

                    // aodTitle 优先用于息屏场景的摘要
                    if (pv.has("aodTitle") && (title == null || title.isEmpty())) {
                        title = pv.optString("aodTitle", title)
                    }

                    // param_island 中可能包含更丰富的摘要数据
                    if (pv.has("param_island")) {
                        val island = pv.getJSONObject("param_island")
                        rawExtras["param_island"] = island.toString()
                        // 尝试从 island 的 summary 区或 smallIslandArea 提取简单文本
                        if (island.has("smallIslandArea")) {
                            val small = island.getJSONObject("smallIslandArea")
                            if (small.has("title") && title.isNullOrEmpty()) title = small.optString("title", title)
                            if (small.has("content") && text.isNullOrEmpty()) text = small.optString("content", text)
                        }
                        if (island.has("bigIslandArea")) rawExtras["bigIslandArea"] = island.getJSONObject("bigIslandArea").toString()
                    }

                    // 记录 param_v2 原始内容
                    rawExtras["param_v2"] = pv.toString()
                    // 保留 param_v2 原始字符串以便发送
                    if (pv != null) rawExtras["param_v2_raw"] = pv.toString()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w("SuperIsland", "parse miui.focus.param failed: ${e.message}")
                }
            }

            // 其次：尝试直接从 android 标准 title/text 补全
            if (title == null) title = extras.getString("android.title") ?: extras.getCharSequence("android.title")?.toString()
            if (text == null) text = extras.getString("android.text") ?: extras.getCharSequence("android.text")?.toString()

            // 图片信息：支持 miui.focus.pics bundle 或者多个 miui.focus.pic_xxx string/url
            try {
                val picsBundle = extras.getBundle("miui.focus.pics")
                if (picsBundle != null) {
                    rawExtras["miui.focus.pics"] = picsBundle.toString()
                }
            } catch (_: Exception) {}
            // 支持单独的 pic keys
            val picMap = mutableMapOf<String, String>()
            for (k in extras.keySet()) {
                if (k.startsWith("miui.focus.pic_") || k.startsWith("miui.focus.pic")) {
                    try {
                        val v = extras.get(k)
                        if (v is String) {
                            rawExtras[k] = v
                            picMap[k] = v
                        } else if (v is android.os.Parcelable) {
                            rawExtras[k] = v.toString()
                        } else {
                            rawExtras[k] = v
                        }
                    } catch (_: Exception) {}
                }
            }

            // 将 picMap 放入 rawExtras 以便上层读取，同时返回到 SuperIslandData
            rawExtras["pic_map"] = picMap

            // appName 尝试从包管理器获取
            try {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                appName = pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {}

            if (BuildConfig.DEBUG) Log.i("SuperIsland", "extractSuperIslandData: pkg=$pkg, title=$title, text=$text, keys=${extras.keySet()}")

            return SuperIslandData(
                sourcePackage = pkg,
                appName = appName,
                title = title,
                text = text,
                rawExtras = rawExtras,
                paramV2Raw = rawExtras["param_v2_raw"] as? String,
                picMap = (rawExtras["pic_map"] as? Map<String, String>)?.toMap()
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("SuperIsland", "extractSuperIslandData error: ${e.message}")
            return null
        }
    }
}
