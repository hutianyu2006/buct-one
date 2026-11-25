// Kotlin Multiplatform 共享模块：
//  - commonMain：双端复用的平台无关逻辑（数据模型、解析器、公告通道）
//  - iosMain：iOS 专用部分（Compose Multiplatform UI、Darwin 网络引擎）
//  - jvm：Android 端消费的 JVM 变体，不含任何 Android API，APK 无平台耦合
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 与 Compose Multiplatform 1.12.2 的官方依赖组合保持一致（material3 仍停留在 1.9.0 稳定线）。
val composeVersion = "1.12.2"
val composeMaterial3Version = "1.9.0"

kotlin {
    // Android 以标准 JVM 变体消费（Kotlin 的 androidJvm → jvm 兼容规则），
    // 这样共享层不需要 AGP，iOS 构建也不需要 Android SDK。
    jvm()

    // Compose Multiplatform 1.12 只发布 arm64 产物：真机 + Apple Silicon 模拟器（Intel 模拟器已不再支持）。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("io.ktor:ktor-client-core:3.6.1")
            // jsoup 的 Kotlin Multiplatform 移植版，解析层双端复用
            // 解析器公共签名暴露 Ksoup 类型，需要 api 而不是 implementation
            api("com.fleeksoft.ksoup:ksoup:0.2.6")
            // SM2 国密需要的双端大整数（按 Kotlin 2.4 同代构建）
            implementation("com.ionspin.kotlin:bignum:0.4.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Compose 编译器插件对整个模块生效（iOS UI 需要），这里给 JVM 编译补一个仅编译期的
        // Compose Runtime，满足插件检查且不会把桌面 Compose 带进 Android 运行时依赖。
        jvmMain.dependencies {
            compileOnly("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("io.ktor:ktor-client-okhttp:3.6.1")
        }
        jvmTest.dependencies {
            compileOnly("org.jetbrains.compose.runtime:runtime:$composeVersion")
            // 仅测试用：用 BouncyCastle 做 SM2 差分校验（共享实现加密 → BC 解密）
            implementation("org.bouncycastle:bcprov-jdk15to18:1.70")
        }
        iosMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:$composeMaterial3Version")
            implementation("io.ktor:ktor-client-darwin:3.6.1")
        }
    }
}
