package com.buct.one.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.buct.one.data.login.CasLoginController
import com.buct.one.data.login.CasPasswordLoginClient
import com.buct.one.data.login.CasSmsLoginClient
import com.buct.one.data.login.LoginState
import com.buct.one.data.source.campusnet.CampusNetController
import com.buct.one.data.source.campusnet.CampusNetGateway
import com.buct.one.data.source.electricity.ElectricityController
import com.buct.one.data.source.electricity.ElectricityGatewayClient
import com.buct.one.data.source.tygl.TyglController
import com.buct.one.data.source.tygl.TyglGateway
import com.buct.one.data.store.CampusNetSnapshotStore
import com.buct.one.data.store.ElectricitySnapshotStore
import com.buct.one.data.store.JwNoticeSnapshotStore
import com.buct.one.data.source.jwnotice.JwNoticeController
import com.buct.one.data.source.jwnotice.JwNoticeGateway
import com.buct.one.data.source.scs.ScsController
import com.buct.one.data.source.scs.ScsGateway
import com.buct.one.data.store.ScsSnapshotStore
import com.buct.one.data.sync.ReloginWrapper
import com.buct.one.data.sync.SsoReloginRunner
import com.buct.one.platform.IosAppConfig
import com.buct.one.platform.IosCredentialStore
import com.buct.one.platform.UserDefaultsKeyValueStore
import com.buct.one.platform.clearIosSessionCookies
import com.buct.one.platform.createIosReloginCoordinator
import com.buct.one.platform.createIosSessionClient
import com.buct.one.platform.iosSecureVault
import com.buct.one.platform.reloginWithSavedCredentials
import com.buct.one.shared.notice.AppNotice
import com.buct.one.shared.notice.NoticeRepository
import com.buct.one.shared.ui.BuctOneTheme
import com.buct.one.shared.ui.CampusNetScreen
import com.buct.one.shared.ui.ElectricityScreen
import com.buct.one.shared.ui.TyglScreen
import com.buct.one.shared.ui.HomeScreen
import com.buct.one.shared.ui.JwNoticeScreen
import com.buct.one.shared.ui.LoginScreen
import com.buct.one.shared.ui.ScsScreen
import com.buct.one.shared.ui.NoticeDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface NoticeUiState {
    data object Loading : NoticeUiState
    data class Loaded(val notices: List<AppNotice>) : NoticeUiState
    data class Failed(val message: String) : NoticeUiState
}

private enum class HomeSection { Notices, JwNotices, Scs, CampusNet, Electricity, Tygl }

