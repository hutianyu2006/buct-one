package com.buct.course.widget

import android.content.Context
import com.buct.one.data.store.TheolStore

/** 还有未完成事项的一门课程；小组件只显示课程名称与条数。 */
internal data class PendingCourse(
    val courseName: String,
    val homeworkCount: Int,
    val testCount: Int
) {
    val totalCount: Int get() = homeworkCount + testCount
}

internal data class TodoWidgetData(
    val items: List<PendingCourse>,
    /** 从没同步过北化在线：此时「没有待办」不等于「全部做完」，要分开提示。 */
    val neverSynced: Boolean
)

/**
 * 「待办事项」小组件的数据汇总。
 *
 * 两个数据源（北化在线待交作业 + 未完成测验）按课程名合并成一条：
 * 用户想看的是「哪几门课还有东西没交」，而不是逐条作业标题。
 * 排序按最近的截止时间，最急的课排最上面。
 */
internal object TodoWidgetSource {

    private const val OTHER_COURSE = "其他课程"

    /** 小组件只读展示，不需要解析器版本，仅在快照文件缺失时用作 provenance 占位。 */
    private const val FALLBACK_PARSER_VERSION = 0

    fun load(context: Context): TodoWidgetData {
        val store = TheolStore(context.applicationContext)
        val slice = runCatching { store.readTheol(FALLBACK_PARSER_VERSION) }.getOrNull()
            ?: return TodoWidgetData(emptyList(), neverSynced = true)
        // 没同步过就是「不知道」，不能显示成「全部做完了」（数据层铁律 2）
        val snapshot = slice.data ?: return TodoWidgetData(emptyList(), neverSynced = true)

        val filters = runCatching { store.readFilterPrefs() }.getOrNull()
        val nowMillis = System.currentTimeMillis()
        val counters = LinkedHashMap<String, IntArray>()
        val urgency = HashMap<String, Long>()

        fun add(courseName: String, homework: Boolean, deadlineMillis: Long?) {
            val key = courseName.ifBlank { OTHER_COURSE }
            val slot = counters.getOrPut(key) { IntArray(2) }
            if (homework) slot[0]++ else slot[1]++
            val current = urgency[key]
            val candidate = deadlineMillis ?: Long.MAX_VALUE
            if (current == null || candidate < current) urgency[key] = candidate
        }

        // 与 App 一样按 lid+id 去重：快照里同一条作业/测验出现两次时不能算成两条
        snapshot.homeworks
            .distinctBy { "${it.lid}:${it.hwtid}" }
            .filter { it.canSubmit && filters?.isHomeworkVisible(it) != false }
            .forEach { add(it.courseName, homework = true, deadlineMillis = it.deadlineMillis) }

        snapshot.tests
            .distinctBy { "${it.lid}:${it.testId}" }
            .filter { test ->
                val endTimeMillis = test.endTimeMillis
                !test.hasResult &&
                    (endTimeMillis == null || endTimeMillis > nowMillis) &&
                    filters?.isTestVisible(test) != false
            }
            .forEach { add(it.courseName, homework = false, deadlineMillis = it.endTimeMillis) }

        val items = counters.map { (courseName, counts) ->
            PendingCourse(courseName, homeworkCount = counts[0], testCount = counts[1])
        }.sortedWith(
            compareBy({ urgency[it.courseName] ?: Long.MAX_VALUE }, { it.courseName })
        )

        return TodoWidgetData(items, neverSynced = false)
    }

    /** 一行右侧的条数文案，例如「作业 2 · 测试 1」。 */
    fun describeCount(item: PendingCourse): String = when {
        item.homeworkCount > 0 && item.testCount > 0 ->
            "作业 ${item.homeworkCount} · 测试 ${item.testCount}"
        item.homeworkCount > 0 -> "作业 ${item.homeworkCount}"
        else -> "测试 ${item.testCount}"
    }
}
