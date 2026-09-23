package com.water.widget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenImportResolverTest {
    @Test
    fun inspectUsesObservedMissionPlatformSets() {
        val device = inspectMissions("device-token", "[[1,5],[1],[1],[1],[1]]")
        val score = inspectMissions("score-token", "[[1,5],[5],[5],[5]]")

        assertTrue(device.valid)
        assertTrue(score.valid)
        assertEquals(ImportedTokenPlatform.APP, device.platform)
        assertEquals(ImportedTokenPlatform.MAIN, score.platform)
        assertEquals("u1", device.uid)
    }

    @Test
    fun inspectDoesNotGuessFromEmptySharedOrConflictingPlatforms() {
        for (types in listOf("[]", "[[1,5]]", "[[1],[5]]")) {
            val result = inspectMissions("token", types)
            assertTrue(result.valid)
            assertEquals(types, ImportedTokenPlatform.UNKNOWN, result.platform)
        }
    }

    @Test
    fun inspectRequiresSuccessfulWellFormedMissionEvidence() {
        val probes = listOf(
            probeMissions("[[1]]").apply {
                getJSONObject("missionMain").put("code", -99)
            },
            probeMissions("[[1]]").apply {
                getJSONObject("missionMain").getJSONObject("data")
                    .getJSONArray("missions").getJSONObject(0).remove("stype")
            },
            probeMissions("[[1,9]]")
        )
        for (probe in probes) {
            assertEquals(
                ImportedTokenPlatform.UNKNOWN,
                TokenImportResolver.inspect("token", probe, null).platform
            )
        }
    }

    @Test
    fun inspectIgnoresNamesAndUnequalProfileRequestResults() {
        val probe = probeMissions("[[1,5]]")
        probe.put("viewApp", JSONObject().put("code", -99))
        probe.getJSONObject("viewMain").getJSONObject("data").put("applicationType", "1,5")
        probe.getJSONObject("missionMain").getJSONObject("data")
            .getJSONArray("missions").getJSONObject(0).put("name", "官方APP客户端任务")

        assertEquals(
            ImportedTokenPlatform.UNKNOWN,
            TokenImportResolver.inspect("token", probe, "request failed").platform
        )
    }

    @Test
    fun resolveCorrectsReversedTokens() {
        val resolution = TokenImportResolver.resolve(
            candidates = listOf(
                TokenImportCandidate(
                    inspectMissions("app-token", "[[1,5],[1],[1],[1],[1]]"),
                    setOf(ImportedTokenPlatform.MAIN)
                ),
                TokenImportCandidate(
                    inspectMissions("main-token", "[[1,5],[5],[5],[5]]"),
                    setOf(ImportedTokenPlatform.APP)
                )
            ),
            expectedPhone = "13800000000"
        )

        assertTrue(resolution.success)
        assertEquals("main-token", resolution.mainToken)
        assertEquals("app-token", resolution.appToken)
        assertTrue(resolution.notices.contains("设备与积分登录信息的位置已自动调整"))
    }

    @Test
    fun resolveMergesTwoAppTokensAndPrefersAppField() {
        val resolution = TokenImportResolver.resolve(
            candidates = listOf(
                candidate("older-app-token", ImportedTokenPlatform.APP, ImportedTokenPlatform.MAIN),
                candidate("newer-app-token", ImportedTokenPlatform.APP, ImportedTokenPlatform.APP)
            ),
            expectedPhone = "13800000000"
        )

        assertTrue(resolution.success)
        assertEquals("", resolution.mainToken)
        assertEquals("newer-app-token", resolution.appToken)
        assertTrue(resolution.notices.contains("检测到多条设备控制登录，已合并"))
    }

    @Test
    fun resolveDeduplicatesSameTokenInBothFields() {
        val resolution = TokenImportResolver.resolve(
            candidates = listOf(
                TokenImportCandidate(
                    inspection = InspectedToken(
                        token = "same-token",
                        valid = true,
                        platform = ImportedTokenPlatform.APP,
                        uid = "u1"
                    ),
                    requestedPlatforms = setOf(
                        ImportedTokenPlatform.MAIN,
                        ImportedTokenPlatform.APP
                    )
                )
            ),
            expectedPhone = "13800000000"
        )

        assertTrue(resolution.success)
        assertEquals("", resolution.mainToken)
        assertEquals("same-token", resolution.appToken)
        assertTrue(resolution.notices.contains("重复登录信息已合并"))
    }

    @Test
    fun resolveRejectsDifferentAccounts() {
        val resolution = TokenImportResolver.resolve(
            candidates = listOf(
                candidate("main-token", ImportedTokenPlatform.MAIN, ImportedTokenPlatform.MAIN, "u1"),
                candidate("app-token", ImportedTokenPlatform.APP, ImportedTokenPlatform.APP, "u2")
            ),
            expectedPhone = "13800000000"
        )

        assertFalse(resolution.success)
        assertTrue(resolution.error.contains("不同账户"))
    }

    @Test
    fun resolveKeepsUnknownInputsInPlaceAndWarns() {
        val result = TokenImportResolver.resolve(
            candidates = listOf(
                candidate("first", ImportedTokenPlatform.UNKNOWN, ImportedTokenPlatform.MAIN),
                candidate("second", ImportedTokenPlatform.UNKNOWN, ImportedTokenPlatform.APP)
            ),
            expectedPhone = "13800000000"
        )

        assertTrue(result.success)
        assertEquals("first", result.mainToken)
        assertEquals("second", result.appToken)
        assertEquals(listOf("部分登录信息的用途暂时无法确认，请核对填写位置"), result.notices)
    }

    @Test
    fun resolveDoesNotMoveUnknownTokenWhenItsSlotIsOccupied() {
        val result = TokenImportResolver.resolve(
            candidates = listOf(
                candidate("device", ImportedTokenPlatform.APP, ImportedTokenPlatform.MAIN),
                candidate("unknown", ImportedTokenPlatform.UNKNOWN, ImportedTokenPlatform.APP)
            ),
            expectedPhone = "13800000000"
        )

        assertFalse(result.success)
        assertTrue(result.error.contains("不能自动调整"))
    }

    private fun inspectMissions(token: String, types: String) =
        TokenImportResolver.inspect(token, probeMissions(types), null)

    private fun probeMissions(types: String): JSONObject {
        // Preserve only the platform-set structure observed in read-only probes, not account data.
        val sets = org.json.JSONArray(types)
        val missions = org.json.JSONArray()
        for (index in 0 until sets.length()) {
            missions.put(JSONObject().put("stype", sets.getJSONArray(index)))
        }
        return JSONObject()
            .put("viewMain", JSONObject().put("code", 0).put("data", JSONObject().put("id", "u1")))
            .put("viewApp", JSONObject().put("code", 0).put("data", JSONObject().put("id", "u1")))
            .put("missionMain", JSONObject().put("code", 0).put("data", JSONObject().put("missions", missions)))
    }

    private fun candidate(
        token: String,
        detected: ImportedTokenPlatform,
        requested: ImportedTokenPlatform,
        uid: String = "u1"
    ) = TokenImportCandidate(
        inspection = InspectedToken(token, true, detected, uid = uid),
        requestedPlatforms = setOf(requested)
    )
}
