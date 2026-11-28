package com.buct.one.platform.network

/**
 * 北化认证入口的 TLS 证书链（DER Base64 内嵌），双端共用。
 *
 * 学校有两个认证入口，签发者不同：
 * - 校园网入口 `portal.buct.edu.cn` / `*.buct.edu.cn`：GlobalSign RSA OV SSL CA 2018 ← GlobalSign Root CA - R3；
 * - 公网入口 `experimental-auth-endpoint.buct.edu.cn`：ZeroSSL ECC DV SSL CA 2 ← Sectigo Public Server Authentication Root E46。
 *
 * 部分系统（尤其 Android 老版本与某些 iOS 版本）信任库缺少 R3 / E46，会导致握手失败，
 * 因此内置这两条链作为**兜底**：先走系统默认校验，失败才比对内置链，**绝不**无条件信任
 * （Android 见 `BuctTlsTrustManager`，iOS 见 `IosTlsBridge` + Swift 侧 `SecTrust` 兜底）。
 */
object BuctCertificatePins {

    /** GlobalSign RSA OV SSL CA 2018（中间证书）。 */
    val GLOBALSIGN_INTERMEDIATE_B64: String =
        "MIIETjCCAzagAwIBAgINAe5fIh38YjvUMzqFVzANBgkqhkiG9w0BAQsFADBMMSAwHgYDVQQLExdHbG9iYWxTaWduIFJvb3QgQ0Eg" +
        "LSBSMzETMBEGA1UEChMKR2xvYmFsU2lnbjETMBEGA1UEAxMKR2xvYmFsU2lnbjAeFw0xODExMjEwMDAwMDBaFw0yODExMjEwMDAw" +
        "MDBaMFAxCzAJBgNVBAYTAkJFMRkwFwYDVQQKExBHbG9iYWxTaWduIG52LXNhMSYwJAYDVQQDEx1HbG9iYWxTaWduIFJTQSBPViBT" +
        "U0wgQ0EgMjAxODCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKdaydUMGCEAI9WXD+uu3Vxoa2uPUGATeoHLl+6OimGU" +
        "SyZ59gSnKvuk2la77qCk8HuKf1UfR5NhDW5xUTolJAgvjOH3idaSz6+zpz8w7bXfIa7+9UQX/dhj2S/TgVprX9NHsKzyqzskeU8f" +
        "xy7quRU6fBhMabO1IFkJXinDY+YuRluqlJBJDrnw9UqhCS98NE3QvADFBlV5Bs6i0BDxSEPouVq1lVW9MdIbPYa+oewNEtssmSSt" +
        "R8JvA+Z6cLVwzM0nLKWMjsIYPJLJLnNvBhBWk0Cqo8VS++XFBdZpaFwGue5RieGKDkFNm5KQConpFmvv73W+eka440eKHRwup08C" +
        "AwEAAaOCASkwggElMA4GA1UdDwEB/wQEAwIBhjASBgNVHRMBAf8ECDAGAQH/AgEAMB0GA1UdDgQWBBT473/yzXhnqN5vjySNiPGH" +
        "AwKz6zAfBgNVHSMEGDAWgBSP8Et/qC5FJK5NUPpjmove4t0bvDA+BggrBgEFBQcBAQQyMDAwLgYIKwYBBQUHMAGGImh0dHA6Ly9v" +
        "Y3NwMi5nbG9iYWxzaWduLmNvbS9yb290cjMwNgYDVR0fBC8wLTAroCmgJ4YlaHR0cDovL2NybC5nbG9iYWxzaWduLmNvbS9yb290" +
        "LXIzLmNybDBHBgNVHSAEQDA+MDwGBFUdIAAwNDAyBggrBgEFBQcCARYmaHR0cHM6Ly93d3cuZ2xvYmFsc2lnbi5jb20vcmVwb3Np" +
        "dG9yeS8wDQYJKoZIhvcNAQELBQADggEBAJmQyC1fQorUC2bbmANzEdSIhlIoU4r7rd/9c446ZwTbw1MUcBQJfMPg+NccmBqixD7b" +
        "6QDjynCy8SIwIVbb0615XoFYC20UgDX1b10d65pHBf9ZjQCxQNqQmJYaumxtf4z1s4DfjGRzNpZ5eWl06r/4ngGPoJVpjemEuunl" +
        "1Ig423g7mNA2eymw0lIYkN5SQwCuaifIFJ6GlazhgDEwfpolu4usBCOmmQDo8dIm7A9+O4orkjgTHY+GzYZSR+Y0fFukAj6KYXwi" +
        "dlNalFMzhriSqHKvoflShx8xpfywgVcvzfTO3PYkz6fiNJBonf6q8amaEsybwMbDqKWwIX7eSPY="

