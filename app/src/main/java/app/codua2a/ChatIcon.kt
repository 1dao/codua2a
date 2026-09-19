package app.codua2a

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Resolution-independent icons keep the native toolbar consistent across fonts. */
class ChatIcon(private val icon: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.style = Paint.Style.STROKE
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (icon) {
            "history" -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, paint)
                line(9f, 4f, 9f, 20f); line(13f, 9f, 17f, 9f); line(13f, 13f, 17f, 13f)
            }
            "new" -> {
                val path = Path().apply { moveTo(13f, 4f); lineTo(5f, 4f); quadTo(3f, 4f, 3f, 6f); lineTo(3f, 19f); quadTo(3f, 21f, 5f, 21f); lineTo(18f, 21f); quadTo(20f, 21f, 20f, 19f); lineTo(20f, 12f) }
                canvas.drawPath(path, paint)
                canvas.drawPath(Path().apply { moveTo(10f, 14f); lineTo(11f, 10f); lineTo(18f, 3f); lineTo(21f, 6f); lineTo(14f, 13f); close() }, paint)
            }
            "more" -> { paint.style = Paint.Style.FILL; for (y in listOf(5f, 12f, 19f)) canvas.drawCircle(12f, y, 1.6f, paint) }
            "plus" -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            "camera" -> {
                canvas.drawPath(Path().apply { moveTo(3f, 7f); lineTo(7f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(17f, 7f); lineTo(21f, 7f); lineTo(21f, 20f); lineTo(3f, 20f); close() }, paint)
                canvas.drawCircle(12f, 13f, 4f, paint)
            }
            "image" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint); canvas.drawCircle(8f, 8f, 1.5f, paint)
                canvas.drawPath(Path().apply { moveTo(4f, 18f); lineTo(10f, 12f); lineTo(15f, 17f); lineTo(18f, 14f); lineTo(21f, 17f) }, paint)
            }
            "file" -> {
                canvas.drawPath(Path().apply { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) }, paint)
                line(9f, 12f, 16f, 12f); line(9f, 16f, 16f, 16f)
            }
            "send" -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(12f, 5f, 18f, 11f) }
            "stop" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
