# BUCT One 双端对齐开发指南（INSTRUCTIONS）

> **受众**：接手本仓库推进 Android → 双端（KMP + Compose Multiplatform）迁移的 AI 编码助手 / 开发者。  
> **目标**：在**不破坏现有 Android 端功能、安全性与 CI 门禁**的前提下，逐步将 Android 独占能力下沉至 `:shared`，最终实现业务逻辑与主界面双端复用、系统级能力平台桥接。
>
> **本文件只保留未完成的工作**。已完成项（`SecureVault` 与 Cookie 持久化、短信验证码登录、WebVPN 编解码与会话管理、证书链单一数据源与 iOS TLS 双链兜底、电费 / 体测 / i北化 共享网关与 iOS 页面、iOS 短信登录界面）已从正文删除，落地状态见 `docs/规划.md`。

---

## 0. 开工前必读：硬约束与工程红线

在写任何代码前，必须先遵守 `AGENTS.md` 与 `.github/workflows/check.yml` 的强制门禁：

1. **本机禁止跑 Gradle 构建**：所有编译与单测走 GitHub Actions。提交推送后通过 `gh run list --limit 5` / `gh run view <id> --log-failed` 查看结果（本机执行 `git push` / `gh` 需配代理 `http://127.0.0.1:7897`）。
2. **包结构与依赖铁律（`check.yml` 静态 grep 拦截，违反即失败）**：
   - `model/`（含 `app/.../data/model` 与 `shared/commonMain/.../data/model`）：**零任何 `import`**（纯 Kotlin 数据类与枚举；序列化注解除外若无外部 import——注意当前共享 `model` 也严格遵守**零 `import`**，编解码放在 `store/` 或解析器里！写新 model 前先看现有 `shared/src/commonMain/kotlin/com/buct/one/data/model/` 写法）。
   - `shared/commonMain/.../data/source/<域>`：**禁止** `org.json`、`org.jsoup`、`java.*`、`android.*`、`javax.*`；**只能**用 `com.buct.one.data.json`（`JsonFacade`）、`com.buct.one.data.text`、`com.buct.one.platform.*` 与 `com.fleeksoft.ksoup`。各域子包互不依赖。
   - Android `:app` 侧：`ui/*` 禁止 import `data.source.*`；`sync/*` 禁止 import `ui`；`store/*` 禁止 import `source/sync/ui`。
3. **数据层四条铁律**（新网关/状态机直接复用 `shared/commonMain/.../data/sync/DomainSync.kt`）：
   - 失败永不产生新 `data`（只改 `refreshState` 与 `lastError`，保留旧快照）；
   - `NEVER` ≠ `EMPTY_CONFIRMED`；
   - 改动解析逻辑必须递增对应域的 `parserVersion`，并在 commit message 注明 `parserVersion: n → n+1`；
   - `capturedAt` 单调不减。
4. **安全与隐私底线**：
   - 登录必须原生协议（Ktor / OkHttp），**禁止 WebView 登录**；
   - **禁止** `trustAllCerts`、`AllowAllHostnameVerifier`、`hostnameVerifier { true }`；Android 侧所有 Ktor 客户端必须通过 `AndroidHttpClients`（挂有 `BuctTlsTrustManager` + `BuctDns`）注入；
   - 凭据与会话 Cookie **绝不落明文**（iOS 禁止把 Cookie/密码写入普通 `NSUserDefaults`，必须进 Keychain 且禁止 iCloud 备份）；内存 `char[]` 用后清零；
   - **禁止**引入第三方商业统计/崩溃/广告 SDK（仅保留自托管 `stats.buct.one` Umami 匿名统计，且受开关控制）；
   - 控制 APK 体积（硬门禁 100MB / 预警 80MB），不引入重型第三方库。
5. **“只写一份”原则**：每次把某域迁入 `shared/commonMain` 后，**同步把 Android `:app` 对应代码改为委托调用 `:shared`**（参考 `ElectricityClient`、`JwNoticeSyncService`、`BuctCertificates` 的做法），避免双份逻辑漂移。

---

## 1. 当前代码资产基线（切勿重复造轮子）

在动手前先确认 `:shared` 里已经有什么，直接复用：

