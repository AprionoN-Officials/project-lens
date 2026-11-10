package id.projectasisten.lens.feature.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.projectasisten.lens.core.OcrLayoutAssembler
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrLineBox
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrWordBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class OcrResultData(
    val fullText: String,
    val lines: List<OcrLineBox>
)

/**
 * On-demand OCR processor backed by Google ML Kit Text Recognition (Latin script: Indonesian, English, etc.).
 * Instantiates the recognizer only when requested and closes native resources immediately on session teardown.
 */
class OcrEngine {

    private var recognizer: TextRecognizer? = null

    private fun getOrCreateRecognizer(): TextRecognizer {
        val existing = recognizer
        if (existing != null) return existing
        val created = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer = created
        return created
    }

    suspend fun recognizeBitmap(bitmap: Bitmap): OcrResultData = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = getOrCreateRecognizer().process(inputImage).await()

        var nextLineId = 1
        var nextWordId = 1
        val extractedLines = mutableListOf<OcrLineBox>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineRect = line.boundingBox ?: continue
                val lineId = nextLineId++
                val words = mutableListOf<OcrWordBox>()

                for (element in line.elements) {
                    val wordRect = element.boundingBox ?: continue
                    val wordText = element.text.trim()
                    if (wordText.isNotEmpty()) {
                        words.add(
                            OcrWordBox(
                                id = nextWordId++,
                                lineId = lineId,
                                text = wordText,
                                left = wordRect.left,
                                top = wordRect.top,
                                right = wordRect.right,
                                bottom = wordRect.bottom
                            )
                        )
                    }
                }

                if (words.isNotEmpty()) {
                    extractedLines.add(
                        OcrLineBox(
                            id = lineId,
                            text = line.text.trim(),
                            left = lineRect.left,
                            top = lineRect.top,
                            right = lineRect.right,
                            bottom = lineRect.bottom,
                            words = words
                        )
                    )
                }
            }
        }

        val sortedLines = OcrLayoutAssembler.sortLinesReadingOrder(extractedLines)
        val allWordIds = sortedLines.flatMap { it.words }.map { it.id }.toSet()
        val assembledFullText = OcrLayoutAssembler.assembleSelectedText(sortedLines, allWordIds)

        OcrResultData(
            fullText = assembledFullText,
            lines = sortedLines
        )
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
