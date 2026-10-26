package io.github.miyouchat.data

import org.json.JSONObject
import java.util.Base64

/** Short-lived verification data, kept in memory and never included in logs. */
class LoginChallenge private constructor(val session: String, val version: Int, val options: String) {
    fun header(result: String): String {
        require(result.length in 2..16384)
        val proof = JSONObject(result)
        val names = if (version == 4) listOf("captcha_id", "lot_number", "pass_token", "gen_time", "captcha_output")
            else listOf("geetest_challenge", "geetest_validate", "geetest_seccode")
        val clean = JSONObject()
        names.forEach { name ->
            val value = proof.optString(name)
            require(value.isNotBlank() && value.length <= 8192 && value.none { it.isISOControl() })
            clean.put(name, value)
        }
        return "$session;${Base64.getEncoder().encodeToString(clean.toString().toByteArray(Charsets.UTF_8))}"
    }

    companion object {
        fun parse(raw: String): LoginChallenge {
            require(raw.length in 2..32768)
            val envelope = JSONObject(raw)
            val session = envelope.getString("session_id")
            require(session.matches(Regex("[A-Za-z0-9_.:-]{1,256}")))
            val data = when (val value = envelope.opt("data")) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> throw IllegalArgumentException("Missing verification data")
            }
            // Current CN responses name the v4 captcha ID "gt" and omit "challenge".
            val version = if (data.optBoolean("use_v4") || data.has("captcha_id") ||
                (data.has("risk_type") && data.optString("challenge").isBlank())) 4 else 3
            val options = JSONObject().put("product", "popup")
            if (version == 4) {
                val id = data.optString("captcha_id").ifBlank { data.getString("gt") }
                require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                options.put("captchaId", id).put("language", "zho").put("protocol", "https://")
                    .put("userInfo", JSONObject().put("session_id", session).toString())
                data.optString("risk_type").takeIf { it.isNotBlank() }?.let { risk ->
                    require(risk.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    options.put("riskType", risk)
                }
            } else {
                for (name in listOf("gt", "challenge")) {
                    val value = data.getString(name)
                    require(value.matches(Regex("[A-Za-z0-9_-]{1,256}")))
                    options.put(name, value)
                }
                // Never accept an offline/failback result as successful verification.
                options.put("offline", false).put("new_captcha", true).put("https", true).put("lang", "zh-cn")
            }
            return LoginChallenge(session, version, options.toString())
        }
    }
}

data class SmsReceipt(val action: String, val cooldown: Int) {
    companion object {
        fun parse(data: JSONObject): SmsReceipt {
            val action = data.optString("action_type")
            check(action.isNotBlank() && action.length <= 128) { "Missing SMS action" }
            return SmsReceipt(action, data.optInt("countdown", 60).coerceIn(1, 600))
        }
    }
}