- **已共享（`shared/src/commonMain`）**：
  - **数据模型（14 个，零 `import`）**：`data/model/*Models.kt`（campusnet / card / electricity / jwnotice / mail / portal / scs / sjcx / theol / tygl / taskimport）、`NationalFitnessScorer.kt`、`Provenance.kt`、`ReminderModels.kt`（含规则引擎）。
  - **全部 10 个业务域纯函数解析器**：`data/source/{campusnet,card,electricity,jwnotice,mail,portal,scs,sjcx,theol,tygl}/*Parser.kt`。
  - **基础工具**：`data/json/JsonFacade.kt`、`data/text/{DateTimeTools,UrlTools}.kt`、`data/sync/DomainSync.kt`、`platform/KeyValueStore.kt`、`platform/{AppLogger,AppScheduler,SecureRandom}.kt`、`platform/HttpClientFactory.kt`、`platform/session/{CookieStore,CookieStoreCookiesStorage}.kt`。
  - **加密原语**：`platform/crypto/{AppCrypto,BuiltinCrypto,AesCbc,AesLoginCrypto,Sha256,Md5,Sm2,Sm2LoginCrypto,Sm3,Rsa}.kt`（AES-CBC / AES-CFB-128 / AES-ECB+PKCS7、SHA-256、HMAC-SHA256、MD5、SM2、SM3、RSA-PKCS#1 公钥加密）。
  - **安全存储与网络底座**：`platform/security/{SecureVault,PersistentCookieStore}.kt`（凭据与 Cookie 加密落盘）、`platform/network/{WebVpnCodec,WebVpnSession,WebVpnRequestExecutor,BuctCertificatePins}.kt`。
  - **会话与重登**：`data/sync/{SsoSilentReloginCoordinator,SsoReloginRunner,SsoCredentialSources}.kt`（单飞重登 + 30s 冷却 + 各域 Controller 的 `ReloginWrapper` 注入点）。
  - **已共享网关与控制器**：
    - `data/login/{CasLoginPage,CasPasswordLoginClient,CasSmsLoginClient,CasLoginController,CasPasskeyClient}.kt`（账号密码 + 短信验证码 + 图形验证码 PNG + 通行密钥协议；Passkey 的断言签名走 `PlatformPasskeySigner` 平台接口）
    - `data/source/electricity/{ElectricityGatewayClient,ElectricityController,ElectricityRoomCatalogSource}.kt` + `data/store/ElectricitySnapshotStore.kt`（含全校宿舍字典 `shared/src/commonMain/resources/electricity_rooms.json`）
    - `data/source/jwnotice/{JwNoticeGateway,JwNoticeController}.kt` + `data/store/JwNoticeSnapshotStore.kt`（Android 已委托，iOS 已接通）
    - `data/source/campusnet/{CampusNetGateway,CampusNetController}.kt` + `data/store/CampusNetSnapshotStore.kt`（iOS 已接通）
    - `data/source/tygl/{TyglGateway,TyglController}.kt`（iOS 已接通）
    - `data/source/portal/PortalGateway.kt`（校历 / 教学周 / 在借图书 / 四栏资讯，**iOS 尚未接页面**）
    - `shared/notice/NoticeRepository.kt`（iOS 已接通）
- **iOS 现有壳（`shared/src/iosMain` + `iosApp/`）**：
  - Compose 页面：`LoginScreen`、`HomeScreen`（公告）、`NoticeDetailScreen`、`JwNoticeScreen`、`CampusNetScreen`、`ElectricityScreen`、`TyglScreen`、`Markdown`、`Theme`、`Dates`，入口在 `shared/src/iosMain/kotlin/com/buct/one/shared/App.kt`。
  - 网络与凭据：Darwin 引擎 + `IosTlsBridge`（Swift 侧 `SecTrust` 双链兜底，仅限 `*.buct.edu.cn`）、Keychain `SecureVault`、Keychain 持久化 Cookie。
  - 尚缺：WebVPN 请求链路接入（只有共享编解码与会话类，未装到 Ktor 客户端）、门户/教务/一卡通/邮箱/北化在线/实践教学页面。

---

## 2. 分阶段实现路线与技术方案（Roadmap）

请严格按 **Phase 0 → Phase 5** 推进，避免在底座未通时硬写上层 UI。每个 Phase 内的 Task 序号连续编排，做完一项删一项。

---

### Phase 0：跨端底座收尾（阻塞一切后续功能）

> Phase 0 的三项底座已完成（图形验证码与静默重登、Passkey 协议层、WebVPN 自动降级），
> 落地细节见 `docs/规划.md`「已迁移的双端共享代码」。平台认证器（`PlatformPasskeySigner` 的
> Android/iOS 实现）仍待接：接口与协议层已就位，缺的是两端各自的系统认证器绑定。

