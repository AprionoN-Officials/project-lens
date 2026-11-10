package id.projectasisten.lens.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import id.projectasisten.lens.core.OcrLayoutAssembler
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrLineBox
import kotlin.math.abs

/**
 * Interactive overlay for:
 * 1) Selecting OCR words, lines, or multi-line regions (Mode.SELECT_TEXT).
 * 2) Rendering translated text directly over original OCR line coordinates (Mode.TRANSLATE_OVERLAY).
 */
class OcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OverlayMode {
        SELECT_TEXT,
        TRANSLATE_OVERLAY
    }

    var mode: OverlayMode = OverlayMode.SELECT_TEXT
        set(value) {
            field = value
            invalidate()
        }

    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1
    private var lines: List<OcrLineBox> = emptyList()
    private val selectedWordIds = linkedSetOf<Int>()

    private var dragMarquee: RectF? = null
    private var downX = 0f
    private var downY = 0f
    private var isDraggingMarquee = false

    var onSelectedTextChanged: ((String) -> Unit)? = null

    private val unselectedWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 76, 141, 246)
        style = Paint.Style.FILL
    }

    private val unselectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 76, 141, 246)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val selectedWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 11, 87, 208)
        style = Paint.Style.FILL
    }

    private val marqueePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 11, 87, 208)
        style = Paint.Style.FILL
    }

    private val translateBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 28, 29, 33)
        style = Paint.Style.FILL
    }

    private val translateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }

    private val tmpRect = RectF()

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode != OverlayMode.SELECT_TEXT) return false
                val imgX = viewToImageX(e.x)
                val imgY = viewToImageY(e.y)

                for (line in lines) {
                    for (word in line.words) {
                        if (word.containsPoint(imgX, imgY)) {
                            if (selectedWordIds.contains(word.id)) {
                                selectedWordIds.remove(word.id)
                            } else {
                                selectedWordIds.add(word.id)
                            }
                            notifySelectionUpdated()
                            invalidate()
                            return true
                        }
                    }
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (mode != OverlayMode.SELECT_TEXT) return
                selectEntireLineAt(e.x, e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode != OverlayMode.SELECT_TEXT) return false
                return selectEntireLineAt(e.x, e.y)
            }
        }
    )

    private fun selectEntireLineAt(viewX: Float, viewY: Float): Boolean {
        val imgX = viewToImageX(viewX)
        val imgY = viewToImageY(viewY)
        for (line in lines) {
            if (imgX >= line.left - 12 && imgX <= line.right + 12 &&
                imgY >= line.top - 12 && imgY <= line.bottom + 12
            ) {
                val lineWordIds = line.words.map { it.id }
                val allAlreadySelected = lineWordIds.all { it in selectedWordIds }
                if (allAlreadySelected) {
                    selectedWordIds.removeAll(lineWordIds.toSet())
                } else {
                    selectedWordIds.addAll(lineWordIds)
                }
                notifySelectionUpdated()
                invalidate()
                return true
            }
        }
        return false
    }

    fun setOcrData(
        imageWidth: Int,
        imageHeight: Int,
        recognizedLines: List<OcrLineBox>,
        selectAllByDefault: Boolean = true
    ) {
        sourceImageWidth = imageWidth.coerceAtLeast(1)
        sourceImageHeight = imageHeight.coerceAtLeast(1)
        lines = recognizedLines
        selectedWordIds.clear()
        if (selectAllByDefault) {
            for (line in lines) {
                for (word in line.words) {
                    selectedWordIds.add(word.id)
                }
            }
        }
        notifySelectionUpdated()
        invalidate()
    }

    fun selectAllWords() {
        selectedWordIds.clear()
        for (line in lines) {
            for (word in line.words) {
                selectedWordIds.add(word.id)
            }
        }
        notifySelectionUpdated()
        invalidate()
    }

    fun getSelectedText(): String {
        return OcrLayoutAssembler.assembleSelectedText(lines, selectedWordIds)
    }

    private fun notifySelectionUpdated() {
        onSelectedTextChanged?.invoke(getSelectedText())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || width <= 0 || height <= 0) return

        val scaleX = width.toFloat() / sourceImageWidth.toFloat()
        val scaleY = height.toFloat() / sourceImageHeight.toFloat()

        if (mode == OverlayMode.SELECT_TEXT) {
            for (line in lines) {
                for (word in line.words) {
                    tmpRect.set(
                        word.left * scaleX,
                        word.top * scaleY,
                        word.right * scaleX,
                        word.bottom * scaleY
                    )
                    val isSelected = word.id in selectedWordIds
                    canvas.drawRoundRect(
                        tmpRect,
                        8f,
                        8f,
                        if (isSelected) selectedWordPaint else unselectedWordPaint
                    )
                    canvas.drawRoundRect(tmpRect, 8f, 8f, unselectedBorderPaint)
                }
            }
            dragMarquee?.let { marquee ->
                canvas.drawRect(marquee, marqueePaint)
                canvas.drawRect(marquee, unselectedBorderPaint)
            }
        } else {
            // Mode.TRANSLATE_OVERLAY: Draw translated text directly over OCR line coordinates
            for (line in lines) {
                val translated = line.translatedText?.takeIf { it.isNotBlank() } ?: line.text
                tmpRect.set(
                    line.left * scaleX,
                    line.top * scaleY,
                    line.right * scaleX,
                    line.bottom * scaleY
                )
                canvas.drawRoundRect(tmpRect, 10f, 10f, translateBoxPaint)

                val boxHeight = tmpRect.height().coerceAtLeast(18f)
                val boxWidth = (tmpRect.width() - 12f).coerceAtLeast(20f)
                translateTextPaint.textSize = (boxHeight * 0.68f).coerceIn(18f, 44f)

                // Shrink text size slightly if translated line is wider than original bounding box
                val measuredWidth = translateTextPaint.measureText(translated)
                if (measuredWidth > boxWidth && measuredWidth > 0f) {
                    val ratio = (boxWidth / measuredWidth).coerceIn(0.55f, 1f)
                    translateTextPaint.textSize *= ratio
                }

                val fontMetrics = translateTextPaint.fontMetrics
                val baselineY = tmpRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.save()
                canvas.clipRect(tmpRect)
                canvas.drawText(translated, tmpRect.left + 6f, baselineY, translateTextPaint)
                canvas.restore()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != OverlayMode.SELECT_TEXT) return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDraggingMarquee = false
                dragMarquee = null
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 20f || abs(event.y - downY) > 20f) {
                    isDraggingMarquee = true
                }
                if (isDraggingMarquee) {
                    val marquee = RectF(
                        minOf(downX, event.x),
                        minOf(downY, event.y),
                        maxOf(downX, event.x),
                        maxOf(downY, event.y)
                    )
                    dragMarquee = marquee

                    val imgLeft = viewToImageX(marquee.left)
                    val imgTop = viewToImageY(marquee.top)
                    val imgRight = viewToImageX(marquee.right)
                    val imgBottom = viewToImageY(marquee.bottom)

                    selectedWordIds.clear()
                    for (line in lines) {
                        for (word in line.words) {
                            if (word.intersectsRect(imgLeft, imgTop, imgRight, imgBottom)) {
                                selectedWordIds.add(word.id)
                            }
                        }
                    }
                    notifySelectionUpdated()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingMarquee = false
                dragMarquee = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun viewToImageX(viewX: Float): Float {
        if (width <= 0) return 0f
        return (viewX / width.toFloat()) * sourceImageWidth.toFloat()
    }

    private fun viewToImageY(viewY: Float): Float {
        if (height <= 0) return 0f
        return (viewY / height.toFloat()) * sourceImageHeight.toFloat()
    }
}
