package com.water.widget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScoreExchangeParserTest {
    @Test
    fun `两个档位使用所选钱包且只传总积分`() {
        for (score in listOf(100, 1000)) {
            val body = ScoreExchangeParser.requestBody("selected-wallet", score)
            assertEquals("selected-wallet", body.getJSONObject("ep").getString("id"))
            assertEquals(score, body.getInt("score"))
            assertEquals(1, body.getInt("type"))
            assertFalse(body.has("count"))
        }
    }

    @Test
    fun `解析服务器字符串形式的可用积分而非累计积分`() {
        val response = success(JSONObject().put("score", "13315").put("totalScore", "66595"))
        assertEquals(13315, ScoreExchangeParser.availableScore(response))
    }

    @Test
    fun `缺少可用积分不能用零伪装成功`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScoreExchangeParser.availableScore(success(JSONObject()))
        }
    }

    @Test
    fun `提交返回账单号并核验data下的bill状态`() {
        assertEquals("bill-1", ScoreExchangeParser.billId(success(JSONObject().put("sn", "bill-1"))))
        fun bill(status: Int) = success(JSONObject().put("bill", JSONObject().put("id", "bill-1").put("status", status)))
        assertTrue(ScoreExchangeParser.isCompleted(bill(3), "bill-1"))
        assertFalse(ScoreExchangeParser.isCompleted(bill(1), "bill-1"))
        assertThrows(IllegalArgumentException::class.java) {
            ScoreExchangeParser.isCompleted(bill(3), "different-bill")
        }
    }

    @Test
    fun `失败或没有账单号不能认定兑换成功`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScoreExchangeParser.billId(JSONObject().put("code", -99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScoreExchangeParser.billId(success(JSONObject()))
        }
    }

    @Test
    fun `两种档位按份数计算总积分并一次提交`() {
        for ((unit, quantity, expected) in listOf(Triple(100, 2, 200), Triple(1000, 3, 3000))) {
            val score = ScoreExchangeParser.totalScore(unit, quantity, 5000)
            assertEquals(expected, score)
            val body = ScoreExchangeParser.requestBody("selected-wallet", requireNotNull(score))
            assertEquals(expected, body.getInt("score"))
            assertFalse(body.has("count"))
        }
    }

    @Test
    fun `份数必须有效且不能超出当前积分或溢出`() {
        assertNull(ScoreExchangeParser.totalScore(100, 0, 500))
        assertNull(ScoreExchangeParser.totalScore(100, -1, 500))
        assertNull(ScoreExchangeParser.totalScore(100, 6, 500))
        assertNull(ScoreExchangeParser.totalScore(1000, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(500, ScoreExchangeParser.totalScore(100, 5, 500))
    }

    private fun success(data: JSONObject) = JSONObject().put("code", 0).put("data", data)
}
