package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import org.json.JSONObject

/**
 * 解析结果密封类
 * 用于统一处理各种解析操作的结果
 */
sealed class ParseResult<out T> {
    /**
     * 解析成功的结果
     */
    data class Success<T>(val data: T) : ParseResult<T>()
    
    /**
     * 解析失败的结果
     */
    data class Error(val title: String, val content: String) : ParseResult<Nothing>()
}

/**
 * 安全地解析 JSON 对象
 * 封装了 try-catch 逻辑，返回 ParseResult
 */
fun safeParseJson(jsonString: String, parseAction: (JSONObject) -> Any?): ParseResult<Any?> {
    return try {
        val json = JSONObject(jsonString)
        val result = parseAction(json)
        if (result != null) {
            ParseResult.Success(result)
        } else {
            // 解析结果为 null，尝试从 JSON 中提取基本信息作为错误提示
            val title = json.optString("title", "")
            val content = json.optString("content", "")
            ParseResult.Error(
                title = title.takeIf { it.isNotBlank() } ?: "解析失败",
                content = content.takeIf { it.isNotBlank() } ?: "无法解析数据"
            )
        }
    } catch (e: Exception) {
        ParseResult.Error(
            title = "解析失败",
            content = "数据格式错误: ${e.message}"
        )
    }
}

/**
 * 解析结果的扩展函数，用于处理解析结果
 */
fun <T> ParseResult<T>.handleResult(
    onSuccess: (T) -> Unit,
    onError: (ParseResult.Error) -> Unit
) {
    when (this) {
        is ParseResult.Success -> onSuccess(data)
        is ParseResult.Error -> onError(this)
    }
}
