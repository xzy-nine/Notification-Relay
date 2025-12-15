package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigIslandArea.B

import org.json.JSONObject

/**
 * 解析 B区（优先识别 imageTextInfoRight 的 type，其次识别 text/digit/progress/pic）。
 * 若均未命中，返回 BEmpty。
 */
fun parseBComponent(bigIsland: JSONObject?): BComponent {
    val right = bigIsland?.optJSONObject("imageTextInfoRight")
    if (right != null) {
        val type = right.optInt("type", 0)
        val textInfo = right.optJSONObject("textInfo")
        val titleInline = right.optString("title", "").takeIf { it.isNotBlank() }
        val contentInline = right.optString("content", "").takeIf { it.isNotBlank() }
        val titleText = titleInline ?: textInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
        val contentText = contentInline ?: textInfo?.optString("content", "")?.takeIf { it.isNotBlank() }
        val frontTitle = textInfo?.optString("frontTitle", "")?.takeIf { it.isNotBlank() }
        val narrowFont = textInfo?.optBoolean("narrowFont", false) ?: false
        val showHighlightColor = textInfo?.optBoolean("showHighlightColor", false) ?: false

        val picInfo = right.optJSONObject("picInfo")
        val picTypeOk = (picInfo?.optInt("type", 0) == 1)
        val picKey = picInfo?.optString("pic", "")?.takeIf { it.isNotBlank() }

        return when (type) {
            2 -> {
                val title = titleText ?: return BEmpty // 必传
                if (!picTypeOk || picKey == null) return BEmpty // 必传
                BImageText2(
                    frontTitle = frontTitle,
                    title = title,
                    content = contentText,
                    narrowFont = narrowFont,
                    showHighlightColor = showHighlightColor,
                    picKey = picKey
                )
            }
            3 -> {
                val title = titleText ?: return BEmpty // 必传
                if (!picTypeOk || picKey == null) return BEmpty // 必传
                BImageText3(
                    title = title,
                    narrowFont = narrowFont,
                    showHighlightColor = showHighlightColor,
                    picKey = picKey
                )
            }
            // 组件4为系统侧（充电/省电）专用，不走通知数据；本项目不复刻，直接视为空
            4 -> BEmpty
            6 -> {
                val title = titleText ?: return BEmpty // 必传
                // 组件6要求静态图标：picInfo.type==4 且 picKey 必传
                val staticIcon = (picInfo?.optInt("type", 0) == 4)
                if (!staticIcon || picKey == null) return BEmpty
                BImageText6(
                    title = title,
                    narrowFont = narrowFont,
                    showHighlightColor = showHighlightColor,
                    picKey = picKey
                )
            }
            else -> BEmpty
        }
    }

    bigIsland?.optJSONObject("textInfo")?.let { ti ->
        val title = ti.optString("title", "").takeIf { it.isNotBlank() } ?: return BEmpty
        val frontTitle = ti.optString("frontTitle", "").takeIf { it.isNotBlank() }
        val content = ti.optString("content", "").takeIf { it.isNotBlank() }
        val narrowFont = ti.optBoolean("narrowFont", false)
        val showHighlightColor = ti.optBoolean("showHighlightColor", false)
        return BTextInfo(
            frontTitle = frontTitle,
            title = title,
            content = content,
            narrowFont = narrowFont,
            showHighlightColor = showHighlightColor
        )
    }

    bigIsland?.optJSONObject("fixedWidthDigitInfo")?.let { fi ->
        val digit = fi.optString("digit", "").takeIf { it.isNotBlank() }
            ?: fi.optString("text", "").takeIf { it.isNotBlank() } // 兼容旧字段名
        digit ?: return@let
        val content = fi.optString("content", "").takeIf { it.isNotBlank() }
        val showHighlightColor = fi.optBoolean("showHighlightColor", false)
        return BFixedWidthDigitInfo(
            digit = digit,
            content = content,
            showHighlightColor = showHighlightColor
        )
    }

    bigIsland?.optJSONObject("sameWidthDigitInfo")?.let { si ->
        // 先尝试解析 timerInfo
        val timerObj = si.optJSONObject("timerInfo")
        val timer = timerObj?.let { to ->
            val typeExists = to.has("timerType")
            if (!typeExists) null else {
                val timerType = to.optInt("timerType")
                val timerWhen = if (to.has("timerWhen")) to.optLong("timerWhen") else null
                val timerTotal = if (to.has("timerTotal")) to.optLong("timerTotal") else null
                val timerSystemCurrent = if (to.has("timerSystemCurrent")) to.optLong("timerSystemCurrent") else null
                TimerInfo(
                    timerType = timerType,
                    timerWhen = timerWhen,
                    timerTotal = timerTotal,
                    timerSystemCurrent = timerSystemCurrent
                )
            }
        }

        // 若无 timerInfo 或不合法，则回退到 digit/text
        val digit = si.optString("digit", "").takeIf { it.isNotBlank() }
            ?: si.optString("text", "").takeIf { it.isNotBlank() }

        // 二选一：timer 或 digit 至少一个存在
        if (timer == null && digit == null) return@let

        val content = si.optString("content", "").takeIf { it.isNotBlank() }
        val showHighlightColor = si.optBoolean("showHighlightColor", false)
        return BSameWidthDigitInfo(
            digit = digit,
            timer = timer,
            content = content,
            showHighlightColor = showHighlightColor
        )
    }

    bigIsland?.optJSONObject("progressTextInfo")?.let { root ->
        // textInfo
        val ti = root.optJSONObject("textInfo")
        val frontTitle = ti?.optString("frontTitle", "")?.takeIf { it.isNotBlank() }
        val title = ti?.optString("title", "")?.takeIf { it.isNotBlank() }
        val content = ti?.optString("content", "")?.takeIf { it.isNotBlank() }
        val narrowFont = ti?.optBoolean("narrowFont", false) ?: false
        val showHighlightColor = ti?.optBoolean("showHighlightColor", false) ?: false

        // progressInfo (required)
        val pInfo = root.optJSONObject("progressInfo") ?: return@let
        val progress = pInfo.optInt("progress", -1)
        if (progress !in 0..100) return@let
        val colorReach = pInfo.optString("colorReach", "").takeIf { it.isNotBlank() }
        val colorUnReach = pInfo.optString("colorUnReach", "").takeIf { it.isNotBlank() }
        val isCCW = pInfo.optBoolean("isCCW", false)

        // picInfo (optional but if present must be type=1 & pic required)
        val picObj = root.optJSONObject("picInfo")
        val picKey = picObj?.let { po ->
            val typeOk = po.optInt("type", 0) == 1
            val key = po.optString("pic", "").takeIf { it.isNotBlank() }
            if (typeOk) key else null
        }

        return BProgressTextInfo(
            frontTitle = frontTitle,
            title = title,
            content = content,
            narrowFont = narrowFont,
            showHighlightColor = showHighlightColor,
            progress = progress,
            colorReach = colorReach,
            colorUnReach = colorUnReach,
            isCCW = isCCW,
            picKey = picKey
        )
    }

    bigIsland?.optJSONObject("picInfo")?.let { pi ->
        val type = pi.optInt("type", -1)
        if (type != 1 && type != 4) return@let
        val picKey = pi.optString("pic", "").takeIf { it.isNotBlank() } ?: return@let
        return BPicInfo(picKey = picKey, type = type)
    }

    return BEmpty
}
