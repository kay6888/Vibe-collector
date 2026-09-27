package com.vibecollector

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.vibecollector.capture.CaptureCoordinator
import com.vibecollector.data.SettingsStore
import com.vibecollector.data.VibeSettings
import com.vibecollector.storage.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class VibeApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var projectStore: ProjectStore
        private set
    lateinit var captureCoordinator: CaptureCoordinator
        private set

    val mainHandler = Handler(Looper.getMainLooper())
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsFlow: StateFlow<VibeSettings> by lazy {
        settingsStore.flow.stateIn(appScope, SharingStarted.Eagerly, VibeSettings())
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        projectStore = ProjectStore(this)
        captureCoordinator = CaptureCoordinator(this, projectStore)
        Vibe.attach(this)
    }
}

/**
 * Minimal service locator. The app is a single process with a handful of
 * long-lived collaborators, so a DI framework would be more ceremony than the
 * problem needs.
 */
object Vibe {

    lateinit var app: VibeApp
        private set

    @Volatile
    var serviceRunning: Boolean = false
        private set

    /** Mirrors the waiting-capture count when notifications are turned off. */
    @Volatile
    var badgeCount: Int = 0
        private set

    fun attach(application: VibeApp) {
        app = application
    }

    fun get(): Vibe = this

    val settings: SettingsStore get() = app.settingsStore
    val settingsFlow: StateFlow<VibeSettings> get() = app.settingsFlow
    val projectStore: ProjectStore get() = app.projectStore
    val captureCoordinator: CaptureCoordinator get() = app.captureCoordinator
    val mainHandler: Handler get() = app.mainHandler

    fun markServiceRunning(running: Boolean) {
        serviceRunning = running
    }

    fun setBadgeCount(count: Int) {
        badgeCount = count
    }
}

fun Context.vibeApp(): VibeApp = applicationContext as VibeApp

fun formatTime(context: Context, millis: Long): String =
    if (millis <= 0) "" else DateFormat.getTimeFormat(context).format(java.util.Date(millis))
