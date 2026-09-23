package com.water.widget.ui

import com.water.widget.WaterBillParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class UsageHistoryLedgerTest {
    @Test
    fun `重复同步同一流水不会重复累计`() {
        val now = Calendar.getInstance()
        val response = response(now.timeInMillis, 160, "order-1")
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response))
        assertFalse(ledger.merge(response))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
    }

    @Test
    fun `服务端后续窗口缩短时本地历史仍保留`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val lastMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val ledger = UsageHistoryLedger()
        ledger.merge(response(lastMonth.timeInMillis, 320, "old-order"))
        ledger.merge(response(now.timeInMillis, 160, "new-order"))

        val restored = UsageHistoryLedger.fromJson(ledger.toJson())
        val state = restored.toUiState(now)

        assertEquals("¥0.16", state.monthCostText)
        assertEquals("¥0.48", state.yearCostText)
    }

    @Test
    fun `嵌套消费金额不被顶层零积分遮住`() {
        val now = Calendar.getInstance()
        val response = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("id", "nested-spend")
                        .put("type", 107)
                        .put("ctime", now.timeInMillis)
                        .put("score", 0)
                        .put("data", JSONObject().put("spend", 160))
                )
            )
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
        assertEquals("500 ml", ledger.toUiState(now).todayWaterText)
    }

    @Test
    fun `秒级时间戳计入当天`() {
        val now = Calendar.getInstance().apply { set(Calendar.MILLISECOND, 0) }
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response(now.timeInMillis / 1000L, 160, "seconds-order")))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
    }

    @Test
    fun `缺少积分登录时的本地账单阻止旧积分流水重复累计`() {
        val now = Calendar.getInstance()
        val billJson = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("id", "shared-order")
                        .put("type", 91)
                        .put("status", 3)
                        .put("ctime", now.timeInMillis)
                        .put("payment", 0.16)
                )
            )
        val bill = requireNotNull(WaterBillParser.latestSince(billJson, 0L))
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.recordLocal(bill.historyKey, bill.occurredAt, bill.scoreEquivalent))
        val restored = UsageHistoryLedger.fromJson(ledger.toJson())
        assertFalse(restored.merge(response(now.timeInMillis, 160, "different-score-record")))
        assertTrue(restored.merge(response(now.timeInMillis + 1_000L, 160, "future-score-record")))
        assertEquals("¥0.32", restored.toUiState(now).todayCostText)
    }

    @Test
    fun `兑换抽奖不计用水且撤销旧误计仅一次`() {
        val now = Calendar.getInstance()
        val water = response(now.timeInMillis, 180, "water").getJSONArray("data").getJSONObject(0)
        val exchange = response(now.timeInMillis, 100, "exchange").getJSONArray("data").getJSONObject(0).put("type", 105)
        val lottery = response(now.timeInMillis, 50, "lottery").getJSONArray("data").getJSONObject(0).put("type", 106)
        val json = JSONObject().put("code", 0).put("data", JSONArray().put(water).put(exchange).put(lottery))
        val fresh = UsageHistoryLedger()
        fresh.merge(json)
        assertEquals("¥0.18", fresh.toUiState(now).todayCostText)
        val old = UsageHistoryLedger()
        old.recordLocal("water", now.timeInMillis, 180)
        old.recordLocal("exchange", now.timeInMillis, 100)
        old.recordLocal("lottery", now.timeInMillis, 50)
        val restored = UsageHistoryLedger.fromJson(old.toJson())
        assertTrue(restored.merge(json))
        assertEquals("¥0.18", restored.toUiState(now).todayCostText)
        val again = UsageHistoryLedger.fromJson(restored.toJson())
        assertFalse(again.merge(json))
        assertEquals("¥0.18", again.toUiState(now).todayCostText)
    }

    private fun response(time: Long, spend: Int, id: String) = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("ctime", time)
                    .put("type", 107)
                    .put("data", JSONObject().put("spend", spend))
            )
        )
}
