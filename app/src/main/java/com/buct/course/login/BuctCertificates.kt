package com.buct.course.login

import com.buct.one.platform.network.BuctCertificatePins

/**
 * 北化统一认证/教务 (*.buct.edu.cn) 的 TLS 证书链。
 *
 * 证书常量已下沉到共享层 [BuctCertificatePins]（Android 与将来的 iOS 兜底共用同一份数据），
 * 这里保留原有常量名，避免 `BuctTlsTrustManager` 等调用点改动。
 */
internal object BuctCertificates {
    /** GlobalSign RSA OV SSL CA 2018（中间证书）。 */
    val INTERMEDIATE_B64: String get() = BuctCertificatePins.GLOBALSIGN_INTERMEDIATE_B64

    /** GlobalSign Root CA - R3（信任点）。 */
    val ROOT_B64: String get() = BuctCertificatePins.GLOBALSIGN_ROOT_B64

    /** ZeroSSL ECC DV SSL CA 2（公网认证入口的中间证书）。 */
    val ZEROSSL_INTERMEDIATE_B64: String get() = BuctCertificatePins.ZEROSSL_INTERMEDIATE_B64

    /** Sectigo Public Server Authentication Root E46（公网认证入口的信任点）。 */
    val SECTIGO_E46_B64: String get() = BuctCertificatePins.SECTIGO_E46_ROOT_B64
}
