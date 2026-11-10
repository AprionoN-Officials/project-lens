package id.projectasisten.lens.core

import id.projectasisten.lens.core.OcrLayoutAssembler.OcrLineBox
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrWordBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun `normalizeAndClamp clamps negative and overflow coordinates safely`() {
        val rect = CropMath.normalizeAndClamp(
            x1 = -50f,
            y1 = 1200f,
            x2 = 300f,
            y2 = 200f,
            maxWidth = 1080,
            maxHeight = 2400,
            minSizePx = 24
        )
        assertEquals(0, rect.left)
        assertEquals(200, rect.top)
        assertEquals(300, rect.right)
        assertEquals(1200, rect.bottom)
    }

    @Test
    fun `normalizeAndClamp enforces minimum size on tiny tap`() {
        val rect = CropMath.normalizeAndClamp(
            x1 = 500f,
            y1 = 500f,
            x2 = 502f,
            y2 = 502f,
            maxWidth = 1080,
            maxHeight = 2400,
            minSizePx = 40
        )
        assertEquals(40, rect.width)
        assertEquals(40, rect.height)
    }

    @Test
    fun `sortLinesReadingOrder sorts multi-line Indonesian and English text accurately`() {
        val lineBottom = OcrLineBox(
            id = 1,
            text = "Baris kedua di bawah",
            left = 40,
            top = 300,
            right = 600,
            bottom = 350,
            words = listOf(
                OcrWordBox(1, 1, "Baris", 40, 300, 150, 350),
                OcrWordBox(2, 1, "kedua", 160, 300, 300, 350)
            )
        )
        val lineTop = OcrLineBox(
            id = 2,
            text = "Hello World",
            left = 40,
            top = 100,
            right = 400,
            bottom = 150,
            words = listOf(
                OcrWordBox(3, 2, "Hello", 40, 100, 180, 150),
                OcrWordBox(4, 2, "World", 200, 100, 400, 150)
            )
        )

        val sorted = OcrLayoutAssembler.sortLinesReadingOrder(listOf(lineBottom, lineTop))
        assertEquals(2, sorted[0].id)
        assertEquals(1, sorted[1].id)

        val assembled = OcrLayoutAssembler.assembleSelectedText(sorted, setOf(1, 2, 3, 4))
        assertEquals("Hello World\nBaris kedua", assembled)
    }

    @Test
    fun `areSampledPixelsAllBlankOrBlack detects FLAG_SECURE black frames`() {
        val blackPixels = List(144) { 0xFF000000.toInt() }
        assertTrue(BitmapSecurityInspector.areSampledPixelsAllBlankOrBlack(blackPixels))

        val normalPixels = blackPixels.toMutableList().apply {
            this[50] = 0xFF0B57D0.toInt()
        }
        assertFalse(BitmapSecurityInspector.areSampledPixelsAllBlankOrBlack(normalPixels))
    }

    @Test
    fun `SensitiveAppGuard identifies banking and wallet packages`() {
        assertTrue(SensitiveAppGuard.isSensitivePackage("com.bca"))
        assertTrue(SensitiveAppGuard.isSensitivePackage("id.co.bri.brimo"))
        assertTrue(SensitiveAppGuard.isSensitivePackage("id.dana"))
        assertFalse(SensitiveAppGuard.isSensitivePackage("com.android.chrome"))
        assertFalse(SensitiveAppGuard.isSensitivePackage("org.wikipedia"))
    }
}
