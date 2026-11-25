import SwiftUI
import UIKit
import Shared

/// 把 Compose Multiplatform 的 UIViewController 嵌进 SwiftUI。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all) // Compose 自己按 safe area 排版
    }
}