    /** GlobalSign Root CA - R3（信任点）。 */
    val GLOBALSIGN_ROOT_B64: String =
        "MIIETjCCAzagAwIBAgINAe5fFp3/lzUrZGXWajANBgkqhkiG9w0BAQsFADBXMQswCQYDVQQGEwJCRTEZMBcGA1UEChMQR2xvYmFs" +
        "U2lnbiBudi1zYTEQMA4GA1UECxMHUm9vdCBDQTEbMBkGA1UEAxMSR2xvYmFsU2lnbiBSb290IENBMB4XDTE4MDkxOTAwMDAwMFoX" +
        "DTI4MDEyODEyMDAwMFowTDEgMB4GA1UECxMXR2xvYmFsU2lnbiBSb290IENBIC0gUjMxEzARBgNVBAoTCkdsb2JhbFNpZ24xEzAR" +
        "BgNVBAMTCkdsb2JhbFNpZ24wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDMJXaQeQZ4Ihb1wIO2hMoonv0FdhHFrYhy" +
        "/EYCQ8eyip0EXyTLLkvhYIJG4VKrDIFHcGzdZNHr9SyjD4I9DCuul9e2FIYQebs7E4B3jAjhSdJqYi8fXvqWaN+JJ5U4nwbXPsnL" +
        "JlkNc96wyOkmDoMVxu9bi9IEYMpJpij2aTv2y8gokeWdimFXN6x0FNx04Druci8unPvQu7/1PQDhBjPogiuuU6Y6FnOM3UEOIDrA" +
        "tKeh6bJPkC4yYOlXy7kEkmho5TgmYHWyn3f/kRTvriBJ/K1AFUjRAjFhGV64l++td7dkmnq/X8ET75ti+w1s4FRpFqkD2m7pg5Nx" +
        "dsZphYIXAgMBAAGjggEiMIIBHjAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUj/BLf6guRSSuTVD6" +
        "Y5qL3uLdG7wwHwYDVR0jBBgwFoAUYHtmGkUNl8qJUC99BM00qP/8/UswPQYIKwYBBQUHAQEEMTAvMC0GCCsGAQUFBzABhiFodHRw" +
        "Oi8vb2NzcC5nbG9iYWxzaWduLmNvbS9yb290cjEwMwYDVR0fBCwwKjAooCagJIYiaHR0cDovL2NybC5nbG9iYWxzaWduLmNvbS9y" +
        "b290LmNybDBHBgNVHSAEQDA+MDwGBFUdIAAwNDAyBggrBgEFBQcCARYmaHR0cHM6Ly93d3cuZ2xvYmFsc2lnbi5jb20vcmVwb3Np" +
        "dG9yeS8wDQYJKoZIhvcNAQELBQADggEBACNw6c/ivvVZrpRCb8RDM6rNPzq5ZBfyYgZLSPFAiAYXof6r0V88xjPy847dHx0+zBpg" +
        "mYILrMf8fpqHKqV9D6ZX7qw7aoXW3r1AY/itpsiIsBL89kHfDwmXHjjqU5++BfQ+6tOfUBJ2vgmLwgtIfR4uUfaNU9OrH0Abio7t" +
        "fftPeVZwXwzTjhuzp3ANNyuXlava4BJrHEDOxcd+7cJiWOx37XMiwor1hkOIreoTbv3Y/kIvuX1erRjvlJDKPSerJpSZdcfL03v3" +
        "ykzTr1EhkluEfSufFT90y1HonoMOFm8b50bOI7355KKL0jlrqnkckSziYSQtjipIcJDEHsXo4HA="

    /** ZeroSSL ECC DV SSL CA 2（中间证书）。 */
    val ZEROSSL_INTERMEDIATE_B64: String =
        "MIIDNTCCArugAwIBAgIRAMThxbsA8CePNHvk+F1j/MowCgYIKoZIzj0EAwMwXzELMAkGA1UEBhMCR0IxGDAWBgNVBAoTD1NlY3Rp" +
        "Z28gTGltaXRlZDE2MDQGA1UEAxMtU2VjdGlnbyBQdWJsaWMgU2VydmVyIEF1dGhlbnRpY2F0aW9uIFJvb3QgRTQ2MB4XDTI1MDky" +
        "NDAwMDAwMFoXDTM1MDkyMzIzNTk1OVowRjELMAkGA1UEBhMCQVQxFTATBgNVBAoTDFplcm9TU0wgR21iSDEgMB4GA1UEAxMXWmVy" +
        "b1NTTCBFQ0MgRFYgU1NMIENBIDIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATjn4qR1IOXAceCMsej8Qyr4PrFw+PwjPm2vrAf" +
        "ijBj4u2C++Y3pZqpeLi1z05b2owKKJzQk1AtL+m1fEoFeDkao4IBbzCCAWswHwYDVR0jBBgwFoAU0SLaTFnxS18mOKqd1u7rDcP7" +
        "qWEwHQYDVR0OBBYEFJRFn9pRR3HVc66sHasIchJtrXxiMA4GA1UdDwEB/wQEAwIBhjASBgNVHRMBAf8ECDAGAQH/AgEAMBMGA1Ud" +
        "JQQMMAoGCCsGAQUFBwMBMBMGA1UdIAQMMAowCAYGZ4EMAQIBMFQGA1UdHwRNMEswSaBHoEWGQ2h0dHA6Ly9jcmwuc2VjdGlnby5j" +
        "b20vU2VjdGlnb1B1YmxpY1NlcnZlckF1dGhlbnRpY2F0aW9uUm9vdEU0Ni5jcmwwgYQGCCsGAQUFBwEBBHgwdjBPBggrBgEFBQcw" +
        "AoZDaHR0cDovL2NydC5zZWN0aWdvLmNvbS9TZWN0aWdvUHVibGljU2VydmVyQXV0aGVudGljYXRpb25Sb290RTQ2LnA3YzAjBggr" +
        "BgEFBQcwAYYXaHR0cDovL29jc3Auc2VjdGlnby5jb20wCgYIKoZIzj0EAwMDaAAwZQIwXKxxvuUkvPftBuaOFx73U3FxmFtKxP1F" +
        "d/+8Jq/ut2XgDrJ1vEUDdJb3iSDXVe9AAjEA8iUwhrRHdZrIWqfgGmDMBbuXRZ4GoQbLpwxgcPhKo3OZR7SecTp4juWAOpuawM2n"

