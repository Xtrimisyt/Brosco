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
 * gently move around, plus a field of twinkling stars. Pure canvas drawing,
 * no image assets, cheap enough to run continuously behind the UI.
 */
class GalaxyBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)
    private data class Blob(
        val baseX: Float, val baseY: Float, val radius: Float,
        val color: Int, val driftX: Float, val driftY: Float, val phase: Float
    )

    private val stars = mutableListOf<Star>()
    private val blobs = mutableListOf<Blob>()

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint().apply { color = Color.parseColor("#0B0B18") }

    private var animator: ValueAnimator? = null
    private var t = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
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
        // Purple, blue and a big red/magenta nebula for color variety.
        blobs.add(Blob(w * 0.22f, h * 0.18f, w * 0.55f, Color.parseColor("#7F5AF0"), 34f, 22f, 0f))
        blobs.add(Blob(w * 0.82f, h * 0.32f, w * 0.5f, Color.parseColor("#2CB1FF"), -26f, 28f, 2.1f))
        blobs.add(Blob(w * 0.5f, h * 0.88f, w * 0.65f, Color.parseColor("#E63368"), 22f, -32f, 4.2f))
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)

        for (b in blobs) {
            val cx = b.baseX + sin(t * 0.3 + b.phase) * b.driftX
            val cy = b.baseY + sin(t * 0.22 + b.phase + 1.5f) * b.driftY
            blobPaint.shader = RadialGradient(
                cx, cy, b.radius,
                intArrayOf(colorWithAlpha(b.color, 95), colorWithAlpha(b.color, 0)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, b.radius, blobPaint)
        }

        for (s in stars) {
            val twinkle = (sin(t * s.speed + s.phase) + 1f) / 2f
            starPaint.alpha = (60 + twinkle * 195).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.radius, starPaint)
        }
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
