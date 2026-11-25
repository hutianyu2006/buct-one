package com.buct.one.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.buct.one.data.login.LoginState
import com.buct.one.platform.createIosSessionClient
import com.buct.one.shared.notice.AppNotice
import com.buct.one.shared.notice.NoticeRepository
import com.buct.one.shared.ui.BuctOneTheme
import com.buct.one.shared.ui.HomeScreen
import com.buct.one.shared.ui.LoginScreen
import com.buct.one.shared.ui.NoticeDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface NoticeUiState {
    data object Loading : NoticeUiState
    data class Loaded(val notices: List<AppNotice>) : NoticeUiState
    data class Failed(val message: String) : NoticeUiState
}

/** iOS 端的 Compose 根节点，被 MainViewController 直接嵌入 UIViewController。 */
@Composable
fun App() {
    BuctOneTheme {
        val repository = remember { NoticeRepository(createDarwinHttpClient()) }
        DisposableEffect(repository) {
            onDispose { repository.close() }
        }
        val loginController = remember {
            CasLoginController(
                CasPasswordLoginClient(
                    httpClient = createIosSessionClient(),
                    userAgent = iosUserAgent(),
                ),
            )
        }
        val scope = rememberCoroutineScope()
        val loginState by loginController.state.collectAsState()

        var state by remember { mutableStateOf<NoticeUiState>(NoticeUiState.Loading) }
        var reloadKey by remember { mutableStateOf(0) }
        var selected by remember { mutableStateOf<AppNotice?>(null) }
        var skippedLogin by remember { mutableStateOf(false) }
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

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val notice = selected
                when {
                    notice != null -> NoticeDetailScreen(notice = notice, onBack = { selected = null })
                    loggedInUsername != null || skippedLogin -> HomeScreen(
                        state = state,
                        onRefresh = { reloadKey += 1 },
                        onSelect = { selected = it },
                        sessionUsername = loggedInUsername,
                        onLogout = {
                            loginController.reset()
                            skippedLogin = false
                        },
                    )
                    else -> LoginScreen(
                        state = loginState,
                        onSubmit = { username, password ->
                            scope.launch { loginController.login(username, password) }
                        },
                        onSkip = { skippedLogin = true },
                    )
                }
            }
        }
    }
}