    /** Sectigo Public Server Authentication Root E46（信任点）。 */
    val SECTIGO_E46_ROOT_B64: String =
        "MIIDRjCCAsugAwIBAgIQGp6v7G3o4ZtcGTFBto2Q3TAKBggqhkjOPQQDAzCBiDELMAkGA1UEBhMCVVMxEzARBgNVBAgTCk5ldyBK" +
        "ZXJzZXkxFDASBgNVBAcTC0plcnNleSBDaXR5MR4wHAYDVQQKExVUaGUgVVNFUlRSVVNUIE5ldHdvcmsxLjAsBgNVBAMTJVVTRVJU" +
        "cnVzdCBFQ0MgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkwHhcNMjEwMzIyMDAwMDAwWhcNMzgwMTE4MjM1OTU5WjBfMQswCQYDVQQG" +
        "EwJHQjEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQDEy1TZWN0aWdvIFB1YmxpYyBTZXJ2ZXIgQXV0aGVudGljYXRp" +
        "b24gUm9vdCBFNDYwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAR2+pmpbiDt+dd34wc7qNs9Xzjoq1WmVk/WSOrsfy2qw7LFeeyZYX8Q" +
        "eccCWvkEN/U0NSt3zn8gj1KjAIns1aeibVvjS5KToID1AZTc8GgHHs3u/iVStSBDHBv+6xnOQ6OjggEgMIIBHDAfBgNVHSMEGDAW" +
        "gBQ64QmG1M8ZwpZ2dEl23OA1xmNjmjAdBgNVHQ4EFgQU0SLaTFnxS18mOKqd1u7rDcP7qWEwDgYDVR0PAQH/BAQDAgGGMA8GA1Ud" +
        "EwEB/wQFMAMBAf8wHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMBEGA1UdIAQKMAgwBgYEVR0gADBQBgNVHR8ESTBHMEWg" +
        "Q6BBhj9odHRwOi8vY3JsLnVzZXJ0cnVzdC5jb20vVVNFUlRydXN0RUNDQ2VydGlmaWNhdGlvbkF1dGhvcml0eS5jcmwwNQYIKwYB" +
        "BQUHAQEEKTAnMCUGCCsGAQUFBzABhhlodHRwOi8vb2NzcC51c2VydHJ1c3QuY29tMAoGCCqGSM49BAMDA2kAMGYCMQCMCyBit99v" +
        "X2ba6xEkDe+YO7vC0twjbkv9PKpqGGuZ61JZryjFsp+DFpEclCVy4noCMQCwvZDXD/m2Ko1HA5Bkmz7YQOFAiNDD49IWa2wdT7R3" +
        "DtODaSXH/BiXv8fwB9su4tU="

    /** 内置链的信任点（根证书）。 */
    val trustPointBase64: List<String> = listOf(GLOBALSIGN_ROOT_B64, SECTIGO_E46_ROOT_B64)

    /**
     * 内置双链的全部证书（根证书 + 中间证书）。
     * iOS `SecTrust` 兜底需同时传入中间证书，避免服务端只下发叶证书（缺中间链）时仍校验失败。
     */
    val allChainCertificatesBase64: List<String> = listOf(
        GLOBALSIGN_ROOT_B64,
        SECTIGO_E46_ROOT_B64,
        GLOBALSIGN_INTERMEDIATE_B64,
        ZEROSSL_INTERMEDIATE_B64,
    )

    /** 允许走内置链兜底的域名后缀（仅校内）。 */
    val BUCT_HOST_SUFFIXES: List<String> = listOf("buct.edu.cn")

    /** 是否属于允许兜底校验的校内域名。 */
    fun isBuctHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return BUCT_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }
    }
}
