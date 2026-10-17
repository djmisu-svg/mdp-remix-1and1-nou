package ro.mdp.remix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android vector/adaptive rendering checks; these do not claim physical One UI testing. */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val glyphs = listOf(
        Glyph("M", 26f, 44f),
        Glyph("D", 45f, 63f),
        Glyph("P", 64f, 82f),
    )

    @Test
    fun legacyFallbackContainsThreeCompleteLettersAndReadableP() {
        assertEquals(R.mipmap.ic_launcher, target.applicationInfo.icon)
        val icon = render(drawable(R.drawable.ic_launcher), 108)
        assertEquals(Color.rgb(9, 9, 11), icon.getPixel(0, 0))
        assertLettersPresent(icon, expanded = false)
        assertTrue("M must have its left stem", isWhite(icon.getPixel(29, 55)))
        assertTrue("M must have its central diagonal", isWhite(icon.getPixel(35, 54)))
        assertTrue("D must have its enclosing right stroke", isWhite(icon.getPixel(60, 54)))
        assertFalse("D counter must remain open", isWhite(icon.getPixel(53, 54)))
        assertTrue("P must have its complete lower stem", isWhite(icon.getPixel(67, 64)))
        assertTrue("P upper bowl must reach the right side", isWhite(icon.getPixel(79, 50)))
        assertFalse("P counter must remain open", isWhite(icon.getPixel(73, 49)))
        assertFalse("P must have space to the right of its lower stem", isWhite(icon.getPixel(75, 63)))
        assertWhiteLettersWithinSafeCircle(icon)
        save("launcher-legacy", render(drawable(R.drawable.ic_launcher)))
        save("launcher-legacy-round", render(drawable(R.mipmap.ic_launcher_round)))
    }

    @Test
    fun foregroundArtworkKeepsEveryLetterInsideAdaptiveSafeCircle() {
        val foreground = render(drawable(R.drawable.ic_launcher_foreground), 108)
        assertLettersPresent(foreground, expanded = false)
        assertForegroundWithinSafeCircle(foreground)
        assertEquals("Foreground must remain transparent outside its artwork", 0, Color.alpha(foreground.getPixel(0, 0)))
        save("launcher-foreground-safe-area", render(drawable(R.drawable.ic_launcher_foreground)))
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun adaptiveNormalAndRoundIconsPreserveMDPUnderCircleAndSquircleMasks() {
        for ((name, resource) in listOf("adaptive" to R.mipmap.ic_launcher, "round" to R.mipmap.ic_launcher_round)) {
            val adaptive = drawable(resource)
            assertTrue("$name must resolve to the API 26 adaptive resource", adaptive is AdaptiveIconDrawable)
            val rendered = render(adaptive)
            val expected = render(drawable(R.drawable.ic_launcher_foreground), expanded = true)
            assertExpandedLettersPreserved("$name native adaptive", expected, rendered)
            assertMasksPreserveLetters(name, rendered)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun themedMonochromeVariantHasIdenticalCompleteMDPGeometry() {
        for ((name, resource) in listOf("themed" to R.mipmap.ic_launcher, "themed-round" to R.mipmap.ic_launcher_round)) {
            val adaptive = drawable(resource) as AdaptiveIconDrawable
            val monochrome = requireNotNull(adaptive.monochrome) { "$name must declare the Android 13 monochrome layer" }
            val rawMono = render(monochrome, 108)
            val rawForeground = render(drawable(R.drawable.ic_launcher_foreground), 108)
            assertLettersPresent(rawMono, expanded = false)
            assertForegroundWithinSafeCircle(rawMono)
            for (y in 0 until 108) for (x in 0 until 108) {
                assertEquals("Themed geometry differs at ($x,$y)", Color.alpha(rawForeground.getPixel(x, y)), Color.alpha(rawMono.getPixel(x, y)))
            }
            // Launchers tint this layer. Render that supported path with neutral
            // white on black, including the framework's adaptive layer insets.
            monochrome.mutate().setTint(Color.WHITE)
            val themed = AdaptiveIconDrawable(ColorDrawable(Color.rgb(9, 9, 11)), monochrome)
            val rendered = render(themed)
            val expected = render(drawable(R.drawable.ic_launcher_monochrome), expanded = true)
            assertExpandedLettersPreserved(name, expected, rendered)
            assertMasksPreserveLetters(name, rendered)
        }
    }

    private fun drawable(resource: Int): Drawable = requireNotNull(target.getDrawable(resource))

    private fun render(drawable: Drawable, size: Int = 324, expanded: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // AdaptiveIconDrawable expands a 108 dp foreground by 25% on each side.
        // The raw 33 dp safe-circle assertion must therefore precede this step.
        val inset = if (expanded) size / 4 else 0
        drawable.setBounds(-inset, -inset, size + inset, size + inset)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertLettersPresent(bitmap: Bitmap, expanded: Boolean) {
        for (glyph in glyphs) {
            assertTrue("${glyph.name} letter region is empty", whitePixels(bitmap, glyphRegion(glyph, bitmap.width, expanded)) > bitmap.width / 3)
        }
    }

    private fun assertWhiteLettersWithinSafeCircle(bitmap: Bitmap) {
        for (y in 0 until 108) for (x in 0 until 108) {
            if (isWhite(bitmap.getPixel(x, y))) assertInSafeCircle(x, y)
        }
    }

    private fun assertForegroundWithinSafeCircle(bitmap: Bitmap) {
        for (y in 0 until 108) for (x in 0 until 108) {
            if (Color.alpha(bitmap.getPixel(x, y)) >= 128) assertInSafeCircle(x, y)
        }
    }

    private fun assertInSafeCircle(x: Int, y: Int) {
        val dx = x + .5f - 54f
        val dy = y + .5f - 54f
        assertTrue("Artwork escapes the central 66 dp safe circle at ($x,$y)", dx * dx + dy * dy <= 33f * 33f)
    }

    private fun assertExpandedLettersPreserved(name: String, expected: Bitmap, actual: Bitmap) {
        assertLettersPresent(actual, expanded = true)
        for (glyph in glyphs) {
            val region = glyphRegion(glyph, actual.width, expanded = true)
            val expectedPixels = whitePixels(expected, region)
            val actualPixels = whitePixels(actual, region)
            assertTrue("$name clipped ${glyph.name}: $actualPixels / $expectedPixels", actualPixels >= expectedPixels * .97f)
        }
    }

    private fun assertMasksPreserveLetters(name: String, source: Bitmap) {
        save("launcher-$name-native", source)
        val center = source.width / 2f
        val circle = Path().apply { addCircle(center, center, center, Path.Direction.CW) }
        val squircle = Path().apply {
            val radius = source.width * .48f
            for (step in 0..240) {
                val angle = 2.0 * PI * step / 240
                val cosine = cos(angle).toFloat()
                val sine = sin(angle).toFloat()
                val x = center + radius * sign(cosine) * sqrt(abs(cosine))
                val y = center + radius * sign(sine) * sqrt(abs(sine))
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        for ((maskName, mask) in listOf("circle" to circle, "squircle" to squircle)) {
            val clipped = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(clipped).apply { clipPath(mask); drawBitmap(source, 0f, 0f, null) }
            for (glyph in glyphs) {
                val region = glyphRegion(glyph, source.width, expanded = true)
                assertEquals("$name $maskName clipped ${glyph.name}", whitePixels(source, region), whitePixels(clipped, region))
            }
            save("launcher-$name-$maskName", clipped)
        }
    }

    private fun glyphRegion(glyph: Glyph, size: Int, expanded: Boolean): Rect {
        val scale = size / 108f * if (expanded) 1.5f else 1f
        val inset = if (expanded) -size / 4f else 0f
        return Rect(
            floor(glyph.left * scale + inset).toInt().coerceAtLeast(0),
            floor(40f * scale + inset).toInt().coerceAtLeast(0),
            ceil(glyph.right * scale + inset).toInt().coerceAtMost(size),
            ceil(68f * scale + inset).toInt().coerceAtMost(size),
        )
    }

    private fun whitePixels(bitmap: Bitmap, region: Rect): Int {
        var count = 0
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if (isWhite(bitmap.getPixel(x, y))) count++
        }
        return count
    }

    private fun isWhite(color: Int): Boolean = Color.alpha(color) >= 200 && Color.red(color) >= 220 && Color.green(color) >= 220 && Color.blue(color) >= 220

    private fun save(name: String, bitmap: Bitmap) {
        val directory = File(requireNotNull(target.getExternalFilesDir(null)), "screenshots")
        assertTrue("Screenshot directory must be writable", directory.isDirectory || directory.mkdirs())
        FileOutputStream(File(directory, "$name.png")).use { stream ->
            assertTrue("PNG compression failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private data class Glyph(val name: String, val left: Float, val right: Float)
}
