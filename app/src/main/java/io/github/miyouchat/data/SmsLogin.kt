package io.github.miyouchat.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SmsLoginState(val busy: Boolean = false, val status: String = "", val error: Boolean = false,
                         val cooldown: Int = 0, val canSubmit: Boolean = false, val challenge: LoginChallenge? = null)

/** Binds a captcha and its manual verification to one phone number and one attempt. */
class SmsLogin(private val scope: CoroutineScope,
               private val send: suspend (String, String) -> SmsReceipt,
               private val login: suspend (String, String, String, String) -> Session,
               private val signedIn: suspend (Session) -> Unit,
               private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(SmsLoginState())
    val state = mutable.asStateFlow()
    private var phone = ""
    private var action = ""
    private var job: Job? = null
    private var cooldownJob: Job? = null
    private var deadline = 0L
    private var pending: Attempt? = null
    private class Attempt(val phone: String, val code: String? = null, var challenges: Int = 0)

    fun phoneChanged(value: String) {
        if (phone == value) return
        job?.cancel(); pending = null; action = ""; phone = value
        mutable.value = SmsLoginState(cooldown = remaining())
    }
    private fun remaining() = ((deadline - now() + 999) / 1000).coerceIn(0, 600).toInt()
    fun request(value: String) {
        if (mutable.value.busy || mutable.value.challenge != null || remaining() > 0) return
        phoneChanged(value)
        if (!value.matches(Regex("1[3-9][0-9]{9}"))) {
            mutable.value = mutable.value.copy(status = "请输入有效的中国大陆手机号", error = true); return
        }
        action = ""
        run(Attempt(value))
    }
    fun submit(value: String, code: String) {
        if (mutable.value.busy || mutable.value.challenge != null) return
        if (value != phone || action.isBlank()) {
            phoneChanged(value)
            mutable.value = mutable.value.copy(status = "请先为当前手机号获取验证码", error = true); return
        }
        if (!code.matches(Regex("[0-9]{4,8}"))) {
            mutable.value = mutable.value.copy(status = "请输入短信验证码", error = true); return
        }
        run(Attempt(value, code))
    }
    fun verified(challenge: LoginChallenge, result: String) {
        if (mutable.value.challenge !== challenge) return
        val attempt = pending ?: return
        val header = runCatching { challenge.header(result) }.getOrElse { verificationFailed(challenge); return }
        mutable.value = mutable.value.copy(challenge = null)
        run(attempt, header)
    }
    fun verificationFailed(challenge: LoginChallenge) {
        if (mutable.value.challenge !== challenge) return
        pending = null
        mutable.value = mutable.value.copy(challenge = null, busy = false, error = true, status = "验证未完成，请重新尝试或使用扫码登录")
    }
    private fun run(attempt: Attempt, aigis: String = "") {
        mutable.value = mutable.value.copy(busy = true, error = false, canSubmit = action.isNotBlank(),
            status = if (attempt.code == null) "正在请求验证码…" else "正在登录…")
        job = scope.launch {
            try {
                if (attempt.code == null) {
                    val receipt = send(attempt.phone, aigis)
                    ensureActive()
                    if (phone != attempt.phone) return@launch
                    action = receipt.action; pending = null
                    deadline = now() + receipt.cooldown * 1000L
                    mutable.value = SmsLoginState(status = "验证码已发送，请在手机查看", cooldown = remaining(), canSubmit = true)
                    cooldownJob?.cancel()
                    cooldownJob = scope.launch {
                        while (remaining() > 0) {
                            delay(1000)
                            mutable.value = mutable.value.copy(cooldown = remaining())
                        }
                    }
                } else {
                    val session = login(attempt.phone, attempt.code, action, aigis)
                    ensureActive()
                    if (phone != attempt.phone) return@launch
                    signedIn(session)
                    ensureActive()
                    job = null
                    reset()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (phone != attempt.phone) return@launch
                val challenge = (failure as? ApiFailure)?.challenge?.let { runCatching { LoginChallenge.parse(it) }.getOrNull() }
                if (challenge != null && attempt.challenges++ < 3) {
                    pending = attempt
                    mutable.value = mutable.value.copy(busy = false, challenge = challenge, status = "请先完成安全验证")
                } else {
                    pending = null
                    val text = if (failure is ApiFailure) failure.message.orEmpty() else "连接未完成，请检查网络后重试"
                    mutable.value = mutable.value.copy(busy = false, error = true, status = text)
                }
            }
        }
    }
    fun reset() {
        job?.cancel(); cooldownJob?.cancel(); pending = null; phone = ""; action = ""; deadline = 0
        mutable.value = SmsLoginState()
    }
}
