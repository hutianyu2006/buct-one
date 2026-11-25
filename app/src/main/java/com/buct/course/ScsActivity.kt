package com.buct.course

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassProgressBar
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.sync.ScsSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第二课堂综合素质评价平台（scs.buct.edu.cn）原生液态玻璃界面。
 * 复用 sidportal 统一身份认证换取 JWT，展示德/智/体/美/劳五育得分、历年学期评定明细与校园活动。
 */
class ScsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 1)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    ScsScreen(
                        initialTab = initialTab,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_INITIAL_TAB = "extra_initial_tab"

        fun launch(context: Context, initialTab: Int = 0) {
            context.startActivity(
                Intent(context, ScsActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab)
                }
            )
        }
    }
}

@Composable
private fun ScsScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { ScsSyncService.getInstance(context) }

    val slice by service.scsState.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=五育成绩 1=二课活动
    var selectedTermKey by rememberSaveable { mutableStateOf("") }
    var showOnlyScored by rememberSaveable { mutableStateOf(true) }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    val webVpnAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            feedbackMsg = null
            scope.launch {
                service.refresh(userInitiated = true)
            }
        }
    }
    fun launchWebVpnAuth() {
        webVpnAuthLauncher.launch(
            Intent(context, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_WEBVPN_AUTH_MODE, true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            }
        )
    }

    LaunchedEffect(Unit) {
        if ((slice.provenance.refreshState == RefreshState.NEVER ||
                slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ||
                !slice.lastError.isNullOrBlank()) && service.canAutoSync()
        ) {
            service.refresh()
        }
    }

    val snapshot = slice.data
    val terms = snapshot?.terms.orEmpty()
    val defaultTerm = remember(terms) {
        terms.firstOrNull { !it.isFullYear && it.totalScore != null }
            ?: terms.firstOrNull { !it.isFullYear }
            ?: terms.firstOrNull()
    }
    LaunchedEffect(terms) {
        if (selectedTermKey.isBlank() && defaultTerm != null) {
            selectedTermKey = "${defaultTerm.yearId}:${defaultTerm.termId}"
        }
    }
    val selectedTerm = remember(terms, selectedTermKey, defaultTerm) {
        terms.firstOrNull { "${it.yearId}:${it.termId}" == selectedTermKey } ?: defaultTerm
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    val statusText = when {
        syncing -> "正在同步第二课堂数据…"
        slice.provenance.refreshState == RefreshState.NEVER -> "尚未同步第二课堂（复用统一身份认证）"
        slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ->
            "刷新失败，展示历史快照 · ${timeFmt.format(Date(slice.provenance.capturedAt))}"
        else -> "更新于 ${timeFmt.format(Date(slice.provenance.capturedAt))}"
    }

    GlassPageScaffold(
        title = "第二课堂",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Login,
                contentDescription = "统一身份登录",
                enabled = !syncing,
                onClick = { showLoginDialog = true }
            )
            if (syncing) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        scope.launch {
                            feedbackMsg = null
                            val res = service.refresh(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "第二课堂刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                val needWebVpn = err.contains("WebVPN", ignoreCase = true) ||
                                    err.contains("二次验证")
                                if (!needWebVpn && (err.contains("登录") || err.contains("过期") || err.contains("会话"))) {
                                    showLoginDialog = true
                                }
                            } else {
                                GlassToaster.show("第二课堂数据已刷新")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 4.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val bannerError = feedbackMsg ?: slice.lastError
            if (!bannerError.isNullOrBlank()) {
                val needWebVpnAuth = bannerError.contains("WebVPN", ignoreCase = true) ||
                    bannerError.contains("二次验证")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                        .border(0.5.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = bannerError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF453A)
                    )
                    if (needWebVpnAuth) {
                        SystemPrimaryButton(
                            text = "去完成 WebVPN 认证",
                            enabled = !syncing,
                            onClick = ::launchWebVpnAuth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SystemCompactSegmentedControl(
                options = listOf(
                    "五育成绩(${terms.count { !it.isFullYear }})",
                    "二课活动(${snapshot?.activities?.size ?: 0})"
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (snapshot == null) {
                    item {
                        SystemEmptyState(
                            title = if (syncing) "正在同步第二课堂" else "暂无第二课堂本地快照",
                            message = if (syncing) {
                                "正在通过统一身份认证拉取五育综测与二课活动数据…"
                            } else {
                                "校内网或校园 VPN 下点击右上角刷新或登录统一身份认证。"
                            },
                            icon = Icons.Outlined.School
                        )
                    }
                } else if (selectedTab == 0) {
                    item {
                        ScsSummaryHeroCard(
                            snapshot = snapshot,
                            selectedTerm = selectedTerm
                        )
                    }

                    if (terms.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                terms.forEach { t ->
                                    val key = "${t.yearId}:${t.termId}"
                                    val active = selectedTerm?.let { "${it.yearId}:${it.termId}" == key } == true
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (active) MaterialTheme.colorScheme.primary else glassSurfaceColor()
                                            )
                                            .border(
                                                0.5.dp,
                                                if (active) MaterialTheme.colorScheme.primary else glassBorderColor(),
                                                RoundedCornerShape(50)
                                            )
                                            .clickable { selectedTermKey = key }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        val label = buildString {
                                            append(t.yearName)
                                            append(' ')
                                            append(t.termName)
                                            t.totalScore?.let { append(" · ${formatScore(it)}") }
                                        }
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                            ),
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    selectedTerm?.let { term ->
                        item {
                            ScsFiveDimensionsCard(
                                term = term,
                                snapshot = snapshot
                            )
                        }

                        if (term.details.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${term.yearName} ${term.termName} 评分细则",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(glassSurfaceColor())
                                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                                            .clickable { showOnlyScored = !showOnlyScored }
                                            .padding(horizontal = 11.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = if (showOnlyScored) "仅看得分项" else "显示全部细则",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            items(term.details, key = { "${term.termId}:${it.typeId}" }) { category ->
                                ScsCategoryDetailCard(
                                    category = category,
                                    onlyScored = showOnlyScored
                                )
                            }
                        } else if (term.isFullYear) {
                            item {
                                SystemEmptyState(
                                    title = "年度汇总视图",
                                    message = "「全年」视图展示年度汇总总分；点击上方「第一学期 / 第二学期」胶囊可查看各维度评分明细。",
                                    icon = Icons.Outlined.School
                                )
                            }
                        } else {
                            item {
                                SystemEmptyState(
                                    title = "暂无细则数据",
                                    message = "该学期暂未生成五育评分细则数据。",
                                    icon = Icons.Outlined.School
                                )
                            }
                        }
                    }
                } else {
                    val activities = snapshot.activities
                    if (activities.isEmpty()) {
                        item {
                            SystemEmptyState(
                                title = "暂无二课活动",
                                message = if (slice.provenance.refreshState == RefreshState.EMPTY_CONFIRMED) {
                                    "当前第二课堂活动列表为空（学校暂未发布可报名活动）。"
                                } else {
                                    "当前暂无第二课堂活动记录。"
                                },
                                icon = Icons.Outlined.WorkspacePremium
                            )
                        }
                    } else {
                        items(activities, key = { it.id }) { act ->
                            ScsActivityCard(act)
                        }
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        ScsSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, passwordChars ->
                scope.launch {
                    feedbackMsg = null
                    val res = service.loginAndSync(username, passwordChars)
                    if (res.isSuccess) {
                        showLoginDialog = false
                        GlassToaster.show("登录成功并已同步第二课堂数据")
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "登录失败"
                        feedbackMsg = err
                        GlassToaster.show(err)
                        if (err.contains("WebVPN", ignoreCase = true) || err.contains("二次验证")) {
                            showLoginDialog = false
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ScsSummaryHeroCard(
    snapshot: ScsSnapshot,
    selectedTerm: ScsTermScore?
) {
    val shape = RoundedCornerShape(24.dp)
    val cumulativeTotal = snapshot.terms
        .filter { !it.isFullYear }
        .sumOf { it.totalScore ?: 0.0 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WorkspacePremium,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = buildString {
                            append(snapshot.studentName.ifBlank { "北化同学" })
                            if (snapshot.studentId.isNotBlank()) append(" · ${snapshot.studentId}")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val orgText = listOf(snapshot.collegeName, snapshot.className)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (orgText.isNotBlank()) {
                        Text(
                            text = orgText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatScore(selectedTerm?.totalScore ?: cumulativeTotal),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (selectedTerm?.totalScore != null) {
                        "${selectedTerm.yearName} ${selectedTerm.termName}"
                    } else {
                        "累计已评总分"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        snapshot.currentWindow?.let { win ->
            val badgeBg = if (win.isOpen) Color(0xFF34C759).copy(alpha = 0.14f) else glassSurfaceColor()
            val badgeFg = if (win.isOpen) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg)
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${win.yearName} ${win.termName} · ${if (win.isOpen) "学生自评开放中" else "自评未开放"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeFg
                )
                if (win.endTime.isNotBlank()) {
                    Text(
                        text = "截止 ${win.endTime.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg
                    )
                }
            }
        }
    }
}

@Composable
private fun ScsFiveDimensionsCard(
    term: ScsTermScore,
    snapshot: ScsSnapshot
) {
    val shape = RoundedCornerShape(24.dp)
    val dimMaxMap = remember(snapshot.dimensions, term.isFullYear) {
        val factor = if (term.isFullYear) 2.0 else 1.0
        val defaults = mapOf(1 to 180.0, 2 to 120.0, 3 to 100.0, 4 to 100.0, 5 to 100.0)
        val parsed = snapshot.dimensions.associate { it.typeId to it.totalMax }
        (1..5).associateWith { id -> (parsed[id] ?: defaults[id] ?: 100.0) * factor }
    }
    val rows = listOf(
        Triple("德 · 道德思想", term.moral, Color(0xFFFF6B6B)) to (dimMaxMap[1] ?: 180.0),
        Triple("智 · 学术科创", term.academic, Color(0xFF4D96FF)) to (dimMaxMap[2] ?: 120.0),
        Triple("体 · 体育健康", term.physical, Color(0xFF34C759)) to (dimMaxMap[3] ?: 100.0),
        Triple("美 · 美学人文", term.aesthetics, Color(0xFFAF52DE)) to (dimMaxMap[4] ?: 100.0),
        Triple("劳 · 劳动实践", term.labor, Color(0xFFFF9F0A)) to (dimMaxMap[5] ?: 100.0)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "五育分项得分（${term.yearName} ${term.termName}）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = term.totalScore?.let { "总分 ${formatScore(it)}" } ?: "待评定",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        rows.forEach { (triple, maxScore) ->
            val (label, value, tint) = triple
            val ratio = if (value != null && maxScore > 0.0) (value / maxScore).toFloat().coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (value != null) "${formatScore(value)} / ${formatScore(maxScore)}" else "-- / ${formatScore(maxScore)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (value != null) tint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassProgressBar(
                    progress = ratio,
                    tint = tint,
                    height = 6.dp
                )
            }
        }
    }
}

@Composable
private fun ScsCategoryDetailCard(
    category: ScsCategoryDetail,
    onlyScored: Boolean
) {
    var expanded by remember(category.typeId, onlyScored) {
        mutableStateOf(category.items.any { (it.score ?: 0.0) > 0.0 || it.mark.isNotBlank() })
    }
    val visibleItems = remember(category.items, onlyScored) {
        if (onlyScored) {
            val scored = category.items.filter { (it.score ?: 0.0) > 0.0 || it.mark.isNotBlank() || (it.laborHours ?: 0.0) > 0.0 }
            scored.ifEmpty { category.items }
        } else {
            category.items
        }
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title.ifBlank { "类别 ${category.typeId}" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val summary = buildString {
                    category.basicScore?.let { append("基础 ${formatScore(it)}") }
                    category.extScore?.let {
                        if (isNotEmpty()) append(" · ")
                        append("扩展 ${formatScore(it)}")
                    }
                    if (isEmpty()) append("共 ${category.items.size} 项细则")
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                category.totalScore?.let { total ->
                    Text(
                        text = buildString {
                            append(formatScore(total))
                            category.maxTotalScore?.let { append(" / ${formatScore(it)}") }
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemDivider(alpha = 0.35f)
                visibleItems.forEach { item ->
                    ScsEvalItemRow(item)
                }
            }
        }
    }
}

@Composable
private fun ScsEvalItemRow(item: ScsEvalItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "[${if (item.isBasic) "基础" else "扩展"}] ${item.shortDesc.ifBlank { item.description }}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            val scoreVal = item.score
            Text(
                text = if (scoreVal != null) "+${formatScore(scoreVal)} 分" else "未填",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if ((scoreVal ?: 0.0) > 0.0) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.mark.isNotBlank()) {
            Text(
                text = "申报说明：${item.mark}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item.laborHours?.takeIf { it > 0.0 }?.let { hrs ->
            Text(
                text = "劳动时长：${formatScore(hrs)} 小时",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF9F0A)
            )
        }
        if (item.teacherComments.isNotBlank()) {
            Text(
                text = "评语：${item.teacherComments}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScsActivityCard(act: ScsActivityItem) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = act.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (act.score.isNotBlank()) {
                Text(
                    text = "+${act.score} 分",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        val meta = listOf(act.categoryName, act.startTime, act.location, act.status)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScsSidLoginDialog(
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (username: String, passwordChars: CharArray) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val userManager = remember { UserManager.getInstance().apply { init(context) } }
    var username by remember { mutableStateOf(userManager.username.ifBlank { userManager.studentId.orEmpty() }) }
    var password by remember { mutableStateOf("") }

    fun submit() {
        if (syncing || username.isBlank() || password.isEmpty()) return
        val chars = password.toCharArray()
        password = ""
        onSubmit(username.trim(), chars)
    }

    SystemDialog(
        onDismissRequest = { if (!syncing) onDismiss() },
        title = {
            Text(
                text = "统一身份认证登录",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = if (syncing) "登录中…" else "登录并同步",
                enabled = !syncing && username.isNotBlank() && password.isNotEmpty(),
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                enabled = !syncing,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "登录 sidportal.buct.edu.cn 以获取第二课堂五育评定成绩（凭据仅用于本次换票，学号密码不落盘）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "请输入学号",
                leadingIcon = Icons.Outlined.Person,
                singleLine = true,
                enabled = !syncing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
            GlassTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "请输入统一身份认证密码",
                leadingIcon = Icons.Outlined.Lock,
                singleLine = true,
                enabled = !syncing,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatScore(value: Double): String {
    val rounded = value.toInt()
    return if (kotlin.math.abs(value - rounded) < 0.001) {
        rounded.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
