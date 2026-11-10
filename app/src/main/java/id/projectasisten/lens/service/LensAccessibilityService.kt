package id.projectasisten.lens.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import id.projectasisten.lens.core.BitmapSecurityInspector
import id.projectasisten.lens.core.ScreenshotRepository
import id.projectasisten.lens.data.AppPreferences
import id.projectasisten.lens.ui.overlay.AssistantOverlayActivity

/**
 * Method B — On-Demand Accessibility Shortcut Service.
 *
 * Security & Banking Compliance:
 * - Configured with accessibilityEventTypes="" and canRetrieveWindowContent="false".
 * - [onAccessibilityEvent] is never invoked by the system and does zero screen reading.
 * - Activated ONLY when the user manually presses the system Accessibility button/shortcut.
 * - On Android 11+ (API 30+), uses official [takeScreenshot] API which respects FLAG_SECURE.
 */
class LensAccessibilityService : AccessibilityService() {

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val controller = accessibilityButtonController
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                handleShortcutTriggered()
            }
        }
        accessibilityButtonCallback = callback
        runCatching {
            controller.registerAccessibilityButtonCallback(callback)
        }
    }

    private fun handleShortcutTriggered() {
        val prefs = AppPreferences(this)
        if (!prefs.isShortcutEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenshotApi30 {
                launchOverlayActivity(hasPreCapturedState = true)
            }
        } else {
            launchOverlayActivity(hasPreCapturedState = false)
        }
    }

    /**
     * Captures a single screenshot on Android 11+ (API 30+) using the official AccessibilityService API.
     * Can also be invoked on-demand by [AssistantOverlayActivity] when the user activates via QS Tile.
     */
    fun captureScreenshotApi30(onComplete: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onComplete(false)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    val softwareBitmap = try {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)?.also {
                            hwBitmap.recycle()
                        }
                    } finally {
                        hardwareBuffer.close()
                    }

                    if (softwareBitmap == null || BitmapSecurityInspector.isLikelyBlankOrSecure(softwareBitmap)) {
                        softwareBitmap?.recycle()
                        ScreenshotRepository.storeMemoryBitmap(null)
                        ScreenshotRepository.lastCaptureBlockedBySecureFlag = true
                        onComplete(false)
                    } else {
                        ScreenshotRepository.storeMemoryBitmap(softwareBitmap)
                        ScreenshotRepository.lastCaptureBlockedBySecureFlag = false
                        onComplete(true)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    ScreenshotRepository.storeMemoryBitmap(null)
                    if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        ScreenshotRepository.lastCaptureBlockedBySecureFlag = true
                    }
                    onComplete(false)
                }
            }
        )
    }

    private fun launchOverlayActivity(hasPreCapturedState: Boolean) {
        val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AssistantOverlayActivity.EXTRA_FROM_ASSISTANT_SESSION, hasPreCapturedState)
        }
        startActivity(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentional no-op: service does not subscribe to any accessibility events.
    }

    override fun onInterrupt() {
        // Intentional no-op.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityButtonCallback?.let { cb ->
            runCatching {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(cb)
            }
        }
        accessibilityButtonCallback = null
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: LensAccessibilityService? = null
            private set
    }
}
