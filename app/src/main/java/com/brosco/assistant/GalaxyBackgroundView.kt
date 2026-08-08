package com.brosco.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * A slow-drifting "galaxy" backdrop: a few big soft-glow nebula blobs that
 * gently move around, plus a field of twinkling stars.
 *
 * Pure Canvas drawing — no image assets required.
 */
class GalaxyBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val phase: Float,
        val speed: Float
    )

    private data class Blob(
        val baseX: Float,
        val baseY: Float,
        val radius: Float,
        val color: Int,
        val driftX: Float,
        val driftY: Float,
        val phase: Float
    )

    private val stars = mutableListOf<Star>()
    private val blobs = mutableListOf<Blob>()

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val basePaint = Paint().apply {
        color = Color.parseColor("#0B0B18")
    }

    private var animator: ValueAnimator? = null
    private var t = 0f

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w == 0 || h == 0) return

        stars.clear()

        repeat(80) {
            stars.add(
                Star(
                    x = Random.nextFloat() * w,
                    y = Random.nextFloat() * h,
                    radius = Random.nextFloat() * 1.6f + 0.4f,
                    phase = Random.nextFloat() * 6.28f,
                    speed = Random.nextFloat() * 0.6f + 0.3f
                )
            )
        }

        blobs.clear()

        // Purple nebula
        blobs.add(
            Blob(
                baseX = w * 0.22f,
                baseY = h * 0.18f,
                radius = w * 0.55f,
                color = Color.parseColor("#7F5AF0"),
                driftX = 34f,
                driftY = 22f,
                phase = 0f
            )
        )

        // Blue nebula
        blobs.add(
            Blob(
                baseX = w * 0.82f,
                baseY = h * 0.32f,
                radius = w * 0.5f,
                color = Color.parseColor("#2CB1FF"),
                driftX = -26f,
                driftY = 28f,
                phase = 2.1f
            )
        )

        // Red / magenta nebula
        blobs.add(
            Blob(
                baseX = w * 0.5f,
                baseY = h * 0.88f,
                radius = w * 0.65f,
                color = Color.parseColor("#E63368"),
                driftX = 22f,
                driftY = -32f,
                phase = 4.2f
            )
        )
    }

    fun start() {
        if (animator?.isRunning == true) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = ValueAnimator.INFINITE

            addUpdateListener {
                t += 0.016f
                invalidate()
            }

            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dark galaxy background
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            basePaint
        )

        // Draw nebula blobs
        for (b in blobs) {

            // IMPORTANT:
            // sin() returns Double, so convert the result to Float.
            val cx = (
                b.baseX +
                    sin((t * 0.3f + b.phase).toDouble()).toFloat() * b.driftX
                )

            val cy = (
                b.baseY +
                    sin((t * 0.22f + b.phase + 1.5f).toDouble()).toFloat() * b.driftY
                )

            blobPaint.shader = RadialGradient(
                cx,
                cy,
                b.radius,
                intArrayOf(
                    colorWithAlpha(b.color, 95),
                    colorWithAlpha(b.color, 0)
                ),
                null,
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(
                cx,
                cy,
                b.radius,
                blobPaint
            )
        }

        // Draw twinkling stars
        for (s in stars) {

            val twinkle = (
                sin((t * s.speed + s.phase).toDouble()).toFloat() + 1f
            ) / 2f

            starPaint.alpha = (
                60f + twinkle * 195f
            ).toInt().coerceIn(0, 255)

            canvas.drawCircle(
                s.x,
                s.y,
                s.radius,
                starPaint
            )
        }
    }

    private fun colorWithAlpha(
        color: Int,
        alpha: Int
    ): Int {
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}

