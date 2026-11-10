package id.projectasisten.lens.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import id.projectasisten.lens.core.BitmapSecurityInspector
import id.projectasisten.lens.core.ScreenshotRepository
import id.projectasisten.lens.core.SensitiveAppGuard
import id.projectasisten.lens.data.AppPreferences
import id.projectasisten.lens.ui.overlay.AssistantOverlayActivity

/**
 * Handles the system assistant trigger (corner swipe gesture, home long-press, or power button
 * depending on device ROM) and receives the current screen Bitmap directly from the OS via
 * [onHandleScreenshot] without needing a MediaProjection prompt.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private var detectedSensitiveForegroundApp = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        detectedSensitiveForegroundApp = false

        val prefs = AppPreferences(context)
        if (!prefs.isShortcutEnabled) {
            hide()
            return
        }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val foregroundPkg = state.assistStructure?.activityComponent?.packageName
        if (SensitiveAppGuard.isSensitivePackage(foregroundPkg)) {
            detectedSensitiveForegroundApp = true
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)

        val prefs = AppPreferences(context)
        if (!prefs.isShortcutEnabled) {
            hide()
            return
        }

        if (detectedSensitiveForegroundApp ||
            screenshot == null ||
            BitmapSecurityInspector.isLikelyBlankOrSecure(screenshot)
        ) {
            ScreenshotRepository.storeMemoryBitmap(null)
            ScreenshotRepository.lastCaptureBlockedBySecureFlag = true
        } else {
            val safeCopy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            ScreenshotRepository.storeMemoryBitmap(safeCopy)
            ScreenshotRepository.lastCaptureBlockedBySecureFlag = false
        }

        val overlayIntent = Intent(context, AssistantOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AssistantOverlayActivity.EXTRA_FROM_ASSISTANT_SESSION, true)
        }
        context.startActivity(overlayIntent)
        hide()
    }
}
