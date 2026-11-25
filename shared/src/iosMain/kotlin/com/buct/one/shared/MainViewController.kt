package com.buct.one.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Swift 侧调用入口：MainViewControllerKt.MainViewController()。 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
