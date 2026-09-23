package com.water.widget

import org.json.JSONArray
import org.json.JSONObject

data class RechargeWallet(
    val endpointId: String,
    val name: String,
    val ownerId: String,
    val balance: Double
)

data class RechargeProduct(
    val id: String,
    val name: String,
    val price: Double,
    val originalPrice: Double?,
    val description: String
) {
    val hasDiscount: Boolean
        get() = originalPrice != null && originalPrice > price

    val displayName: String
        get() = name.normalizedRechargeText()

    val displayDescription: String?
        get() = description.takeIf {
            it.isNotBlank() && it.rechargeMeaningKey() != name.rechargeMeaningKey()
        }

    val displayKey: List<Any?>
        get() = listOf(displayName, price, originalPrice, displayDescription?.normalizedRechargeText())
}

enum class AlipayResultKind {
    SUCCESS,
    PROCESSING,
    CANCELLED,
    FAILED
}

data class AlipayResult(
    val kind: AlipayResultKind,
    val message: String
)

object WalletResponseParser {
    fun dashboardBalance(response: JSONObject, endpointId: String?): Double? {
        val wallets = parseWallets(response)
        if (wallets.isEmpty()) return 0.0
        return (wallets.singleOrNull { it.endpointId == endpointId }
            ?: wallets.singleOrNull())?.balance
    }

    fun parseWallets(response: JSONObject): List<RechargeWallet> {
        val data = successData(response) as? JSONObject
            ?: throw IllegalArgumentException("钱包响应缺少 data")
        val entries = buildList {
            data.optJSONObject("aw")?.let(::add)
            val endpoints = data.optJSONArray("eps")
            if (endpoints != null) {
                for (index in 0 until endpoints.length()) {
                    add(endpoints.requiredObject(index, "钱包列表格式错误"))
                }
            }
        }
        return entries.map(::parseWallet).distinctBy { it.endpointId to it.ownerId }
    }

    fun parseProducts(response: JSONObject): List<RechargeProduct> {
        val data = successData(response)
        if (data == null || data == JSONObject.NULL) return emptyList()
        val products = data as? JSONArray
            ?: throw IllegalArgumentException("充值产品响应格式错误")
        return buildList {
            for (index in 0 until products.length()) {
                val product = products.requiredObject(index, "充值产品格式错误")
                add(
                    RechargeProduct(
                        id = product.requiredString("id", "充值产品缺少 id"),
                        name = product.requiredString("name", "充值产品缺少名称"),
                        price = product.requiredNumber("curPrice", "充值产品缺少价格"),
                        originalPrice = product.optionalNumber("ogiPrice"),
                        description = product.optString("desc", "")
                    )
                )
            }
        }.distinctBy(RechargeProduct::displayKey)
    }

    fun parseOrderId(response: JSONObject): String =
        successDataString(response, "创建订单响应缺少订单号")

    fun parsePaymentString(response: JSONObject): String =
        successDataString(response, "支付响应缺少支付参数")

    private fun parseWallet(wallet: JSONObject): RechargeWallet {
        val endpoint = wallet.optJSONObject("ep")
            ?: throw IllegalArgumentException("钱包缺少端点信息")
        val owner = wallet.optJSONObject("owner")
            ?: throw IllegalArgumentException("钱包缺少用户信息")
        val balance = wallet.optionalNumber("total")
            ?: wallet.optionalNumber("olCash")
            ?: (wallet.optionalNumber("balance") ?: 0.0)
        return RechargeWallet(
            endpointId = endpoint.requiredString("id", "钱包缺少端点 ID"),
            name = endpoint.requiredString("name", "钱包缺少名称"),
            ownerId = owner.requiredString("id", "钱包缺少用户 ID"),
            balance = balance
        )
    }

    private fun successDataString(response: JSONObject, missingMessage: String): String {
        val data = successData(response) as? String
            ?: throw IllegalArgumentException(missingMessage)
        if (data.isBlank()) throw IllegalArgumentException(missingMessage)
        return data
    }

    private fun successData(response: JSONObject): Any? {
        val code = (response.opt("code") as? Number)?.toInt()
            ?: throw IllegalArgumentException("接口响应缺少 code")
        if (code != 0) {
            throw IllegalArgumentException(response.optString("msg", "请求失败（code=$code）"))
        }
        return response.opt("data")
    }

    private fun JSONArray.requiredObject(index: Int, message: String): JSONObject =
        opt(index) as? JSONObject ?: throw IllegalArgumentException(message)

    private fun JSONObject.requiredString(key: String, message: String): String {
        val value = opt(key)?.toString().orEmpty()
        if (value.isBlank()) throw IllegalArgumentException(message)
        return value
    }

    private fun JSONObject.requiredNumber(key: String, message: String): Double =
        optionalNumber(key) ?: throw IllegalArgumentException(message)

    private fun JSONObject.optionalNumber(key: String): Double? {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return null
        return (value as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$key 不是数值")
    }
}

private val WHOLE_NUMBER_DECIMAL = Regex("(\\d+)\\.0+(?=\\D|$)")

private fun String.normalizedRechargeText(): String =
    trim()
        .filterNot { it.isWhitespace() }
        .replace(WHOLE_NUMBER_DECIMAL) { it.groupValues[1] }

private fun String.rechargeMeaningKey(): String =
    normalizedRechargeText()
        .removePrefix("充值")
        .removeSuffix("充值卡")
        .removeSuffix("元")

object AlipayResultParser {
    fun parse(result: Map<String, String>): AlipayResult {
        val status = result["resultStatus"].orEmpty()
        val memo = result["memo"].orEmpty()
        return when (status) {
            "9000" -> AlipayResult(AlipayResultKind.SUCCESS, "充值成功")
            "8000", "6004" -> AlipayResult(
                AlipayResultKind.PROCESSING,
                "支付结果确认中，请稍后刷新余额"
            )
            "6001" -> AlipayResult(AlipayResultKind.CANCELLED, "已取消支付")
            else -> AlipayResult(
                AlipayResultKind.FAILED,
                if (memo.isBlank()) "支付失败" else "支付失败：$memo"
            )
        }
    }
}

object ScoreExchangeParser {
    val amounts = listOf(100, 1000)

    fun requestBody(endpointId: String, score: Int): JSONObject {
        require(endpointId.isNotBlank())
        require(score > 0 && score % 100 == 0)
        return JSONObject()
            .put("ep", JSONObject().put("id", endpointId))
            .put("score", score)
            .put("type", 1)
    }

    fun totalScore(unitScore: Int, quantity: Int, available: Int): Int? {
        if (unitScore !in amounts || quantity <= 0 || quantity > available / unitScore) return null
        return unitScore * quantity
    }

    fun availableScore(response: JSONObject): Int {
        val value = data(response).opt("score")
        return value?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("可用积分格式错误")
    }

    fun billId(response: JSONObject): String =
        data(response).optString("sn", "").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IllegalArgumentException("兑换响应缺少账单号")

    fun isCompleted(response: JSONObject, expectedBillId: String): Boolean {
        val bill = data(response).optJSONObject("bill")
            ?: throw IllegalArgumentException("兑换账单缺失")
        require(bill.optString("id") == expectedBillId) { "兑换账单不匹配" }
        return bill.optInt("status", -1) == 3
    }

    private fun data(response: JSONObject): JSONObject {
        require(response.optInt("code", -1) == 0) { "接口未返回成功结果" }
        return response.optJSONObject("data")
            ?: throw IllegalArgumentException("接口响应缺少 data")
    }
}
