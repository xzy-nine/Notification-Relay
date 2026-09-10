package com.xzyht.notifyrelay.feature.notification.superisland.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import notifyrelay.base.util.Logger
import notifyrelay.core.util.image.ImageUtils
import org.json.JSONObject

/**
 * 超级岛结构化数据注入工具�?
 * 负责为通知添加符合小米官方规范的超级岛结构化数�?
 */
object SuperIslandStructuredDataHelper {
    private const val TAG = "SuperIslandStructuredDataHelper"

    // 媒体图片注入上限：缩放最长边并限制总字节数，避免通知事务过大
    private const val MAX_MEDIA_PIC_DIMENSION = 512
    private const val MAX_MEDIA_PIC_PER_IMAGE_BYTES = 256 * 1024
    private const val MAX_MEDIA_PIC_TOTAL_BYTES = 768 * 1024

    // FocusTemplate V3 序列化标识（对齐 Xiaomi-SuperIsland-Playground）
    private const val FOCUS_V3_SERIAL_NAME =
        "com.xzakota.hyper.notification.focus.FocusNotification.FocusTemplateFactory.V3"

    /**
     * 添加超级岛相关的结构化数据到通知
     * @param builder 通知构建�?
     * @param context 上下�?
     * @param paramV2Raw ParamV2原始JSON字符�?
     * @param picMap 图片映射
     * @param title 通知标题
     * @param text 通知内容
     * @param isSuperIslandSpecInjectionEnabled 是否开启超级岛规范信息注入
     */
    fun addSuperIslandStructuredData(
        builder: NotificationCompat.Builder,
        context: Context,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        title: String?,
        text: String?,
        isSuperIslandSpecInjectionEnabled: Boolean = true,
    ) {
        try {
            val extras = builder.extras

            if (isSuperIslandSpecInjectionEnabled) {
                paramV2Raw?.let { rawData ->
                    try {
                        val paramV2Json = JSONObject(rawData)
                        val tickerValue = title ?: paramV2Json.optString("ticker", "")

                        // 在原始 param_v2 基础上补充缺失字段
                        if (!paramV2Json.has("protocol")) paramV2Json.put("protocol", 1)
                        if (!paramV2Json.has("ticker") || paramV2Json.optString("ticker").isBlank()) {
                            paramV2Json.put("ticker", tickerValue)
                        }
                        if (!paramV2Json.has("aodTitle") || paramV2Json.optString("aodTitle").isBlank()) {
                            paramV2Json.put("aodTitle", tickerValue)
                        }
                        if (!paramV2Json.has("updatable")) paramV2Json.put("updatable", true)
                        if (!paramV2Json.has("reopen")) paramV2Json.put("reopen", "close")
                        if (!paramV2Json.has("enableFloat")) paramV2Json.put("enableFloat", false)
                        if (!paramV2Json.has("islandFirstFloat")) paramV2Json.put("islandFirstFloat", false)

                        // 顶层包装：type(可选) + param_v2
                        val fullFocusParam = JSONObject().apply {
                            put("param_v2", paramV2Json)
                        }

                        extras.putString("miui.focus.param", fullFocusParam.toString())
                        Logger.i(TAG, "添加miui.focus.param成功")
                    } catch (e: Exception) {
                        extras.putString("miui.focus.param", rawData)
                        Logger.w(TAG, "构建完整焦点通知参数结构失败，回退到原始数据 ${e.message}")
                    }
                }

                addPicMapToExtras(extras, picMap)
                addActionBundlesToExtras(extras)

                extras.putBoolean("miui.island.updateNoFloat", false)
                extras.putBoolean("miui.island.firstFloat", false)
                extras.putBoolean("miui.enableFloat", false)

                val titleValue = title ?: ""
                if (titleValue.contains("计时") || titleValue.contains("秒表")) {
                    extras.putBoolean("android.chronometerCountDown", false)
                    extras.putBoolean("android.showChronometer", true)
                }

                extras.putBoolean("android.reduced.images", true)
                extras.putString("superIslandSourcePackage", context.packageName)
                extras.putString("app_package", context.packageName)
                extras.putBoolean("miui.isFocusNotification", true)
                extras.putBoolean("miui.showBadge", false)

                Logger.i(TAG, "添加超级岛结构化数据成功")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "添加超级岛结构化数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 为媒体类型通知添加超级岛结构化数据
     * @param builder 通知构建器
     * @param context 上下文
     * @param title 通知标题（用于展开态 animTextInfo）
     * @param text 通知内容（用于展开态 animTextInfo）
     * @param picMap 图片映射
     * @param iconText 左侧文本（分割后的歌词左半部分，用于收起态 imageTextInfoLeft）
     * @param capsuleText 右侧文本（分割后的歌词右半部分，用于收起态 textInfo）
     */
    suspend fun addMediaSuperIslandStructuredData(
        builder: NotificationCompat.Builder,
        context: Context,
        title: String?,
        text: String?,
        picMap: Map<String, String>?,
        iconText: String? = null,
        capsuleText: String? = null,
    ) {
        try {
            val extras = builder.extras

            // 按照小米超级岛模板库"序号二：a图文组件1 + b文本组件"构建
            // 外层 type 为 FocusTemplate V3 序列化标识，缺失时 SystemUI 无法识别 V3 模板（picInfo 自定义图失效）
            val fullFocusParam = JSONObject().apply {
                put("type", FOCUS_V3_SERIAL_NAME)
                put("param_v2", JSONObject().apply {
                    put("protocol", 1)
                    put("business", "music")
                    put("ticker", title ?: "")
                    put("aodTitle", title ?: "")
                    put("updatable", true)
                    put("reopen", "close")
                    put("enableFloat", false)
                    put("islandFirstFloat", false)

                    // 焦点通知数据（展开态生效）
                    put("baseInfo", JSONObject().apply {
                        put("type", 2)
                        put("title", title ?: "")
                        put("content", text ?: "")
                    })

                    // 岛数据
                    put("param_island", JSONObject().apply {
                        put("islandProperty", 1)
                        put("islandOrder", false)
                        put("highlightColor", "#FFFFFF")
                        // 大岛：a图文组件1（图+歌词左） + b文本组件（歌词右）
                        put("bigIslandArea", JSONObject().apply {
                            put("imageTextInfoLeft", JSONObject().apply {
                                put("type", 1)
                                put("picInfo", JSONObject().apply {
                                    put("type", 1)
                                    put("pic", "miui.focus.pic_cover")
                                })
                                // 短文本（iconText 为空）时左侧为纯专辑图，不放文字
                                if (!iconText.isNullOrEmpty()) {
                                    put("textInfo", JSONObject().apply {
                                        put("title", iconText)
                                        put("content", "")
                                        put("narrowFont", false)
                                        put("showHighlightColor", true)
                                    })
                                }
                            })
                            put("textInfo", JSONObject().apply {
                                put("frontTitle", "")
                                put("title", capsuleText ?: "")
                                put("content", "")
                                put("narrowFont", false)
                                put("showHighlightColor", true)
                            })
                        })
                        // 小岛
                        put("smallIslandArea", JSONObject().apply {
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_cover")
                            })
                        })
                    })
                })
            }

            extras.putString("miui.focus.param", fullFocusParam.toString())

            addActionBundlesToExtras(extras)
            addMediaPicMapToExtras(context, extras, picMap)

            extras.putBoolean("android.reduced.images", true)
            extras.putString("superIslandSourcePackage", context.packageName)
            extras.putString("app_package", context.packageName)
            extras.putBoolean("miui.isFocusNotification", true)
            extras.putBoolean("miui.showBadge", false)

            Logger.i(TAG, "添加媒体类型超级岛结构化数据成功")
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，避免被当作普通异常吞掉
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "添加媒体类型超级岛结构化数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 为非媒体类型通知添加超级岛结构化数据（支持右胶囊文本更新）
     * @param builder 通知构建器
     * @param context 上下文
     * @param paramV2Raw ParamV2原始JSON字符串
     * @param picMap 图片映射
     * @param title 通知标题
     * @param text 通知内容
     * @param bTitle 右胶囊标题
     * @param bContent 右胶囊内容
     */
    fun addNonMediaSuperIslandStructuredData(
        builder: NotificationCompat.Builder,
        context: Context,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        title: String?,
        text: String?,
        bTitle: String? = null,
        bContent: String? = null,
    ) {
        try {
            val extras = builder.extras

            // 构建符合小米官方规范的完整miui.focus.param结构
            paramV2Raw?.let {
                try {
                    // 解析原始paramV2数据
                    val paramV2Json = JSONObject(it)

                    // 如果提供了右胶囊文本，更新imageTextInfoRight
                    if (bTitle != null || bContent != null) {
                        val bigIslandJson = paramV2Json.optJSONObject("bigIsland") ?: JSONObject()
                        val islandAreaJson = bigIslandJson.optJSONObject("imageTextInfoRight") ?: JSONObject()

                        // 设置右胶囊文本
                        if (bTitle != null) {
                            islandAreaJson.put("title", bTitle)
                        }
                        if (bContent != null) {
                            islandAreaJson.put("content", bContent)
                        }

                        // 更新 bigIsland 和 paramV2Json
                        bigIslandJson.put("imageTextInfoRight", islandAreaJson)
                        paramV2Json.put("bigIsland", bigIslandJson)
                    }

                    // 构建完整的焦点通知参数结构，包含外层scene、ticker等字段
                    val fullFocusParam =
                        JSONObject().apply {
                            put("protocol", 1)
                            put("scene", paramV2Json.optString("business", "default"))
                            put("ticker", title ?: "")
                            put("content", text ?: "")
                            put("timerType", 0)
                            put("timerWhen", 0)
                            put("timerSystemCurrent", 0)
                            put("enableFloat", false)
                            put("updatable", true)
                            put("param_v2", paramV2Json) // 将更新后的paramV2作为嵌套字段
                        }

                    extras.putString("miui.focus.param", fullFocusParam.toString())
                } catch (e: Exception) {
                    // 如果构建完整结构失败，回退到直接使用原始数据
                    extras.putString("miui.focus.param", it)
                }
            }

            addPicMapToExtras(extras, picMap)
            addActionBundlesToExtras(extras)

            // 添加应用信息，与原始通知保持一致
            extras.putBoolean("android.reduced.images", true)

            // 添加超级岛源包信息，与原始通知保持一致
            extras.putString("superIslandSourcePackage", context.packageName)

            // 包名信息
            extras.putString("app_package", context.packageName)

            Logger.i(TAG, "添加非媒体类型超级岛结构化数据成功")
        } catch (e: Exception) {
            Logger.w(TAG, "添加非媒体类型超级岛结构化数据失败 ${e.message}")
            e.printStackTrace()
        }
    }

    private fun addPicMapToExtras(
        extras: Bundle,
        picMap: Map<String, String>?,
    ) {
        picMap?.let { map ->
            map.forEach { (picKey, picUrl) ->
                if (picKey.startsWith("miui.focus.pic_")) {
                    extras.putString(picKey, picUrl)
                }
            }
            val picsBundle = Bundle()
            map.forEach { (picKey, picUrl) ->
                if (picKey.startsWith("miui.focus.pic_")) {
                    picsBundle.putString(picKey, picUrl)
                }
            }
            extras.putBundle("miui.focus.pics", picsBundle)
            Logger.i(TAG, "添加图片资源成功，共${map.size}个图片")
        }
    }

    /**
     * 媒体类型专用：下载图片为 Bitmap 并转为 Icon 放入 miui.focus.pics（客户端模式要求 Parcelable Icon）
     */
    private suspend fun addMediaPicMapToExtras(
        context: Context,
        extras: Bundle,
        picMap: Map<String, String>?,
    ) {
        picMap?.let { map ->
            val picsBundle = Bundle()
            var count = 0
            var totalBytes = 0
            map.forEach { (picKey, picUrl) ->
                if (!picKey.startsWith("miui.focus.pic_") || picUrl.isBlank()) return@forEach
                if (totalBytes >= MAX_MEDIA_PIC_TOTAL_BYTES) {
                    Logger.w(TAG, "媒体图片总大小已达上限，跳过后续图片: $picKey")
                    return@forEach
                }
                val bitmap = try {
                    ImageUtils.loadBitmap(context, picUrl)
                } catch (e: CancellationException) {
                    // 协程取消必须原样抛出，避免被当作普通异常吞掉
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "媒体图片加载失败 ${picKey}: ${e.message}")
                    null
                }
                if (bitmap != null) {
                    // 按比例缩放到上限尺寸并压缩，限制单张与总体大小，避免通知事务过大
                    val data = encodePicData(scaleDownBitmap(bitmap, MAX_MEDIA_PIC_DIMENSION))
                    if (totalBytes + data.size > MAX_MEDIA_PIC_TOTAL_BYTES) {
                        Logger.w(TAG, "媒体图片超过总大小限制，跳过: $picKey (${data.size} bytes)")
                        return@forEach
                    }
                    picsBundle.putParcelable(picKey, Icon.createWithData(data, 0, data.size))
                    totalBytes += data.size
                    count++
                }
            }
            if (count > 0) {
                extras.putBundle("miui.focus.pics", picsBundle)
                Logger.i(TAG, "媒体图片资源注入成功，共 $count 个图片（总计 $totalBytes bytes）")
            }
        }
    }

    /**
     * 按比例缩小位图，使最长边不超过 [maxDimension]；已在范围内则原样返回。
     */
    private fun scaleDownBitmap(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension || longest <= 0) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * 将位图编码为图标数据：优先 PNG（保留透明度），过大时改用 JPEG 压缩以减小体积。
     */
    private fun encodePicData(bitmap: Bitmap): ByteArray {
        val pngOut = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
        val png = pngOut.toByteArray()
        if (png.size <= MAX_MEDIA_PIC_PER_IMAGE_BYTES) return png
        val jpegOut = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, jpegOut)
        return jpegOut.toByteArray()
    }

    private fun addActionBundlesToExtras(extras: Bundle) {
        extras.putBoolean("miui.showAction", true)
        val actionsBundle = Bundle()
        actionsBundle.putString("miui.focus.action_1", "dummy_action_1")
        actionsBundle.putString("miui.focus.action_2", "dummy_action_2")
        extras.putBundle("miui.focus.actions", actionsBundle)
    }
}
