package com.buct.one.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 可取消的调度句柄。 */
interface ScheduledTask {
    fun cancel()
}

/**
 * 后台定时任务抽象：同步巡检、缓存刷新等逻辑只写一份。
 * Android 侧最终可桥接 WorkManager / AlarmManager，iOS 侧可桥接 BGTaskScheduler；
 * 当前提供的 [CoroutineAppScheduler] 用协程实现，双端行为一致。
 */
interface AppScheduler {
    fun schedulePeriodic(intervalMillis: Long, initialDelayMillis: Long = 0L, task: suspend () -> Unit): ScheduledTask
    fun scheduleOnce(delayMillis: Long, task: suspend () -> Unit): ScheduledTask
}

class CoroutineAppScheduler(private val scope: CoroutineScope) : AppScheduler {

    override fun schedulePeriodic(
        intervalMillis: Long,
        initialDelayMillis: Long,
        task: suspend () -> Unit,
    ): ScheduledTask {
        require(intervalMillis > 0) { "intervalMillis must be > 0" }
        val job: Job = scope.launch {
            if (initialDelayMillis > 0) delay(initialDelayMillis)
            while (isActive) {
                task()
                delay(intervalMillis)
            }
        }
        return object : ScheduledTask {
            override fun cancel() = job.cancel()
        }
    }

    override fun scheduleOnce(delayMillis: Long, task: suspend () -> Unit): ScheduledTask {
        val job = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            task()
        }
        return object : ScheduledTask {
            override fun cancel() = job.cancel()
        }
    }
}
