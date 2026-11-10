package id.projectasisten.lens.core

/**
 * Pure domain model and layout ordering logic for OCR text recognition, selection, and
 * coordinate-based translation overlay.
 */
object OcrLayoutAssembler {

    data class OcrWordBox(
        val id: Int,
        val lineId: Int,
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun containsPoint(x: Float, y: Float, slopPx: Float = 8f): Boolean {
            return x >= (left - slopPx) && x <= (right + slopPx) &&
                y >= (top - slopPx) && y <= (bottom + slopPx)
        }

        fun intersectsRect(rLeft: Float, rTop: Float, rRight: Float, rBottom: Float): Boolean {
            val minX = minOf(rLeft, rRight)
            val maxX = maxOf(rLeft, rRight)
            val minY = minOf(rTop, rBottom)
            val maxY = maxOf(rTop, rBottom)
            return right >= minX && left <= maxX && bottom >= minY && top <= maxY
        }
    }

    data class OcrLineBox(
        val id: Int,
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val words: List<OcrWordBox>,
        val translatedText: String? = null
    )

    /**
     * Sorts recognized OCR lines in natural reading order (top-to-bottom, left-to-right when two lines
     * are on the same visual row) and preserves line breaks when assembling selected text.
     */
    fun sortLinesReadingOrder(lines: List<OcrLineBox>): List<OcrLineBox> {
        return lines.sortedWith { a, b ->
            val aCenterY = (a.top + a.bottom) / 2
            val bCenterY = (b.top + b.bottom) / 2
            val avgHeight = ((a.bottom - a.top) + (b.bottom - b.top)).coerceAtLeast(2) / 2
            // If vertical centers are within 45% of line height, treat them as same row (left-to-right)
            if (kotlin.math.abs(aCenterY - bCenterY) <= avgHeight * 0.45f) {
                a.left.compareTo(b.left)
            } else {
                a.top.compareTo(b.top)
            }
        }
    }

    /**
     * Builds formatted text from the set of selected word IDs, preserving word order within each line
     * and newline separation between distinct lines.
     */
    fun assembleSelectedText(
        sortedLines: List<OcrLineBox>,
        selectedWordIds: Set<Int>
    ): String {
        if (selectedWordIds.isEmpty()) return ""
        val lineStrings = mutableListOf<String>()

        for (line in sortedLines) {
            val selectedWordsInLine = line.words
                .filter { it.id in selectedWordIds }
                .sortedBy { it.left }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }

            if (selectedWordsInLine.isNotEmpty()) {
                lineStrings.add(selectedWordsInLine.joinToString(" "))
            }
        }
        return lineStrings.joinToString("\n")
    }
}
