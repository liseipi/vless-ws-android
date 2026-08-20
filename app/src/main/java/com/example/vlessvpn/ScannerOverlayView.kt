package com.example.vlessvpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Draws the dark mask, corner brackets and the animated scan line over the
 * camera preview. Purely visual — decoding runs on the full preview frame.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply { color = Color.BLACK; alpha = 112 }
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#7C93F5") // accent_blue
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint().apply { alpha = 200 }

    private val frame = RectF()
    private val cornerLen = dp(22f)
    private val lineHeight = dp(2.5f)

    private var lineAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h) * 0.72f
        val left = (w - size) / 2f
        val top = h * 0.44f - size / 2f
        frame.set(left, top, left + size, top + size)
        updateLineShader()
        if (lineAnimator == null) startLineAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Dim everything outside the frame.
        canvas.drawRect(0f, 0f, w, frame.top, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint)
        canvas.drawRect(0f, frame.bottom, w, h, dimPaint)

        // Corner brackets.
        val r = frame
        canvas.drawLine(r.left, r.top + cornerLen, r.left, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left + cornerLen, r.top, cornerPaint)
        canvas.drawLine(r.right - cornerLen, r.top, r.right, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + cornerLen, cornerPaint)
        canvas.drawLine(r.left, r.bottom - cornerLen, r.left, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left + cornerLen, r.bottom, cornerPaint)
        canvas.drawLine(r.right - cornerLen, r.bottom, r.right, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - cornerLen, cornerPaint)

        // Animated scan line.
        lineAnimator?.let { anim ->
            val progress = anim.animatedValue as Float
            val y = frame.top + progress * (frame.height() - lineHeight)
            canvas.drawRect(frame.left + cornerLen, y, frame.right - cornerLen, y + lineHeight, linePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (frame.width() > 0f) startLineAnimation()
    }

    override fun onDetachedFromWindow() {
        lineAnimator?.cancel()
        lineAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startLineAnimation() {
        lineAnimator?.cancel()
        lineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun updateLineShader() {
        linePaint.shader = LinearGradient(
            frame.left, 0f, frame.right, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#7C93F5"),
                Color.parseColor("#7C93F5"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