#### Task 0.4：平台通行密钥认证器绑定
- **现状**：`data/login/CasPasskeyClient.kt`（协议）+ `PlatformPasskeySigner`（接口）+ `platform/crypto/Rsa.kt`（RSA-PKCS#1）已共享，MockEngine 覆盖 options 解析、断言提交、`/finish` 成功与失败分支。
- **做什么**：
  1. **Android**：把 `app/.../LoginActivity.kt` 里现有的 `BuctWebAuthnManager` + `BuctSoftwarePasskey` 改造成 `PlatformPasskeySigner` 实现，协议部分改为调用共享 `CasPasskeyClient`（登录页交互与文案保持不变）。
  2. **iOS**：在 `shared/src/iosMain` 用 `AuthenticationServices` 的 `ASAuthorizationPlatformPublicKeyCredentialProvider` 实现同一接口，凭据只进 Keychain。
- **验收**：Android 通行密钥登录/注册行为与现网一致；iOS 能弹出系统认证器并把断言交给共享协议层；`:shared:jvmTest` 全绿。

---

### Phase 1：已有 Parser 的轻量业务域双端化（收尾）

> **通用模板（所有域通用）**：  
> `shared/commonMain/data/source/<域>/<Domain>Gateway.kt`（Ktor 请求 + 调用现有 `*Parser`） → `shared/commonMain/data/source/<域>/<Domain>Controller.kt`（组合 `DomainSync` + `*SnapshotStore`） → `shared/commonTest` 补 `MockEngine` 集成测试 → Android 侧 `SyncService` 改为委托共享 Gateway/Controller。  
> 共享网关/控制器已落地的域，只剩最后一步"Android 改委托"与平台参数注入。

> **Phase 1 已全部完成**（电费签名密钥注入、门户/体测 Android 改委托、第二课堂网关下沉 + iOS 页面），
> 落地细节见 `docs/规划.md`。下面只保留尚未接通的 iOS 门户页面。

#### Task 1.5：iOS i北化页面
- **现状**：`PortalGateway`（校历 / 教学周 / 在借图书 / 四栏资讯）已下沉，Android `PortalClient` 已改委托；
  iOS 尚无对应页面（只有网关与 `PortalGatewayTest`）。
- **做什么**：
  1. 在 `shared/src/iosMain/.../ui/` 新增「i北化」页：校历与当前教学周、图书馆在借清单与应还提醒、
     四栏资讯列表与正文阅读（复用现有 `Markdown` / 主题组件）。
  2. 新增 `PortalController`（组合 `DomainSync` + 新的 `PortalSnapshotStore`，三份快照 calendar / library / news），
     会话失效单独成态，失败保留旧快照。
  3. 校外访问依赖 `WebVpnRequestExecutor`（Phase 0 已完成）；确认 `i.buct.edu.cn` / `mnews.buct.edu.cn`
     都走「直连优先 → 403 转代理」链路。
- **验收**：iOS 能看校历/教学周、在借图书与四栏资讯；`:shared:jvmTest` 覆盖 Controller 的失败保留旧快照与会话失效分支。

---

### Phase 2：核心教务域下沉与双端化（本科 `jwglxt` + 研究生 `gmis`）

这是工作量最大的一块，目前所有代码都在 `app/src/main/java/com/buct/course/`（包含 Java 历史代码），拆成 4 步走：

#### Task 2.1：模型迁移（严格零 `import`）
- 检查 `model/Course.java`、`model/ExamInfo.kt`、`model/CourseFilter.kt`、`model/BuctGpaScale.kt`、`academic/AcademicModels.kt`、`academic/AcademicStudyModels.kt`。
- 在 `shared/commonMain/data/model/` 新建纯 Kotlin 数据模型（如 `AcademicModels.kt`）：课表课程、自定义课程、成绩条目、GPA 统计点、培养方案树节点（递归/扁平带 `parentId` 均可）、考试安排、空闲教室、教学周校历。

#### Task 2.2：解析器重写与单测
- 用 `JsonFacade` + `Ksoup` + `DateTimeTools` 把 `utils/CourseParser.java`、`academic/AcademicStudyParser.kt`、`academic/SouthsoftProtocol.kt`、`schedule/ScheduleJson.kt` 迁入 `shared/commonMain/data/source/academic/`。
- **必做**：脱敏真实响应样本放 `shared/src/commonTest`，覆写 `parserVersion` 校验（注意 `AGENTS.md`：绝不在样本里残留真实学号/姓名/成绩）。

