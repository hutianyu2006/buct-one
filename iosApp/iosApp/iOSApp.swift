import Foundation
import Security
import Shared
import SwiftUI

private enum BuctTlsChallengeEvaluator {
    private static let fallbackAnchors: [SecCertificate] = {
        BuctCertificatePins.shared.allChainCertificatesBase64.compactMap { b64 in
            guard let data = Data(base64Encoded: b64, options: .ignoreUnknownCharacters) else {
                return nil
            }
            return SecCertificateCreateWithData(nil, data as CFData)
        }
    }()

    static func evaluate(challenge: URLAuthenticationChallenge) -> IosTrustDecision {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = space.serverTrust else {
            return IosTlsBridge.shared.performDefault()
        }

        // 1) 系统默认信任库优先（严格校验链 + 域名 + 有效期）
        if SecTrustEvaluateWithError(trust, nil) {
            return IosTlsBridge.shared.performDefault()
        }

        // 2) 仅对 *.buct.edu.cn 启用内置校方双链兜底，非校内域一律拒绝
        let host = space.host
        guard !host.isEmpty, BuctCertificatePins.shared.isBuctHost(host: host) else {
            return IosTlsBridge.shared.cancel()
        }
        guard !fallbackAnchors.isEmpty else {
            return IosTlsBridge.shared.cancel()
        }

        // 3) 注入内置双链（根 + 中间证书）并保留系统锚点后重新走 SecTrust 校验
        SecTrustSetAnchorCertificates(trust, fallbackAnchors as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, false)
        if SecTrustEvaluateWithError(trust, nil) {
            return IosTlsBridge.shared.useCredential(credential: URLCredential(trust: trust))
        }
        return IosTlsBridge.shared.cancel()
    }
}

@main
struct iOSApp: App {
    init() {
        IosTlsBridge.shared.registerServerTrustHandler { challenge in
            BuctTlsChallengeEvaluator.evaluate(challenge: challenge)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
