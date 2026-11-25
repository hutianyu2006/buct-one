package com.buct.course.ui.route

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.buct.course.CampusNetActivity
import com.buct.course.EcardActivity
import com.buct.course.ElectricityActivity
import com.buct.course.MailActivity
import com.buct.course.PortalActivity
import com.buct.course.ScsActivity
import com.buct.course.SjcxActivity
import com.buct.course.TheolCourseActivity
import com.buct.course.TyglActivity
import com.buct.course.portalBookDaysRemaining
import com.buct.course.manager.BottomNavPreferences
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.StartupPage
import com.buct.course.manager.UserManager
import com.buct.course.manager.UserProfileStore
import com.buct.course.ui.screen.EmptyClassroomDialog
import com.buct.course.ui.system.GlassCircleButton
import com.buct.course.ui.system.GlassStatChip
import com.buct.course.ui.system.GlassSubpage
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.widget.WidgetScheduleSource
import com.buct.course.widget.parseClockMinutes
import com.buct.one.data.model.CardSnapshot
import com.buct.one.data.model.CardTransaction
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.MailSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.model.SjcxSnapshot
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.ElectricitySnapshot
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.model.displayCampusNetTraffic
import com.buct.one.data.model.electricityTargetBaseRoomName
import com.buct.one.data.model.electricityTargetShortLabel
import com.buct.one.data.model.formatCampusNetYuan
import com.buct.one.data.model.formatElectricityPrimaryValue
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.model.isLowElectricityBalance
import com.buct.one.data.sync.CampusNetSyncService
import com.buct.one.data.sync.CardSyncService
import com.buct.one.data.sync.ElectricitySyncService
import com.buct.one.data.sync.MailSyncService
import com.buct.one.data.sync.PortalSyncService
import com.buct.one.data.sync.ScsSyncService
import com.buct.one.data.sync.SjcxSyncService
import com.buct.one.data.sync.TheolSyncService
import com.buct.one.data.sync.TyglSyncService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val GRID_COLS = 4
private const val MAX_UNDO_HISTORY = 30

/**
 * 2D 栅格工作台卡片规格：
 * - [MINI]：`0.5×0.5` 迷你小卡片（占 1 列 × 1 行，约 1/4 屏宽 × 76dp，支持并排四列或竖向成对对齐中卡片）
 * - [MEDIUM]：`1×1` 中卡片（占 2 列 × 2 行，半屏宽 × 162dp，可自由竖排成一列）
 * - [LARGE]：`2×1` 大卡片（占 4 列 × 2 行，整行宽 × 162dp）
 */
private enum class CardSize(
    val colSpan: Int,
    val rowSpan: Int,
    val label: String
) {
    MINI(1, 1, "0.5×0.5"),
    MEDIUM(2, 2, "1×1"),
    LARGE(4, 2, "2×1");

    companion object {
        fun fromSpanOrUnits(units: Int): CardSize = when {
            units >= 4 -> LARGE
            units >= 2 -> MEDIUM
            else -> MINI
        }
    }
}

private enum class DashboardModuleId(
    val id: String,
    val title: String,
    val shortTitle: String,
    val icon: ImageVector,
    val defaultSize: CardSize,
    val defaultCol: Int,
    val defaultRow: Int
) {
    THEOL(
        id = "theol",
        title = "作业与 DDL",
        shortTitle = "作业待办",
        icon = Icons.Outlined.Assignment,
        defaultSize = CardSize.LARGE,
        defaultCol = 0,
        defaultRow = 0
    ),
    SCS(
        id = "scs",
        title = "第二课堂 · 五育综测",
        shortTitle = "第二课堂",
        icon = Icons.Outlined.School,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 2
    ),
    ECARD(
        id = "ecard",
        title = "校园卡 · 余额与流水",
        shortTitle = "校园卡",
        icon = Icons.Outlined.CreditCard,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 2,
        defaultRow = 2
    ),
    SJCX(
        id = "sjcx",
        title = "实践教学 · 实验与讲座",
        shortTitle = "实践教学",
        icon = Icons.Outlined.Science,
        defaultSize = CardSize.LARGE,
        defaultCol = 0,
        defaultRow = 4
    ),
    CLASSROOM(
        id = "classroom",
        title = "查空闲教室",
        shortTitle = "空闲教室",
        icon = Icons.Outlined.MeetingRoom,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 6
    ),
    COURSE_SELECT(
        id = "course_select",
        title = "选课与队列抢课",
        shortTitle = "选课抢课",
        icon = Icons.Outlined.LibraryAdd,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 2,
        defaultRow = 6
    ),
    TYGL(
        id = "tygl",
        title = "体测与阳光跑",
        shortTitle = "体测体育",
        icon = Icons.Outlined.FitnessCenter,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 8
    ),
    MAIL(
        id = "mail",
        title = "校园邮箱",
        shortTitle = "校园邮箱",
        icon = Icons.Outlined.MailOutline,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 2,
        defaultRow = 8
    ),
    CAMPUS_NEWS(
        id = "campus_news",
        title = "校内资讯 · 通知与招聘",
        shortTitle = "校内资讯",
        icon = Icons.Outlined.Campaign,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 10
    ),
    LIBRARY(
        id = "library",
        title = "图书借阅 · 待还提醒",
        shortTitle = "图书借阅",
        icon = Icons.Outlined.MenuBook,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 2,
        defaultRow = 10
    ),
    ELECTRICITY(
        id = "electricity",
        title = "宿舍电费 · 实时余量",
        shortTitle = "宿舍电费",
        icon = Icons.Outlined.Bolt,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 12
    ),
    CAMPUS_NET(
        id = "campus_net",
        title = "校园网 · 网费与账单",
        shortTitle = "校园网费",
        icon = Icons.Outlined.Wifi,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 2,
        defaultRow = 12
    ),
    THEOL_COURSE(
        id = "theol_course",
        title = "北化在线 · 课程与资源",
        shortTitle = "北化在线",
        icon = Icons.Outlined.MenuBook,
        defaultSize = CardSize.MEDIUM,
        defaultCol = 0,
        defaultRow = 14
    );

    val supportedSizes: List<CardSize>
        get() = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)

    fun stepSize(delta: Int, current: CardSize): CardSize {
        val idx = supportedSizes.indexOf(current).let { if (it >= 0) it else 0 }
        return supportedSizes[(idx + delta).coerceIn(0, supportedSizes.lastIndex)]
    }

    fun nextCycleSize(current: CardSize): CardSize {
        val desc = supportedSizes.sortedByDescending { it.colSpan }
        val idx = desc.indexOf(current).let { if (it >= 0) it else 0 }
        return desc[(idx + 1) % desc.size]
    }

    companion object {
        fun fromId(id: String): DashboardModuleId? = entries.firstOrNull { it.id == id }
    }
}

private data class DashboardModuleConfig(
    val module: DashboardModuleId,
    val visible: Boolean = true,
    val size: CardSize = module.defaultSize,
    val col: Int = module.defaultCol,
    val row: Int = module.defaultRow
)

private data class GridPos(val col: Int, val row: Int)

private fun rectsOverlap(
    c1: Int,
    r1: Int,
    s1: CardSize,
    c2: Int,
    r2: Int,
    s2: CardSize
): Boolean {
    val noXOverlap = c1 + s1.colSpan <= c2 || c2 + s2.colSpan <= c1
    val noYOverlap = r1 + s1.rowSpan <= r2 || r2 + s2.rowSpan <= r1
    return !(noXOverlap || noYOverlap)
}

/**
 * 2D 坐标栅格碰撞解析器：
 * - 允许任意留白、单列竖排 1×1 等自由布局（绝不强行左挤填满行）；
 * - 若目标位置恰好只与单个卡片 B 重叠且 B 可无冲突交换回原位，则丝滑互换；
 * - 否则保持其他非冲突卡片原位不动，仅把发生碰撞的卡片按行向下顺推直至零重叠。
 */
private fun resolveGridPlacement(
    configs: List<DashboardModuleConfig>,
    activeModule: DashboardModuleId,
    rawCol: Int,
    rawRow: Int,
    targetSize: CardSize,
    prevCol: Int,
    prevRow: Int
): List<DashboardModuleConfig> {
    val activeIdx = configs.indexOfFirst { it.module == activeModule }
    if (activeIdx < 0) return configs

    val clampedCol = rawCol.coerceIn(0, GRID_COLS - targetSize.colSpan)
    val clampedRow = rawRow.coerceAtLeast(0)
    val mutable = configs.toMutableList()
    mutable[activeIdx] = mutable[activeIdx].copy(
        size = targetSize,
        col = clampedCol,
        row = clampedRow
    )

    val overlappingIndices = mutable.indices.filter { idx ->
        idx != activeIdx && mutable[idx].visible &&
            rectsOverlap(
                clampedCol,
                clampedRow,
                targetSize,
                mutable[idx].col,
                mutable[idx].row,
                mutable[idx].size
            )
    }

    // 单对单可逆互换：B 放回 A 原坐标后若不撞第三者，直接互换位置
    if (overlappingIndices.size == 1) {
        val bIdx = overlappingIndices.first()
        val b = mutable[bIdx]
        val swappedCol = prevCol.coerceIn(0, GRID_COLS - b.size.colSpan)
        val swappedRow = prevRow.coerceAtLeast(0)
        val swapHitsOther = mutable.indices.any { other ->
            other != bIdx && mutable[other].visible &&
                rectsOverlap(
                    swappedCol,
                    swappedRow,
                    b.size,
                    mutable[other].col,
                    mutable[other].row,
                    mutable[other].size
                )
        }
        if (!swapHitsOther) {
            mutable[bIdx] = b.copy(col = swappedCol, row = swappedRow)
            return mutable
        }
    }

    // 按当前 y/x 顺序推开冲突项，保持其余未碰撞卡片原坐标纹丝不动
    val pushQueue = ArrayDeque<Int>()
    pushQueue.addAll(overlappingIndices)
    var guard = 0
    while (pushQueue.isNotEmpty() && guard < 200) {
        guard++
        val idx = pushQueue.removeFirst()
        val item = mutable[idx]
        if (!item.visible) continue

        var candidateRow = item.row
        while (mutable.indices.any { other ->
                other != idx && mutable[other].visible &&
                    rectsOverlap(
                        item.col,
                        candidateRow,
                        item.size,
                        mutable[other].col,
                        mutable[other].row,
                        mutable[other].size
                    )
            }
        ) {
            candidateRow++
        }
        if (candidateRow != item.row) {
            mutable[idx] = item.copy(row = candidateRow)
        }
    }
    return mutable
}

private fun findNearestFreeSlot(
    configs: List<DashboardModuleConfig>,
    targetModule: DashboardModuleId,
    size: CardSize
): GridPos {
    val others = configs.filter { it.module != targetModule && it.visible }
    val maxSearchRow = (others.maxOfOrNull { it.row + it.size.rowSpan } ?: 0) + 4
    for (r in 0..maxSearchRow) {
        for (c in 0..(GRID_COLS - size.colSpan)) {
            val conflict = others.any { o -> rectsOverlap(c, r, size, o.col, o.row, o.size) }
            if (!conflict) return GridPos(c, r)
        }
    }
    return GridPos(0, maxSearchRow)
}

private object DashboardLayoutStore {
    private const val PREF_NAME = "buct_dashboard_grid_prefs"
    private const val KEY_LAYOUT_V3 = "layout_v3"

    fun defaultConfigs(): List<DashboardModuleConfig> =
        DashboardModuleId.entries.map { mod ->
            DashboardModuleConfig(
                module = mod,
                visible = true,
                size = mod.defaultSize,
                col = mod.defaultCol,
                row = mod.defaultRow
            )
        }

