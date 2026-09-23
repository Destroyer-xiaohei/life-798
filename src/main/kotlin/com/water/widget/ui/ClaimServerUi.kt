package com.water.widget.ui

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** 服务端托管账号在界面上的呈现数据。 */
data class ClaimAccountUi(
    val id: Int,
    val title: String,
    val phone: String,
    val statusLabel: String,
    val lastRunText: String,
    val hasToken: Boolean,
    val enabled: Boolean
)

/** 服务端领取日志在界面上的呈现数据。 */
data class ClaimLogUi(
    val id: Int,
    val timeText: String,
    val accountId: Int,
    val level: String,
    val task: String,
    val message: String,
    val delta: Int
) {
    val headline: String
        get() = buildString {
            append(timeText)
            append("  #").append(accountId)
            if (task.isNotBlank()) append("  [").append(task).append("]")
        }
}

/**
 * 服务端托管相关的纯数据转换。
 * 界面只负责渲染，解析与格式化集中在这里，便于单元测试。
 */
object ClaimServerUi {
    const val STATUS_EMPTY = "未执行"

    /** 规范化用户填写的服务端地址：缺协议补 http://，去掉尾部斜杠。 */
    fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        while (value.endsWith("/")) value = value.dropLast(1)
        return value
    }

    /** 毫秒时间戳 → MM-dd HH:mm:ss；0 或非法值返回 "-"。 */
    fun timeText(ms: Long): String {
        if (ms <= 0L) return "-"
        val calendar = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.CHINA,
            "%02d-%02d %02d:%02d:%02d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }

    /** 毫秒时间戳 → MM-dd HH:mm；0 或非法值返回 "-"。 */
    fun shortTimeText(ms: Long): String {
        if (ms <= 0L) return "-"
        val calendar = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.CHINA,
            "%02d-%02d %02d:%02d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }

    /** 服务端 last_status → 中文标签。 */
    fun statusLabel(status: String): String = when (status) {
        "", "unknown" -> STATUS_EMPTY
        "ok" -> "成功"
        "empty" -> "无可领积分"
        "token_expired" -> "登录已过期"
        "error" -> "失败"
        else -> status
    }

    private fun dataArray(json: JSONObject?): org.json.JSONArray? {
        if (json == null || json.optInt("code", -999) != 0) return null
        return json.optJSONArray("data")
    }

    fun accountItems(json: JSONObject?): List<ClaimAccountUi> {
        val array = dataArray(json) ?: return emptyList()
        val out = ArrayList<ClaimAccountUi>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optInt("id", 0)
            val phone = item.optString("phone", "")
            val name = item.optString("name", "").ifBlank { phone }
            out += ClaimAccountUi(
                id = id,
                title = name.ifBlank { "账号 #$id" },
                phone = phone,
                statusLabel = statusLabel(item.optString("last_status", "")),
                lastRunText = shortTimeText(item.optLong("last_run_at", 0L)),
                hasToken = item.optBoolean("has_token", false),
                enabled = item.optBoolean("enabled", true)
            )
        }
        return out
    }

    fun logItems(json: JSONObject?): List<ClaimLogUi> {
        val array = dataArray(json) ?: return emptyList()
        val out = ArrayList<ClaimLogUi>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            out += ClaimLogUi(
                id = item.optInt("id", 0),
                timeText = timeText(item.optLong("ts", 0L)),
                accountId = item.optInt("account_id", 0),
                level = item.optString("level", "info"),
                task = item.optString("task", ""),
                message = item.optString("message", ""),
                delta = item.optInt("delta", 0)
            )
        }
        return out
    }

    /** /api/health 成功时的摘要文案。 */
    fun healthText(json: JSONObject?): String {
        if (json == null || json.optInt("code", -999) != 0) return ""
        val data = json.optJSONObject("data") ?: return ""
        val runTime = data.optString("run_time", "-")
        val count = data.optInt("accounts", 0)
        val running = data.optBoolean("running", false)
        val suffix = if (running) " · 正在执行" else ""
        return "连接成功 · 每日 $runTime 执行 · 已托管 $count 个账号$suffix"
    }

    /** 优先取服务端 msg，其次取调用方传入的 err，最后回落到未知错误。 */
    fun messageOf(json: JSONObject?, err: String?): String {
        val msg = json?.optString("msg", "").orEmpty()
        return when {
            msg.isNotBlank() -> msg
            !err.isNullOrBlank() -> err
            else -> "未知错误"
        }
    }
}