#### Task 2.3：网络网关与存储迁移
- 把 `network/CourseApiClient.java`、`network/EmptyClassroomApi.kt`、`academic/AcademicStudyReader.kt`、`academic/SouthsoftStudyReader.kt` 迁为 Ktor `AcademicGateway`（支持本科 `jwglxt` 直连与研究生 `gmis` 走 WebVPN，以及本科/研究生模式切换配置）。
- `ScheduleCacheStore` / `ScheduleCalendarStore` / `CustomExamStore` 迁为基于 `KeyValueStore` 的 `SnapshotStore`。
- Android `:app` 对应 `CourseApiClient` / `AcademicStudyReader` 逐步改为桥接共享层输出。

#### Task 2.4：核心教务页面
- 周/日课表（多学期切换、校历教学周校准、自定义课程增删改）；
- 成绩与学业分析（累计 GPA 折线图与目标 GPA 试算——Android 现用纯 Compose `Canvas` 绘制，可直接写进跨端 Compose）；
- 多级树状培养方案（折叠线与一键全展）、考试倒计时、空闲教室查询。

---

### Phase 3：高交互教学与校园生活闭环

#### Task 3.1：北化在线（THEOL）
- 迁移 `TheolClient.kt` 为两阶段共享网关（第一阶段待办作业/测验，第二阶段互斥队列后台补全全量课程/单元/资源）。
- 抽象两个平台能力接口：
  - `PlatformFileViewer`：带 Cookie 下载附件到临时目录并用系统预览（Android 用 `FileProvider` + Intent，iOS 用 `QLPreviewController` + `UIActivityViewController`）。
  - `PlatformWebSubmitter`：弹出携带当前 `CookieStore` 的浏览器提交作业（Android 保留 `TheolSubmitWebViewActivity`，iOS 用 `WKWebView` 注入 `WKHTTPCookieStore`）。
- **Excel/CSV 表格批量导入**：CSV 解析放 `commonMain`；`.xlsx` 读表（`XlsxSheetReader.kt` 当前用 `java.util.zip.ZipInputStream`）可抽象 `PlatformZipReader` 或引入轻量纯 KMP 解压逻辑，再配合 iOS `UIDocumentPickerViewController` 选文件。

#### Task 3.2：校园邮箱（Mail）
- 迁移 `MailClient.kt`（`sidportal` → `mailuser` → `/js6/s` JSON-RPC：收件箱/未读/文件夹/搜索/分页/标已读/删除还原/附件列表）。
- 邮件 HTML 正文安全渲染：在平台层提供只读 HTML 容器（Android 用安全配置 WebView，iOS 用禁 JS 的 `WKWebView.loadHTMLString`，拦截外跳链接）。

#### Task 3.3：校园一卡通（Ecard）
- 迁移 `EcardClient.kt`（独立 AES+RSA 登录流程 + WebVPN + `SecureVault` 读写一卡通凭据，登录失败自动抹除密文）。
- 消费洞察图表：参考 `app/.../ui/screen/EcardInsightsCard.kt` 用纯 Compose `Canvas` 跨端实现。

#### Task 3.4：实践教学（SJCX）与选课抢课
- 迁移 `SjcxClient.kt` 为 `SjcxGateway`（实验课表、讲座报名/退选/前台轮询蹲守、创新学分）。
- 选课/退课/队列抢课：将 `AcademicGrabQueue`、`ProtocolGrabRunner` 迁为纯 Kotlin 协程状态机（`commonMain`）。*注意：iOS 受系统后台机制限制，只支持**前台**队列抢课，不做后台常驻 `GrabService` 等价物。*

---

### Phase 4：UI 层合流（实现 `commonMain` 单份 Compose UI）

#### Task 4.1：打通 `:shared` Android 目标
- 在 `shared/build.gradle.kts` 接入 `com.android.kotlin.multiplatform.library`（替代单纯 `jvm()`，或评估保持兼容的同时让 Compose UI 编译进 Android AAR）。
- 同步检查 `.github/workflows/check.yml` 与 `build.yml` 缓存及构建命令是否受影响（不违反"全云端 CI、无本地工具链插件"约束）。

#### Task 4.2：共享页面与导航迁入 `commonMain`
- 把 `shared/src/iosMain/.../ui/` 下已有页面，以及 Phase 1~3 新写的 Compose 页面全部移到 `shared/src/commonMain/kotlin/com/buct/one/shared/ui/`。
- 落地五栏底栏主框架（`课表 · 成绩 · 工作台 · 作业 · 我的`）、2D 自由坐标栅格工作台拖拽/缩放布局引擎（纯 Compose 状态计算可完全共享）、`DemoData` 演示模式、多账号切换与设置页。

