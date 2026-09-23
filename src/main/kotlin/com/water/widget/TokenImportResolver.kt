package com.water.widget

import org.json.JSONObject

enum class ImportedTokenPlatform { MAIN, APP, UNKNOWN }

data class InspectedToken(
    val token: String,
    val valid: Boolean,
    val platform: ImportedTokenPlatform,
    val uid: String = "",
    val eid: String = "",
    val phone: String = "",
    val name: String = "",
    val error: String = ""
)

data class TokenImportCandidate(
    val inspection: InspectedToken,
    val requestedPlatforms: Set<ImportedTokenPlatform>
)

data class TokenImportResolution(
    val success: Boolean,
    val mainToken: String = "",
    val appToken: String = "",
    val uid: String = "",
    val eid: String = "",
    val phone: String = "",
    val name: String = "",
    val notices: List<String> = emptyList(),
    val error: String = ""
)

object TokenImportResolver {
    private val phoneKeys = listOf("phone", "phoneNumber", "mobile", "un", "pn")

    fun inspect(token: String, probe: JSONObject?, requestError: String?): InspectedToken {
        if (probe == null) {
            return InspectedToken(token, false, ImportedTokenPlatform.UNKNOWN, error = requestError.orEmpty())
        }
        val viewMain = probe.optJSONObject("viewMain")
        val viewApp = probe.optJSONObject("viewApp")
        val missionMain = probe.optJSONObject("missionMain")
        val masterApp = probe.optJSONObject("masterApp")
        val mainValid = viewMain.isSuccess()
        val appValid = viewApp.isSuccess()
        val valid = mainValid || appValid || missionMain.isSuccess() || masterApp.isSuccess()
        if (!valid) {
            val message = listOf(viewMain, viewApp, missionMain, masterApp)
                .firstNotNullOfOrNull { it?.optString("msg")?.takeIf(String::isNotBlank) }
                ?: requestError.orEmpty()
            return InspectedToken(token, false, ImportedTokenPlatform.UNKNOWN, error = message)
        }

        val platform = missionPlatform(missionMain)

        val data = listOf(viewMain, viewApp)
            .firstNotNullOfOrNull { response ->
                response?.takeIf { it.isSuccess() }?.optJSONObject("data")
            }
            ?: JSONObject()
        return InspectedToken(
            token = token,
            valid = true,
            platform = platform,
            uid = data.firstString("id", "uid"),
            eid = data.firstString("eid"),
            phone = data.firstString(*phoneKeys.toTypedArray()).filter(Char::isDigit),
            name = data.firstString("name", "nickName", "nickname"),
            error = requestError.orEmpty()
        )
    }

