package app.codua2a

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ImageAttachment {
    fun read(resolver: ContentResolver, uri: Uri): JSONObject {
        val raw = ByteArrayOutputStream()
        resolver.openInputStream(uri).use { input ->
            checkNotNull(input)
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                check(raw.size() + n <= 16 * 1024 * 1024) { "图片超过 16 MiB" }
                raw.write(buffer, 0, n)
            }
        }
        val bytes = raw.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1600) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        options.inJustDecodeBounds = false
        var bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        try {
            val exif = try { ExifInterface(bytes.inputStream()) } catch (_: Exception) { null }
            val matrix = Matrix()
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                6 -> matrix.setRotate(90f)
                7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                8 -> matrix.setRotate(270f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val output = ByteArrayOutputStream()
            for (quality in listOf(85, 65, 45)) {
                output.reset(); check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= 1024 * 1024) break
            }
            check(output.size() <= 1024 * 1024) { "图片压缩后仍过大" }
            return JSONObject().put("media_type", "image/jpeg").put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        } finally { bitmap.recycle() }
    }
}
