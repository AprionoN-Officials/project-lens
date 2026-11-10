package id.projectasisten.lens.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import id.projectasisten.lens.core.CropMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive overlay for selecting a rectangular screen region in Scan mode.
 * Supports dragging corners to resize, dragging inside to move, or dragging anywhere to draw a new box.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C8DF6")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val selectionRect = RectF()
    private var initializedDefaultRect = false

    private enum class DragMode {
        NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, NEW_SELECTION
    }

    private var dragMode = DragMode.NONE
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var onCropRectChanged: (() -> Unit)? = null

    fun resetDefaultSelection() {
        initializedDefaultRect = false
        if (width > 0 && height > 0) {
            ensureDefaultSelection()
            invalidate()
        }
    }

    private fun ensureDefaultSelection() {
        if (initializedDefaultRect || width <= 0 || height <= 0) return
        val marginX = width * 0.12f
        val topY = height * 0.18f
        val bottomY = height * 0.62f
        selectionRect.set(marginX, topY, width - marginX, bottomY)
        initializedDefaultRect = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureDefaultSelection()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureDefaultSelection()

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw 4 dimmed scrim regions around selectionRect
        canvas.drawRect(0f, 0f, w, selectionRect.top, scrimPaint)
        canvas.drawRect(0f, selectionRect.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, selectionRect.top, selectionRect.left, selectionRect.bottom, scrimPaint)
        canvas.drawRect(selectionRect.right, selectionRect.top, w, selectionRect.bottom, scrimPaint)

        // Draw selection border
        canvas.drawRoundRect(selectionRect, 20f, 20f, borderPaint)

        // Draw 4 corner handles
        val handleRadius = 14f
        canvas.drawCircle(selectionRect.left, selectionRect.top, handleRadius, cornerPaint)
        canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, cornerPaint)
        canvas.drawCircle(selectionRect.left, selectionRect.bottom, handleRadius, cornerPaint)
        canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        val handleSlop = 56f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = x
                startTouchY = y
                lastTouchX = x
                lastTouchY = y

                dragMode = when {
                    isNear(x, y, selectionRect.left, selectionRect.top, handleSlop) -> DragMode.RESIZE_TL
                    isNear(x, y, selectionRect.right, selectionRect.top, handleSlop) -> DragMode.RESIZE_TR
                    isNear(x, y, selectionRect.left, selectionRect.bottom, handleSlop) -> DragMode.RESIZE_BL
                    isNear(x, y, selectionRect.right, selectionRect.bottom, handleSlop) -> DragMode.RESIZE_BR
                    selectionRect.contains(x, y) -> DragMode.MOVE
                    else -> {
                        selectionRect.set(x, y, x, y)
                        DragMode.NEW_SELECTION
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                lastTouchX = x
                lastTouchY = y
                val minBox = 64f

                when (dragMode) {
                    DragMode.MOVE -> {
                        val boxW = selectionRect.width()
                        val boxH = selectionRect.height()
                        val newLeft = (selectionRect.left + dx).coerceIn(0f, width - boxW)
                        val newTop = (selectionRect.top + dy).coerceIn(0f, height - boxH)
                        selectionRect.set(newLeft, newTop, newLeft + boxW, newTop + boxH)
                    }
                    DragMode.RESIZE_TL -> {
                        selectionRect.left = min(x, selectionRect.right - minBox)
                        selectionRect.top = min(y, selectionRect.bottom - minBox)
                    }
                    DragMode.RESIZE_TR -> {
                        selectionRect.right = max(x, selectionRect.left + minBox)
                        selectionRect.top = min(y, selectionRect.bottom - minBox)
                    }
                    DragMode.RESIZE_BL -> {
                        selectionRect.left = min(x, selectionRect.right - minBox)
                        selectionRect.bottom = max(y, selectionRect.top + minBox)
                    }
                    DragMode.RESIZE_BR -> {
                        selectionRect.right = max(x, selectionRect.left + minBox)
                        selectionRect.bottom = max(y, selectionRect.top + minBox)
                    }
                    DragMode.NEW_SELECTION -> {
                        val clamped = CropMath.normalizeAndClamp(
                            startTouchX,
                            startTouchY,
                            x,
                            y,
                            width,
                            height,
                            minBox.toInt()
                        )
                        selectionRect.set(
                            clamped.left.toFloat(),
                            clamped.top.toFloat(),
                            clamped.right.toFloat(),
                            clamped.bottom.toFloat()
                        )
                    }
                    DragMode.NONE -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                onCropRectChanged?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isNear(x: Float, y: Float, targetX: Float, targetY: Float, radius: Float): Boolean {
        return abs(x - targetX) <= radius && abs(y - targetY) <= radius
    }

    /**
     * Crops [sourceBitmap] to the currently selected rectangle safely clamped to bitmap bounds.
     */
    fun cropBitmap(sourceBitmap: Bitmap): Bitmap {
        ensureDefaultSelection()
        val viewRect = CropMath.PixelRect(
            left = selectionRect.left.toInt(),
            top = selectionRect.top.toInt(),
            right = selectionRect.right.toInt(),
            bottom = selectionRect.bottom.toInt()
        )
        val bmpRect = CropMath.mapViewRectToBitmapRect(
            viewRect = viewRect,
            viewWidth = width.coerceAtLeast(1),
            viewHeight = height.coerceAtLeast(1),
            bitmapWidth = sourceBitmap.width,
            bitmapHeight = sourceBitmap.height,
            minSizePx = 24
        )
        return Bitmap.createBitmap(
            sourceBitmap,
            bmpRect.left,
            bmpRect.top,
            bmpRect.width.coerceAtLeast(1),
            bmpRect.height.coerceAtLeast(1)
        )
    }
}