    fun load(context: Context): List<DashboardModuleConfig> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT_V3, null) ?: return defaultConfigs()
        return runCatching {
            val arr = JSONArray(raw)
            val loaded = ArrayList<DashboardModuleConfig>()
            val seen = HashSet<DashboardModuleId>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val mod = DashboardModuleId.fromId(obj.optString("id")) ?: continue
                if (!seen.add(mod)) continue
                val visible = obj.optBoolean("visible", true)
                val size = CardSize.fromSpanOrUnits(obj.optInt("units", mod.defaultSize.colSpan))
                val col = obj.optInt("col", mod.defaultCol).coerceIn(0, GRID_COLS - size.colSpan)
                val row = obj.optInt("row", mod.defaultRow).coerceAtLeast(0)
                loaded.add(DashboardModuleConfig(mod, visible, size, col, row))
            }
            for (mod in DashboardModuleId.entries) {
                if (mod !in seen) {
                    val slot = findNearestFreeSlot(loaded, mod, mod.defaultSize)
                    loaded.add(DashboardModuleConfig(mod, true, mod.defaultSize, slot.col, slot.row))
                }
            }
            loaded
        }.getOrElse { defaultConfigs() }
    }

    fun save(context: Context, configs: List<DashboardModuleConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            arr.put(
                JSONObject().apply {
                    put("id", cfg.module.id)
                    put("visible", cfg.visible)
                    put("units", cfg.size.colSpan)
                    put("col", cfg.col)
                    put("row", cfg.row)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT_V3, arr.toString())
            .apply()
    }
}

/**
 * 工作台首屏（五栏底栏居中主入口）。
 * 支持真 2D 坐标栅格自由摆位（可竖排单列 `1×1` 留空右侧）、编辑态顶部固定操作栏、零灰边与角标外置设计。
 */