#### Task 4.3：液态玻璃与导航适配层
- 抽象 `PlatformGlassSurface` 与 `PlatformBackHandler`：
  - Android 继续保留现有 `Backdrop` / AGSL 液态玻璃渲染与**工作台一级功能独立 `ComponentActivity` + `GlassWindowHost` 预测性返回手势**（`AGENTS.md` 硬要求，不可破坏！）；
  - iOS 侧实现轻量化半透明毛玻璃容器（或桥接 `UIVisualEffectView`）与原生侧滑返回手势。

---

### Phase 5：iOS 原生系统生态补齐（`iosApp/` Swift + `iosMain` 桥接）

此阶段为纯平台特性，双端各自维护薄壳：

#### Task 5.1：桌面小组件（WidgetKit）
- 让 `:shared` 在 iOS 写入 `KeyValueStore` 时支持指定 **App Group**（`NSUserDefaults(suiteName: "group.com.buct.one")`）导出脱敏后的课表/待办/考试/余额快照 JSON；
- 在 `iosApp` Xcode 工程新增 Widget Extension，用 SwiftUI 实现对应的 6 种小组件（周课表、今日课表、今日课表·卡片、待办事项、考试倒计时、余额信息卡）。

#### Task 5.2：本地智能提醒与后台同步
- 共享层的 `ReminderRuleEngine`（已在 `commonMain`）负责算出提醒事件；
- iOS 侧用 `UNUserNotificationCenter` 注册本地定时通知，并在 `AppDelegate` / `SwiftUI App` 注册 `BGAppRefreshTask` 执行低频后台同步刷新（UI 提示需告知用户 iOS 后台调度由系统控制，无法做到 Android `AlarmManager` 的精确唤醒）。

#### Task 5.3：系统集成能力
- **ICS 日历导出**：iOS 桥接 `EventKit`（请求日历权限写入课程）或生成 `.ics` 临时文件拉起分享面板；
- **头像与文件**：`PHPickerViewController`（自定义头像）、`UIDocumentPickerViewController`（表格导入）；
- **Umami 匿名统计**：将 `app/.../analytics/UmamiTracker.kt` 迁入 `commonMain` Ktor（请求 `https://stats.buct.one/api/send`，随机 `UUIDv4` 存本地不进云备份，受「数据与安全」开关实时控制）。

#### Task 5.4：版本检查与更新提示
- 复用 `https://update.buct.one/update.json` 检查逻辑到 `commonMain`，Android 保持 APK 下载 + SHA-256/签名校验安装；iOS 仅弹窗提示新版本并跳转发布页 / TestFlight / App Store。

---

## 3. 每个任务的标准执行 SOP（请按此节奏提交）

每接手一个子任务，严格按以下步骤执行：

1. **先读现网协议与 Android 参考实现**：读对应的 `app/.../data/source/<域>/*Client.kt` 与 `app/.../ui/route/`，弄清全部请求头、Referer、重定向与异常分支。
2. **先写 `commonMain` + `commonTest`**：
   - 模型放 `data/model`（零 `import`）；
   - 网关放 `data/source/<域>`（只用 Ktor + `JsonFacade` + `Ksoup` + `data/text` + `platform/`）；
   - 控制器组合 `DomainSync` + `SnapshotStore`；
   - 在 `shared/src/commonTest` 用 Ktor `MockEngine` 写通正常解析、会话失效识别、失败保留旧快照测试。
3. **同步接入 Android 委托（保持不退化）**：让 Android 侧调用共享层实现，确保原有功能、UI 样式与预测性返回手势零破坏。
4. **接通 iOS 界面**：在 `shared/src/iosMain`（Phase 4 之后直接在 `commonMain`）接入入口与刷新/错误提示。
5. **自查静态规则并提交**：
   - 核对无 `org.json` / `org.jsoup` / `java.*` / `android.*` / `trustAllCerts` 越界引入；
   - 核对无硬编码真实学号、密码、Cookie；
   - Commit 格式：`<type>: <一行中文说明>`（涉及解析器改动必写 `parserVersion: n → n+1`）；
   - Push 后用 `gh run list` 确认 `code-check`（含 `:app:compileDebugSources` 和 `:shared:jvmTest`）与 `build`（`apk` + `ipa`）全绿再进入下一项。
6. **做完即删**：任务完成后从本文件删除该 Task，并顺延后续编号，保持序号连续。