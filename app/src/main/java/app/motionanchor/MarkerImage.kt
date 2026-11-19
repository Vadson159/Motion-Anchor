// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

object MarkerImage {
    private const val FILE_NAME = "marker.png"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_SIDE = 512
    private val worker = Executors.newSingleThreadExecutor()

    fun exists(context: Context): Boolean = File(context.filesDir, FILE_NAME).isFile

    fun load(context: Context): Bitmap? =
        BitmapFactory.decodeFile(File(context.filesDir, FILE_NAME).absolutePath)

    fun import(context: Context, uri: Uri, finished: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val success = try {
                copyImage(app, uri)
                touchRevision(app, activateImage = true)
                true
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: SecurityException) {
                false
            }
            finished(success)
        }
    }

    fun remove(context: Context, finished: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val image = File(app.filesDir, FILE_NAME)
            val success = !image.exists() || image.delete()
            if (success) touchRevision(app, activateImage = false)
            finished(success)
        }
    }

    private fun copyImage(context: Context, uri: Uri) {
        val raw = File.createTempFile("marker-source", ".tmp", context.cacheDir)
        var output: File? = null
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException()
            stream.use { input ->
                raw.outputStream().use { destination ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_BYTES) throw IOException("Image too large")
                        destination.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (minOf(bounds.outWidth, bounds.outHeight) <= 0) throw IOException("Invalid image")
            var sample = 1
            while (longest / sample > MAX_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                raw.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: throw IOException("Unsupported image")
            val oriented = orient(bitmap, raw)
            try {
                output = File.createTempFile("marker-output", ".png", context.filesDir)
                FileOutputStream(output).use { stream ->
                    if (!oriented.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException()
                    stream.fd.sync()
                }
                if (!output.renameTo(File(context.filesDir, FILE_NAME))) throw IOException()
            } finally {
                if (oriented !== bitmap) oriented.recycle()
                bitmap.recycle()
            }
        } finally {
            raw.delete()
            output?.delete()
        }
    }

    private fun orient(bitmap: Bitmap, source: File): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            return bitmap
        }
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(-90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    }

    private fun touchRevision(context: Context, activateImage: Boolean) {
        val settings = AnchorSettings(context)
        val prefs = settings.preferences
        val editor = prefs.edit()
            .putInt(AnchorSettings.IMAGE_REVISION, prefs.getInt(AnchorSettings.IMAGE_REVISION, 0) + 1)
        if (activateImage || settings.appearance.style == CueStyle.Image) {
            editor.putString(AnchorSettings.STYLE, if (activateImage) CueStyle.Image.name else CueStyle.Dots.name)
        }
        editor.apply()
    }
}
