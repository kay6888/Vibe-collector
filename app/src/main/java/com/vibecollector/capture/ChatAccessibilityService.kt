package com.vibecollector.capture

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.vibecollector.Vibe
import com.vibecollector.notify.CaptureNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The capture engine.
 *
 * ## Why an accessibility service
 *
 * Android 10 removed background clipboard access: only the app with focus and the
 * default keyboard may call `getPrimaryClip()`. A bound accessibility service is
 * the one exemption that survives, which is why capture lives here rather than in
 * a plain service.
 *
 * ## What it does and does not read
 *
 * It reads the clipboard text and tracks which app is in the foreground so that
 * "chat apps only" can be honoured. It does not scrape the screen, walk the view
 * tree for text, or send anything anywhere. No accessibility content is collected
 * or stored — the window class name is only used as a source label.
 *
 * Copy detection works two ways: the clipboard listener catches every copy, and
 * `TYPE_VIEW_CLICKED` on a control labelled Copy gives a more accurate "this came
 * from a chatbot" signal for the "chat apps only" filter.
 */
class ChatAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var clipboard: ClipboardManager? = null
    private var lastForegroundPackage: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = Vibe.get().app
        runCatching { clipboard?.addPrimaryClipChangedListener(listener) }
        // Read on connect so a copy made just before the service bound is not missed.
        main.postDelayed({ readClipboardNow() }, 500)
        CaptureNotifier.ensureChannels(app)
        Vibe.markServiceRunning(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> lastForegroundPackage = pkg
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = event.text?.joinToString(" ")?.trim()?.lowercase().orEmpty()
                if (label == "copy" || label == "copy code" || label == "copy text") {
                    // The clipboard listener fires a moment later; reading here too
                    // means an explicit Copy tap is never missed.
                    main.postDelayed({ readClipboardNow() }, 120)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        scope.cancel()
        Vibe.markServiceRunning(false)
    }

    private fun readClipboardNow() {
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (text.isNotBlank()) handleText(text)
    }

    private fun onClipboardChanged() {
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)

        // Ignore rich content we cannot flatten; a code block is always plain text.
        val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) return
        handleText(text)
    }

    private fun handleText(text: String) {
        val vibe = Vibe.get()
        val source = lastForegroundPackage
        scope.launch {
            val settings = vibe.settings.flow.first()
            if (!settings.captureEnabled) return@launch
            if (!settings.copyCollectEnabled) return@launch

            // The clipboard listener fires in our own process when vibe-collector
            // itself copies text, so filter on the foreground app instead.
            if (source == vibe.app.packageName) return@launch

            val verdict = CaptureRules.evaluate(
                text = text,
                settings = settings,
                sourcePackage = source,
                ownPackages = setOf(vibe.app.packageName, vibe.app.packageName + ".debug"),
            )
            if (!verdict.capture) return@launch

            val label = labelFor(source)
            val capture = vibe.captureCoordinator.build(
                text = text,
                settings = settings,
                sourcePackage = source.orEmpty(),
                sourceLabel = label,
            ) ?: return@launch

            if (capture.isScaffoldOnly) {
                CaptureNotifier.showResult(
                    vibe.app,
                    "Project structure created",
                    "${capture.project}: ${capture.scaffoldedDirs} folders, ${capture.scaffoldedFiles} files ready for your code.",
                )
                return@launch
            }

            if (settings.savesWithoutAsking) {
                val report = vibe.captureCoordinator.save(capture.id, capture.files)
                main.post {
                    CaptureNotifier.showResult(
                        vibe.app,
                        if (report.ok) "Auto-saved" else "Could not save",
                        report.summary,
                    )
                }
                return@launch
            }

            vibe.captureCoordinator.enqueue(capture)
            main.post {
                if (settings.notificationsEnabled) {
                    CaptureNotifier.showCapturePrompt(vibe.app, capture, settings)
                } else {
                    Vibe.setBadgeCount(vibe.captureCoordinator.count())
                }
            }
        }
    }

    private fun labelFor(pkg: String?): String {
        if (pkg.isNullOrBlank() || pkg == Vibe.get().app.packageName) return "clipboard"
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }
}
