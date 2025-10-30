package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

// 数据类定义模板组件，使用分支结构避免重复定义
// 基础信息模板：文本组件1和2，用于显示主要文本、次要文本等
data class BaseInfo(
    val type: Int, // 文本组件类型：1文本组件1，2文本组件2
    val title: String? = null, // 主要文本1：关键信息
    val subTitle: String? = null, // 主要文本2：关键信息
    val extraTitle: String? = null, // 补充文本
    val specialTitle: String? = null, // 特殊标签文本
    val content: String? = null, // 次要文本1：前置描述
    val subContent: String? = null, // 次要文本2：前置描述
    val picFunction: String? = null, // 功能图标资源key
    val picFunctionDark: String? = null, // 深色模式功能图标
    val colorTitle: String? = null, // 主要文本1颜色
    val colorTitleDark: String? = null, // 深色模式主要文本1颜色
    val colorSubTitle: String? = null, // 主要文本2颜色
    val colorSubTitleDark: String? = null, // 深色模式主要文本2颜色
    val colorExtraTitle: String? = null, // 补充文本颜色
    val colorExtraTitleDark: String? = null, // 深色模式补充文本颜色
    val colorSpecialTitle: String? = null, // 特殊标签文本颜色
    val colorSpecialTitleDark: String? = null, // 深色模式特殊标签文本颜色
    val colorSpecialBg: String? = null, // 特殊标签背景色
    val colorContent: String? = null, // 次要文本1颜色
    val colorContentDark: String? = null, // 深色模式次要文本1颜色
    val colorSubContent: String? = null, // 次要文本2颜色
    val colorSubContentDark: String? = null, // 深色模式次要文本2颜色
    val showDivider: Boolean? = null, // 是否显示主要文本间分割符
    val showContentDivider: Boolean? = null // 是否显示主要文本和补充文本分割符
)

// 解析基础信息组件（文本组件1和2）
fun parseBaseInfo(json: JSONObject): BaseInfo {
    return BaseInfo(
        type = json.optInt("type", 1),
        title = json.optString("title", null)?.takeIf { it.isNotEmpty() },
        subTitle = json.optString("subTitle", null)?.takeIf { it.isNotEmpty() },
        extraTitle = json.optString("extraTitle", null)?.takeIf { it.isNotEmpty() },
        specialTitle = json.optString("specialTitle", null)?.takeIf { it.isNotEmpty() },
        content = json.optString("content", null)?.takeIf { it.isNotEmpty() },
        subContent = json.optString("subContent", null)?.takeIf { it.isNotEmpty() },
        picFunction = json.optString("picFunction", null)?.takeIf { it.isNotEmpty() },
        picFunctionDark = json.optString("picFunctionDark", null)?.takeIf { it.isNotEmpty() },
        colorTitle = json.optString("colorTitle", null)?.takeIf { it.isNotEmpty() },
        colorTitleDark = json.optString("colorTitleDark", null)?.takeIf { it.isNotEmpty() },
        colorSubTitle = json.optString("colorSubTitle", null)?.takeIf { it.isNotEmpty() },
        colorSubTitleDark = json.optString("colorSubTitleDark", null)?.takeIf { it.isNotEmpty() },
        colorExtraTitle = json.optString("colorExtraTitle", null)?.takeIf { it.isNotEmpty() },
        colorExtraTitleDark = json.optString("colorExtraTitleDark", null)?.takeIf { it.isNotEmpty() },
        colorSpecialTitle = json.optString("colorSpecialTitle", null)?.takeIf { it.isNotEmpty() },
        colorSpecialTitleDark = json.optString("colorSpecialTitleDark", null)?.takeIf { it.isNotEmpty() },
        colorSpecialBg = json.optString("colorSpecialBg", null)?.takeIf { it.isNotEmpty() },
        colorContent = json.optString("colorContent", null)?.takeIf { it.isNotEmpty() },
        colorContentDark = json.optString("colorContentDark", null)?.takeIf { it.isNotEmpty() },
        colorSubContent = json.optString("colorSubContent", null)?.takeIf { it.isNotEmpty() },
        colorSubContentDark = json.optString("colorSubContentDark", null)?.takeIf { it.isNotEmpty() },
        showDivider = json.optBoolean("showDivider", false),
        showContentDivider = json.optBoolean("showContentDivider", false)
    )
}

// 构建BaseInfo视图
fun buildBaseInfoView(context: Context, baseInfo: BaseInfo, picMap: Map<String, String>?): LinearLayout {
    val textContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    baseInfo.title?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(baseInfo.colorTitle) ?: 0xFFFFFFFF.toInt())
            textSize = 14f
        }
        textContainer.addView(tv)
    }

    baseInfo.content?.let {
        val tv = TextView(context).apply {
            text = it
            setTextColor(parseColor(baseInfo.colorContent) ?: 0xFFDDDDDD.toInt())
            textSize = 12f
        }
        textContainer.addView(tv)
    }

    return textContainer
}