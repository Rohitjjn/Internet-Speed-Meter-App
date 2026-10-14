package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.services.SpeedIconRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeedIconRendererTest {
    @Test
    fun repeatedUpdatesNeverAccumulateOrMutatePublishedGlyphs() {
        val renderer = SpeedIconRenderer()
        val first = renderer.render("888", "KB/s")
        val pixelsBefore = pixels(first)
        repeat(100) { renderer.render(it.toString(), if (it % 2 == 0) "MB/s" else "Kbps") }
        val final = renderer.render("1", "Mbps")
        assertArrayEquals(pixelsBefore, pixels(first))
        assertArrayEquals(pixels(SpeedIconRenderer().render("1", "Mbps")), pixels(final))
        assertNotSame(first, final)
    }

    @Test
    fun sameLabelIsCachedButAUnitChangeRedraws() {
        val renderer = SpeedIconRenderer()
        val first = renderer.render("12.4", "MB/s")
        assertSame(first, renderer.render("12.4", "MB/s"))
        assertNotSame(first, renderer.render("12.4", "Mbps"))
    }

    @Test
    fun glyphsHaveTransparentMarginsAndBothRowsFitIncludingLongValues() {
        for (value in listOf("0", "842", "104.9", "1024", "9999.9")) {
            for (unit in listOf("KB/s", "MB/s", "Kbps", "Mbps")) {
                val bitmap = SpeedIconRenderer().render(value, unit)
                val size = bitmap.width
                val all = pixels(bitmap)
                val visible = all.count { Color.alpha(it) > 0 }
                assertTrue("must render real glyphs", visible > 100)
                assertTrue("not an opaque box", visible < all.size / 2)
                for (i in 0 until size) {
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, i)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(size - 1, i)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(i, 0)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(i, size - 1)))
                }
                assertTrue((0 until 57).any { y -> (0 until size).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 } })
                assertTrue((61 until size).any { y -> (0 until size).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 } })
                all.filter { Color.alpha(it) > 0 }.forEach {
                    assertEquals(255, Color.red(it))
                    assertEquals(255, Color.green(it))
                    assertEquals(255, Color.blue(it))
                }
            }
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