/** iOS 端的 Compose 根节点，被 MainViewController 直接嵌入 UIViewController。 */
@Composable
fun App() {
    BuctOneTheme {
        val repository = remember { NoticeRepository(createDarwinHttpClient()) }
        DisposableEffect(repository) {
            onDispose { repository.close() }
        }

        val sessionClient = remember { createIosSessionClient() }
        val passwordClient = remember {
            CasPasswordLoginClient(httpClient = sessionClient, userAgent = iosUserAgent())
        }
        val loginController = remember {
            CasLoginController(
                loginClient = passwordClient,
                smsClient = CasSmsLoginClient(httpClient = sessionClient, userAgent = iosUserAgent()),
            )
        }
        val reloginCoordinator = remember { createIosReloginCoordinator() }
        // 会话过期时自动用 Keychain 里的密码续期一次，失败才把「会话已失效」交给界面
        val reloginRunner = remember {
            SsoReloginRunner(reloginCoordinator) { username, passwordChars ->
                reloginWithSavedCredentials(passwordClient, username, passwordChars)
            }
        }

        val cacheStore = remember { UserDefaultsKeyValueStore() }
        val jwController = remember {
            JwNoticeController(
                gateway = JwNoticeGateway(httpClient = sessionClient, userAgent = iosUserAgent()),
                cache = JwNoticeSnapshotStore(cacheStore),
                // 会话过期先尝试用 Keychain 里的密码自动续期，失败才提示重新登录
                withRelogin = ReloginWrapper { block -> reloginRunner.run { block() } },
            )
        }
        val campusNetController = remember {
            CampusNetController(
                gateway = CampusNetGateway(httpClient = sessionClient, userAgent = iosUserAgent()),
                cache = CampusNetSnapshotStore(cacheStore),
                withRelogin = ReloginWrapper { block -> reloginRunner.run { block() } },
            )
        }
        val tyglController = remember {
            TyglController(
                gateway = TyglGateway(httpClient = sessionClient, userAgent = iosUserAgent()),
                withRelogin = ReloginWrapper { block -> reloginRunner.run { block() } },
            )
        }
        val scsController = remember {
            ScsController(
                gateway = ScsGateway(httpClient = sessionClient, userAgent = iosUserAgent(), deviceId = "buct-one-ios"),
                cache = ScsSnapshotStore(cacheStore),
                withRelogin = ReloginWrapper { block -> reloginRunner.run { block() } },
            )
        }
        val electricityController = remember {
            ElectricityController(
                gateway = ElectricityGatewayClient(
                    httpClient = sessionClient,
                    userAgent = iosUserAgent(),
                    appVersion = AppInfo.VERSION,
                ),
                cache = ElectricitySnapshotStore(cacheStore),
            )
        }
        val scope = rememberCoroutineScope()
        val loginState by loginController.state.collectAsState()
        val jwState by jwController.state.collectAsState()
        val campusNetState by campusNetController.state.collectAsState()
        val electricityState by electricityController.state.collectAsState()
        val tyglState by tyglController.state.collectAsState()
        val scsState by scsController.state.collectAsState()

        var state by remember { mutableStateOf<NoticeUiState>(NoticeUiState.Loading) }
        var reloadKey by remember { mutableStateOf(0) }
        var selected by remember { mutableStateOf<AppNotice?>(null) }
        var skippedLogin by remember { mutableStateOf(false) }
        var rememberPassword by remember { mutableStateOf(IosCredentialStore.hasSaved(iosSecureVault)) }
        var section by remember { mutableStateOf(HomeSection.Notices) }
        val loggedInUsername = (loginState as? LoginState.Success)?.username

        LaunchedEffect(reloadKey) {
            state = NoticeUiState.Loading
            state = try {
                NoticeUiState.Loaded(repository.load())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NoticeUiState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
            }
        }

        LaunchedEffect(section, loggedInUsername) {
            when {
                section == HomeSection.JwNotices && loggedInUsername != null -> {
                    if (jwController.lastSnapshot() == null) jwController.restoreFromCache()
                    jwController.refresh()
                }
                section == HomeSection.CampusNet && loggedInUsername != null -> {
                    if (campusNetController.lastSnapshot() == null) campusNetController.restoreFromCache()
                    campusNetController.refresh(loggedInUsername)
                }
                section == HomeSection.Electricity && loggedInUsername != null -> {
                    if (electricityController.lastReadings() == null) electricityController.restoreFromCache()
                    if (electricityController.targets().isEmpty()) electricityController.loadCatalog()
                }
                section == HomeSection.Tygl && loggedInUsername != null -> tyglController.refresh()
                section == HomeSection.Scs && loggedInUsername != null -> {
                    if (scsController.lastSnapshot() == null) scsController.restoreFromCache()
                    scsController.refresh()
                }
            }
        }

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (loggedInUsername != null) {
                    TabRow(
                        selectedTabIndex = section.ordinal,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        Tab(
                            selected = section == HomeSection.Notices,
                            onClick = { section = HomeSection.Notices },
                            text = { Text("公告") },
                        )
                        Tab(
                            selected = section == HomeSection.JwNotices,
                            onClick = { section = HomeSection.JwNotices },
                            text = { Text("教务消息") },
                        )
                        Tab(
                            selected = section == HomeSection.Scs,
                            onClick = { section = HomeSection.Scs },
                            text = { Text("第二课堂") },
                        )
                        Tab(
                            selected = section == HomeSection.CampusNet,
                            onClick = { section = HomeSection.CampusNet },
                            text = { Text("校园网") },
                        )
                        Tab(
                            selected = section == HomeSection.Electricity,
                            onClick = { section = HomeSection.Electricity },
                            text = { Text("电费") },
                        )
                        Tab(
                            selected = section == HomeSection.Tygl,
                            onClick = { section = HomeSection.Tygl },
                            text = { Text("体测") },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    val notice = selected
                    when {
                        notice != null -> NoticeDetailScreen(notice = notice, onBack = { selected = null })
                        loggedInUsername != null || skippedLogin -> {
                            if (section == HomeSection.JwNotices && loggedInUsername != null) {
                                JwNoticeScreen(
                                    state = jwState,
                                    onRefresh = { scope.launch { jwController.refresh() } },
                                    loadDetail = { item -> jwController.fetchDetail(item) },
                                )
                            } else if (section == HomeSection.Scs && loggedInUsername != null) {
                                ScsScreen(
                                    state = scsState,
                                    onRefresh = { scope.launch { scsController.refresh() } },
                                    onBack = { section = HomeSection.Notices },
                                )
                            } else if (section == HomeSection.CampusNet && loggedInUsername != null) {
                                CampusNetScreen(
                                    state = campusNetState,
                                    onRefresh = { scope.launch { campusNetController.refresh(loggedInUsername) } },
                                )
                            } else if (section == HomeSection.Electricity && loggedInUsername != null) {
                                ElectricityScreen(
                                    state = electricityState,
                                    lowBalanceThreshold = 20.0,
                                    onRefresh = {
                                        scope.launch {
                                            electricityController.restoreFromCache()
                                            electricityController.refresh(
                                                targets = electricityController.targets(),
                                                clientId = "buct-one-ios",
                                                studentId = loggedInUsername,
                                                // 构建期从 Info.plist 注入；未配置时为空串（查询失败但不崩溃）
                                                signSecret = IosAppConfig.electricitySignSecret,
                                            )
                                        }
                                    },
                                    onBack = { section = HomeSection.Notices },
                                )
                            } else if (section == HomeSection.Tygl && loggedInUsername != null) {
                                TyglScreen(
                                    state = tyglState,
                                    onRefresh = { scope.launch { tyglController.refresh() } },
                                    onBack = { section = HomeSection.Notices },
                                )
                            } else {
                                HomeScreen(
                                    state = state,
                                    onRefresh = { reloadKey += 1 },
                                    onSelect = { selected = it },
                                    sessionUsername = loggedInUsername,
                                    onLogout = {
                                        loginController.reset()
                                        jwController.reset()
                                        scsController.reset()
                                        clearIosSessionCookies()
                                        // 退出登录立即擦除 Keychain 里的「记住密码」与重登冷却状态
                                        IosCredentialStore.clear(iosSecureVault)
                                        reloginCoordinator.resetCooldown()
                                        rememberPassword = false
                                        section = HomeSection.Notices
                                        skippedLogin = false
                                    },
                                )
                            }
                        }
                        else -> LoginScreen(
                            state = loginState,
                            onSubmit = { username, password, captchaCode ->
                                scope.launch {
                                    loginController.login(username, password, captchaCode)
                                    if (loginController.state.value is LoginState.Success) {
                                        if (rememberPassword) {
                                            // 只在登录成功后落 Keychain；失败绝不留存密码
                                            IosCredentialStore.save(iosSecureVault, username, password.toCharArray())
                                        } else {
                                            IosCredentialStore.clear(iosSecureVault)
                                        }
                                        reloginCoordinator.resetCooldown()
                                    }
                                }
                            },
                            rememberPassword = rememberPassword,
                            onRememberPasswordChange = { rememberPassword = it },
                            onSkip = { skippedLogin = true },
                            onRequestSmsCaptcha = { username ->
                                scope.launch { loginController.requestSmsCaptcha(username) }
                            },
                            onSendSmsCode = { username, captcha ->
                                scope.launch { loginController.sendSmsCode(username, captcha) }
                            },
                            onSmsLogin = { username, smsCode ->
                                scope.launch { loginController.loginWithSmsCode(username, smsCode) }
                            },
                        )
                    }
                }
            }
        }
    }
}