    fun resolve(
        candidates: List<TokenImportCandidate>,
        expectedPhone: String,
        existingMainToken: String = "",
        existingAppToken: String = ""
    ): TokenImportResolution {
        if (candidates.isEmpty()) {
            return TokenImportResolution(false, error = "请至少填写一条登录信息")
        }
        val invalid = candidates.firstOrNull { !it.inspection.valid }
        if (invalid != null) {
            return TokenImportResolution(
                false,
                error = invalid.inspection.error.ifBlank { "有登录信息已失效或无法验证" }
            )
        }

        val inspections = candidates.map { it.inspection }
        val uids = inspections.map { it.uid }.filter(String::isNotBlank).toSet()
        val eids = inspections.map { it.eid }.filter(String::isNotBlank).toSet()
        if (uids.size > 1 || eids.size > 1) {
            return TokenImportResolution(false, error = "两条登录信息属于不同账户，未保存")
        }
        val returnedPhones = inspections.map { it.phone }
            .filter { it.length == 11 }
            .toSet()
        if (returnedPhones.size > 1 ||
            (expectedPhone.length == 11 && returnedPhones.any { it != expectedPhone })
        ) {
            return TokenImportResolution(false, error = "登录信息与填写的手机号不一致，未保存")
        }

        val notices = mutableListOf<String>()
        candidates.filter { it.requestedPlatforms.size > 1 }.forEach {
            notices += "重复登录信息已合并"
        }
        candidates.forEach { candidate ->
            val detected = candidate.inspection.platform
            if (detected != ImportedTokenPlatform.UNKNOWN &&
                detected !in candidate.requestedPlatforms
            ) {
                notices += "设备与积分登录信息的位置已自动调整"
            }
        }

        fun choose(platform: ImportedTokenPlatform): String {
            val matches = candidates.filter { it.inspection.platform == platform }
            if (matches.size > 1) {
                notices += if (platform == ImportedTokenPlatform.APP) {
                    "检测到多条设备控制登录，已合并"
                } else {
                    "检测到多条积分服务登录，已合并"
                }
            }
            return matches.lastOrNull { platform in it.requestedPlatforms }
                ?.inspection?.token
                ?: matches.lastOrNull()?.inspection?.token.orEmpty()
        }

        var mainToken = choose(ImportedTokenPlatform.MAIN)
        var appToken = choose(ImportedTokenPlatform.APP)
        candidates.filter { it.inspection.platform == ImportedTokenPlatform.UNKNOWN }.forEach { candidate ->
            notices += "部分登录信息的用途暂时无法确认，请核对填写位置"
            when {
                ImportedTokenPlatform.MAIN in candidate.requestedPlatforms && mainToken.isBlank() ->
                    mainToken = candidate.inspection.token
                ImportedTokenPlatform.APP in candidate.requestedPlatforms && appToken.isBlank() ->
                    appToken = candidate.inspection.token
                else -> return TokenImportResolution(
                    false,
                    error = "部分登录信息的用途无法确认，不能自动调整，请核对填写位置后重试"
                )
            }
        }

        if (mainToken.isBlank()) mainToken = existingMainToken
        if (appToken.isBlank()) appToken = existingAppToken
        if (mainToken.isNotBlank() && mainToken == appToken) {
            val detected = candidates.firstOrNull { it.inspection.token == mainToken }
                ?.inspection?.platform
            if (detected == ImportedTokenPlatform.APP) mainToken = ""
            else appToken = ""
            notices += "相同登录信息已去重"
        }

        val identity = inspections.firstOrNull { it.uid.isNotBlank() || it.eid.isNotBlank() || it.name.isNotBlank() }
        return TokenImportResolution(
            success = true,
            mainToken = mainToken,
            appToken = appToken,
            uid = identity?.uid.orEmpty(),
            eid = identity?.eid.orEmpty(),
            phone = returnedPhones.firstOrNull().orEmpty(),
            name = identity?.name.orEmpty(),
            notices = notices.distinct()
        )
    }

    private fun JSONObject?.isSuccess(): Boolean = this?.optInt("code", -999) == 0

    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun missionPlatform(json: JSONObject?): ImportedTokenPlatform {
        if (!json.isSuccess()) return ImportedTokenPlatform.UNKNOWN
        val missions = json?.optJSONObject("data")?.optJSONArray("missions")
            ?: return ImportedTokenPlatform.UNKNOWN
        var platforms = setOf(1, 5)
        for (index in 0 until missions.length()) {
            val types = missions.optJSONObject(index)?.optJSONArray("stype")
                ?: return ImportedTokenPlatform.UNKNOWN
            val applicable = (0 until types.length()).map {
                types.opt(it) as? Int ?: return ImportedTokenPlatform.UNKNOWN
            }.toSet()
            if (applicable.isEmpty() || applicable.any { it !in setOf(1, 5) }) {
                return ImportedTokenPlatform.UNKNOWN
            }
            platforms = platforms.intersect(applicable)
        }
        return when (platforms.singleOrNull()) {
            1 -> ImportedTokenPlatform.APP
            5 -> ImportedTokenPlatform.MAIN
            else -> ImportedTokenPlatform.UNKNOWN
        }
    }
}
