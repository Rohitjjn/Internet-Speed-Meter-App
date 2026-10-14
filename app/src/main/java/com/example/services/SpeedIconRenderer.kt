package com.example.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/** Alpha-only white glyphs: SystemUI supplies the tint in both light and dark mode. */
internal class SpeedIconRenderer {
    private var lastLabel: Pair<String, String>? = null
    private var lastBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun render(value: String, unit: String): Bitmap {
        val label = value to unit
        if (label == lastLabel) return lastBitmap!!

        // Never redraw a bitmap already handed to SystemUI. Two rotating buffers were
        // not an ownership guarantee, and drawColor(TRANSPARENT) with SRC_OVER did NOT
        // clear old glyphs: repeated updates accumulated into a solid-looking box.
        // One 96x96 bitmap (~36 KiB), only when the displayed label changes.
        val bitmap = createBitmap(SIZE, SIZE)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        drawLine(canvas, value, top = 2f, bottom = 57f, maxSize = 60f)
        drawLine(canvas, unit, top = 61f, bottom = 93f, maxSize = 34f)
        lastLabel = label
        lastBitmap = bitmap
        return bitmap
    }

    private fun drawLine(canvas: Canvas, text: String, top: Float, bottom: Float, maxSize: Float) {
        paint.textSize = maxSize
        val metrics = paint.fontMetrics
        val widthScale = (SIZE - 6f) / paint.measureText(text).coerceAtLeast(1f)
        val heightScale = (bottom - top) / (metrics.descent - metrics.ascent)
        paint.textSize *= minOf(1f, widthScale, heightScale)
        val fitted = paint.fontMetrics
        val baseline = (top + bottom - fitted.ascent - fitted.descent) / 2f
        canvas.drawText(text, SIZE / 2f, baseline, paint)
    }

    companion object {
        const val SIZE = 96
    }
}
