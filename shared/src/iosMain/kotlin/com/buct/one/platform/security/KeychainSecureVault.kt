package com.buct.one.platform.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS 侧 [SecureVault]：Apple Keychain（`kSecClassGenericPassword`）。
 *
 * 安全约定（不可放宽）：
 * - 条目必须使用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
 *   仅本机、首次解锁后可读，**不进 iCloud 钥匙串、不随备份迁移**；
 * - 只写 Keychain，**绝不**降级到 `NSUserDefaults` 或明文文件；
 * - 读失败（条目缺失/权限异常）返回 null，由调用方按"无凭据"处理。
 *
 * [alias] 映射到 Keychain 的 service 字段，[key] 映射到 account 字段，实现多用途隔离。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecureVault(
    private val servicePrefix: String = "com.buct.one.vault",
) : SecureVault {

    override fun get(alias: String, key: String): String? {
        val query = baseQuery(alias, key) ?: return null
        try {
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status != errSecSuccess) return@memScoped null
                val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
                NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
            }
        } finally {
            CFRelease(query)
        }
    }

    override fun put(alias: String, key: String, value: String) {
        val encoded = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = baseQuery(alias, key) ?: return
        try {
            val attributes = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            if (attributes != null) {
                CFDictionaryAddValue(attributes, kSecValueData, CFBridgingRetain(encoded))
                CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            }
            val addStatus = if (attributes == null) -1 else SecItemAdd(query, null)
            if (addStatus != errSecSuccess && attributes != null) {
                // 已存在则更新；更新同样受 accessible 约束。
                SecItemUpdate(query, attributes)
            }
            if (attributes != null) CFRelease(attributes)
        } finally {
            CFRelease(query)
        }
    }

    override fun remove(alias: String, key: String) {
        val query = baseQuery(alias, key) ?: return
        try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    override fun clearAlias(alias: String) {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain("$servicePrefix.$alias" as NSString))
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun baseQuery(alias: String, key: String): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return null
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain("$servicePrefix.$alias" as NSString))
        CFDictionaryAddValue(query, kSecAttrAccount, CFBridgingRetain(key as NSString))
        return query
    }
}
