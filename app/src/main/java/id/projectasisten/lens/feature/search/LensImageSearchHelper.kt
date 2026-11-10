package id.projectasisten.lens.feature.search

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import id.projectasisten.lens.R
import id.projectasisten.lens.core.ScreenshotRepository

/**
 * Handles sending the user's cropped screen region to Google Lens (if an official compatible
 * ACTION_SEND image receiver is installed) or falling back cleanly to the Android Sharesheet.
 *
 * Strict Compliance:
 * - Never uses undocumented internal APIs or reflection.
 * - Never claims Google Lens succeeded if the target app is absent.
 * - Always provides a reliable Android Sharesheet fallback.
 */
object LensImageSearchHelper {

    private val OFFICIAL_LENS_PACKAGES = listOf(
        "com.google.ar.lens",
        "com.google.android.googlequicksearchbox"
    )

    enum class LaunchOutcome {
        LAUNCHED_GOOGLE_LENS,
        FALLBACK_SHARESHEET
    }

    /**
     * Attempts to launch Google Lens image search via official ACTION_SEND ("image/png") resolution.
     * If Google Lens is not installed or cannot handle ACTION_SEND, falls back to Android Sharesheet.
     */
    fun searchWithGoogleLensOrFallback(
        context: Context,
        croppedBitmap: Bitmap
    ): LaunchOutcome {
        val imageUri = ScreenshotRepository.saveTemporaryCroppedBitmapForShare(context, croppedBitmap)
        val lensComponent = resolveCompatibleLensShareComponent(context.packageManager, imageUri)

        if (lensComponent != null) {
            val lensIntent = createBaseShareIntent(imageUri).apply {
                component = lensComponent
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                context.startActivity(lensIntent)
                true
            }.getOrDefault(false)

            if (launched) {
                return LaunchOutcome.LAUNCHED_GOOGLE_LENS
            }
        }

        launchAndroidSharesheet(context, imageUri)
        return LaunchOutcome.FALLBACK_SHARESHEET
    }

    /**
     * Explicitly opens the official Android Sharesheet chooser for the cropped image.
     */
    fun shareViaAndroidSharesheet(
        context: Context,
        croppedBitmap: Bitmap
    ) {
        val imageUri = ScreenshotRepository.saveTemporaryCroppedBitmapForShare(context, croppedBitmap)
        launchAndroidSharesheet(context, imageUri)
    }

    private fun launchAndroidSharesheet(context: Context, imageUri: Uri) {
        val baseIntent = createBaseShareIntent(imageUri)
        val chooser = Intent.createChooser(
            baseIntent,
            context.getString(R.string.action_share_fallback)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun createBaseShareIntent(imageUri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = ClipData.newRawUri("Cropped Screen", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun resolveCompatibleLensShareComponent(
        packageManager: PackageManager,
        sampleUri: Uri
    ): ComponentName? {
        val probeIntent = createBaseShareIntent(sampleUri)
        val resolvedInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                probeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        // Prioritize standalone Google Lens (com.google.ar.lens), then Lens activity in Google App
        for (pkg in OFFICIAL_LENS_PACKAGES) {
            val match = resolvedInfos.firstOrNull { info ->
                val activityInfo = info.activityInfo ?: return@firstOrNull false
                activityInfo.packageName == pkg &&
                    (pkg == "com.google.ar.lens" ||
                        activityInfo.name.contains("lens", ignoreCase = true) ||
                        activityInfo.name.contains("Send", ignoreCase = true))
            } ?: resolvedInfos.firstOrNull { it.activityInfo?.packageName == pkg }

            if (match?.activityInfo != null) {
                return ComponentName(match.activityInfo.packageName, match.activityInfo.name)
            }
        }
        return null
    }
}
