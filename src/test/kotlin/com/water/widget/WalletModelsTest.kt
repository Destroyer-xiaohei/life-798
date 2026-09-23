package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletModelsTest {
    @Test
    fun `钱包响应按端点和用户去重并优先使用总余额`() {
        val primary = wallet("endpoint-1", "主钱包", "owner-1")
            .put("olCash", 12.5)
            .put("total", 99)
            .put("balance", 88)
        val duplicate = wallet("endpoint-1", "主钱包", "owner-1").put("olCash", 10)
        val second = wallet("endpoint-2", "宿舍钱包", "owner-1")
            .put("total", 7.25)
            .put("balance", 5)
        val third = wallet("endpoint-3", "公共钱包", "owner-1")
            .put("total", 0)
            .put("balance", 4.5)
        val response = success(
            JSONObject()
                .put("aw", primary)
                .put("eps", JSONArray().put(duplicate).put(second).put(third))
        )

        val wallets = WalletResponseParser.parseWallets(response)

        assertEquals(3, wallets.size)
        assertEquals("endpoint-1", wallets[0].endpointId)
        assertEquals(99.0, wallets[0].balance, 0.0)
        assertEquals("endpoint-2", wallets[1].endpointId)
        assertEquals(7.25, wallets[1].balance, 0.0)
        assertEquals("endpoint-3", wallets[2].endpointId)
        assertEquals(0.0, wallets[2].balance, 0.0)
    }

    @Test
    fun `首页只取对应钱包且不重复合并不同端点`() {
        val first = wallet("ep-1", "钱包一", "owner").put("total", 5.0)
        val second = wallet("ep-2", "钱包二", "owner").put("total", 2.0)
        val single = success(JSONObject().put("aw", first).put("eps", JSONArray().put(first)))
        assertEquals(5.0, WalletResponseParser.dashboardBalance(single, "unknown")!!, 0.0)
        val multiple = success(JSONObject().put("aw", first).put("eps", JSONArray().put(first).put(second)))
        assertEquals(2.0, WalletResponseParser.dashboardBalance(multiple, "ep-2")!!, 0.0)
        assertEquals(null, WalletResponseParser.dashboardBalance(multiple, "unknown"))
    }

    @Test
    fun `充值产品解析服务端价格与优惠价格`() {
        val response = success(
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "product-1")
                        .put("name", "充值 20 元")
                        .put("curPrice", 20)
                        .put("ogiPrice", 25)
                        .put("desc", "赠送 5 元")
                )
                .put(
                    JSONObject()
                        .put("id", "product-2")
                        .put("name", "充值 50 元")
                        .put("curPrice", 50)
                )
        )

        val products = WalletResponseParser.parseProducts(response)

        assertEquals(2, products.size)
        assertEquals(20.0, products[0].price, 0.0)
        assertTrue(products[0].hasDiscount)
        assertFalse(products[1].hasDiscount)
    }

    @Test
    fun `重复面额且没有额外权益的充值产品只保留一次`() {
        val response = success(
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "product-1")
                        .put("name", "充值20元")
                        .put("curPrice", 20)
                        .put("desc", "充值20元")
                )
                .put(
                    JSONObject()
                        .put("id", "product-2")
                        .put("name", "充值 20.0 元")
                        .put("curPrice", 20)
                        .put("desc", "充值 20.0 元")
                )
        )

        val products = WalletResponseParser.parseProducts(response)

        assertEquals(1, products.size)
        assertEquals("充值20元", products.single().displayName)
        assertEquals(null, products.single().displayDescription)
    }

    @Test
    fun `充值订单请求使用钱包端点和钱包用户`() {
        val body = IlifeApi.rechargeOrderBody("endpoint-1", "owner-1", "product-1")

        assertEquals(1, body.getInt("cata"))
        assertEquals("owner-1", body.getJSONObject("contact").getString("id"))
        assertEquals("endpoint-1", body.getJSONObject("ep").getString("id"))
        assertEquals("owner-1", body.getJSONObject("owner").getString("id"))
        assertEquals("钱包充值", body.getString("note"))
        assertEquals("product-1", body.getJSONArray("prds").getJSONObject(0).getString("id"))
        assertEquals(1, body.getJSONArray("prds").getJSONObject(0).getInt("count"))
    }

    @Test
    fun `订单号和支付宝支付字符串读取 data`() {
        assertEquals("order-1", WalletResponseParser.parseOrderId(success("order-1")))
        assertEquals("signed-payment", WalletResponseParser.parsePaymentString(success("signed-payment")))
    }

    @Test
    fun `支付宝支付结果区分成功处理中取消和失败`() {
        assertEquals(
            AlipayResultKind.SUCCESS,
            AlipayResultParser.parse(mapOf("resultStatus" to "9000")).kind
        )
        val processing = AlipayResultParser.parse(mapOf("resultStatus" to "8000"))
        assertEquals(AlipayResultKind.PROCESSING, processing.kind)
        assertEquals("支付结果确认中，请稍后刷新余额", processing.message)
        assertEquals(
            AlipayResultKind.PROCESSING,
            AlipayResultParser.parse(mapOf("resultStatus" to "6004")).kind
        )
        assertEquals(
            AlipayResultKind.CANCELLED,
            AlipayResultParser.parse(mapOf("resultStatus" to "6001")).kind
        )
        assertEquals(
            AlipayResultKind.FAILED,
            AlipayResultParser.parse(mapOf("resultStatus" to "4000", "memo" to "订单支付失败")).kind
        )
    }

    @Test
    fun `积分兑换金额计入钱包总余额`() {
        val entry = wallet("endpoint-1", "主钱包", "owner-1")
            .put("olCash", 0)
            .put("olGift", 5)
            .put("total", 5)
        val wallets = WalletResponseParser.parseWallets(success(JSONObject().put("aw", entry)))

        assertEquals(5.0, wallets.single().balance, 0.0)
    }

    @Test
    fun `总余额缺失时保留现金余额回退`() {
        val entry = wallet("endpoint-1", "主钱包", "owner-1").put("olCash", 3)
        val wallets = WalletResponseParser.parseWallets(success(JSONObject().put("aw", entry)))

        assertEquals(3.0, wallets.single().balance, 0.0)
    }

    private fun success(data: Any): JSONObject = JSONObject()
        .put("code", 0)
        .put("data", data)

    private fun wallet(endpointId: String, name: String, ownerId: String): JSONObject =
        JSONObject()
            .put("ep", JSONObject().put("id", endpointId).put("name", name))
            .put("owner", JSONObject().put("id", ownerId))
}
