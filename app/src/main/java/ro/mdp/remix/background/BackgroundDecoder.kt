package ro.mdp.remix.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

internal sealed interface DecodedBackground {
    data class Still(val bitmap: Bitmap) : DecodedBackground
    data class Animated(val drawable: Drawable, val encodedBytes: ByteArray) : DecodedBackground
    @Suppress("DEPRECATION")
    data class Legacy(val movie: Movie) : DecodedBackground
}

/** All reads and decoding happen on Dispatchers.IO; encoded and decoded sizes are bounded. */
internal object BackgroundDecoder {
    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    private const val MAX_GIF_BYTES = 16 * 1024 * 1024
    private const val MAX_LEGACY_GIF_BYTES = 8 * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private const val MAX_LEGACY_EDGE = 1_024

    fun decode(context: Context, source: String, kind: BackgroundKind, maximumEdge: Int): DecodedBackground {
        val gif = kind == BackgroundKind.GIF || kind == BackgroundKind.ORIGINAL
        val limit = if (!gif) MAX_IMAGE_BYTES else if (Build.VERSION.SDK_INT >= 28) MAX_GIF_BYTES else MAX_LEGACY_GIF_BYTES
        val bytes = open(context, source).use { readBounded(it, limit) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) {
            throw IOException("This background image has invalid or excessive dimensions.")
        }
        val edge = maximumEdge.coerceIn(256, 2_048)
        if (gif) {
            return if (Build.VERSION.SDK_INT >= 28) {
                decodeAnimated(bytes, width, height, edge)
            } else {
                decodeLegacy(bytes, width, height)
            }
        }
        var sample = 1
        while (max(width / sample, height / sample) > edge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("This background image could not be decoded.")
        try {
            val orientation = ByteArrayInputStream(bytes).use { stream ->
                runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (matrix.isIdentity) return DecodedBackground.Still(bitmap)
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            return DecodedBackground.Still(oriented)
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    @RequiresApi(28)
    private fun decodeAnimated(bytes: ByteArray, width: Int, height: Int, maximumEdge: Int): DecodedBackground {
        val ratio = minOf(1.0, maximumEdge.toDouble() / max(width, height))
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
        }
        return DecodedBackground.Animated(drawable, bytes)
    }

    @Suppress("DEPRECATION")
    private fun decodeLegacy(bytes: ByteArray, width: Int, height: Int): DecodedBackground {
        if (width > MAX_LEGACY_EDGE || height > MAX_LEGACY_EDGE || width.toLong() * height > 1_048_576L) {
            throw IOException("This GIF is too large for this Android version. Choose one up to 1024 pixels per side.")
        }
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("This GIF could not be decoded.")
        return DecodedBackground.Legacy(movie)
    }

    private fun open(context: Context, source: String): InputStream =
        if (source.startsWith("asset:///")) {
            context.assets.open(source.removePrefix("asset:///"))
        } else {
            context.contentResolver.openInputStream(android.net.Uri.parse(source))
                ?: throw IOException("This background is no longer available. Select it again.")
        }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("This background file is too large. Choose a smaller image or GIF.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
