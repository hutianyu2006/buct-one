package com.buct.one.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buct.one.data.login.LoginState

/** iOS 登录页：只负责输入与展示，登录状态机在共享层 [com.buct.one.data.login.CasLoginController]。 */
@Composable
internal fun LoginScreen(
    state: LoginState,
    onSubmit: (String, String) -> Unit,
    onSkip: () -> Unit,
    onRequestSmsCaptcha: (String) -> Unit = {},
    onSendSmsCode: (String, String) -> Unit = { _, _ -> },
    onSmsLogin: (String, String) -> Unit = { _, _ -> },
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var smsMode by remember { mutableStateOf(false) }
    val loading = state is LoginState.Loading
    val colors = MaterialTheme.colorScheme
    val smsCaptcha = state as? LoginState.SmsCaptchaRequired
    val smsSent = state is LoginState.SmsCodeSent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录北化统一身份认证",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "iOS 预览版：登录用于验证会话链路，教务 / 校园卡等功能会逐步迁移过来。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("学号") },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (!smsMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = captchaCode,
                onValueChange = { captchaCode = it },
                label = { Text(if (smsCaptcha != null) "图形验证码（见下方提示）" else "图形验证码") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            )
            if (smsCaptcha != null && smsCaptcha.pngBytes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已获取图形验证码（${smsCaptcha.pngBytes.size} 字节 PNG）；" +
                        "iOS 预览版暂未内置图片解码，请先点「发送短信」再按提示输入短信码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = smsCode,
                onValueChange = { smsCode = it },
                label = { Text(if (smsSent) "短信验证码（已发送）" else "短信验证码") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (smsMode) {
                    if (smsSent) {
                        onSmsLogin(username.trim(), smsCode)
                    } else {
                        onSendSmsCode(username.trim(), captchaCode)
                    }
                } else {
                    onSubmit(username.trim(), password)
                }
            },
            enabled = !loading && username.isNotBlank() &&
                if (smsMode) (if (smsSent) smsCode.isNotBlank() else captchaCode.isNotBlank())
                else password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.onPrimary,
                )
            } else {
                Text(
                    when {
                        !smsMode -> "登录"
                        smsSent -> "用短信码登录"
                        else -> "发送短信验证码"
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                smsMode = !smsMode
                captchaCode = ""
                smsCode = ""
                if (!smsMode) Unit else if (username.isNotBlank()) onRequestSmsCaptcha(username.trim())
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (smsMode) "改用密码登录" else "改用短信验证码登录")
        }

        Spacer(Modifier.height(12.dp))
        when (state) {
            is LoginState.Failed -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
            is LoginState.CaptchaRequired -> Text(
                text = "统一认证要求图形验证码（${state.message}）。当前 iOS 预览版暂未内置验证码图片，请在 Android 端完成登录。",
                style = MaterialTheme.typography.bodySmall,
                color = WarningOrange,
            )
            is LoginState.SmsRequired -> Text(
                text = "已触发短信二次验证（${state.phoneMask}），iOS 预览版暂未支持短信验证。",
                style = MaterialTheme.typography.bodySmall,
                color = WarningOrange,
            )
            else -> Unit
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("跳过，先看公告")
        }
    }
}
