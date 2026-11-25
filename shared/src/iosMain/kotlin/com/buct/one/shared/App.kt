package com.buct.one.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.buct.one.shared.notice.AppNotice
import com.buct.one.shared.notice.NoticeRepository
import com.buct.one.shared.ui.BuctOneTheme
import com.buct.one.shared.ui.HomeScreen
import com.buct.one.shared.ui.NoticeDetailScreen
import kotlinx.coroutines.CancellationException

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

        var state by remember { mutableStateOf<NoticeUiState>(NoticeUiState.Loading) }
        var reloadKey by remember { mutableStateOf(0) }
        var selected by remember { mutableStateOf<AppNotice?>(null) }

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
                if (notice == null) {
                    HomeScreen(
                        state = state,
                        onRefresh = { reloadKey += 1 },
                        onSelect = { selected = it },
                    )
                } else {
                    NoticeDetailScreen(notice = notice, onBack = { selected = null })
                }
            }
        }
    }
}
