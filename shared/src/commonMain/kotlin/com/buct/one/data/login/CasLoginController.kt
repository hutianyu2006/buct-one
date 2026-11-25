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
    data class Failed(val message: String) : LoginState
}

/**
 * CAS 登录状态机：把 [CasPasswordLoginClient] 的结果映射成 UI 状态，
 * 双端界面只负责展示与提交，业务分支不重复实现。
 */
class CasLoginController(private val loginClient: CasPasswordLoginClient) {

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

    fun reset() {
        _state.value = LoginState.Idle
    }
}
