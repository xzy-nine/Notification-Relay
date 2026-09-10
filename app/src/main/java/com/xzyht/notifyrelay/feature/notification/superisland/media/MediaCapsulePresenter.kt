package com.xzyht.notifyrelay.feature.notification.superisland.media

import android.content.Context
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import github.xzynine.superislandui.common.TextSplitter
import notifyrelay.base.util.DeviceUtils
import notifyrelay.data.StorageManager
import org.json.JSONObject

object MediaCapsulePresenter {
    fun show(
        context: Context,
        sourceId: String,
        title: String?,
        text: String?,
        appName: String? = null,
        picMap: Map<String, String>? = null,
        coverUrl: String? = null,
    ) {
        // 处理歌词拆分
        val lyricText = title.orEmpty()
        var capsuleText = lyricText
        var iconText = ""

        // 检查歌词分割模式设置
        val lyricsSplitMode = StorageManager.getInt(context, "lyrics_split_mode", 0)
        val shouldSplit =
            when (lyricsSplitMode) {
                1 -> true
                2 -> false
                else -> !DeviceUtils.isTablet(context)
            }

        if (shouldSplit) {
            val threshold = 12
            val textLength = TextSplitter.calculateTextLength(lyricText)
            if (textLength > threshold) {
                val (splitIconText, splitCapsuleText) = TextSplitter.splitLyric(lyricText, threshold)
                iconText = splitIconText
                capsuleText = splitCapsuleText
            }
        } else {
            capsuleText = lyricText
            iconText = ""
        }

        val paramV2Raw = buildParamV2(title.orEmpty(), text.orEmpty(), iconText, capsuleText)
        val resolvedPicMap = picMap ?: buildDefaultPicMap(coverUrl)
        FloatingReplicaManager.showFloating(
            context = context,
            sourceId = sourceId,
            title = title,
            text = text,
            paramV2Raw = paramV2Raw,
            picMap = resolvedPicMap,
            appName = appName,
        )
    }

    fun buildParamV2(
        title: String,
        text: String,
        iconText: String = "",
        capsuleText: String = "",
    ): String =
        JSONObject()
            .apply {
                put("business", "media")
                put(
                    "baseInfo",
                    JSONObject().apply {
                        put("title", title)
                        put("content", text)
                    },
                )
                put(
                    "param_island",
                    JSONObject().apply {
                        put(
                            "bigIslandArea",
                            JSONObject().apply {
                                // 左侧：图文组件1（图+歌词左）
                                put(
                                    "imageTextInfoLeft",
                                    JSONObject().apply {
                                        put("type", 1)
                                        put(
                                            "picInfo",
                                            JSONObject().apply {
                                                put("type", 1)
                                                put("pic", "miui.focus.pic_cover")
                                            },
                                        )
                                        // 短文本（iconText 为空）时左侧为纯专辑图，不放文字
                                        if (iconText.isNotEmpty()) {
                                            put(
                                                "textInfo",
                                                JSONObject().apply {
                                                    put("title", iconText)
                                                    put("content", "")
                                                    put("narrowFont", false)
                                                    put("showHighlightColor", true)
                                                },
                                            )
                                        }
                                    },
                                )
                                // 右侧：文本组件（歌词右）
                                put(
                                    "textInfo",
                                    JSONObject().apply {
                                        put("frontTitle", "")
                                        put("title", capsuleText)
                                        put("content", "")
                                        put("narrowFont", false)
                                        put("showHighlightColor", true)
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()

    private fun buildDefaultPicMap(coverUrl: String?): Map<String, String>? {
        if (coverUrl.isNullOrBlank()) return null
        return mapOf(
            "miui.focus.pic_cover" to coverUrl,
            "miui.focus.pic_app_icon" to coverUrl,
        )
    }
}
