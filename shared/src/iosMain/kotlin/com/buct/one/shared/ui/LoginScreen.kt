package com.buct.one.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buct.one.data.login.LoginState

/** iOS 登录页：只负责输入与展示，登录状态机在共享层 [com.buct.one.data.login.CasLoginController]。 */
@Composable
internal fun LoginScreen(
    state: LoginState,
    onSubmit: (String, String, String) -> Unit,
    onSkip: () -> Unit,
    onRequestSmsCaptcha: (String) -> Unit = {},
    onSendSmsCode: (String, String) -> Unit = { _, _ -> },
    onSmsLogin: (String, String) -> Unit = { _, _ -> },
    rememberPassword: Boolean = false,
    onRememberPasswordChange: (Boolean) -> Unit = {},
    passkeyMessage: String? = null,
    passkeyBusy: Boolean = false,
    onPasskeyLogin: (String) -> Unit = {},
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
    val passwordCaptcha = state as? LoginState.CaptchaRequired

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
            if (passwordCaptcha != null) {
                Spacer(Modifier.height(12.dp))
                // 验证码图片由共享 CasPasswordLoginClient 用同一 Cookie 会话拉取，这里只负责解码渲染
                val bitmap = remember(passwordCaptcha.pngBytes) {
                    decodeCaptchaBitmap(passwordCaptcha.pngBytes)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "图形验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = captchaCode,
                    onValueChange = { captchaCode = it },
                    label = { Text(if (passwordCaptcha.wrongCode) "验证码错误，请重新输入" else "图形验证码") },
                    singleLine = true,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
                val bitmap = remember(smsCaptcha.pngBytes) { decodeCaptchaBitmap(smsCaptcha.pngBytes) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "图形验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    )
                } else {
                    Text(
                        text = "图形验证码获取失败，请点击下方按钮重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
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
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = onRememberPasswordChange,
                enabled = !loading && !smsMode,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "记住密码（仅本机钥匙串，不进 iCloud 备份）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = "开启后会话过期可自动续期，退出登录立即擦除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
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
                    onSubmit(username.trim(), password, if (passwordCaptcha != null) captchaCode else "")
                }
            },
            enabled = !loading && username.isNotBlank() &&
                when {
                    smsMode && smsSent -> smsCode.isNotBlank()
                    smsMode -> captchaCode.isNotBlank()
                    passwordCaptcha != null -> password.isNotBlank() && captchaCode.isNotBlank()
                    else -> password.isNotBlank()
                },
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
        OutlinedButton(
            onClick = { onPasskeyLogin(username.trim()) },
            enabled = !loading && !passkeyBusy && !smsMode && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (passkeyBusy) "正在验证通行密钥…" else "用通行密钥（Face ID / 指纹）登录")
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
        if (passkeyMessage != null) {
            Text(
                text = passkeyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
        when (state) {
            is LoginState.Failed -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
            is LoginState.CaptchaRequired -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = WarningOrange,
            )
            is LoginState.SmsRequired -> Text(
                text = "已触发短信二次验证（${state.phoneMask}），请改用「短信验证码登录」完成验证。",
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
/** 把共享层下发的 PNG 字节解码成 Compose 位图；解码失败（空/损坏）返回 null，界面自动隐藏图片。 */
private fun decodeCaptchaBitmap(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    // 全限定名：foundation 的 Image 组合函数与本类的图形 Image 同名，避免歧义
    return runCatching {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}