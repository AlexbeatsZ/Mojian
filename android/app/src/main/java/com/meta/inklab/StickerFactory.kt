package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

enum class BuiltInSticker(val label: String) {
    STAR("星标"),
    HEART("喜欢"),
    CHECK("完成"),
    IDEA("灵感"),
}

object StickerFactory {
    fun render(kind: BuiltInSticker, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.035f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(49, 45, 39)
        }
        when (kind) {
            BuiltInSticker.STAR -> {
                val path = Path()
                for (index in 0 until 10) {
                    val radius = size * if (index % 2 == 0) 0.41f else 0.19f
                    val angle = -Math.PI / 2 + index * Math.PI / 5
                    val x = size * 0.5f + cos(angle).toFloat() * radius
                    val y = size * 0.5f + sin(angle).toFloat() * radius
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                paint.color = Color.rgb(255, 203, 61)
                canvas.drawPath(path, paint)
                canvas.drawPath(path, outline)
            }
            BuiltInSticker.HEART -> {
                val path = Path().apply {
                    moveTo(size * 0.5f, size * 0.86f)
                    cubicTo(size * 0.1f, size * 0.61f, size * 0.09f, size * 0.23f, size * 0.31f, size * 0.18f)
                    cubicTo(size * 0.43f, size * 0.15f, size * 0.49f, size * 0.24f, size * 0.5f, size * 0.29f)
                    cubicTo(size * 0.56f, size * 0.17f, size * 0.68f, size * 0.14f, size * 0.79f, size * 0.19f)
                    cubicTo(size * 1.01f, size * 0.3f, size * 0.86f, size * 0.65f, size * 0.5f, size * 0.86f)
                    close()
                }
                paint.color = Color.rgb(242, 94, 122)
                canvas.drawPath(path, paint)
                canvas.drawPath(path, outline)
            }
            BuiltInSticker.CHECK -> {
                paint.color = Color.rgb(94, 193, 135)
                canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.4f, paint)
                canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.4f, outline)
                val check = Path().apply {
                    moveTo(size * 0.29f, size * 0.51f)
                    lineTo(size * 0.44f, size * 0.66f)
                    lineTo(size * 0.73f, size * 0.35f)
                }
                Paint(outline).apply {
                    color = Color.WHITE
                    strokeWidth = size * 0.075f
                }.also { canvas.drawPath(check, it) }
            }
            BuiltInSticker.IDEA -> {
                paint.color = Color.rgb(255, 210, 74)
                canvas.drawCircle(size * 0.5f, size * 0.43f, size * 0.28f, paint)
                canvas.drawCircle(size * 0.5f, size * 0.43f, size * 0.28f, outline)
                paint.color = Color.rgb(219, 178, 45)
                canvas.drawRoundRect(RectF(size * 0.39f, size * 0.66f, size * 0.61f, size * 0.82f), size * 0.04f, size * 0.04f, paint)
                canvas.drawLine(size * 0.39f, size * 0.71f, size * 0.61f, size * 0.71f, outline)
                canvas.drawLine(size * 0.42f, size * 0.82f, size * 0.58f, size * 0.82f, outline)
            }
        }
        return bitmap
    }

    fun write(kind: BuiltInSticker, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output -> render(kind).compress(Bitmap.CompressFormat.PNG, 100, output) }
    }
}

