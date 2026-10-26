package io.github.miyouchat

import io.github.miyouchat.data.*
import io.github.miyouchat.ui.verificationHtml
import io.github.miyouchat.ui.verificationResourceAllowed
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SmsLoginTest {
    // Synthetic input only; no request in these tests can reach a network.
    private val phone = "13800000000"
    private val challengeJson = """{"session_id":"fixture-session","data":{"gt":"abc123","challenge":"def456"}}"""
    private val proof = """{"geetest_challenge":"fixture","geetest_validate":"proof","geetest_seccode":"proof|jordan"}"""
    private val session = Session("101", "fixture-mid", "fixture-token", "fixture-device")

    @Test fun acceptsObjectAndStringChallengeDataAndEncodesOnlyProofFields() {
        for (data in listOf(JSONObject(challengeJson), JSONObject(challengeJson).apply { put("data", getJSONObject("data").toString()) })) {
            val challenge = LoginChallenge.parse(data.toString())
            assertEquals(3, challenge.version)
            val header = challenge.header(JSONObject(proof).put("phone", "must-not-return").toString())
            assertTrue(header.startsWith("fixture-session;"))
            val decoded = JSONObject(String(Base64.getDecoder().decode(header.substringAfter(';'))))
            assertEquals(3, decoded.length()); assertFalse(decoded.has("phone"))
        }
    }
    @Test fun supportsVersionFourAndRejectsIncompleteProof() {
        val challenge = LoginChallenge.parse("""{"session_id":"v4-session","data":{"use_v4":true,"captcha_id":"id123"}}""")
        assertEquals(4, challenge.version)
        assertTrue(verificationHtml(challenge).contains("initGeetest4"))
        try { challenge.header(proof); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun acceptsCurrentCnVersionFourGtAliasWithoutChallenge() {
        val data = JSONObject().put("success", 1).put("gt", "fixturecaptcha").put("new_captcha", true).put("risk_type", "slide")
        val challenge = LoginChallenge.parse(JSONObject().put("session_id", "fixture-session").put("mmt_type", 1).put("data", data.toString()).toString())
        assertEquals(4, challenge.version)
        assertEquals("fixturecaptcha", JSONObject(challenge.options).getString("captchaId"))
        assertEquals("slide", JSONObject(challenge.options).getString("riskType"))
        val result = """{"captcha_id":"fixturecaptcha","lot_number":"lot","pass_token":"pass","gen_time":"1234","captcha_output":"output"}"""
        val header = challenge.header(result)
        assertEquals(5, JSONObject(String(Base64.getDecoder().decode(header.substringAfter(';')))).length())
    }
    @Test fun rejectsExecutableConfigAndUntrustedResources() {
        val malicious = JSONObject(challengeJson).apply { getJSONObject("data").put("gt", "</script><script>") }
        try { LoginChallenge.parse(malicious.toString()); fail() } catch (_: IllegalArgumentException) { }
        for (url in listOf("http://static.geetest.com/a", "https://geetest.com.evil.test/a", "file:///a", "https://@static.geetest.com/a", "https://static.geetest.com:444/a")) assertFalse(verificationResourceAllowed(url))
        assertTrue(verificationResourceAllowed("https://static.geetest.com/static/js/gt.0.5.0.js"))
    }
    @Test fun parsesNumericActionAndServerCooldown() {
        assertEquals(SmsReceipt("1", 42), SmsReceipt.parse(JSONObject("""{"action_type":1,"countdown":"42"}""")))
        assertEquals(60, SmsReceipt.parse(JSONObject("""{"action_type":"login"}""")).cooldown)
        try { SmsReceipt.parse(JSONObject()); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun manualChallengeResumesSameSendOnlyOnceThenEnablesLogin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var sends = 0; var logins = 0; var saved = 0
            val flow = SmsLogin(scope, send = { number, aigis ->
                assertEquals(phone, number)
                sends++
                if (aigis.isBlank()) throw ApiFailure(-3101, challengeJson)
                assertTrue(aigis.startsWith("fixture-session;"))
                SmsReceipt("login", 42)
            }, login = { number, code, action, _ ->
                assertEquals(phone, number); assertEquals("123456", code); assertEquals("login", action)
                logins++; session
            }, signedIn = { assertEquals(session, it); yield(); saved++ })
            flow.request(phone)
            assertEquals(1, sends); assertFalse(flow.state.value.busy)
            val challenge = flow.state.value.challenge!!
            flow.request(phone); assertEquals(1, sends)
            flow.verified(challenge, proof); flow.verified(challenge, proof)
            assertEquals(2, sends); assertTrue(flow.state.value.canSubmit); assertEquals(42, flow.state.value.cooldown)
            flow.request(phone); assertEquals(2, sends)
            flow.submit(phone, "123456"); yield()
            assertEquals(1, logins); assertEquals(1, saved)
            assertEquals(SmsLoginState(), flow.state.value)
        } finally { scope.cancel() }
    }
    @Test fun loginMayAlsoRequireVerificationAndCancelDoesNotSend() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var logins = 0; var saves = 0
            val flow = SmsLogin(scope, { _, _ -> SmsReceipt("login", 60) }, { _, _, _, aigis ->
                logins++; if (aigis.isBlank()) throw ApiFailure(-3101, challengeJson); session
            }, { saves++ })
            flow.request(phone); flow.submit(phone, "123456")
            val old = flow.state.value.challenge!!
            flow.verificationFailed(old)
            flow.verified(old, proof)
            assertEquals(1, logins); assertEquals(0, saves); assertTrue(flow.state.value.error)
            flow.submit(phone, "123456")
            flow.verified(flow.state.value.challenge!!, proof)
            assertEquals(3, logins); assertEquals(1, saves)
        } finally { scope.cancel() }
    }
    @Test fun phoneChangeInvalidatesCaptchaAndLateChallengeCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var sends = 0; var logins = 0
            val flow = SmsLogin(scope, { _, _ -> sends++; throw ApiFailure(-3101, challengeJson) }, { _, _, _, _ -> logins++; session }, {})
            flow.request(phone)
            val old = flow.state.value.challenge!!
            flow.phoneChanged("13900000000")
            flow.verified(old, proof)
            flow.submit("13900000000", "123456")
            assertEquals(1, sends); assertEquals(0, logins); assertFalse(flow.state.value.canSubmit)
        } finally { scope.cancel() }
    }
    @Test fun repeatedClicksAndAccountResetDiscardLateNetworkResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var sends = 0
            val gate = CompletableDeferred<Unit>()
            val flow = SmsLogin(scope, { _, _ -> sends++; gate.await(); SmsReceipt("login", 60) }, { _, _, _, _ -> session }, {})
            flow.request(phone); flow.request(phone)
            assertEquals(1, sends); assertTrue(flow.state.value.busy)
            flow.reset(); gate.complete(Unit); yield()
            assertEquals(SmsLoginState(), flow.state.value)
        } finally { scope.cancel() }
    }
    @Test fun failedNetworkRequestsRemainRetryableWithoutFalseCountdown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var sends = 0
            val flow = SmsLogin(scope, { _, _ -> sends++; throw java.io.IOException("fixture") }, { _, _, _, _ -> session }, {})
            flow.request(phone); assertTrue(flow.state.value.error)
            assertEquals(0, flow.state.value.cooldown); assertFalse(flow.state.value.canSubmit)
            flow.request(phone); assertEquals(2, sends)
        } finally { scope.cancel() }
    }
}
