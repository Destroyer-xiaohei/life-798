package com.water.widget.ui

import android.content.Context
import com.water.widget.WaterConsumption
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 将服务端当前可见的积分流水与本地确认的设备账单合并到日汇总。
 * 服务端缩短流水窗口时，已经记录到本地的历史不会随之减少。
 */
object UsageHistoryStore {
    private const val PREFS = "usage_history_v1"

    fun mergeAndRead(context: Context, accountKey: String, scoreJson: JSONObject?): WaterUsageUiState {
        if (accountKey.isBlank()) return WaterUsageUiState()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "account_${digest(accountKey)}"
        val ledger = UsageHistoryLedger.fromJson(
            runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        )
        if (ledger.merge(scoreJson)) {
            prefs.edit().putString(key, ledger.toJson().toString()).apply()
        }
        return ledger.toUiState()
    }

    fun record(context: Context, accountKey: String, consumption: WaterConsumption) {
        if (accountKey.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "account_${digest(accountKey)}"
        val ledger = UsageHistoryLedger.fromJson(
            runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        )
        if (ledger.recordLocal(consumption.historyKey, consumption.occurredAt, consumption.scoreEquivalent)) {
            prefs.edit().putString(key, ledger.toJson().toString()).apply()
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal class UsageHistoryLedger(
    private val seen: LinkedHashSet<String> = LinkedHashSet(),
    private val dayTotals: MutableMap<String, Int> = linkedMapOf(),
    private var localThrough: Long = 0L
) {
    fun merge(scoreJson: JSONObject?): Boolean {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return false
        val records = scoreJson.optJSONArray("data") ?: return false
        var changed = false
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val data = record.optJSONObject("data")
            val amount = readSpentScore(record, data)
            if (amount <= 0) continue
            val time = normalizeEpochMillis(record.optLong("ctime", 0L))
            if (!isConsumption(record, data)) {
                // 只撤销旧版本确实累计过的同一条流水，不清空窗口外的历史。
                if (seen.remove(fingerprint(recordIdentity(record)))) {
                    val day = dayKey(time)
                    dayTotals[day] = (dayTotals[day] ?: 0) - amount
                    changed = true
                }
                continue
            }
            if (time <= localThrough) continue
            if (record(recordIdentity(record), time, amount)) changed = true
        }
        return changed
    }

    fun recordLocal(historyKey: String, time: Long, amount: Int): Boolean {
        val occurredAt = normalizeEpochMillis(time)
        if (historyKey.isBlank() || occurredAt <= 0L || amount <= 0) return false
        val boundaryChanged = occurredAt > localThrough
        if (boundaryChanged) localThrough = occurredAt
        return record(historyKey, occurredAt, amount) || boundaryChanged
    }

    private fun record(historyKey: String, occurredAt: Long, amount: Int): Boolean {
        if (!seen.add(fingerprint(historyKey))) return false
        val day = dayKey(occurredAt)
        dayTotals[day] = (dayTotals[day] ?: 0) + amount
        return true
    }

    fun toUiState(now: Calendar = Calendar.getInstance()): WaterUsageUiState {
        if (dayTotals.isEmpty()) return WaterUsageUiState()
        val todayKey = dayKey(now.timeInMillis)
        val monthPrefix = "%04d-%02d".format(
            Locale.ROOT,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1
        )
        val yearPrefix = "%04d".format(Locale.ROOT, now.get(Calendar.YEAR))
        val today = dayTotals[todayKey] ?: 0
        val month = dayTotals.filterKeys { it.startsWith(monthPrefix) }.values.sum()
        val year = dayTotals.filterKeys { it.startsWith(yearPrefix) }.values.sum()
        return WaterUsageUiState(
            todayCostText = moneyText(today),
            monthCostText = moneyText(month),
            yearCostText = moneyText(year),
            todayWaterText = waterText(today),
            monthWaterText = waterText(month),
            yearWaterText = waterText(year)
        )
    }

    fun toJson(): JSONObject {
        val days = JSONObject()
        dayTotals.forEach { (day, total) -> days.put(day, total) }
        return JSONObject()
            .put("seen", JSONArray(seen.toList()))
            .put("days", days)
            .put("localThrough", localThrough)
    }

    companion object {
        fun fromJson(json: JSONObject): UsageHistoryLedger {
            val seen = LinkedHashSet<String>()
            json.optJSONArray("seen")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(seen::add)
                }
            }
            val days = linkedMapOf<String, Int>()
            json.optJSONObject("days")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val day = keys.next()
                    values.optInt(day, 0).takeIf { it >= 0 }?.let { days[day] = it }
                }
            }
            return UsageHistoryLedger(seen, days, json.optLong("localThrough", 0L))
        }

        private fun recordIdentity(record: JSONObject): String {
            return arrayOf("id", "sid", "serialNo", "orderId", "bizId")
                .firstNotNullOfOrNull { key -> record.optString(key, "").takeIf(String::isNotBlank) }
                ?: record.toString()
        }

        private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun dayKey(time: Long): String {
            val date = Calendar.getInstance().apply { timeInMillis = time }
            return "%04d-%02d-%02d".format(
                Locale.ROOT,
                date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH)
            )
        }

        private fun readSpentScore(record: JSONObject, data: JSONObject?): Int {
            val keys = arrayOf("spend", "score", "changeScore", "change_score", "amount", "value", "num", "points")
            readNonZero(data, "spend")?.let { return kotlin.math.abs(it) }
            readNonZero(record, "spend")?.let { return kotlin.math.abs(it) }
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            if (type != 107) return 0
            for (key in keys) {
                if (key != "spend") readNonZero(record, key)?.let { return kotlin.math.abs(it) }
            }
            if (data != null) {
                for (key in keys) {
                    if (key != "spend") readNonZero(data, key)?.let { return kotlin.math.abs(it) }
                }
            }
            return 0
        }

        private fun isConsumption(record: JSONObject, data: JSONObject?): Boolean {
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            return type == 107
        }

        private fun readNonZero(json: JSONObject?, key: String): Int? {
            if (json == null || !json.has(key) || json.isNull(key)) return null
            return json.optInt(key, 0).takeIf { it != 0 }
        }

        private fun normalizeEpochMillis(value: Long): Long = when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }

        private fun moneyText(score: Int): String = "¥${String.format(Locale.CHINA, "%.2f", score / 1000.0)}"

        private fun waterText(score: Int): String {
            val millilitres = score * 500 / 160
            return if (millilitres >= 1000) {
                String.format(Locale.CHINA, "%.1f L", millilitres / 1000.0)
            } else {
                val roundedMillilitres = (millilitres + 5) / 10 * 10
                "$roundedMillilitres ml"
            }
        }
    }
}
