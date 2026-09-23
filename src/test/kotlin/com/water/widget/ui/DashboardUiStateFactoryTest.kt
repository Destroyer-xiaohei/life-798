package com.water.widget.ui

import com.water.widget.Account
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiStateFactoryTest {
    @Test
    fun `聚合状态承载主页账号数据`() {
        val account = Account("账号A").apply { token = "token" }
        val summary = DashboardUiStateFactory.from(account, 1, 1200)
        val accounts = DashboardUiStateFactory.accountsFrom(listOf(account), account.phone)
        val state = DashboardUiState(summary, accounts)

        assertEquals("--", state.summary.scoreTitle)
        assertTrue(state.summary.scoreSubtitle.contains("钱包未获取"))
        assertEquals("积分登录已完成 · 设备登录未完成", state.summary.accountSubtitle)
        assertEquals(1, state.accounts.size)
        assertTrue(state.accounts.single().isCurrent)
    }

    @Test
    fun `未登录时展示空状态并禁用设备能力`() {
        val state = DashboardUiStateFactory.from(null, 0)

        assertEquals("未登录", state.accountTitle)
        assertEquals("完成设备登录后即可使用设备", state.accountSubtitle)
        assertEquals("--", state.scoreTitle)
        assertEquals("登录后查看积分与钱包", state.scoreSubtitle)
        assertFalse(state.hasAccount)
        assertFalse(state.hasAppToken)
        assertFalse(state.hasDevices)
        assertEquals(emptyList<DeviceUiState>(), state.devices)
        assertEquals(0, state.accountCount)
    }

    @Test
    fun `已登录且选择设备时展示统一设备列表`() {
        val account = Account("示例账户").apply {
            name = "测试账户"
            token = "token"
            appToken = "app-token"
            rememberDevice("device-001", "图书馆饮水机")
            selectDevice("device-001")
        }

        val state = DashboardUiStateFactory.from(account, 2, 2380, walletBalance = 5.0)

        assertEquals("测试账户", state.accountTitle)
        assertEquals("设备与积分登录均已完成", state.accountSubtitle)
        assertEquals("≈¥7.38", state.scoreTitle)
        assertEquals("钱包 ¥5.00  +  积分 2380（≈¥2.38）", state.scoreSubtitle)
        assertTrue(state.hasAccount)
        assertTrue(state.hasAppToken)
        assertTrue(state.hasDevices)
        assertEquals(
            listOf(DeviceUiState("device-001", "图书馆饮水机", isControlCenterDevice = true)),
            state.devices
        )
        assertEquals(2, state.accountCount)
    }

    @Test
    fun `只有选择的设备标记为控制中心默认设备`() {
        val account = Account("示例账户").apply {
            token = "token"
            rememberDevice("device-001", "一号设备")
            rememberDevice("device-002", "二号设备")
            selectDevice("device-002")
        }

        val devices = DashboardUiStateFactory.from(account, 1).devices

        assertEquals("device-002", devices.first().id)
        assertTrue(devices.first().isControlCenterDevice)
        assertFalse(devices.last().isControlCenterDevice)
    }

    @Test
    fun `消费流水可按今日本月本年汇总并换算预计饮水量`() {
        val account = Account("示例账户").apply { token = "token" }
        val now = java.util.Calendar.getInstance().timeInMillis
        val earlierThisYear = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -1) }.timeInMillis
        val scoreJson = JSONObject()
            .put("code", 0)
            .put("data", JSONArray()
                .put(
                    JSONObject()
                        .put("ctime", now)
                        .put("type", 107)
                        .put("msg", "饮水消费")
                        .put("data", JSONObject().put("spend", 250))
                )
                .put(
                    JSONObject()
                        .put("ctime", earlierThisYear)
                        .put("type", 107)
                        .put("msg", "饮水消费")
                        .put("data", JSONObject().put("spend", 160))
                )
            )

        val state = DashboardUiStateFactory.from(account, 1, 750, scoreJson)

        assertEquals("¥0.25", state.usage.todayCostText)
        assertEquals("¥0.25", state.usage.monthCostText)
        assertEquals("¥0.41", state.usage.yearCostText)
        assertEquals("780 ml", state.usage.todayWaterText)
        assertEquals("780 ml", state.usage.monthWaterText)
        assertEquals("1.3 L", state.usage.yearWaterText)
    }

    @Test
    fun `兑换前后总额度不变且未知余额不冒充零`() {
        val account = Account("示例账户")
        val before = DashboardUiStateFactory.from(account, 1, 2055, walletBalance = 5.0)
        val after = DashboardUiStateFactory.from(account, 1, 1955, walletBalance = 5.1)
        assertEquals("≈¥7.06", before.scoreTitle)
        assertEquals(before.scoreTitle, after.scoreTitle)
        assertEquals("--", DashboardUiStateFactory.from(account, 1, walletBalance = 5.0).scoreTitle)
    }

    @Test
    fun `账号列表展示每个账号的平台与设备状态`() {
        val a = Account("账号A").apply { token = "token"; appToken = "app"; selectDevice("device-001") }
        val b = Account("账号B").apply { appToken = "app" }

        val states = DashboardUiStateFactory.accountsFrom(listOf(a, b), "账号A")

        assertEquals(2, states.size)
        assertTrue(states[0].isCurrent)
        assertEquals("设备与积分登录均已完成", states[0].subtitle)
        assertEquals("积分登录已完成 · 设备登录已完成", states[0].tokenSummary)
        assertEquals("1 台设备", states[0].deviceSummary)
        assertFalse(states[1].isCurrent)
        assertEquals("设备登录已完成 · 可补充积分登录", states[1].subtitle)
    }
}
