package id.projectasisten.lens.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the transient in-memory screenshot Bitmap and temporary cropped image file.
 * Guarantees that screenshots are NEVER saved permanently by default and bitmaps are
 * recycled promptly to avoid memory leaks.
 */
object ScreenshotRepository {

    @Volatile
    private var currentScreenshot: Bitmap? = null

    @Volatile
    var lastCaptureBlockedBySecureFlag: Boolean = false

    @Synchronized
    fun storeMemoryBitmap(bitmap: Bitmap?) {
        val previous = currentScreenshot
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
        currentScreenshot = bitmap
    }

    @Synchronized
    fun getMemoryBitmap(): Bitmap? {
        val bmp = currentScreenshot
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    @Synchronized
    fun clearMemoryBitmap() {
        val bmp = currentScreenshot
        currentScreenshot = null
        if (bmp != null && !bmp.isRecycled) {
            bmp.recycle()
        }
        lastCaptureBlockedBySecureFlag = false
    }

    /**
     * Writes the cropped bitmap into an ephemeral cache file (`cacheDir/shared_crops/crop_latest.png`)
     * and returns its FileProvider content:// Uri for sharing to Google Lens or Android Sharesheet.
     */
    fun saveTemporaryCroppedBitmapForShare(context: Context, croppedBitmap: Bitmap): Uri {
        val cropDir = File(context.cacheDir, "shared_crops")
        if (!cropDir.exists()) {
            cropDir.mkdirs()
        }
        // Remove any prior temporary files first
        cropDir.listFiles()?.forEach { it.delete() }

        val targetFile = File(cropDir, "crop_latest.png")
        FileOutputStream(targetFile).use { out ->
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            out.flush()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )
    }

    /**
     * Deletes all temporary cropped files and returns the number of bytes freed.
     */
    fun clearDiskCache(context: Context): Long {
        var bytesFreed = 0L
        val cropDir = File(context.cacheDir, "shared_crops")
        if (cropDir.exists()) {
            cropDir.listFiles()?.forEach { file ->
                bytesFreed += file.length()
                file.delete()
            }
        }
        return bytesFreed
    }

    fun getDiskCacheSizeBytes(context: Context): Long {
        val cropDir = File(context.cacheDir, "shared_crops")
        if (!cropDir.exists()) return 0L
        return cropDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
