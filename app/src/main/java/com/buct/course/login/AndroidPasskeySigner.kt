package com.buct.course.login

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.buct.one.data.login.PlatformPasskeySigner
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android 通行密钥认证器：[PlatformPasskeySigner] 的平台实现。
 *
 * 分工：
 * - 私钥只进 Android Keystore（[BuctSoftwarePasskey] 生成 EC P-256 并用它签名），绝不离开 App 安全区；
 * - 用户验证走系统 BiometricPrompt（指纹 / 面部 / 设备密码），断言签名绑在 CryptoObject 上，
 *   没有通过生物识别就拿不到签名能力；
 * - **协议不在本类**：请求认证选项、提交断言、跟随票据全部由共享
 *   [com.buct.one.data.login.CasPasskeyClient] 负责（与 iOS 同一份实现）。
 */
class AndroidPasskeySigner(private val activity: FragmentActivity) : PlatformPasskeySigner {

    private val context: Context get() = activity.applicationContext

    override fun hasCredential(username: String): Boolean =
        BuctSoftwarePasskey.hasCredential(context, username)

    override suspend fun createAssertion(username: String, requestOptionsJson: String): String? {
        val session = try {
            BuctSoftwarePasskey.beginAuthentication(context, username, requestOptionsJson)
        } catch (_: BuctSoftwarePasskey.NotRegisteredException) {
            return null
        }
        if (!prompt(title = "通行密钥登录", subtitle = "验证指纹、面部或设备密码", signature = session.signature)) {
            return null
        }
        return runCatching { BuctSoftwarePasskey.completeAssertion(context, session) }.getOrNull()
    }

    override suspend fun createCredential(
        username: String,
        displayName: String,
        creationOptionsJson: String,
    ): String? {
        if (!prompt(title = "创建通行密钥", subtitle = "验证指纹、面部或设备密码", signature = null)) return null
        return runCatching { BuctSoftwarePasskey.createCredential(context, username, creationOptionsJson) }.getOrNull()
    }

    /**
     * 弹系统生物识别；用户取消 / 设备未录入 / 验证失败一律返回 false。
     * 必须在主线程执行（BiometricPrompt 的约束），因此这里显式切到 Main。
     */
    private suspend fun prompt(title: String, subtitle: String, signature: Signature?): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                return@withContext false
            }
            suspendCancellableCoroutine { continuation ->
                var completed = false
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (completed) return
                            completed = true
                            continuation.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (completed) return
                            completed = true
                            continuation.resume(false)
                        }

                        // 指纹没识别上：系统会让用户重试，这里保持挂起等待最终结果
                        override fun onAuthenticationFailed() = Unit
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()
                if (signature != null) {
                    prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
                } else {
                    prompt.authenticate(info)
                }
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
}
