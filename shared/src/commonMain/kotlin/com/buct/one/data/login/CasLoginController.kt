package com.buct.one.data.login

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 登录界面状态（iOS / Android 共用同一状态机）。 */
sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Success(val username: String) : LoginState
    data class CaptchaRequired(val message: String, val wrongCode: Boolean) : LoginState
    data class SmsRequired(val phoneMask: String) : LoginState

    /** 短信登录：需要输入图形验证码（[pngBytes] 为 PNG，UI 负责解码渲染）。 */
    data class SmsCaptchaRequired(val pngBytes: ByteArray) : LoginState

    /** 短信登录：图形码通过，短信已下发。 */
    data class SmsCodeSent(val expireSeconds: Int) : LoginState

    /** 短信登录：短信码错误，可重试。 */
    data class SmsCodeInvalid(val message: String) : LoginState

    data class Failed(val message: String) : LoginState
}

/**
 * CAS 登录状态机：把 [CasPasswordLoginClient] 的结果映射成 UI 状态，
 * 双端界面只负责展示与提交，业务分支不重复实现。
 */
class CasLoginController(
    private val loginClient: CasPasswordLoginClient,
    private val smsClient: CasSmsLoginClient? = null,
) {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    suspend fun login(username: String, password: String, captchaCode: String = "") {
        _state.value = LoginState.Loading
        _state.value = try {
            when (val outcome = loginClient.login(username, password, captchaCode)) {
                CasLoginOutcome.Success -> LoginState.Success(username)
                is CasLoginOutcome.CaptchaRequired ->
                    LoginState.CaptchaRequired(outcome.message, outcome.wrongCode)
                is CasLoginOutcome.SmsVerificationRequired ->
                    LoginState.SmsRequired(outcome.phoneMask)
                is CasLoginOutcome.Failed -> LoginState.Failed(outcome.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoginState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    /** 短信登录第 1 步：拉取图形验证码。 */
    suspend fun requestSmsCaptcha(username: String) {
        val client = smsClient ?: run {
            _state.value = LoginState.Failed("当前平台暂不支持短信登录")
            return
        }
        _state.value = LoginState.Loading
        _state.value = try {
            when (val outcome = client.fetchCaptcha(username)) {
                is CasSmsOutcome.CaptchaRequired -> LoginState.SmsCaptchaRequired(outcome.pngBytes)
                is CasSmsOutcome.Failed -> LoginState.Failed(outcome.message)
                else -> LoginState.Failed("图形验证码获取失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoginState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    /** 短信登录第 2 步：提交图形码，触发短信下发。 */
    suspend fun sendSmsCode(username: String, captchaCode: String) {
        val client = smsClient ?: run {
            _state.value = LoginState.Failed("当前平台暂不支持短信登录")
            return
        }
        _state.value = LoginState.Loading
        _state.value = try {
            when (val outcome = client.sendSmsCode(username, captchaCode)) {
                is CasSmsOutcome.SmsSent -> LoginState.SmsCodeSent(outcome.expireSeconds)
                CasSmsOutcome.CaptchaInvalid -> LoginState.SmsCaptchaRequired(ByteArray(0))
                is CasSmsOutcome.SmsSendRejected -> LoginState.Failed(outcome.message)
                is CasSmsOutcome.Failed -> LoginState.Failed(outcome.message)
                else -> LoginState.Failed("发送短信失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoginState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    /** 短信登录第 3 步：提交短信码完成登录。 */
    suspend fun loginWithSmsCode(username: String, smsCode: String) {
        val client = smsClient ?: run {
            _state.value = LoginState.Failed("当前平台暂不支持短信登录")
            return
        }
        _state.value = LoginState.Loading
        _state.value = try {
            when (val outcome = client.loginWithSmsCode(username, smsCode)) {
                CasSmsOutcome.Success -> LoginState.Success(username)
                is CasSmsOutcome.SmsCodeInvalid -> LoginState.SmsCodeInvalid(outcome.message)
                is CasSmsOutcome.Failed -> LoginState.Failed(outcome.message)
                else -> LoginState.Failed("短信登录失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoginState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    fun reset() {
        _state.value = LoginState.Idle
    }
}
