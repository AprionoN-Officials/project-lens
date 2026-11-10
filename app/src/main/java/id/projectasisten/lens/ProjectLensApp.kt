package id.projectasisten.lens

import android.app.Application
import com.google.android.material.color.DynamicColors
import id.projectasisten.lens.core.ScreenshotRepository
import id.projectasisten.lens.data.AppPreferences

class ProjectLensApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply Material 3 Dynamic Color (Monet) on Android 12+ if supported by device
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Apply saved Dark / Light / System theme mode
        val prefs = AppPreferences(this)
        prefs.applySavedThemeMode()

        // Ensure any leftover temporary cropped image in cacheDir is cleaned on cold start
        ScreenshotRepository.clearDiskCache(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            ScreenshotRepository.clearMemoryBitmap()
        }
    }
}
