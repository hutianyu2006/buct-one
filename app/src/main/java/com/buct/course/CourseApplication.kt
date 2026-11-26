package com.buct.course

import android.app.Application
import com.buct.course.login.AndroidHttpClients
import com.buct.course.login.AndroidSecureVault
import com.buct.course.login.BuctWebVpn
import com.buct.course.manager.AppearanceSettingsManager
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.FontScaleManager
import com.buct.course.manager.SyncSettings
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassRuntimeGuard
import com.buct.one.data.session.SidPortalSessionVault

class CourseApplication : Application() {
    companion object {
        @Volatile
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // 会话 Cookie 加密落盘（Keystore）：重启 App 不掉登录态；密文已从备份规则排除。
        runCatching { AndroidHttpClients.useEncryptedCookieStore(AndroidSecureVault(this)) }
        BuctWebVpn.configure(
            readSidCookies = { SidPortalSessionVault.readCookies(appContext).first },
            saveSidCookies = { SidPortalSessionVault.saveSidPortalCookies(appContext, it) },
            readSavedCredentials = {
                if (!SyncSettings.enabled(appContext)) {
                    null
                } else {
                    val user = UserManager.getInstance()
                    synchronized(user) {
                        if (user.currentSchool == null) {
                            user.init(appContext)
                        }
                    }
                    if (user.isDemoMode || user.currentSchool?.id != "buct") {
                        null
                    } else {
                        val username = user.username.ifBlank { user.studentId.orEmpty() }.trim()
                        val password = user.accountPassword
                        if (username.isNotEmpty() && !password.isNullOrEmpty()) {
                            username to password.toCharArray()
                        } else {
                            null
                        }
                    }
                }
            },
            readVpnTicket = { SidPortalSessionVault.readVpnTicket(appContext) },
            saveVpnTicket = { SidPortalSessionVault.saveVpnTicket(appContext, it) }
        )
        GlassRuntimeGuard.initialize(this)
        AppearanceSettingsManager.initialize(this)
        FontScaleManager.initialize(this)
        com.buct.course.manager.BottomNavPreferences.initialize(this)
        AppThemeCoordinator.initialize(this)
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        if (processName == packageName) {
            com.buct.course.analytics.UmamiTracker.install(this)
            com.buct.course.schedule.ScheduleReminderScheduler.get(this).start(this)
            // 自建考试的提醒：每次冷启动重排一遍，代价很小，能兜住漏掉的闹钟
            runCatching { com.buct.course.schedule.ExamReminderScheduler.reschedule(this) }
            runCatching { com.buct.course.reminder.ReminderChannels.ensureCreated(this) }
            runCatching { com.buct.course.reminder.SmartReminderScheduler.reschedule(this) }
            runCatching { com.buct.course.reminder.SmartReminderScheduler.syncWorkSchedule(this) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppThemeCoordinator.configurationChanged()
    }
}