@Composable
fun DashboardRoute(
    onSwitchPage: (StartupPage) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val userManager = remember { UserManager.getInstance().apply { init(context) } }
    val session by userManager.sessionState.state.collectAsState()

    var customNickname by remember {
        mutableStateOf(UserProfileStore.load(context, userManager.currentAccountKey).nickname)
    }
    DisposableEffect(lifecycleOwner, session.token) {
        fun refreshNick() {
            customNickname = UserProfileStore.load(context, userManager.currentAccountKey).nickname
        }
        refreshNick()
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNick()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val displayName = remember(session.token, session.expired, userManager.studentName, customNickname) {
        customNickname.trim().takeIf { it.isNotBlank() }
            ?: userManager.studentName?.trim()?.takeIf { it.isNotBlank() }
            ?: "北化同学"
    }

    val theolService = remember(context) { TheolSyncService.getInstance(context) }
    val theolSlice by theolService.theolState.collectAsState()
    val localTodos by theolService.todosState.collectAsState()
    val filterPrefs by theolService.filterPrefs.collectAsState()
    val syncing by theolService.syncing.collectAsState()

    val cardService = remember(context) { CardSyncService.getInstance(context) }
    val cardSlice by cardService.cardState.collectAsState()
    val cardHasSession by cardService.hasSession.collectAsState()
    val cardHasSavedPassword by cardService.hasSavedPassword.collectAsState()
    val cardCanAutoSync = cardHasSession || cardHasSavedPassword
    val cardSyncing by cardService.syncing.collectAsState()

    val sjcxService = remember(context) { SjcxSyncService.getInstance(context) }
    val sjcxSlice by sjcxService.sjcxState.collectAsState()
    val sjcxSyncing by sjcxService.syncing.collectAsState()

    val scsService = remember(context) { ScsSyncService.getInstance(context) }
    val scsSlice by scsService.scsState.collectAsState()
    val scsSyncing by scsService.syncing.collectAsState()

    val tyglService = remember(context) { TyglSyncService.getInstance(context) }
    val tyglSlice by tyglService.tyglState.collectAsState()
    val tyglSyncing by tyglService.syncing.collectAsState()
    val mailService = remember(context) { MailSyncService.getInstance(context) }
    val mailSlice by mailService.mailState.collectAsState()
    val mailSyncing by mailService.syncing.collectAsState()

    val portalService = remember(context) { PortalSyncService.getInstance(context) }
    val portalNewsSlice by portalService.newsState.collectAsState()
    val portalLibrarySlice by portalService.libraryState.collectAsState()
    val portalNewsSyncing by portalService.newsSyncing.collectAsState()
    val portalLibrarySyncing by portalService.librarySyncing.collectAsState()
    val electricityService = remember(context) { ElectricitySyncService.getInstance(context) }
    val electricitySlice by electricityService.electricityState.collectAsState()
    val electricitySyncing by electricityService.syncing.collectAsState()
    val electricityConsentAccepted by electricityService.consentAccepted.collectAsState()
    val campusNetService = remember(context) { CampusNetSyncService.getInstance(context) }
    val campusNetSlice by campusNetService.campusNetState.collectAsState()
    val campusNetSyncing by campusNetService.syncing.collectAsState()

    var showEmptyClassroomDialog by remember { mutableStateOf(false) }
    var emptyClassroomInitialCampus by remember { mutableIntStateOf(2) }
    var emptyClassroomPeriodOffset by remember { mutableIntStateOf(0) }
    var showCourseSelectSubpage by remember { mutableStateOf(false) }
    val classroomSlot = remember(showEmptyClassroomDialog) { resolveCurrentClassroomSlot(context) }
    val classroomDayLabel = listOf("一", "二", "三", "四", "五", "六", "日")
        .getOrElse(classroomSlot.day - 1) { "一" }

    var savedConfigs by remember { mutableStateOf(DashboardLayoutStore.load(context)) }
    var isEditingLayout by rememberSaveable { mutableStateOf(false) }
    var draftConfigs by remember { mutableStateOf(savedConfigs) }
    var undoStack by remember { mutableStateOf<List<List<DashboardModuleConfig>>>(emptyList()) }

    var draggingModule by remember { mutableStateOf<DashboardModuleId?>(null) }
    var dragOriginPx by remember { mutableStateOf(Offset.Zero) }
    var dragDeltaPx by remember { mutableStateOf(Offset.Zero) }
    var dragHoverSlot by remember { mutableStateOf<GridPos?>(null) }
    var lastMoveMs by remember { mutableLongStateOf(0L) }

    var resizingModule by remember { mutableStateOf<DashboardModuleId?>(null) }
    var resizePreviewScale by remember { mutableStateOf(1f) }

    fun pushUndoSnapshot(snapshot: List<DashboardModuleConfig>) {
        if (undoStack.lastOrNull() == snapshot) return
        undoStack = (undoStack + listOf(snapshot)).takeLast(MAX_UNDO_HISTORY)
    }

    fun enterEditMode() {
        draftConfigs = savedConfigs
        undoStack = emptyList()
        draggingModule = null
        dragHoverSlot = null
        resizingModule = null
        isEditingLayout = true
    }

    fun applyStepSize(module: DashboardModuleId, delta: Int) {
        val current = draftConfigs.firstOrNull { it.module == module } ?: return
        val nextSize = module.stepSize(delta, current.size)
        if (nextSize == current.size) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = resolveGridPlacement(
            configs = draftConfigs,
            activeModule = module,
            rawCol = current.col,
            rawRow = current.row,
            targetSize = nextSize,
            prevCol = current.col,
            prevRow = current.row
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun applyCycleSize(module: DashboardModuleId) {
        val current = draftConfigs.firstOrNull { it.module == module } ?: return
        val nextSize = module.nextCycleSize(current.size)
        if (nextSize == current.size) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = resolveGridPlacement(
            configs = draftConfigs,
            activeModule = module,
            rawCol = current.col,
            rawRow = current.row,
            targetSize = nextSize,
            prevCol = current.col,
            prevRow = current.row
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun hideModule(module: DashboardModuleId) {
        val idx = draftConfigs.indexOfFirst { it.module == module }
        if (idx < 0 || !draftConfigs[idx].visible) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = draftConfigs.toMutableList().apply {
            this[idx] = this[idx].copy(visible = false)
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun restoreModule(module: DashboardModuleId) {
        val idx = draftConfigs.indexOfFirst { it.module == module }
        if (idx < 0 || draftConfigs[idx].visible) return
        pushUndoSnapshot(draftConfigs)
        val item = draftConfigs[idx]
        val freeSlot = findNearestFreeSlot(draftConfigs, module, item.size)
        draftConfigs = draftConfigs.toMutableList().apply {
            this[idx] = item.copy(visible = true, col = freeSlot.col, row = freeSlot.row)
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun navigateToPageOrWarn(page: StartupPage) {
        if (BottomNavPreferences.isVisible(page)) {
            onSwitchPage(page)
        } else {
            GlassToaster.show("该页面已在底栏隐藏，可在「我的 → 底部导航栏」恢复")
        }
    }

    val activeConfigs = if (isEditingLayout) draftConfigs else savedConfigs
    val visibleConfigs = remember(activeConfigs) { activeConfigs.filter { it.visible } }
    val hiddenConfigs = remember(draftConfigs) { draftConfigs.filter { !it.visible } }

    val now = remember { Date() }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }
    val dateLine = remember(now) { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now) }

    val pendingHomeworks = remember(theolSlice, filterPrefs) {
        theolSlice.data?.homeworks?.filter { it.canSubmit && filterPrefs.isHomeworkVisible(it) }.orEmpty()
    }
    val pendingTests = remember(theolSlice, filterPrefs) {
        theolSlice.data?.tests?.filter { it.canStart && filterPrefs.isTestVisible(it) }.orEmpty()
    }
    val activeLocalTodos = remember(localTodos) {
        localTodos.filter { !it.deleted && !it.completed }
    }
    val bottomInset = LocalAppOverlayBottomInset.current
    val scrollState = rememberScrollState()
    val anySyncing = syncing || cardSyncing || sjcxSyncing || scsSyncing || tyglSyncing || mailSyncing || portalNewsSyncing || portalLibrarySyncing || electricitySyncing || campusNetSyncing

    val latestDraftState by rememberUpdatedState(draftConfigs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 编辑态顶栏始终固定在屏幕最上方（不随工作台内容滚出视口），撤回/重置只用图标确保保存完整展示
        if (isEditingLayout) {
            DashboardEditTopBar(
                canUndo = undoStack.isNotEmpty(),
                onUndo = {
                    val previous = undoStack.lastOrNull() ?: return@DashboardEditTopBar
                    undoStack = undoStack.dropLast(1)
                    draftConfigs = previous
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onReset = {
                    val defaults = DashboardLayoutStore.defaultConfigs()
                    if (draftConfigs != defaults) {
                        pushUndoSnapshot(draftConfigs)
                        draftConfigs = defaults
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        GlassToaster.show("已重置默认布局（点击保存生效）")
                    }
                },
                onCancel = {
                    draftConfigs = savedConfigs
                    undoStack = emptyList()
                    draggingModule = null
                    dragHoverSlot = null
                    resizingModule = null
                    isEditingLayout = false
                },
                onSave = {
                    savedConfigs = draftConfigs
                    DashboardLayoutStore.save(context, draftConfigs)
                    undoStack = emptyList()
                    draggingModule = null
                    dragHoverSlot = null
                    resizingModule = null
                    isEditingLayout = false
                    GlassToaster.show("工作台布局已保存")
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = if (isEditingLayout) 6.dp else 14.dp,
                    bottom = bottomInset + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isEditingLayout) {
                Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                    DashboardNormalHeader(
                        greeting = greeting,
                        displayName = displayName,
                        dateLine = dateLine,
                        anySyncing = anySyncing,
                        onRefreshAll = {
                            scope.launch { theolService.refreshIfSessionValid(userInitiated = true) }
                            scope.launch { sjcxService.refresh(userInitiated = true) }
                            if (scsService.canAutoSync()) {
                                scope.launch { scsService.refresh(userInitiated = true) }
                            }
                            if (tyglService.canAutoSync()) {
                                scope.launch { tyglService.refresh(userInitiated = true) }
                            }
                            if (mailService.canAutoSync()) {
                                scope.launch { mailService.refresh(userInitiated = true) }
                            }
                            if (portalService.canAutoSync()) {
                                scope.launch { portalService.refreshNews(userInitiated = true) }
                                scope.launch { portalService.refreshLibrary(userInitiated = true) }
                            }
                            if (cardService.canAutoSync()) {
                                scope.launch { cardService.refresh() }
                            }
                            if (electricityService.canAutoSync()) {
                                scope.launch { electricityService.refresh() }
                            }
                            scope.launch { campusNetService.refresh() }
                        }
                    )
                }

                // 顶部总待办统计胶囊条（只在正常态显示，进入自定义直接专注摆布卡片）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassStatChip(
                        value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingHomeworks.size.toString(),
                        label = "待交作业",
                        modifier = Modifier
                            .weight(1f)
                            .clickable { navigateToPageOrWarn(StartupPage.Homework) },
                        valueColor = if (pendingHomeworks.isNotEmpty()) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurface
                    )
                    GlassStatChip(
                        value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingTests.size.toString(),
                        label = "在线测验",
                        modifier = Modifier
                            .weight(1f)
                            .clickable { navigateToPageOrWarn(StartupPage.Homework) },
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                    GlassStatChip(
                        value = activeLocalTodos.size.toString(),
                        label = "本地待办",
                        modifier = Modifier
                            .weight(1f)
                            .clickable { navigateToPageOrWarn(StartupPage.Homework) }
                    )
                }
            }

            // 2D 坐标工作台栅格区（内留 6dp 外溢边距给角标 × / ⤢，避免遮挡任何卡片标题）
            val maxRowEnd = visibleConfigs.maxOfOrNull { it.row + it.size.rowSpan } ?: 0
            val gridRowsCount = if (isEditingLayout) maxOf(maxRowEnd + 2, 8) else maxRowEnd

            if (gridRowsCount == 0 && !isEditingLayout) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .height(104.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(glassSurfaceColor().copy(alpha = 0.6f))
                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "所有模块均已隐藏，点击下方「自定义工作台」恢复卡片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val gap = 10.dp
                val cellH = 76.dp
                val canvasHeight = cellH * gridRowsCount + gap * (gridRowsCount - 1).coerceAtLeast(0)
                val density = LocalDensity.current
                val gapPx = with(density) { gap.toPx() }
                val cellHPx = with(density) { cellH.toPx() }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .height(canvasHeight)
                ) {
                    val canvasW = maxWidth
                    val cellW = (canvasW - gap * (GRID_COLS - 1)) / GRID_COLS
                    val cellWPx = with(density) { cellW.toPx() }
                    val skeletonDashColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

                    if (isEditingLayout) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                            )
                            val radius = CornerRadius(18.dp.toPx())
                            for (r in 0 until gridRowsCount) {
                                for (c in 0 until GRID_COLS) {
                                    val x = (cellWPx + gapPx) * c
                                    val y = (cellHPx + gapPx) * r
                                    drawRoundRect(
                                        color = skeletonDashColor,
                                        topLeft = Offset(x, y),
                                        size = Size(cellWPx, cellHPx),
                                        cornerRadius = radius,
                                        style = stroke
                                    )
                                }
                            }
                        }
                    }

                    // 拖拽目标骨架高亮影（紧随落点，丝滑弹簧补位）
                    val activeDragId = draggingModule
                    val activeHover = dragHoverSlot
                    val activeDragCfg = if (activeDragId != null) {
                        draftConfigs.firstOrNull { it.module == activeDragId }
                    } else null

                    if (isEditingLayout && activeDragCfg != null && activeHover != null) {
                        val targetSize = activeDragCfg.size
                        val ghostXTarget = (cellW + gap) * activeHover.col
                        val ghostYTarget = (cellH + gap) * activeHover.row
                        val ghostW = cellW * targetSize.colSpan + gap * (targetSize.colSpan - 1)
                        val ghostH = cellH * targetSize.rowSpan + gap * (targetSize.rowSpan - 1)

                        val animGhostX by animateDpAsState(
                            targetValue = ghostXTarget,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "ghostX"
                        )
                        val animGhostY by animateDpAsState(
                            targetValue = ghostYTarget,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "ghostY"
                        )
                        val cornerDp = if (targetSize == CardSize.MINI) 20.dp else 26.dp
                        Box(
                            modifier = Modifier
                                .offset(x = animGhostX, y = animGhostY)
                                .width(ghostW)
                                .height(ghostH)
                                .clip(RoundedCornerShape(cornerDp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(cornerDp)
                                )
                        )
                    }

                    // 渲染每个可见卡片，按 (col, row, size) 坐标精确定位并提供弹簧动画
                    visibleConfigs.forEach { cfg ->
                        key(cfg.module) {
                            val modId = cfg.module
                            val isDragging = draggingModule == modId
                            val isResizing = resizingModule == modId

                            val slotX = (cellW + gap) * cfg.col
                            val slotY = (cellH + gap) * cfg.row
                            val slotW = cellW * cfg.size.colSpan + gap * (cfg.size.colSpan - 1)
                            val slotH = cellH * cfg.size.rowSpan + gap * (cfg.size.rowSpan - 1)

                            val draggedXDp = with(density) { (dragOriginPx.x + dragDeltaPx.x).toDp() }
                            val draggedYDp = with(density) { (dragOriginPx.y + dragDeltaPx.y).toDp() }

                            val targetX = if (isDragging) draggedXDp else slotX
                            val targetY = if (isDragging) draggedYDp else slotY

                            val animX by animateDpAsState(
                                targetValue = targetX,
                                animationSpec = if (isDragging) snap() else spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "cardX"
                            )
                            val animY by animateDpAsState(
                                targetValue = targetY,
                                animationSpec = if (isDragging) snap() else spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "cardY"
                            )
                            val animW by animateDpAsState(
                                targetValue = slotW,
                                animationSpec = spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "cardW"
                            )
                            val animH by animateDpAsState(
                                targetValue = slotH,
                                animationSpec = spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "cardH"
                            )

                            val stepX = cellWPx + gapPx
                            val stepY = cellHPx + gapPx

                            Editable2DCardSlot(
                                config = cfg,
                                isEditing = isEditingLayout,
                                isDragging = isDragging,
                                isResizing = isResizing,
                                resizePreviewScale = if (isResizing) resizePreviewScale else 1f,
                                modifier = Modifier
                                    .offset(x = animX, y = animY)
                                    .width(animW)
                                    .height(animH)
                                    .zIndex(
                                        when {
                                            isDragging -> 30f
                                            isResizing -> 20f
                                            else -> 1f
                                        }
                                    )
                                    .pointerInput(modId, isEditingLayout, cellWPx, cellHPx) {
                                        if (!isEditingLayout) return@pointerInput
                                        var startSnapshot = latestDraftState
                                        var activeOrigin = Offset.Zero
                                        var lastCol = cfg.col
                                        var lastRow = cfg.row
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                startSnapshot = latestDraftState
                                                val startCfg = startSnapshot.firstOrNull { it.module == modId } ?: return@detectDragGesturesAfterLongPress
                                                activeOrigin = Offset(startCfg.col * stepX, startCfg.row * stepY)
                                                dragOriginPx = activeOrigin
                                                dragDeltaPx = Offset.Zero
                                                dragHoverSlot = GridPos(startCfg.col, startCfg.row)
                                                lastCol = startCfg.col
                                                lastRow = startCfg.row
                                                draggingModule = modId
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragDeltaPx += dragAmount
                                                val currentList = latestDraftState
                                                val curCfg = currentList.firstOrNull { it.module == modId } ?: return@detectDragGesturesAfterLongPress
                                                val curX = activeOrigin.x + dragDeltaPx.x
                                                val curY = activeOrigin.y + dragDeltaPx.y
                                                val snappedCol = (curX / stepX)
                                                    .roundToInt()
                                                    .coerceIn(0, GRID_COLS - curCfg.size.colSpan)
                                                val snappedRow = (curY / stepY)
                                                    .roundToInt()
                                                    .coerceAtLeast(0)

                                                dragHoverSlot = GridPos(snappedCol, snappedRow)
                                                val nowMs = SystemClock.uptimeMillis()
                                                if ((snappedCol != lastCol || snappedRow != lastRow) &&
                                                    nowMs - lastMoveMs >= 100L
                                                ) {
                                                    lastMoveMs = nowMs
                                                    lastCol = snappedCol
                                                    lastRow = snappedRow
                                                    draftConfigs = resolveGridPlacement(
                                                        configs = currentList,
                                                        activeModule = modId,
                                                        rawCol = snappedCol,
                                                        rawRow = snappedRow,
                                                        targetSize = curCfg.size,
                                                        prevCol = curCfg.col,
                                                        prevRow = curCfg.row
                                                    )
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                if (latestDraftState != startSnapshot) {
                                                    pushUndoSnapshot(startSnapshot)
                                                }
                                                draggingModule = null
                                                dragHoverSlot = null
                                            },
                                            onDragCancel = {
                                                draggingModule = null
                                                dragHoverSlot = null
                                            }
                                        )
                                    },
                                onHideModule = { hideModule(modId) },
                                onStepSize = { delta -> applyStepSize(modId, delta) },
                                onCycleSize = { applyCycleSize(modId) },
                                onResizePreview = { scale ->
                                    resizingModule = modId
                                    resizePreviewScale = scale
                                },
                                onResizePreviewEnd = {
                                    if (resizingModule == modId) {
                                        resizingModule = null
                                        resizePreviewScale = 1f
                                    }
                                }
                            ) {
                                RenderModuleCard(
                                    config = cfg,
                                    slotHeightDp = slotH,
                                    isEditing = isEditingLayout,
                                    context = context,
                                    theolSlice = theolSlice,
                                    pendingHomeworks = pendingHomeworks,
                                    pendingTests = pendingTests,
                                    activeLocalTodos = activeLocalTodos,
                                    cardSlice = cardSlice,
                                    cardHasSession = cardCanAutoSync,
                                    sjcxSlice = sjcxSlice,
                                    scsSlice = scsSlice,
                                    tyglSlice = tyglSlice,
                                    mailSlice = mailSlice,
                                    portalNewsSlice = portalNewsSlice,
                                    portalLibrarySlice = portalLibrarySlice,
                                    electricitySlice = electricitySlice,
                                    electricityConsentAccepted = electricityConsentAccepted,
                                    campusNetSlice = campusNetSlice,
                                    classroomSlot = classroomSlot,
                                    classroomDayLabel = classroomDayLabel,
                                    onNavigateTab = ::navigateToPageOrWarn,
                                    onOpenClassroom = { campusId, periodOffset ->
                                        com.buct.course.EmptyClassroomActivity.launch(
                                            context = context,
                                            semester = classroomSlot.semester,
                                            week = classroomSlot.week,
                                            day = classroomSlot.day,
                                            period = (classroomSlot.period + periodOffset).coerceIn(1, 12),
                                            allowTimeSwitch = true,
                                            initialCampusId = campusId
                                        )
                                    },
                                    onOpenCourseSelect = {
                                        if (BottomNavPreferences.isVisible(StartupPage.Courses)) {
                                            onSwitchPage(StartupPage.Courses)
                                        } else {
                                            com.buct.course.CourseSelectionActivity.launch(context)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 编辑态底部的轻量添加模块胶囊区
            AnimatedVisibility(visible = isEditingLayout && hiddenConfigs.isNotEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                    HiddenModulesDock(
                        hiddenConfigs = hiddenConfigs,
                        onRestoreModule = ::restoreModule
                    )
                }
            }

            if (!isEditingLayout) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                            .clickable { enterEditMode() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DashboardCustomize,
                            contentDescription = "自定义工作台",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "自定义工作台",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    if (showEmptyClassroomDialog) {
        EmptyClassroomDialog(
            semester = classroomSlot.semester,
            week = classroomSlot.week,
            day = classroomSlot.day,
            period = (classroomSlot.period + emptyClassroomPeriodOffset).coerceIn(1, 12),
            allowTimeSwitch = true,
            initialCampusId = emptyClassroomInitialCampus,
            onDismiss = { showEmptyClassroomDialog = false }
        )
    }

    if (showCourseSelectSubpage) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.buct.course.analytics.UmamiTracker.trackPageVisit(context, "/module/course_select", "选课抢课")
        }
        GlassSubpage(onDismiss = { showCourseSelectSubpage = false }) { close ->
            CourseSectionRoute(onBack = close)
        }
    }
}

@Composable
private fun DashboardNormalHeader(
    greeting: String,
    displayName: String,
    dateLine: String,
    anySyncing: Boolean,
    onRefreshAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "$greeting，$displayName",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$dateLine · BUCT One 工作台",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        GlassCircleButton(
            onClick = onRefreshAll,
            icon = Icons.Outlined.Refresh,
            contentDescription = "刷新快照",
            enabled = !anySyncing,
            size = 42.dp,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 固定在工作台编辑态最上方的操作栏：
 * 撤回与重置仅保留紧凑图标；取消与保存完整展示文字（softWrap = false，绝不截断）。
 */
@Composable
private fun DashboardEditTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val barShape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(barShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), barShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "自定义工作台",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 纯图标：撤回
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (canUndo) glassSurfaceColor() else glassSurfaceColor().copy(alpha = 0.4f)
                        )
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(enabled = canUndo, onClick = onUndo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "撤回",
                        tint = if (canUndo) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                // 纯图标：重置
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "重置默认布局",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // 文字胶囊：取消
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(16.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                // 主色胶囊：保存
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onSave)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        Text(
            text = "长按卡片自由拖放 · 拖或点右上角箭头调尺寸（可单列竖排 1×1）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 2D 栅格悬浮卡片外框：
 * - 零灰边：禁用 Android ambient shadowElevation，改用轻盈的半透/主色边线区分拖拽与静态编辑态；
 * - 角标外置：关闭与缩放徽章向外偏移 6dp 贴合边角，绝不遮挡任何卡片标题文字。
 */
@Composable
private fun Editable2DCardSlot(
    config: DashboardModuleConfig,
    isEditing: Boolean,
    isDragging: Boolean,
    isResizing: Boolean,
    resizePreviewScale: Float,
    modifier: Modifier = Modifier,
    onHideModule: () -> Unit,
    onStepSize: (Int) -> Unit,
    onCycleSize: () -> Unit,
    onResizePreview: (Float) -> Unit,
    onResizePreviewEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val dragThresholdPx = remember(density) { with(density) { 34.dp.toPx() } }

    val targetScale = when {
        isDragging -> 1.04f
        isResizing -> resizePreviewScale
        else -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardScale"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.primary
            isEditing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else -> Color.Transparent
        },
        label = "cardBorder"
    )

    val cornerDp = if (config.size == CardSize.MINI) 20.dp else 26.dp
    val cornerShape = RoundedCornerShape(cornerDp)
    val latestOnStep by rememberUpdatedState(onStepSize)
    val latestOnCycle by rememberUpdatedState(onCycleSize)
    val latestOnPreview by rememberUpdatedState(onResizePreview)
    val latestOnPreviewEnd by rememberUpdatedState(onResizePreviewEnd)

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
            clip = false
        }
    ) {
        content()

        if (isEditing) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = if (isDragging) 2.dp else 1.dp,
                        color = animatedBorderColor,
                        shape = cornerShape
                    )
            )

            // 左上角隐藏徽章：外移 6dp，绝不遮挡标题首字
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .border(0.6.dp, glassBorderColor(), CircleShape)
                    .clickable(onClick = onHideModule),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "隐藏${config.module.shortTitle}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(13.dp)
                )
            }

            // 右上角双向斜箭头：外移 6dp，支持拖动实时调尺寸与轻点循环尺寸
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .pointerInput(config.module, isEditing) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var totalDelta = Offset.Zero
                            var stepAccum = Offset.Zero
                            var didDragResize = false
                            var released = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    released = true
                                    break
                                }
                                val d = change.positionChange()
                                if (d != Offset.Zero) {
                                    change.consume()
                                    totalDelta += d
                                    stepAccum += d
                                    val score = stepAccum.x - stepAccum.y * 0.45f
                                    latestOnPreview((1f + score / (dragThresholdPx * 2.8f)).coerceIn(0.88f, 1.12f))
                                    if (score >= dragThresholdPx) {
                                        latestOnStep(+1)
                                        stepAccum = Offset.Zero
                                        didDragResize = true
                                    } else if (score <= -dragThresholdPx) {
                                        latestOnStep(-1)
                                        stepAccum = Offset.Zero
                                        didDragResize = true
                                    }
                                }
                            }
                            latestOnPreviewEnd()
                            if (released && !didDragResize && hypot(totalDelta.x, totalDelta.y) <= viewConfig.touchSlop * 1.5f) {
                                latestOnCycle()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInFull,
                    contentDescription = "拖动或点击调整尺寸",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HiddenModulesDock(
    hiddenConfigs: List<DashboardModuleConfig>,
    onRestoreModule: (DashboardModuleId) -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "点击加回工作台",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hiddenConfigs.forEach { cfg ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .clickable { onRestoreModule(cfg.module) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = cfg.module.shortTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun pickDisplayScsTerm(terms: List<ScsTermScore>): ScsTermScore? =
    terms.firstOrNull {
        it.totalScore != null || it.moral != null || it.academic != null ||
            it.physical != null || it.aesthetics != null || it.labor != null
    } ?: terms.firstOrNull()

private fun isCardRecharge(tx: CardTransaction): Boolean =
    tx.amountFen > 0L && (
        tx.txName.contains("充") || tx.txName.contains("补") || tx.txName.contains("退") ||
            tx.txCode in setOf("2239", "2240", "2241")
        )

private fun shortCardDateTime(raw: String): String {
    val clean = raw.trim().replaceFirst(Regex("^20\\d{2}-"), "")
    return if (clean.length > 11) clean.take(11) else clean
}

private fun formatCompactScore(v: Double?): String =
    v?.let {
        if (kotlin.math.abs(it - it.toInt()) < 0.05) it.toInt().toString()
        else String.format(Locale.ROOT, "%.1f", it)
    } ?: "--"

@Composable
private fun RenderModuleCard(
    config: DashboardModuleConfig,
    slotHeightDp: Dp,
    isEditing: Boolean,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    tyglSlice: DomainSlice<TyglSnapshot>,
    mailSlice: DomainSlice<MailSnapshot>,
    portalNewsSlice: DomainSlice<PortalNewsSnapshot>,
    portalLibrarySlice: DomainSlice<PortalLibrarySnapshot>,
    electricitySlice: DomainSlice<ElectricitySnapshot>,
    electricityConsentAccepted: Boolean,
    campusNetSlice: DomainSlice<CampusNetSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: (campusId: Int, periodOffset: Int) -> Unit,
    onOpenCourseSelect: () -> Unit
) {
    when (config.size) {
        CardSize.LARGE -> RenderLargeModuleCard(
            module = config.module,
            slotHeightDp = slotHeightDp,
            isEditing = isEditing,
            context = context,
            theolSlice = theolSlice,
            pendingHomeworks = pendingHomeworks,
            pendingTests = pendingTests,
            activeLocalTodos = activeLocalTodos,
            cardSlice = cardSlice,
            cardHasSession = cardHasSession,
            sjcxSlice = sjcxSlice,
            scsSlice = scsSlice,
            tyglSlice = tyglSlice,
            mailSlice = mailSlice,
            portalNewsSlice = portalNewsSlice,
            portalLibrarySlice = portalLibrarySlice,
            electricitySlice = electricitySlice,
            electricityConsentAccepted = electricityConsentAccepted,
            campusNetSlice = campusNetSlice,
            classroomSlot = classroomSlot,
            classroomDayLabel = classroomDayLabel,
            onNavigateTab = onNavigateTab,
            onOpenClassroom = onOpenClassroom,
            onOpenCourseSelect = onOpenCourseSelect
        )

        CardSize.MEDIUM -> RenderMediumModuleCard(
            module = config.module,
            slotHeightDp = slotHeightDp,
            isEditing = isEditing,
            context = context,
            theolSlice = theolSlice,
            pendingHomeworks = pendingHomeworks,
            pendingTests = pendingTests,
            activeLocalTodos = activeLocalTodos,
            cardSlice = cardSlice,
            cardHasSession = cardHasSession,
            sjcxSlice = sjcxSlice,
            scsSlice = scsSlice,
            tyglSlice = tyglSlice,
            mailSlice = mailSlice,
            portalNewsSlice = portalNewsSlice,
            portalLibrarySlice = portalLibrarySlice,
            electricitySlice = electricitySlice,
            electricityConsentAccepted = electricityConsentAccepted,
            campusNetSlice = campusNetSlice,
            classroomSlot = classroomSlot,
            classroomDayLabel = classroomDayLabel,
            onNavigateTab = onNavigateTab,
            onOpenClassroom = onOpenClassroom,
            onOpenCourseSelect = onOpenCourseSelect
        )

        CardSize.MINI -> {
            val summary = buildCompactSummary(
                module = config.module,
                context = context,
                theolSlice = theolSlice,
                pendingHomeworks = pendingHomeworks,
                pendingTests = pendingTests,
                activeLocalTodos = activeLocalTodos,
                cardSlice = cardSlice,
                sjcxSlice = sjcxSlice,
                scsSlice = scsSlice,
                tyglSlice = tyglSlice,
                mailSlice = mailSlice,
                portalNewsSlice = portalNewsSlice,
                portalLibrarySlice = portalLibrarySlice,
                electricitySlice = electricitySlice,
                electricityConsentAccepted = electricityConsentAccepted,
                campusNetSlice = campusNetSlice,
                classroomSlot = classroomSlot,
                classroomDayLabel = classroomDayLabel,
                onNavigateTab = onNavigateTab,
                onOpenClassroom = { onOpenClassroom(2, 0) },
                onOpenCourseSelect = onOpenCourseSelect
            )
            CompactMiniCard(
                icon = config.module.icon,
                title = config.module.shortTitle,
                miniValue = summary.miniValue,
                onClick = summary.onClick,
                heightDp = slotHeightDp,
                isEditing = isEditing
            )
        }
    }
}

private data class ModuleCompactSummary(
    val title: String,
    val primaryValue: String,
    val subLine: String,
    val miniValue: String,
    val onClick: () -> Unit
)

private fun buildCompactSummary(
    module: DashboardModuleId,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    tyglSlice: DomainSlice<TyglSnapshot>,
    mailSlice: DomainSlice<MailSnapshot>,
    portalNewsSlice: DomainSlice<PortalNewsSnapshot>,
    portalLibrarySlice: DomainSlice<PortalLibrarySnapshot>,
    electricitySlice: DomainSlice<ElectricitySnapshot>,
    electricityConsentAccepted: Boolean,
    campusNetSlice: DomainSlice<CampusNetSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenCourseSelect: () -> Unit
): ModuleCompactSummary = when (module) {
    DashboardModuleId.THEOL -> {
        val never = theolSlice.provenance.refreshState == RefreshState.NEVER
        ModuleCompactSummary(
            title = "北化在线",
            primaryValue = if (never) "待同步" else "${pendingHomeworks.size} 项待交",
            subLine = "测验 ${pendingTests.size} · 待办 ${activeLocalTodos.size}",
            miniValue = if (never) "待同步" else "${pendingHomeworks.size}待交",
            onClick = { onNavigateTab(StartupPage.Homework) }
        )
    }

    DashboardModuleId.SCS -> {
        val scsData = scsSlice.data
        val displayTerm = pickDisplayScsTerm(scsData?.terms.orEmpty())
        val valText = when {
            displayTerm?.totalScore != null -> String.format(Locale.ROOT, "%.1f 分", displayTerm.totalScore)
            scsData != null -> "已登录"
            else -> "五育综测"
        }
        val miniText = displayTerm?.totalScore?.let { String.format(Locale.ROOT, "%.1f分", it) }
            ?: if (scsData?.currentWindow?.isOpen == true) "自评中" else "五育"
        val sub = when {
            displayTerm != null -> "${displayTerm.termName.ifBlank { displayTerm.yearName }} · 活动 ${scsData?.activities?.size ?: 0}"
            scsData?.currentWindow?.isOpen == true -> "自评已开放"
            else -> "德智体美劳与二课活动"
        }
        ModuleCompactSummary("第二课堂", valText, sub, miniText) {
            ScsActivity.launch(context, initialTab = 0)
        }
    }

    DashboardModuleId.ECARD -> {
        val cardData = cardSlice.data
        val valText = if (cardData != null) "¥ ${formatFenToYuan(cardData.account.balanceFen)}" else "点击登录"
        val sub = cardData?.transactions?.firstOrNull()?.let { "${it.title} ¥${formatFenToYuan(it.amountFen)}" }
            ?: if (cardData != null) "${cardData.account.statusLabel} · ${cardData.account.maskedId}" else "独立查询余额与流水"
        val mini = if (cardData != null) "¥${formatFenToYuan(cardData.account.balanceFen)}" else "余额"
        ModuleCompactSummary("校园卡", valText, sub, mini) {
            context.startActivity(Intent(context, EcardActivity::class.java))
        }
    }

    DashboardModuleId.SJCX -> {
        val sjcxData = sjcxSlice.data
        val availLectures = sjcxData?.openLectures?.count { !it.isFull } ?: 0
        val valText = when {
            sjcxData == null -> "实验与讲座"
            availLectures > 0 -> "$availLectures 场可抢"
            sjcxData.openLectures.isNotEmpty() -> "${sjcxData.openLectures.size} 场已满"
            else -> "${sjcxData.schedules.size} 节实验"
        }
        val sub = if (sjcxData != null) {
            "排课 ${sjcxData.schedules.size} · ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)}学分"
        } else {
            "实验课表 · 抢讲座"
        }
        val mini = when {
            sjcxData == null -> "实验"
            availLectures > 0 -> "${availLectures}可抢"
            sjcxData.openLectures.isNotEmpty() -> "${sjcxData.openLectures.size}活动"
            else -> "${sjcxData.schedules.size}节"
        }
        ModuleCompactSummary("实践教学", valText, sub, mini) {
            SjcxActivity.launch(context, initialTab = 0)
        }
    }

    DashboardModuleId.CLASSROOM -> ModuleCompactSummary(
        title = "空闲教室",
        primaryValue = "第 ${classroomSlot.week} 周",
        subLine = "周${classroomDayLabel} · 第 ${classroomSlot.period} 节空教室",
        miniValue = "第${classroomSlot.period}节",
        onClick = onOpenClassroom
    )

    DashboardModuleId.COURSE_SELECT -> ModuleCompactSummary(
        title = "选课抢课",
        primaryValue = "全校选课",
        subLine = "正方可选课程 · 队列自动抢课",
        miniValue = "抢课",
        onClick = onOpenCourseSelect
    )

    DashboardModuleId.TYGL -> {
        val tyglData = tyglSlice.data
        val latestFitness = tyglData?.fitnessRecords?.firstOrNull()
        val valText = latestFitness?.totalScoreText?.takeIf { it.isNotBlank() }?.let { "$it 分" } ?: "体测成绩"
        val miniText = latestFitness?.totalScoreText?.takeIf { it.isNotBlank() }?.let { "${it}分" } ?: "体测"
        val sub = latestFitness?.let {
            "${it.testYear.ifBlank { it.yearLabel }} · ${it.level.ifBlank { "已测" }}"
        } ?: "体测 · 阳光跑 · 算分"
        ModuleCompactSummary("体测与体育", valText, sub, miniText) {
            TyglActivity.launch(context, initialTab = 0)
        }
    }

    DashboardModuleId.MAIL -> {
        val mailData = mailSlice.data
        val unread = mailData?.unreadTotal ?: 0
        val valText = when {
            mailData == null -> "校园邮箱"
            unread > 0 -> "$unread 封未读"
            else -> "已全部已读"
        }
        val miniText = when {
            mailData == null -> "邮箱"
            unread > 0 -> "${unread}未读"
            else -> "已读"
        }
        val latestSubject = mailData?.inboxMessages?.firstOrNull()?.subject?.takeIf { it.isNotBlank() }
        val sub = when {
            latestSubject != null -> latestSubject
            mailData != null -> "收件箱 ${mailData.inboxTotal} 封"
            else -> "mail.buct.edu.cn"
        }
        ModuleCompactSummary("校园邮箱", valText, sub, miniText) {
            MailActivity.launch(context, initialTab = 0)
        }
    }

    DashboardModuleId.CAMPUS_NEWS -> {
        val newsData = portalNewsSlice.data
        val noticePage = newsData?.columns?.get(PortalNewsColumn.NOTICE.columnId)
        val latest = noticePage?.articles?.firstOrNull()
        val valText = when {
            newsData == null -> "点击进入"
            noticePage != null && noticePage.totalCount > 0 -> "${noticePage.totalCount} 条通知"
            else -> "校内资讯"
        }
        val miniText = when {
            newsData == null -> "资讯"
            latest != null -> "最新"
            else -> "资讯"
        }
        val sub = latest?.title?.ifBlank { null } ?: "通知 · 活动 · 招聘 · 新闻"
        ModuleCompactSummary("校内资讯", valText, sub, miniText) {
            PortalActivity.launchNews(context)
        }
    }

    DashboardModuleId.LIBRARY -> {
        val libState = portalLibrarySlice.provenance.refreshState
        val libData = portalLibrarySlice.data
        val isEmptyConfirmed = libState == RefreshState.EMPTY_CONFIRMED ||
            (libData != null && libData.totalCount == 0 && libData.books.isEmpty())
        val urgent = resolveLibraryUrgentInfo(libData)
        val valText = when {
            libState == RefreshState.NEVER && libData == null -> "点击同步"
            isEmptyConfirmed -> "0 本待还"
            libData != null -> "${libData.totalCount} 本待还"
            else -> "图书借阅"
        }
        val miniText = when {
            libState == RefreshState.NEVER && libData == null -> "借阅"
            isEmptyConfirmed -> "0本"
            libData != null -> "${libData.totalCount}本"
            else -> "借阅"
        }
        val sub = when {
            libState == RefreshState.NEVER && libData == null -> "i北化图书馆在借"
            isEmptyConfirmed -> "当前无在借图书"
            urgent != null -> urgent.shortStatus
            else -> "点击查看应还日期"
        }
        ModuleCompactSummary("图书借阅", valText, sub, miniText) {
            PortalActivity.launchLibrary(context)
        }
    }

    DashboardModuleId.ELECTRICITY -> {
        val snap = electricitySlice.data
        val targets = snap?.targets.orEmpty()
        val firstReading = snap?.readings?.firstOrNull()
        val valText = when {
            !electricityConsentAccepted -> "需授权"
            targets.isEmpty() -> "未绑定"
            firstReading != null -> formatElectricityPrimaryValue(firstReading)
            else -> "待查询"
        }
        val firstBalance = firstReading?.balanceValue
        val miniText = when {
            !electricityConsentAccepted -> "需授权"
            targets.isEmpty() -> "未绑定"
            firstBalance != null -> {
                val num = if (firstBalance % 1.0 == 0.0) {
                    firstBalance.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", firstBalance)
                }
                "$num${firstReading?.unit.orEmpty()}"
            }
            firstReading != null -> firstReading.balanceText
            else -> "电费"
        }
        val sub = when {
            !electricityConsentAccepted -> "需共享学号防滥用"
            targets.isEmpty() -> "点击绑定宿舍房间"
            targets.size > 1 -> "${electricityTargetShortLabel(targets.first())} · 双表"
            else -> electricityTargetShortLabel(targets.first())
        }
        ModuleCompactSummary("宿舍电费", valText, sub, miniText) {
            ElectricityActivity.launch(context)
        }
    }

    DashboardModuleId.CAMPUS_NET -> {
        val snap = campusNetSlice.data
        val valText = when {
            snap == null -> "待查询"
            else -> "消费 ¥${formatCampusNetYuan(snap.userCharge)}"
        }
        val miniText = when {
            snap == null -> "网费"
            else -> "¥${formatCampusNetYuan(snap.userCharge)}"
        }
        val sub = when {
            snap == null -> "校园网网费与流量"
            else -> "余 ¥${formatCampusNetYuan(snap.userBalance)} · 钱包 ¥${formatCampusNetYuan(snap.walletBalance)}"
        }
        ModuleCompactSummary("校园网费", valText, sub, miniText) {
            CampusNetActivity.launch(context)
        }
    }

    DashboardModuleId.THEOL_COURSE -> {
        val courseCount = theolSlice.data?.courses?.size ?: 0
        val valText = if (courseCount > 0) "$courseCount 门课程" else "待同步"
        val sub = "单元学习 · 课程资源"
        ModuleCompactSummary("北化在线", valText, sub, if (courseCount > 0) "${courseCount}门课" else "北化在线") {
            TheolCourseActivity.launch(context)
        }
    }
}

private data class LibraryUrgentInfo(
    val bookTitle: String,
    val dueDate: String,
    val daysRemaining: Int?,
    val shortStatus: String,
    val accentColor: Color
)

private fun resolveLibraryUrgentInfo(data: PortalLibrarySnapshot?): LibraryUrgentInfo? {
    val books = data?.books.orEmpty()
    if (books.isEmpty()) return null
    val soonest = books.minByOrNull { portalBookDaysRemaining(it.dueDate) ?: Int.MAX_VALUE } ?: return null
    val days = portalBookDaysRemaining(soonest.dueDate)
    val (status, color) = when {
        days == null -> "应还 ${soonest.dueDate.ifBlank { "--" }}" to Color(0xFF34C759)
        days < 0 -> "已逾期 ${-days} 天" to Color(0xFFFF453A)
        days == 0 -> "今天到期" to Color(0xFFFF9F0A)
        days <= 7 -> "剩 $days 天到期" to Color(0xFFFF9F0A)
        else -> "应还 ${soonest.dueDate}" to Color(0xFF34C759)
    }
    return LibraryUrgentInfo(
        bookTitle = soonest.title,
        dueDate = soonest.dueDate,
        daysRemaining = days,
        shortStatus = status,
        accentColor = color
    )
}

@Composable
private fun MediumGlassCardFrame(
    title: String,
    icon: ImageVector,
    heightDp: Dp,
    isEditing: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(enabled = !isEditing, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content
        )
    }
}

@Composable
private fun RenderMediumModuleCard(
    module: DashboardModuleId,
    slotHeightDp: Dp,
    isEditing: Boolean,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    tyglSlice: DomainSlice<TyglSnapshot>,
    mailSlice: DomainSlice<MailSnapshot>,
    portalNewsSlice: DomainSlice<PortalNewsSnapshot>,
    portalLibrarySlice: DomainSlice<PortalLibrarySnapshot>,
    electricitySlice: DomainSlice<ElectricitySnapshot>,
    electricityConsentAccepted: Boolean,
    campusNetSlice: DomainSlice<CampusNetSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: (campusId: Int, periodOffset: Int) -> Unit,
    onOpenCourseSelect: () -> Unit
) {
    when (module) {
        DashboardModuleId.ECARD -> {
            val cardData = cardSlice.data
            MediumGlassCardFrame(
                title = "校园卡",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { context.startActivity(Intent(context, EcardActivity::class.java)) }
            ) {
                if (cardData == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "点击登录",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "ecard.buct.edu.cn",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    MediumBottomActionPill(text = "余额与消费流水 ›")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "¥ ${formatFenToYuan(cardData.account.balanceFen)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${if (cardHasSession) cardData.account.statusLabel else "待续期"} · ${cardData.account.maskedId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cardHasSession) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFF9500),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SystemDivider(alpha = 0.28f)
                        val txs = cardData.transactions.take(2)
                        if (txs.isEmpty()) {
                            Text(
                                text = "近期暂无流水记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        } else {
                            txs.forEach { tx ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tx.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "¥${formatFenToYuan(tx.amountFen)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isCardRecharge(tx)) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        DashboardModuleId.SJCX -> {
            val sjcxData = sjcxSlice.data
            val availCount = sjcxData?.openLectures?.count { !it.isFull } ?: 0
            val openCount = sjcxData?.openLectures?.size ?: 0
            MediumGlassCardFrame(
                title = "实践教学",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { SjcxActivity.launch(context, initialTab = 0) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            sjcxData == null -> "实验与讲座"
                            availCount > 0 -> "$availCount 场可抢"
                            openCount > 0 -> "$openCount 场开放"
                            else -> "${sjcxData.schedules.size} 节实验"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (sjcxData != null) {
                            "排课 ${sjcxData.schedules.size} · ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)}学分"
                        } else {
                            "实验课表 · 创新学分"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .clickable(enabled = !isEditing) { SjcxActivity.launch(context, initialTab = 1) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (openCount > 0) "直达抢讲座 ($openCount) ›" else "查看讲座与报名 ›",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DashboardModuleId.SCS -> {
            val scsData = scsSlice.data
            val displayTerm = pickDisplayScsTerm(scsData?.terms.orEmpty())
            MediumGlassCardFrame(
                title = "第二课堂",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { ScsActivity.launch(context, initialTab = 0) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            displayTerm?.totalScore != null -> String.format(Locale.ROOT, "%.1f 分", displayTerm.totalScore)
                            scsData != null -> "已登录"
                            else -> "五育综测"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            displayTerm != null -> "${displayTerm.termName.ifBlank { displayTerm.yearName }} · 活动 ${scsData?.activities?.size ?: 0}"
                            scsData?.currentWindow?.isOpen == true -> "自评开放中"
                            else -> "德智体美劳评定"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (displayTerm != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = "德${formatCompactScore(displayTerm.moral)} · 智${formatCompactScore(displayTerm.academic)} · 体${formatCompactScore(displayTerm.physical)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "美${formatCompactScore(displayTerm.aesthetics)} · 劳${formatCompactScore(displayTerm.labor)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    MediumBottomActionPill(text = "查看五育明细 ›")
                }
            }
        }

        DashboardModuleId.CLASSROOM -> {
            MediumGlassCardFrame(
                title = "空闲教室",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { onOpenClassroom(2, 0) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "第 ${classroomSlot.week} 周",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "周${classroomDayLabel} · 第 ${classroomSlot.period} 节",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MediumBottomActionPill(text = "查自习室 ›")
            }
        }

        DashboardModuleId.COURSE_SELECT -> {
            MediumGlassCardFrame(
                title = "选课抢课",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = onOpenCourseSelect
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "全校选课",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "正方可选 · 队列自动抢",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MediumBottomActionPill(text = "进入选课 ›")
            }
        }

        DashboardModuleId.THEOL -> {
            val never = theolSlice.provenance.refreshState == RefreshState.NEVER
            val reportCount = sjcxSlice.data?.reports?.size ?: 0
            MediumGlassCardFrame(
                title = "北化在线",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { onNavigateTab(StartupPage.Homework) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (never) "待同步" else "${pendingHomeworks.size} 项待交",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (pendingHomeworks.isNotEmpty()) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = pendingHomeworks.firstOrNull()?.title
                            ?: "测验 ${pendingTests.size} · 待办 ${activeLocalTodos.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MediumBottomActionPill(text = "实验报告 $reportCount 项 ›")
            }
        }

        DashboardModuleId.TYGL -> {
            val tyglData = tyglSlice.data
            val latestFitness = tyglData?.fitnessRecords?.firstOrNull()
            val latestTerm = tyglData?.exerciseTerms?.firstOrNull()
            val runSummary = if (tyglData != null && latestTerm != null) {
                tyglData.exerciseColumns.firstNotNullOfOrNull { col ->
                    val raw = latestTerm.countsByCode[col.code].orEmpty()
                    val num = raw.toDoubleOrNull() ?: 0.0
                    if (num > 0.0) "${col.label} ${raw}次" else null
                }
            } else {
                null
            }
            MediumGlassCardFrame(
                title = "体测体育",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { TyglActivity.launch(context, initialTab = 0) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = latestFitness?.totalScoreText?.takeIf { it.isNotBlank() }?.let { "$it 分" }
                            ?: "体测成绩",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            latestFitness?.level?.contains("不及格") == true -> Color(0xFFFF453A)
                            latestFitness?.level?.contains("及格") == true -> Color(0xFFFF9F0A)
                            latestFitness != null && latestFitness.totalScore != null -> Color(0xFF34C759)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1
                    )
                    Text(
                        text = when {
                            latestFitness != null -> "${latestFitness.testYear.ifBlank { latestFitness.yearLabel }} · ${latestFitness.level.ifBlank { "已测" }}"
                            else -> tyglData?.runPlan?.let { "${it.projectName} · 点击查看" } ?: "历年体测 · 阳光跑"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MediumBottomActionPill(
                    text = runSummary?.let { "$it ›" } ?: "体测 · 阳光跑 · 算分 ›"
                )
            }
        }

        DashboardModuleId.MAIL -> {
            val mailData = mailSlice.data
            val latestMsg = mailData?.inboxMessages?.firstOrNull()
            MediumGlassCardFrame(
                title = "校园邮箱",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { MailActivity.launch(context, initialTab = 0) }
            ) {
                if (mailData == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "点击登录",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "mail.buct.edu.cn",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MediumBottomActionPill(text = "收件箱 ›")
                } else {
                    val unread = mailData.unreadTotal
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (unread > 0) "$unread 未读" else "共 ${mailData.inboxTotal} 封",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = if (unread > 0) Color(0xFFFF9F0A) else Color(0xFF34C759),
                            maxLines = 1
                        )
                        Text(
                            text = latestMsg?.let { msg ->
                                msg.subject.ifBlank { "(无主题)" }
                            } ?: "收件箱暂无邮件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MediumBottomActionPill(text = "收件箱 ›")
                }
            }
        }

        DashboardModuleId.CAMPUS_NEWS -> {
            val newsData = portalNewsSlice.data
            val noticePage = newsData?.columns?.get(PortalNewsColumn.NOTICE.columnId)
            val latest = noticePage?.articles?.firstOrNull()
            MediumGlassCardFrame(
                title = "校内资讯",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { PortalActivity.launchNews(context) }
            ) {
                if (newsData == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "点击进入",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "通知 · 活动 · 招聘 · 新闻",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MediumBottomActionPill(text = "通知公告 ›")
                } else {
                    val noticeTotal = noticePage?.totalCount ?: 0
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (noticeTotal > 0) "$noticeTotal 条" else "已同步",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        Text(
                            text = latest?.title?.ifBlank { "通知 · 活动 · 招聘 · 新闻" } ?: "校内暂无公告",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MediumBottomActionPill(text = "通知公告 ›")
                }
            }
        }

        DashboardModuleId.LIBRARY -> {
            val libState = portalLibrarySlice.provenance.refreshState
            val libData = portalLibrarySlice.data
            val isEmptyConfirmed = libState == RefreshState.EMPTY_CONFIRMED ||
                (libData != null && libData.totalCount == 0 && libData.books.isEmpty())
            val urgent = remember(libData) { resolveLibraryUrgentInfo(libData) }
            MediumGlassCardFrame(
                title = "图书借阅",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { PortalActivity.launchLibrary(context) }
            ) {
                when {
                    libState == RefreshState.NEVER && libData == null -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "点击同步",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "i北化图书馆在借",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MediumBottomActionPill(text = "待还清单 ›")
                    }

                    isEmptyConfirmed -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "0 本待还",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF34C759),
                                maxLines = 1
                            )
                            Text(
                                text = "当前无在借图书",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MediumBottomActionPill(text = "待还清单 ›")
                    }

                    else -> {
                        val total = libData?.totalCount ?: 0
                        val headlineColor = urgent?.takeIf { (it.daysRemaining ?: Int.MAX_VALUE) <= 7 }?.accentColor
                            ?: MaterialTheme.colorScheme.primary
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$total 本待还",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = headlineColor,
                                maxLines = 1
                            )
                            Text(
                                text = urgent?.let { "《${it.bookTitle}》· ${it.shortStatus}" } ?: "点击查看应还日期",
                                style = MaterialTheme.typography.labelSmall,
                                color = urgent?.takeIf { (it.daysRemaining ?: Int.MAX_VALUE) <= 7 }?.accentColor
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MediumBottomActionPill(text = "待还清单 ›")
                    }
                }
            }
        }

        DashboardModuleId.ELECTRICITY -> {
            val snap = electricitySlice.data
            val targets = snap?.targets.orEmpty()
            val readings = snap?.readings.orEmpty()
            val primary = readings.firstOrNull()
            val threshold = snap?.lowBalanceThreshold ?: 20.0
            MediumGlassCardFrame(
                title = "宿舍电费",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { ElectricityActivity.launch(context) }
            ) {
                when {
                    !electricityConsentAccepted -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "需要授权",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "查询需共享学号防滥用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MediumBottomActionPill(text = "阅读并同意 ›")
                    }

                    targets.isEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "尚未绑定",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "支持全校三校区宿舍",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        MediumBottomActionPill(text = "选择宿舍 ›")
                    }

                    primary == null -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "待查询",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = electricityTargetShortLabel(targets.first()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MediumBottomActionPill(
                            text = if (!electricitySlice.lastError.isNullOrBlank()) "查询失败 · 重试 ›" else "查询电费 ›"
                        )
                    }

                    else -> {
                        val isPrimaryLow = isLowElectricityBalance(primary, threshold)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = formatElectricityPrimaryValue(primary),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (isPrimaryLow) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = electricityTargetShortLabel(targets.first()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (readings.size > 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                SystemDivider(alpha = 0.28f)
                                readings.take(2).forEach { r ->
                                    val meterLow = isLowElectricityBalance(r, threshold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = r.target.meterType.ifBlank { electricityTargetBaseRoomName(r.target.roomName) },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                                        )
                                        Text(
                                            text = formatElectricityPrimaryValue(r),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (meterLow) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        } else {
                            MediumBottomActionPill(
                                text = when {
                                    !electricitySlice.lastError.isNullOrBlank() -> "上次快照 · 点此重试 ›"
                                    isPrimaryLow -> "余量偏低 · 详情 ›"
                                    primary.fromCache -> "网关缓存 · 详情 ›"
                                    else -> "实时余量 · 详情 ›"
                                }
                            )
                        }
                    }
                }
            }
        }

        DashboardModuleId.THEOL_COURSE -> {
            val courseCount = theolSlice.data?.courses?.size ?: 0
            MediumGlassCardFrame(
                title = "北化在线",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { TheolCourseActivity.launch(context) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (courseCount > 0) "$courseCount 门在读" else "点击浏览",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "单元学习 · 课程资源 · 课件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                MediumBottomActionPill(text = "进入课程资料 ›")
            }
        }

        DashboardModuleId.CAMPUS_NET -> {
            val snap = campusNetSlice.data
            MediumGlassCardFrame(
                title = "校园网费",
                icon = module.icon,
                heightDp = slotHeightDp,
                isEditing = isEditing,
                onClick = { CampusNetActivity.launch(context) }
            ) {
                if (snap == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "点击查询",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "消费 · 余额 · 钱包 · 账单",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    MediumBottomActionPill(text = "网费与流量明细 ›")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = displayCampusNetTraffic(snap),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "消费 ¥${formatCampusNetYuan(snap.userCharge)} · 余 ¥${formatCampusNetYuan(snap.userBalance)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MediumBottomActionPill(
                        text = if (snap.payRecords.isNotEmpty()) {
                            "钱包 ¥${formatCampusNetYuan(snap.walletBalance)} · 缴费 ${snap.payRecords.size}笔 ›"
                        } else {
                            "钱包 ¥${formatCampusNetYuan(snap.walletBalance)} · 账单 ›"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MediumBottomActionPill(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactMiniCard(
    icon: ImageVector,
    title: String,
    miniValue: String,
    onClick: () -> Unit,
    heightDp: Dp,
    isEditing: Boolean
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(enabled = !isEditing, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = miniValue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RenderLargeModuleCard(
    module: DashboardModuleId,
    slotHeightDp: Dp,
    isEditing: Boolean,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    tyglSlice: DomainSlice<TyglSnapshot>,
    mailSlice: DomainSlice<MailSnapshot>,
    portalNewsSlice: DomainSlice<PortalNewsSnapshot>,
    portalLibrarySlice: DomainSlice<PortalLibrarySnapshot>,
    electricitySlice: DomainSlice<ElectricitySnapshot>,
    electricityConsentAccepted: Boolean,
    campusNetSlice: DomainSlice<CampusNetSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: (campusId: Int, periodOffset: Int) -> Unit,
    onOpenCourseSelect: () -> Unit
) {
    when (module) {
        DashboardModuleId.THEOL -> {
            DashboardGlassCard(
                title = "北化在线 · 待办与 DDL",
                badge = when (theolSlice.provenance.refreshState) {
                    RefreshState.NEVER -> "未同步"
                    RefreshState.FRESH -> "最新"
                    RefreshState.EMPTY_CONFIRMED -> "已清空"
                    RefreshState.STALE -> "历史快照"
                    RefreshState.FAILED_FALLBACK -> "同步失败"
                },
                badgeColor = when (theolSlice.provenance.refreshState) {
                    RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                    RefreshState.NEVER -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ onNavigateTab(StartupPage.Homework) })
            ) {
                when {
                    theolSlice.provenance.refreshState == RefreshState.NEVER && activeLocalTodos.isEmpty() -> {
                        Text(
                            text = "尚未同步北化在线。点击进入「作业」页，统一身份认证一次即可自动拉取待交与历史课程作业。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    pendingHomeworks.isEmpty() && pendingTests.isEmpty() && activeLocalTodos.isEmpty() -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TheolMiniCounter("待交作业", "0", Modifier.weight(1f))
                            TheolMiniCounter("在线测验", "0", Modifier.weight(1f))
                            TheolMiniCounter("本地待办", "0", Modifier.weight(1f))
                        }
                        Text(
                            text = "当前没有待提交的作业或待办，点击查看历史作业与实验报告",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            pendingHomeworks.take(3).forEach { hw ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${hw.courseName} · ${hw.title}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = hw.deadline.ifBlank { "待交" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFF453A),
                                        maxLines = 1
                                    )
                                }
                            }
                            if (pendingHomeworks.isEmpty()) {
                                if (pendingTests.isNotEmpty()) {
                                    Text(
                                        text = "在线测验：${pendingTests.first().courseName} · ${pendingTests.first().title}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                activeLocalTodos.take(2).forEach { todo ->
                                    Text(
                                        text = "• ${todo.title}${if (todo.courseName.isNotBlank()) "（${todo.courseName}）" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Text(
                            text = "待交 ${pendingHomeworks.size} · 测验 ${pendingTests.size} · 本地待办 ${activeLocalTodos.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        DashboardModuleId.SCS -> {
            val scsData = scsSlice.data
            val displayTerm = pickDisplayScsTerm(scsData?.terms.orEmpty())
            val scsBadge = when {
                scsSlice.provenance.refreshState == RefreshState.NEVER -> "点击进入"
                scsData?.currentWindow?.isOpen == true -> "自评开放中"
                displayTerm != null -> displayTerm.termName.ifBlank { displayTerm.yearName }
                else -> "已同步"
            }
            val scsBadgeColor = when {
                scsSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                scsData?.currentWindow?.isOpen == true -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "第二课堂 · 五育综测",
                badge = scsBadge,
                badgeColor = scsBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ ScsActivity.launch(context, initialTab = 0) })
            ) {
                if (displayTerm != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${displayTerm.yearName} · ${displayTerm.termName}".trim(' ', '·'),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "已认活动 ${scsData?.activities?.size ?: 0} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "总分 ${displayTerm.totalScore?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Outlined.School,
                                contentDescription = "第二课堂",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScsDimensionMiniBadge("德", displayTerm.moral, Color(0xFFFF6B6B), Modifier.weight(1f))
                        ScsDimensionMiniBadge("智", displayTerm.academic, Color(0xFF4D96FF), Modifier.weight(1f))
                        ScsDimensionMiniBadge("体", displayTerm.physical, Color(0xFF34C759), Modifier.weight(1f))
                        ScsDimensionMiniBadge("美", displayTerm.aesthetics, Color(0xFFAF52DE), Modifier.weight(1f))
                        ScsDimensionMiniBadge("劳", displayTerm.labor, Color(0xFFFF9F0A), Modifier.weight(1f))
                    }
                } else {
                    Text(
                        text = "校内网/VPN 下直连 scs.buct.edu.cn，查看德智体美劳五育综测成绩、明细与二课活动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DashboardModuleId.ECARD -> {
            val cardData = cardSlice.data
            DashboardGlassCard(
                title = "校园卡 · 余额与流水",
                badge = when {
                    cardSlice.provenance.refreshState == RefreshState.NEVER -> "未登录"
                    cardSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> "同步失败"
                    !cardHasSession && cardData != null -> "会话过期"
                    cardSlice.provenance.refreshState == RefreshState.EMPTY_CONFIRMED -> "暂无流水"
                    cardSlice.provenance.refreshState == RefreshState.STALE -> "历史快照"
                    else -> "最新"
                },
                badgeColor = when {
                    cardSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                    !cardHasSession && cardData != null -> Color(0xFFFF9500)
                    cardSlice.provenance.refreshState == RefreshState.NEVER -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({
                    context.startActivity(Intent(context, EcardActivity::class.java))
                })
            ) {
                if (cardData == null) {
                    Text(
                        text = "校园卡平台（ecard.buct.edu.cn）独立于统一身份认证，默认密码为身份证后六位（有字母用 1 代替）。点击登录查询实时余额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "¥ ${formatFenToYuan(cardData.account.balanceFen)}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${cardData.account.statusLabel} · ${cardData.account.maskedId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.CreditCard,
                            contentDescription = "进入校园卡",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    SystemDivider(alpha = 0.28f)
                    if (cardData.transactions.isEmpty()) {
                        Text(
                            text = "近期暂无消费或充值流水",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            cardData.transactions.take(3).forEach { tx ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tx.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = shortCardDateTime(tx.formattedTime),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "¥ ${formatFenToYuan(tx.amountFen)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isCardRecharge(tx)) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        DashboardModuleId.SJCX -> {
            val sjcxData = sjcxSlice.data
            val sjcxState = sjcxSlice.provenance.refreshState
            val sjcxBadge = when (sjcxState) {
                RefreshState.NEVER -> "点击进入"
                RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> {
                    val openCount = sjcxData?.openLectures?.size ?: 0
                    if (openCount > 0) "${openCount}场活动开放" else "已同步"
                }
                RefreshState.STALE -> "历史快照"
                RefreshState.FAILED_FALLBACK -> "离线快照"
            }
            val sjcxBadgeColor = when (sjcxState) {
                RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> Color(0xFF34C759)
                RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                else -> MaterialTheme.colorScheme.primary
            }

            DashboardGlassCard(
                title = "实践教学 · 实验与讲座",
                badge = sjcxBadge,
                badgeColor = sjcxBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ SjcxActivity.launch(context, initialTab = 0) })
            ) {
                val firstLecture = sjcxData?.openLectures?.firstOrNull()
                if (firstLecture != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                            .clickable(enabled = !isEditing) { SjcxActivity.launch(context, initialTab = 1) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "活动：${firstLecture.projectName}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val capColor = if (firstLecture.isFull) Color(0xFFFF9F0A) else Color(0xFF34C759)
                        Text(
                            text = if (firstLecture.totalNum > 0) "${firstLecture.registrationNum}/${firstLecture.totalNum}" else "可报名",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = capColor,
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = if (sjcxData != null) {
                            "实验排课 ${sjcxData.schedules.size} 节 · 已认学分 ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)}"
                        } else {
                            "直连 sjcx.buct.edu.cn，一键查实验课表、抢讲座与查创新学分"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("实验课表", Icons.Outlined.Science, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 0)
                    }
                    QuickActionTile("抢讲座", Icons.Outlined.Campaign, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 1)
                    }
                    QuickActionTile("我的讲座", Icons.Outlined.Campaign, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 2)
                    }
                    QuickActionTile("创新学分", Icons.Outlined.WorkspacePremium, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 3)
                    }
                }
            }
        }

        DashboardModuleId.CLASSROOM -> {
            DashboardGlassCard(
                title = "查空闲教室",
                badge = "第 ${classroomSlot.week} 周 · 周${classroomDayLabel}",
                badgeColor = MaterialTheme.colorScheme.primary,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ onOpenClassroom(2, 0) })
            ) {
                Text(
                    text = "当前时段：第 ${classroomSlot.week} 周 · 周${classroomDayLabel} · 第 ${classroomSlot.period} 节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("北区空教室", Icons.Outlined.MeetingRoom, Modifier.weight(1f), !isEditing) {
                        onOpenClassroom(2, 0)
                    }
                    QuickActionTile("东区空教室", Icons.Outlined.LocationOn, Modifier.weight(1f), !isEditing) {
                        onOpenClassroom(1, 0)
                    }
                    QuickActionTile("下节课空闲", Icons.Outlined.Schedule, Modifier.weight(1f), !isEditing) {
                        onOpenClassroom(2, 1)
                    }
                }
            }
        }

        DashboardModuleId.COURSE_SELECT -> {
            DashboardGlassCard(
                title = "选课与队列抢课",
                badge = "正方选课",
                badgeColor = MaterialTheme.colorScheme.primary,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else onOpenCourseSelect
            ) {
                Text(
                    text = "浏览主修/通识可选课程、退课管理与定时自动抢课",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("浏览可选课程", Icons.Outlined.LibraryAdd, Modifier.weight(1f), !isEditing, onOpenCourseSelect)
                    QuickActionTile("抢课队列与定时", Icons.Outlined.Schedule, Modifier.weight(1f), !isEditing, onOpenCourseSelect)
                }
            }
        }

        DashboardModuleId.TYGL -> {
            val tyglData = tyglSlice.data
            val latestFitness = tyglData?.fitnessRecords?.firstOrNull()
            val latestTerm = tyglData?.exerciseTerms?.firstOrNull()
            val runSummary = if (tyglData != null && latestTerm != null) {
                tyglData.exerciseColumns.firstNotNullOfOrNull { col ->
                    val raw = latestTerm.countsByCode[col.code].orEmpty()
                    val num = raw.toDoubleOrNull() ?: 0.0
                    if (num > 0.0) "${latestTerm.academicYear}(${latestTerm.term}) ${col.label} ${raw}次" else null
                }
            } else {
                null
            }
            val tyglBadge = when {
                tyglSlice.provenance.refreshState == RefreshState.NEVER -> "点击进入"
                tyglSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> "同步失败"
                latestFitness?.level?.isNotBlank() == true ->
                    "${latestFitness.testYear.ifBlank { latestFitness.yearLabel }} · ${latestFitness.level}"
                else -> "公网直连"
            }
            val tyglBadgeColor = when {
                tyglSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                latestFitness?.level?.contains("不及格") == true -> Color(0xFFFF453A)
                latestFitness?.level?.contains("及格") == true -> Color(0xFFFF9F0A)
                latestFitness != null && latestFitness.totalScore != null -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "体测与阳光跑",
                badge = tyglBadge,
                badgeColor = tyglBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ TyglActivity.launch(context, initialTab = 0) })
            ) {
                if (latestFitness != null) {
                    val bodyMeta = listOf(latestFitness.heightText, latestFitness.weightText)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "体测总分 ${latestFitness.totalScoreText.ifBlank { "--" }} 分",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = runSummary
                                    ?: bodyMeta.ifBlank { "更新于 ${latestFitness.updatedAt.take(10)}" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (bodyMeta.isNotBlank() && runSummary != null) {
                            Text(
                                text = bodyMeta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Text(
                        text = runSummary
                            ?: "公网直连 tygl.buct.edu.cn，查询历年体测成绩、阳光跑计划与离线《国标》算分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("体测成绩", Icons.Outlined.FitnessCenter, Modifier.weight(1f), !isEditing) {
                        TyglActivity.launch(context, initialTab = 0)
                    }
                    QuickActionTile("课外锻炼", Icons.Outlined.DirectionsRun, Modifier.weight(1f), !isEditing) {
                        TyglActivity.launch(context, initialTab = 1)
                    }
                    QuickActionTile("国标算分", Icons.Outlined.Calculate, Modifier.weight(1f), !isEditing) {
                        TyglActivity.launch(context, initialTab = 3)
                    }
                }
            }
        }

        DashboardModuleId.MAIL -> {
            val mailData = mailSlice.data
            val unread = mailData?.unreadTotal ?: 0
            val mailBadge = when {
                mailSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> "同步失败"
                mailSlice.provenance.refreshState == RefreshState.NEVER && mailData == null -> "点击进入"
                unread > 0 -> "$unread 封未读"
                mailData != null -> "共 ${mailData.inboxTotal} 封"
                else -> "校园邮箱"
            }
            val mailBadgeColor = when {
                mailSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                unread > 0 -> Color(0xFFFF9F0A)
                mailData != null -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "校园邮箱",
                badge = mailBadge,
                badgeColor = mailBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ MailActivity.launch(context, initialTab = 0) })
            ) {
                val previews = mailData?.inboxMessages.orEmpty().take(2)
                if (mailData == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "mail.buct.edu.cn",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "通过统一身份认证一键同步收件箱与附件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (previews.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = mailData.account.email.ifBlank { "收件箱暂无邮件" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "点击进入收件箱或切换文件夹查看",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        previews.forEach { msg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        if (!msg.isRead) append("• ")
                                        val sender = msg.fromName.ifBlank { msg.fromAddress }
                                        if (sender.isNotBlank()) {
                                            append(sender)
                                            append(" · ")
                                        }
                                        append(msg.subject.ifBlank { "(无主题)" })
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (!msg.isRead) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = msg.receivedDate.ifBlank { msg.sentDate },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!msg.isRead) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("收件箱", Icons.Outlined.MailOutline, Modifier.weight(1f), !isEditing) {
                        MailActivity.launch(context, initialTab = 0)
                    }
                    QuickActionTile("未读邮件", Icons.Outlined.MarkEmailUnread, Modifier.weight(1f), !isEditing) {
                        MailActivity.launch(context, initialTab = 1)
                    }
                }
            }
        }

        DashboardModuleId.CAMPUS_NEWS -> {
            val newsData = portalNewsSlice.data
            val noticePage = newsData?.columns?.get(PortalNewsColumn.NOTICE.columnId)
            val previews = noticePage?.articles.orEmpty().take(2)
            val newsBadge = when {
                portalNewsSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> "同步失败"
                portalNewsSlice.provenance.refreshState == RefreshState.NEVER && newsData == null -> "点击进入"
                noticePage != null && noticePage.totalCount > 0 -> "${noticePage.totalCount} 条"
                newsData != null -> "最新"
                else -> "校内资讯"
            }
            val newsBadgeColor = when {
                portalNewsSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                newsData != null -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "校内资讯 · 通知与招聘",
                badge = newsBadge,
                badgeColor = newsBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ PortalActivity.launchNews(context) })
            ) {
                if (newsData == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "i北化 · mnews.buct.edu.cn",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "汇集通知公告、活动公告、招聘信息与北化新闻",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (previews.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "通知公告暂无新条目",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "点击切换活动公告、招聘信息或北化新闻",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        previews.forEach { article ->
                            val dateText = if (article.publishTimestampMs > 0L) {
                                SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(article.publishTimestampMs))
                            } else {
                                article.sourceOrg
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        if (article.sourceOrg.isNotBlank()) {
                                            append(article.sourceOrg)
                                            append(" · ")
                                        }
                                        append(article.title)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (dateText.isNotBlank()) {
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("通知公告", Icons.Outlined.Campaign, Modifier.weight(1f), !isEditing) {
                        PortalActivity.launchNews(context, initialColumnIndex = 0)
                    }
                    QuickActionTile("招聘信息", Icons.Outlined.School, Modifier.weight(1f), !isEditing) {
                        PortalActivity.launchNews(context, initialColumnIndex = 2)
                    }
                }
            }
        }

        DashboardModuleId.LIBRARY -> {
            val libState = portalLibrarySlice.provenance.refreshState
            val libData = portalLibrarySlice.data
            val isEmptyConfirmed = libState == RefreshState.EMPTY_CONFIRMED ||
                (libData != null && libData.totalCount == 0 && libData.books.isEmpty())
            val urgent = remember(libData) { resolveLibraryUrgentInfo(libData) }
            val libBadge = when {
                libState == RefreshState.FAILED_FALLBACK -> "同步失败"
                libState == RefreshState.NEVER && libData == null -> "点击同步"
                isEmptyConfirmed -> "0 本待还"
                urgent != null && (urgent.daysRemaining ?: Int.MAX_VALUE) < 0 -> "有逾期"
                urgent != null && (urgent.daysRemaining ?: Int.MAX_VALUE) <= 7 -> "即将到期"
                libData != null -> "${libData.totalCount} 本待还"
                else -> "图书借阅"
            }
            val libBadgeColor = when {
                libState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                libState == RefreshState.NEVER && libData == null -> MaterialTheme.colorScheme.onSurfaceVariant
                isEmptyConfirmed -> Color(0xFF34C759)
                urgent != null && (urgent.daysRemaining ?: Int.MAX_VALUE) <= 7 -> urgent.accentColor
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "图书借阅 · 待还提醒",
                badge = libBadge,
                badgeColor = libBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ PortalActivity.launchLibrary(context) })
            ) {
                when {
                    libState == RefreshState.NEVER && libData == null -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "i北化图书馆在借查询",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "尚未同步在借清单，点击卡片查看或登录后刷新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    isEmptyConfirmed -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "当前无在借图书",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFF34C759),
                                maxLines = 1
                            )
                            Text(
                                text = "名下暂无待还图书记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    else -> {
                        val sortedBooks = remember(libData) {
                            libData?.books.orEmpty()
                                .sortedBy { portalBookDaysRemaining(it.dueDate) ?: Int.MAX_VALUE }
                                .take(2)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            sortedBooks.forEach { book ->
                                val days = portalBookDaysRemaining(book.dueDate)
                                val dueColor = when {
                                    days != null && days < 0 -> Color(0xFFFF453A)
                                    days != null && days <= 7 -> Color(0xFFFF9F0A)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = book.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            days != null && days < 0 -> "逾期${-days}天"
                                            days == 0 -> "今天到期"
                                            days != null && days <= 7 -> "剩${days}天"
                                            else -> book.dueDate
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dueColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                QuickActionTile(
                    label = "查看在借清单",
                    icon = Icons.Outlined.MenuBook,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isEditing
                ) {
                    PortalActivity.launchLibrary(context)
                }
            }
        }

        DashboardModuleId.ELECTRICITY -> {
            val snap = electricitySlice.data
            val targets = snap?.targets.orEmpty()
            val readings = snap?.readings.orEmpty()
            val threshold = snap?.lowBalanceThreshold ?: 20.0
            val anyLow = readings.any { isLowElectricityBalance(it, threshold) }
            val badgeText = when {
                !electricityConsentAccepted -> "需授权"
                targets.isEmpty() -> "未绑定"
                !electricitySlice.lastError.isNullOrBlank() -> "查询异常"
                anyLow -> "余量偏低"
                readings.isNotEmpty() -> if (readings.first().fromCache) "缓存" else "最新"
                else -> "待查询"
            }
            val badgeColor = when {
                !electricitySlice.lastError.isNullOrBlank() -> Color(0xFFFF453A)
                anyLow -> Color(0xFFD97706)
                !electricityConsentAccepted || targets.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "宿舍电费 · 实时余量",
                badge = badgeText,
                badgeColor = badgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ ElectricityActivity.launch(context) })
            ) {
                when {
                    !electricityConsentAccepted -> {
                        Text(
                            text = "使用宿舍电费查询前需授权共享学号与签名至开发者网关，用于统计查询次数与防滥用拉黑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        QuickActionTile(
                            label = "阅读并授权查询",
                            icon = Icons.Outlined.Bolt,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isEditing
                        ) {
                            ElectricityActivity.launch(context)
                        }
                    }

                    targets.isEmpty() -> {
                        Text(
                            text = "已同意学号防滥用说明，尚未绑定宿舍。支持昌平、东区（照明+空调双表）、西区全校宿舍。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        QuickActionTile(
                            label = "选择并绑定宿舍",
                            icon = Icons.Outlined.Bolt,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isEditing
                        ) {
                            ElectricityActivity.launch(context)
                        }
                    }

                    readings.isEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = electricityTargetShortLabel(targets.first()),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = electricitySlice.lastError ?: "尚未拉取电费快照，点击打开页面查询最新余量",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!electricitySlice.lastError.isNullOrBlank()) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        QuickActionTile(
                            label = "打开宿舍电费",
                            icon = Icons.Outlined.Bolt,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isEditing
                        ) {
                            ElectricityActivity.launch(context)
                        }
                    }

                    else -> {
                        val readingMap = readings.associateBy { "${it.target.schoolid}:${it.target.roomid}" }
                        Text(
                            text = "${targets.first().campusName} · ${electricityTargetShortLabel(targets.first())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            targets.take(2).forEach { target ->
                                val r = readingMap["${target.schoolid}:${target.roomid}"]
                                val isLow = r != null && isLowElectricityBalance(r, threshold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = target.meterType.ifBlank { electricityTargetBaseRoomName(target.roomName) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                    )
                                    Text(
                                        text = if (r != null) formatElectricityPrimaryValue(r) else "待查询",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isLow) Color(0xFFD97706) else MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        QuickActionTile(
                            label = "打开宿舍电费",
                            icon = Icons.Outlined.Bolt,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isEditing
                        ) {
                            ElectricityActivity.launch(context)
                        }
                    }
                }
            }
        }

        DashboardModuleId.THEOL_COURSE -> {
            val courses = theolSlice.data?.courses.orEmpty()
            val badge = when {
                courses.isNotEmpty() -> "${courses.size} 门在读"
                theolSlice.provenance.refreshState == RefreshState.NEVER -> "未同步"
                else -> "可浏览"
            }
            DashboardGlassCard(
                title = "北化在线 · 课程与资源",
                badge = badge,
                badgeColor = if (courses.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ TheolCourseActivity.launch(context) })
            ) {
                if (courses.isEmpty()) {
                    Text(
                        text = "搜索在读课程，进入「单元学习」与「课程资源」，支持端内在线预览课件/视频与原生下载打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = courses.take(3).joinToString(" · ") { it.courseName.ifBlank { "课程 #${it.lid}" } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                QuickActionTile(
                    label = "进入北化在线课程",
                    icon = Icons.Outlined.MenuBook,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isEditing
                ) {
                    TheolCourseActivity.launch(context)
                }
            }
        }

        DashboardModuleId.CAMPUS_NET -> {
            val snap = campusNetSlice.data
            val netBadge = when {
                !campusNetSlice.lastError.isNullOrBlank() -> "需校内网"
                snap == null -> "未查询"
                snap.accountStatus.isNotBlank() -> snap.accountStatus
                snap.isOnline -> "已在线"
                else -> "未在线"
            }
            val netBadgeColor = when {
                !campusNetSlice.lastError.isNullOrBlank() -> Color(0xFFFF9500)
                snap?.isOnline == true || snap?.accountStatus?.contains("正常") == true -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            DashboardGlassCard(
                title = "校园网 · 网费与账单",
                badge = netBadge,
                badgeColor = netBadgeColor,
                heightDp = slotHeightDp,
                onClick = if (isEditing) null else ({ CampusNetActivity.launch(context) })
            ) {
                if (snap == null) {
                    Text(
                        text = "直连优先、校外自动走 WebVPN 免密查询校园网网关（tree.buct.edu.cn），实时查看本月消费、产品余额、电子钱包、已用流量及缴费/月结算账单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    QuickActionTile(
                        label = "查询校园网网费",
                        icon = Icons.Outlined.Wifi,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isEditing
                    ) {
                        CampusNetActivity.launch(context)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "消费 ¥${formatCampusNetYuan(snap.userCharge)}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${snap.productsName.ifBlank { "校园网" }} · 流量 ${displayCampusNetTraffic(snap)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "余 ¥${formatCampusNetYuan(snap.userBalance)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "钱包 ¥${formatCampusNetYuan(snap.walletBalance)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val recentBill = snap.payRecords.firstOrNull()?.let { "最近缴费：¥${it.amountText} (${it.payTime.take(10)})" }
                        ?: snap.checkoutRecords.firstOrNull()?.let { "上月结算：¥${it.feeText} · ${it.trafficText}" }
                    if (!recentBill.isNullOrBlank()) {
                        Text(
                            text = recentBill,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    QuickActionTile(
                        label = "查看网费与账单明细",
                        icon = Icons.Outlined.Wifi,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isEditing
                    ) {
                        CampusNetActivity.launch(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun TheolMiniCounter(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ScsDimensionMiniBadge(
    label: String,
    score: Double?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = score?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (score != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private data class ClassroomSlot(
    val semester: String,
    val week: Int,
    val day: Int,
    val period: Int
)

private fun resolveCurrentClassroomSlot(context: Context): ClassroomSlot {
    val termId = WidgetScheduleSource.resolveTerm(context, AppWidgetManager.INVALID_APPWIDGET_ID).id
    val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context)
    val cal = Calendar.getInstance()
    val week = WidgetScheduleSource.weekForDate(startMillis, cal)
    val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
    val periodTimes = settings.getPeriodTimes().sortedBy { it.period }
    val defaultTimes = settings.getDefaultPeriodTimes().sortedBy { it.period }
    val activePeriod = (if (periodTimes.isNotEmpty()) periodTimes else defaultTimes)
        .firstOrNull { pt ->
            val endMin = parseClockMinutes(pt.endTime)
            endMin != null && endMin > nowMinutes
        }?.period ?: 1
    return ClassroomSlot(
        semester = termId,
        week = week,
        day = day,
        period = activePeriod.coerceIn(1, 12)
    )
}

@Composable
private fun DashboardGlassCard(
    title: String,
    badge: String,
    badgeColor: Color,
    heightDp: Dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = badgeColor,
                    maxLines = 1
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content
        )
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
