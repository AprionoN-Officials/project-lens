package id.projectasisten.lens.core

import android.graphics.Bitmap

/**
 * Detects blank or all-black screenshots produced when Android blocks capture of a window
 * protected by WindowManager.LayoutParams.FLAG_SECURE (such as banking apps, PIN screens, or DRM video).
 */
object BitmapSecurityInspector {

    /**
     * Samples a grid of ARGB pixels (e.g., 12x12 = 144 points) across the interior of the screenshot.
     * Returns true if the image is null, empty, or consists entirely of black/transparent pixels
     * typical of a FLAG_SECURE blocked capture.
     */
    fun isLikelyBlankOrSecure(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 4 || bitmap.height < 4) {
            return true
        }
        val samples = mutableListOf<Int>()
        val gridSteps = 12
        val marginX = (bitmap.width * 0.08f).toInt().coerceAtLeast(1)
        val marginY = (bitmap.height * 0.08f).toInt().coerceAtLeast(1)
        val usableWidth = (bitmap.width - 2 * marginX).coerceAtLeast(1)
        val usableHeight = (bitmap.height - 2 * marginY).coerceAtLeast(1)

        for (iy in 0 until gridSteps) {
            val y = marginY + (iy * usableHeight) / gridSteps
            for (ix in 0 until gridSteps) {
                val x = marginX + (ix * usableWidth) / gridSteps
                samples.add(bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)))
            }
        }
        return areSampledPixelsAllBlankOrBlack(samples)
    }

    /**
     * Pure helper testable on JVM without android.graphics.Bitmap:
     * Checks if every sampled ARGB pixel is completely transparent or near-pure black (R,G,B <= 3).
     */
    fun areSampledPixelsAllBlankOrBlack(argbSamples: List<Int>): Boolean {
        if (argbSamples.isEmpty()) return true
        for (pixel in argbSamples) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha <= 10) continue // transparent
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red > 3 || green > 3 || blue > 3) {
                return false
            }
        }
        return true
    }
}
