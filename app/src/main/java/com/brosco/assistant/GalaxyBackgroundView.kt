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

    private data class ShootingStar(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        var life: Float,
        val maxLife: Float
    )

    private val stars = mutableListOf<Star>()
    private val blobs = mutableListOf<Blob>()
    private val shootingStars = mutableListOf<ShootingStar>()

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint().apply {
        color = Color.parseColor("#0B0B18")
    }

    private var animator: ValueAnimator? = null
    private var t = 0f

    // "Listening/thinking" reactive mode - Brosco visibly comes alive: the
    // nebula pulses brighter/faster and color-shifted, and stray shooting
    // stars streak across occasionally. active01 eases toward the target
    // instead of snapping, so the transition itself feels animated.
    //
    // Two separate reactive states, each with its own tint, so "always
    // listening" (cyan - normal assistant mode) and "background Brosco"
    // (red - it's quietly live even while you're not looking at the app)
    // read as visibly different modes rather than the same glow twice.
    // Background wins if both happen to be on at once - it's the more
    // "serious" state.
    private var listening = false
    private var backgroundOn = false
    private var active01 = 0f
    private var nextShootingStarAt = 0f

    private val cyanTint = Color.parseColor("#00E5C7")
    private val redTint = Color.parseColor("#FF3B5C")
    private var currentTint = cyanTint

    fun setActive(isActive: Boolean) {
        listening = isActive
    }

    /** Reflects BrocoBackgroundService.isRunning - the persistent, off-screen listener. */
    fun setBackgroundActive(isActive: Boolean) {
        backgroundOn = isActive
    }

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

                val active = listening || backgroundOn

                // Ease toward the target active state (~0.3s to settle)
                // rather than snapping, so turning listening on/off reads
                // as a deliberate animation, not a jump-cut.
                val target = if (active) 1f else 0f
                active01 += (target - active01) * 0.12f
                if (kotlin.math.abs(target - active01) < 0.01f) active01 = target

                // Ease the tint color too (not just its intensity), so
                // switching between listening (cyan) and background (red)
                // - or turning either on from idle - crossfades smoothly
                // instead of the hue jump-cutting.
                val targetTint = if (backgroundOn) redTint else cyanTint
                currentTint = blendColors(currentTint, targetTint, 0.08f)

                if (active && t >= nextShootingStarAt && width > 0 && height > 0) {
                    spawnShootingStar()
                    nextShootingStarAt = t + 0.9f + Random.nextFloat() * 1.6f
                }

                val iterator = shootingStars.iterator()
                while (iterator.hasNext()) {
                    val s = iterator.next()
                    s.x += s.vx
                    s.y += s.vy
                    s.life -= 0.016f
                    if (s.life <= 0f) iterator.remove()
                }

                invalidate()
            }

            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun spawnShootingStar() {
        val fromLeft = Random.nextBoolean()
        val startY = Random.nextFloat() * height * 0.5f
        val startX = if (fromLeft) -40f else width + 40f
        val speed = width * (0.9f + Random.nextFloat() * 0.5f) / 40f
        val angle = Math.toRadians((25 + Random.nextFloat() * 20).toDouble())
        val dir = if (fromLeft) 1f else -1f
        shootingStars.add(
            ShootingStar(
                x = startX,
                y = startY,
                vx = dir * speed * kotlin.math.cos(angle).toFloat(),
                vy = speed * kotlin.math.sin(angle).toFloat(),
                life = 0.7f,
                maxLife = 0.7f
            )
        )
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

        // Active mode speeds up the drift and pushes brightness/color
        // toward whichever mode is on (cyan = listening, red = background)
        // - Brosco visibly "wakes up", and which color tells you why.
        val speedMul = 1f + active01 * 0.8f
        val tint = currentTint

        // Background mode gets an extra slow "heartbeat" pulse on top of
        // the twinkle - a steady beat reads as more "quietly always-on"
        // than the faster listening shimmer, and makes the two modes feel
        // distinct at a glance, not just differently colored.
        val heartbeat = if (backgroundOn) {
            (sin((t * 2.2f).toDouble()).toFloat() + 1f) / 2f
        } else 0f

        // Draw nebula blobs
        for (b in blobs) {

            // IMPORTANT:
            // sin() returns Double, so convert the result to Float.
            val cx = (
                b.baseX +
                    sin((t * 0.3f * speedMul + b.phase).toDouble()).toFloat() * b.driftX
                )

            val cy = (
                b.baseY +
                    sin((t * 0.22f * speedMul + b.phase + 1.5f).toDouble()).toFloat() * b.driftY
                )

            val blobColor = blendColors(b.color, tint, active01 * 0.35f)
            val alphaBoost = (95 + active01 * 55 + heartbeat * 30).toInt().coerceIn(0, 255)

            blobPaint.shader = RadialGradient(
                cx,
                cy,
                b.radius * (1f + active01 * 0.08f + heartbeat * 0.02f),
                intArrayOf(
                    colorWithAlpha(blobColor, alphaBoost),
                    colorWithAlpha(blobColor, 0)
                ),
                null,
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(
                cx,
                cy,
                b.radius * (1f + active01 * 0.08f + heartbeat * 0.02f),
                blobPaint
            )
        }

        // Draw twinkling stars - twinkle faster and brighter while active
        for (s in stars) {

            val twinkle = (
                sin((t * s.speed * speedMul + s.phase).toDouble()).toFloat() + 1f
            ) / 2f

            starPaint.alpha = (
                60f + twinkle * (195f + active01 * 40f)
            ).toInt().coerceIn(0, 255)
            starPaint.color = blendColors(Color.WHITE, tint, active01 * 0.25f)

            canvas.drawCircle(
                s.x,
                s.y,
                s.radius * (1f + active01 * 0.15f),
                starPaint
            )
        }

        // Shooting stars - only spawn while active, but let in-flight ones
        // finish their streak even if active mode just switched off.
        for (s in shootingStars) {
            val fade = (s.life / s.maxLife).coerceIn(0f, 1f)
            streakPaint.color = colorWithAlpha(tint, (fade * 220).toInt())
            val tailX = s.x - s.vx * 2.4f
            val tailY = s.y - s.vy * 2.4f
            canvas.drawLine(s.x, s.y, tailX, tailY, streakPaint)
        }

        // Background mode: a soft red vignette breathing at the edges, on
        // top of everything else - the unmistakable "I'm quietly still
        // live" cue even from a glance at the corner of the screen.
        if (backgroundOn && active01 > 0.01f) {
            val vignetteAlpha = (heartbeat * 28 * active01).toInt().coerceIn(0, 60)
            if (vignetteAlpha > 0) {
                blobPaint.shader = RadialGradient(
                    width / 2f,
                    height / 2f,
                    (width.coerceAtLeast(height)) * 0.75f,
                    intArrayOf(
                        colorWithAlpha(redTint, 0),
                        colorWithAlpha(redTint, vignetteAlpha)
                    ),
                    floatArrayOf(0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blobPaint)
            }
        }
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val red = (Color.red(from) + (Color.red(to) - Color.red(from)) * r).toInt()
        val green = (Color.green(from) + (Color.green(to) - Color.green(from)) * r).toInt()
        val blue = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r).toInt()
        return Color.rgb(red, green, blue)
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

