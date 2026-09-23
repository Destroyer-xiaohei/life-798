package com.water.widget.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClaimServerUiTest {
    @Test
    fun `服务端地址补全协议并去掉尾部斜杠`() {
        assertEquals("http://192.168.1.10:8787", ClaimServerUi.normalizeUrl("192.168.1.10:8787"))
        assertEquals("http://192.168.1.10:8787", ClaimServerUi.normalizeUrl("  http://192.168.1.10:8787///  "))
        assertEquals("https://points.example.com", ClaimServerUi.normalizeUrl("https://points.example.com/"))
        assertEquals("", ClaimServerUi.normalizeUrl("   "))
    }

    @Test
    fun `时间戳格式化与非法值回退`() {
        assertEquals("-", ClaimServerUi.timeText(0L))
        assertEquals("-", ClaimServerUi.shortTimeText(0L))

        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 23, 8, 5, 30)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("09-23 08:05:30", ClaimServerUi.timeText(calendar.timeInMillis))
        assertEquals("09-23 08:05", ClaimServerUi.shortTimeText(calendar.timeInMillis))
    }

    @Test
    fun `托管状态码映射为中文标签`() {
        assertEquals("未执行", ClaimServerUi.statusLabel(""))
        assertEquals("未执行", ClaimServerUi.statusLabel("unknown"))
        assertEquals("成功", ClaimServerUi.statusLabel("ok"))
        assertEquals("无可领积分", ClaimServerUi.statusLabel("empty"))
        assertEquals("登录已过期", ClaimServerUi.statusLabel("token_expired"))
        assertEquals("失败", ClaimServerUi.statusLabel("error"))
        assertEquals("自定义状态", ClaimServerUi.statusLabel("自定义状态"))
    }

    @Test
    fun `账号列表解析字段并在失败时返回空`() {
        val data = JSONArray()
        data.put(
            JSONObject()
                .put("id", 3)
                .put("phone", "13800000000")
                .put("name", "")
                .put("last_status", "ok")
                .put("last_run_at", 0L)
                .put("has_token", true)
                .put("enabled", true)
        )
        val ok = JSONObject().put("code", 0).put("data", data)
        val items = ClaimServerUi.accountItems(ok)

        assertEquals(1, items.size)
        assertEquals(3, items.single().id)
        assertEquals("13800000000", items.single().title)
        assertEquals("成功", items.single().statusLabel)
        assertEquals("-", items.single().lastRunText)
        assertTrue(items.single().hasToken)

        val failed = JSONObject().put("code", -1).put("data", data)
        assertTrue(ClaimServerUi.accountItems(failed).isEmpty())
        assertTrue(ClaimServerUi.accountItems(null).isEmpty())
    }

    @Test
    fun `日志解析保留任务与级别并生成标题`() {
        val data = JSONArray()
        data.put(
            JSONObject()
                .put("id", 9)
                .put("account_id", 1)
                .put("ts", 0L)
                .put("level", "success")
                .put("task", "每日签到")
                .put("message", "+10 积分")
                .put("delta", 10)
        )
        val items = ClaimServerUi.logItems(JSONObject().put("code", 0).put("data", data))

        assertEquals(1, items.size)
        val log = items.single()
        assertEquals("success", log.level)
        assertEquals("每日签到", log.task)
        assertEquals(10, log.delta)
        assertEquals("-  #1  [每日签到]", log.headline)
    }

    @Test
    fun `健康检查摘要包含执行时间与账号数`() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject()
                    .put("running", true)
                    .put("run_time", "00:05")
                    .put("accounts", 2)
            )
        val text = ClaimServerUi.healthText(json)

        assertTrue(text.contains("每日 00:05 执行"))
        assertTrue(text.contains("已托管 2 个账号"))
        assertTrue(text.contains("正在执行"))
        assertEquals("", ClaimServerUi.healthText(JSONObject().put("code", -1)))
    }

    @Test
    fun `错误信息优先取服务端 msg`() {
        assertEquals("ApiKey 不正确", ClaimServerUi.messageOf(JSONObject().put("msg", "ApiKey 不正确"), "timeout"))
        assertEquals("timeout", ClaimServerUi.messageOf(null, "timeout"))
        assertEquals("未知错误", ClaimServerUi.messageOf(null, null))
        assertFalse(ClaimServerUi.messageOf(JSONObject().put("code", 0), null).isBlank())
    }
}