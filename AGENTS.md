# 开发约定

## 构建

- **本机不跑 gradle 构建**。所有构建走 GitHub Actions，本地只改代码。
- 需要看构建结果：`gh run list --limit 5` / `gh run view <id> --log-failed`。
- 本机执行 `git push` 与 `gh` 命令需挂载代理：`$env:HTTPS_PROXY="http://127.0.0.1:7897"; $env:HTTP_PROXY="http://127.0.0.1:7897"`。
- `compileSdk android-37.0`、`build-tools 37.0.0`、JDK 21。

## 硬约束（改动前先自查）

1. 全云端 CI：不要引入需要本地工具链的构建插件（如 protobuf、NDK、自定义 Gradle 插件源码）
2. APK 上限 100MB（CI 硬门禁，80MB 预警）：新增依赖在 PR 描述里说明用途与体积增量
3. 登录必须原生 Kotlin：不允许 WebView 登录流程
4. 单人维护：能不加的抽象就不加；一个功能一个提交

## 包结构规则

```
ui/*      → store、session、model；禁止 import source/* 里的 Http/Parse 类
sync/*    → source、store、session；禁止 ui
source/*  → session、model；禁止 store、sync、ui；各域子包互不依赖
store/*   → model；禁止 source、sync、ui
session/* → login、model；禁止一切上层
model/    → 零依赖
```

CI 里 `Package dependency rules` 这一步会 grep 检查，违反即失败。

## 数据层四条铁律

1. **失败永不产生新 data**：失败路径只改 `refreshState` / `lastError`，旧 data 原样保留
2. `NEVER` ≠ `EMPTY_CONFIRMED`：没同步过不能说「学校没有」
3. `parserVersion` 只增不减；改解析逻辑必须 +1
4. `capturedAt` 单调不减（时钟回拨保护）

## 跨端（Android / iOS）

- 双端复用的逻辑一律写在 `shared/src/commonMain`：数据模型（`com.buct.one.data.model`，零 import 约束不变）、响应解析器（`com.buct.one.data.source.<域>`）、公告通道。
- 解析层只允许使用 `com.buct.one.data.json`（kotlinx.serialization 的 org.json 只读外观）、`com.buct.one.data.text`、`com.buct.one.platform` 抽象与 Ksoup；**禁止** `org.json`、`org.jsoup`、`java.*`、`android.*`、`javax.*`。
- 平台能力（存储 / 日志 / 调度 / HTTP / 加密）只用 `com.buct.one.platform` 下的接口；Android 侧网络必须注入挂了 `BuctTlsTrustManager`/`BuctDns` 的 Ktor 引擎。
- Android 通过 `:shared` 的 JVM 变体消费，iOS 通过 `Shared.framework`；新增解析器必须在 `shared/src/commonTest` 补样本单测，CI 的 `:shared:jvmTest` 会执行。
- iOS 专属实现（Compose Multiplatform UI、Darwin 网络）放 `shared/src/iosMain`；`iosApp/` 只保留 SwiftUI 壳与 Xcode 工程。

## 界面与预测性返回手势

- 全局启用 Android 14+ 预测性返回手势（`android:enableOnBackInvokedCallback="true"`），并受「我的 → 外观 → 预测性返回手势」（`AppearanceSettingsManager.predictiveBackEnabled`）控制。
- **工作台一级功能模块必须使用独立 `ComponentActivity` + `GlassWindowHost` 承载**（如校园卡、实践教学、第二课堂、体测、校园邮箱、i北化、教务消息、北化在线 `TheolCourseActivity` / `TheolCourseDetailActivity`、表格导入 `TaskImportActivity`、宿舍电费 `ElectricityActivity`、校园网网费 `CampusNetActivity`、空闲教室 `EmptyClassroomActivity`、选课抢课 `CourseSelectionActivity`），以确保获得系统级跨 Activity 预测性返回动画；`GlassSubpage` 仅用于同一页面内部的二级详情/设置子页。

## 安全

- 学号/密码绝不落盘，内存 `char[]` 用完清零
- 凭据走 `KeystoreCipher`（AES-GCM + Android Keystore），三个 vault 各自独立 alias
- 所有网络请求必须走 `BuctTlsTrustManager`；**禁止** `trustAllCerts`、`hostnameVerifier { true }`
- 日志脱敏：域名可留，学号/姓名/成绩数字必须替换
- `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 必须 exclude 三个 vault 文件
- 匿名统计仅使用自托管 Umami（`https://stats.buct.one`），基于本地随机 `UUIDv4` + `BuctTlsTrustManager`，绝不采集学号/姓名/Cookie/硬件指纹，且必须受「我的 → 数据与安全 → 匿名使用统计」实时开关控制；禁止引入任何第三方商业统计/崩溃上报 SDK。

## 提交

- 使用 `<type>: <一行中文说明>` 格式的 commit message（常用前缀：`feat:`、`fix:`、`docs:`、`ci:`、`refactor:`、`chore:`），一行说清做了什么
- 改动涉及解析器的，commit 里写明 `parserVersion: n → n+1`
- 不提交 `local.properties`、`*.jks`、`release-notes/` 之外的发版材料
- 发版推送 `v*` 标签前，必须先同步更新 `app/version.properties`（`VERSION_NAME` 与 `VERSION_CODE`）、`CHANGELOG.md`、`README.md` 与 `docs/规划.md` 并推送到 `main`（CI 会校验 `v${VERSION_NAME} == $TAG` 并从 `CHANGELOG.md` 提取对应版本的 Release Notes）。