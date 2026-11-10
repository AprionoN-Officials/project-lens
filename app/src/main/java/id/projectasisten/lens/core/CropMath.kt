package id.projectasisten.lens.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure coordinate transformation and clamping utilities for mapping view-space selection boxes
 * onto Bitmap pixel coordinates without out-of-bounds crashes.
 */
object CropMath {

    data class PixelRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
    }

    /**
     * Normalizes any two touch points (x1, y1) and (x2, y2) into a clamped PixelRect inside
     * [0..maxWidth, 0..maxHeight], enforcing [minSizePx] so Bitmap.createBitmap never fails.
     */
    fun normalizeAndClamp(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        maxWidth: Int,
        maxHeight: Int,
        minSizePx: Int = 24
    ): PixelRect {
        if (maxWidth <= 0 || maxHeight <= 0) {
            return PixelRect(0, 0, 0, 0)
        }
        val safeMinSize = min(minSizePx, min(maxWidth, maxHeight))

        var left = min(x1, x2).roundToInt().coerceIn(0, maxWidth)
        var right = max(x1, x2).roundToInt().coerceIn(0, maxWidth)
        var top = min(y1, y2).roundToInt().coerceIn(0, maxHeight)
        var bottom = max(y1, y2).roundToInt().coerceIn(0, maxHeight)

        if (right - left < safeMinSize) {
            val center = (left + right) / 2
            left = (center - safeMinSize / 2).coerceIn(0, maxWidth - safeMinSize)
            right = left + safeMinSize
        }

        if (bottom - top < safeMinSize) {
            val center = (top + bottom) / 2
            top = (center - safeMinSize / 2).coerceIn(0, maxHeight - safeMinSize)
            bottom = top + safeMinSize
        }

        return PixelRect(left, top, right, bottom)
    }

    /**
     * Maps a selection rectangle from View coordinates [viewWidth x viewHeight]
     * to Bitmap coordinates [bitmapWidth x bitmapHeight].
     */
    fun mapViewRectToBitmapRect(
        viewRect: PixelRect,
        viewWidth: Int,
        viewHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        minSizePx: Int = 24
    ): PixelRect {
        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return PixelRect(0, 0, 0, 0)
        }
        val scaleX = bitmapWidth.toFloat() / viewWidth.toFloat()
        val scaleY = bitmapHeight.toFloat() / viewHeight.toFloat()

        return normalizeAndClamp(
            x1 = viewRect.left * scaleX,
            y1 = viewRect.top * scaleY,
            x2 = viewRect.right * scaleX,
            y2 = viewRect.bottom * scaleY,
            maxWidth = bitmapWidth,
            maxHeight = bitmapHeight,
            minSizePx = minSizePx
        )
    }
}
