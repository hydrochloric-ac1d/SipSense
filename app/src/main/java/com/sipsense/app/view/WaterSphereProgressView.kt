package com.sipsense.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom animated 3D Water Sphere Ball progress indicator.
 *
 * Matches the spherical gauge visual design:
 *  1. Outer progress arc with cyan-to-deep-blue gradient around the sphere perimeter.
 *  2. Thick 3D-shaded white/silver outer bezel ring with soft drop shadow.
 *  3. Crisp dark inner border ring and dark charcoal-slate sphere interior.
 *  4. Dual-layer sinusoidal sloshing water waves (soft ice-blue back wave + vibrant
 *     cyan-to-azure gradient front wave) clipped to the inner sphere cavity.
 *  5. Smooth vertical fill animation when [progress] or [max] changes, plus continuous
 *     horizontal wave sloshing animation.
 */
class WaterSphereProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var max: Int = 2000
        set(value) {
            field = value.coerceAtLeast(1)
            animateToTargetFraction()
        }

    var progress: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            animateToTargetFraction()
        }

    // Current animated fill fraction (0.0f .. 1.0f)
    private var currentFillFraction: Float = 0f

    // Continuous wave phase (0 .. 2*PI)
    private var wavePhase: Float = 0f

    // Primary tint color (can be customized via setIndicatorColor)
    private var primaryWaveTopColor: Int = Color.parseColor("#39CBFF")
    private var primaryWaveBottomColor: Int = Color.parseColor("#0072E5")
    private var secondaryWaveColor: Int = Color.parseColor("#BEE8FF")

    // Reusable paints
    private val outerTrackArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2600A3FF")
    }

    private val outerProgressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bezelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bezelRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bezelInnerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CCFFFFFF")
    }

    private val darkRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0D1218")
    }

    private val cavityBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val backWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = secondaryWaveColor
    }

    private val frontWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sphereSpecularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val innerVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#40000000")
    }

    // Reusable geometry objects
    private val outerArcRect = RectF()
    private val cavityClipPath = Path()
    private val backWavePath = Path()
    private val frontWavePath = Path()
    private val sweepMatrix = Matrix()

    // Cached dimensions for shader rebuilding
    private var lastWidth = 0
    private var lastHeight = 0

    // Animators
    private val waveAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            wavePhase = animator.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    private var levelAnimator: ValueAnimator? = null

    /**
     * Compatibility method matching CircularProgressIndicator.setIndicatorColor(int)
     * so DashboardFragment can tint the water wave and arc when the Daily Goal is reached.
     */
    fun setIndicatorColor(color: Int) {
        // Blend custom indicator color with the sphere's vibrant cyan/blue palette
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        primaryWaveTopColor = Color.rgb(
            min(255, (r * 0.45f + 57 * 0.55f).toInt() + 25),
            min(255, (g * 0.45f + 203 * 0.55f).toInt() + 15),
            min(255, (b * 0.45f + 255 * 0.55f).toInt())
        )
        primaryWaveBottomColor = Color.rgb(
            (r * 0.65f).toInt(),
            (g * 0.65f + 90 * 0.35f).toInt(),
            min(255, (b * 0.65f + 229 * 0.35f).toInt())
        )
        updateDynamicShaders()
        invalidate()
    }

    private fun targetFraction(): Float {
        if (max <= 0) return 0f
        return (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private fun animateToTargetFraction() {
        val target = targetFraction()
        levelAnimator?.cancel()
        if (!isAttachedToWindow) {
            currentFillFraction = target
            invalidate()
            return
        }
        levelAnimator = ValueAnimator.ofFloat(currentFillFraction, target).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                currentFillFraction = animator.animatedValue as Float
                updateFrontWaveShader()
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        currentFillFraction = targetFraction()
        if (!waveAnimator.isStarted) {
            waveAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        waveAnimator.cancel()
        levelAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastWidth = w
        lastHeight = h
        updateDynamicShaders()
    }

    private fun updateDynamicShaders() {
        val w = lastWidth.toFloat()
        val h = lastHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val minDim = min(w, h)
        val maxRadius = minDim / 2f

        // Geometry ratios matching the 3D sphere gauge screenshot
        val outerArcRadius = maxRadius * 0.93f
        val outerArcStroke = maxRadius * 0.032f
        val shadowRadius = maxRadius * 0.90f
        val bezelRadius = maxRadius * 0.85f
        val darkRimRadius = maxRadius * 0.69f
        val cavityRadius = maxRadius * 0.655f

        outerTrackArcPaint.strokeWidth = outerArcStroke
        outerProgressArcPaint.strokeWidth = outerArcStroke
        bezelInnerHighlightPaint.strokeWidth = maxRadius * 0.014f
        innerVignettePaint.strokeWidth = maxRadius * 0.035f

        outerArcRect.set(
            cx - outerArcRadius,
            cy - outerArcRadius,
            cx + outerArcRadius,
            cy + outerArcRadius
        )

        // 1. Outer progress arc gradient (bright cyan at bottom-left sweeping to deep ocean blue)
        val arcColors = intArrayOf(
            Color.parseColor("#7CE8FF"),
            Color.parseColor("#35B8EC"),
            Color.parseColor("#0D699E"),
            Color.parseColor("#084B78"),
            Color.parseColor("#7CE8FF")
        )
        val arcPositions = floatArrayOf(0f, 0.35f, 0.70f, 0.92f, 1f)
        val sweep = SweepGradient(cx, cy, arcColors, arcPositions)
        sweepMatrix.reset()
        sweepMatrix.preRotate(90f, cx, cy)
        sweep.setLocalMatrix(sweepMatrix)
        outerProgressArcPaint.shader = sweep

        // 2. Soft drop shadow behind the 3D white bezel
        bezelShadowPaint.shader = RadialGradient(
            cx,
            cy + maxRadius * 0.03f,
            shadowRadius,
            intArrayOf(
                Color.parseColor("#380C2540"),
                Color.parseColor("#1E0C2540"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.76f, 0.90f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // 3. 3D White/Silver bezel ring shader (lit from top-left, soft silver-grey shading at edges)
        bezelRingPaint.shader = LinearGradient(
            cx - bezelRadius * 0.7f,
            cy - bezelRadius * 0.7f,
            cx + bezelRadius * 0.8f,
            cy + bezelRadius * 0.8f,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F5F8FC"),
                Color.parseColor("#DEE6F0"),
                Color.parseColor("#FFFFFF")
            ),
            floatArrayOf(0f, 0.45f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )

        // 4. Dark charcoal-slate inner sphere cavity background
        cavityBackgroundPaint.shader = LinearGradient(
            cx,
            cy - cavityRadius,
            cx,
            cy + cavityRadius,
            intArrayOf(
                Color.parseColor("#2E3742"),
                Color.parseColor("#232B34"),
                Color.parseColor("#1A2028")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // 5. Inner sphere clip path
        cavityClipPath.reset()
        cavityClipPath.addCircle(cx, cy, cavityRadius, Path.Direction.CW)

        // 6. Top-left 3D glass sphere glossy reflection
        sphereSpecularPaint.shader = RadialGradient(
            cx - cavityRadius * 0.28f,
            cy - cavityRadius * 0.32f,
            cavityRadius * 0.85f,
            intArrayOf(
                Color.parseColor("#28FFFFFF"),
                Color.parseColor("#0DFFFFFF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        updateFrontWaveShader()
    }

    private fun updateFrontWaveShader() {
        val w = lastWidth.toFloat()
        val h = lastHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val cavityRadius = min(w, h) / 2f * 0.655f
        val cavityTop = cy - cavityRadius
        val cavityBottom = cy + cavityRadius

        frontWavePaint.shader = LinearGradient(
            cx,
            cavityTop,
            cx,
            cavityBottom,
            intArrayOf(
                primaryWaveTopColor,
                Color.parseColor("#1BA8F8"),
                primaryWaveBottomColor
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) / 2f

        val shadowRadius = maxRadius * 0.90f
        val bezelRadius = maxRadius * 0.85f
        val darkRimRadius = maxRadius * 0.69f
        val cavityRadius = maxRadius * 0.655f

        // 1. Draw outer subtle track arc and animated progress arc (starting from bottom 90° clockwise)
        val totalArcSweep = 290f
        canvas.drawArc(outerArcRect, 90f, totalArcSweep, false, outerTrackArcPaint)
        val activeSweep = (totalArcSweep * currentFillFraction).coerceAtLeast(4f)
        if (currentFillFraction > 0.002f) {
            canvas.drawArc(outerArcRect, 90f, activeSweep, false, outerProgressArcPaint)
        }

        // 2. Draw soft drop shadow & 3D white/silver spherical bezel
        canvas.drawCircle(cx, cy + maxRadius * 0.02f, shadowRadius, bezelShadowPaint)
        canvas.drawCircle(cx, cy, bezelRadius, bezelRingPaint)
        canvas.drawCircle(cx, cy, bezelRadius * 0.96f, bezelInnerHighlightPaint)

        // 3. Draw dark inner border rim and dark slate sphere cavity
        canvas.drawCircle(cx, cy, darkRimRadius, darkRimPaint)
        canvas.drawCircle(cx, cy, cavityRadius, cavityBackgroundPaint)

        // 4. Draw dual-layer sinusoidal water waves clipped inside the spherical cavity
        val saveCount = canvas.save()
        canvas.clipPath(cavityClipPath)

        if (currentFillFraction > 0.001f) {
            val cavityTop = cy - cavityRadius
            val cavityBottom = cy + cavityRadius
            val cavityDiameter = cavityRadius * 2f

            // Water baseline rises smoothly from bottom to top of the sphere cavity
            val waterBaselineY = cavityBottom - (currentFillFraction * cavityDiameter)

            // Wave amplitude tapers near 0% and 100% so empty/full states sit cleanly
            val edgeTaper = sin(currentFillFraction.coerceIn(0.03f, 0.97f) * PI).toFloat()
            val primaryAmplitude = cavityRadius * 0.105f * edgeTaper
            val secondaryAmplitude = cavityRadius * 0.095f * edgeTaper

            val leftX = cx - cavityRadius - 6f
            val rightX = cx + cavityRadius + 6f
            val steps = 36
            val stepX = (rightX - leftX) / steps

            // Back wave (light ice-blue crest, seamless 2*PI periodic loop)
            backWavePath.reset()
            backWavePath.moveTo(leftX, cavityBottom + 10f)
            for (i in 0..steps) {
                val x = leftX + i * stepX
                val normalizedX = (x - leftX) / cavityDiameter
                // Uses exact 1x wavePhase so wrap-around from 2*PI to 0 is 100% seamless
                val angle = normalizedX * (1.35f * PI.toFloat()) - wavePhase + 1.15f
                val y = waterBaselineY - (primaryAmplitude * 0.45f) + sin(angle) * secondaryAmplitude
                backWavePath.lineTo(x, y)
            }
            backWavePath.lineTo(rightX, cavityBottom + 10f)
            backWavePath.close()
            canvas.drawPath(backWavePath, backWavePaint)

            // Front wave (vibrant cyan-to-azure gradient crest, seamless 2*PI periodic loop)
            frontWavePath.reset()
            frontWavePath.moveTo(leftX, cavityBottom + 10f)
            for (i in 0..steps) {
                val x = leftX + i * stepX
                val normalizedX = (x - leftX) / cavityDiameter
                // Uses exact 1x wavePhase so wrap-around from 2*PI to 0 is 100% seamless
                val angle = normalizedX * (1.45f * PI.toFloat()) - wavePhase
                val y = waterBaselineY + cos(angle) * primaryAmplitude
                frontWavePath.lineTo(x, y)
            }
            frontWavePath.lineTo(rightX, cavityBottom + 10f)
            frontWavePath.close()
            canvas.drawPath(frontWavePath, frontWavePaint)
        }

        // 5. Draw 3D glass sphere glossy reflection and inner depth vignette
        canvas.drawCircle(cx, cy, cavityRadius, sphereSpecularPaint)
        canvas.drawCircle(cx, cy, cavityRadius, innerVignettePaint)

        canvas.restoreToCount(saveCount)
    }
}

