// Kotlin Multiplatform 共享模块：iOS 由 Compose Multiplatform 渲染，
// 只声明 Apple 目标，Android 侧 APK 完全不受影响。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 与 Compose Multiplatform 1.12.2 的官方依赖组合保持一致（material3 仍停留在 1.9.0 稳定线）。
val composeVersion = "1.12.2"
val composeMaterial3Version = "1.9.0"

kotlin {
    // Compose Multiplatform 1.12 只发布 arm64 产物：真机 + Apple Silicon 模拟器（Intel 模拟器已不再支持）。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:$composeMaterial3Version")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("io.ktor:ktor-client-core:3.6.1")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.6.1")
        }
    }
}
