package com.xzyht.notifyrelay.feature.superisland.floatingreplicamanager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.animation.doOnEnd
import kotlin.math.max

/**
 * 绘制顺/逆时针圆形进度的简单自定义 View，带动画过渡能力。
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    private var strokeWidthPx = context.resources.displayMetrics.density * 3f
    private var progress = 0f
    private var clockwise = true
    private var animator: ValueAnimator? = null

    init {
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    fun setStrokeWidthDp(widthDp: Float) {
        strokeWidthPx = widthDp * context.resources.displayMetrics.density
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
        invalidate()
    }

    fun setColors(progressColor: Int, trackColor: Int) {
        progressPaint.color = progressColor
        trackPaint.color = trackColor
        invalidate()
    }

    fun setDirection(clockwise: Boolean) {
        this.clockwise = clockwise
        invalidate()
    }

    fun setProgressAnimated(from: Int, to: Int, durationMs: Long = DEFAULT_ANIM_DURATION_MS) {
        animator?.cancel()
        val start = from.coerceIn(0, 100)
        val end = to.coerceIn(0, 100)
        if (durationMs <= 0 || start == end) {
            progress = end.toFloat()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(start.toFloat(), end.toFloat()).apply {
            duration = durationMs
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                invalidate()
            }
            doOnEnd { animator = null }
            start()
        }
    }

    fun setProgressInstant(progress: Int) {
        animator?.cancel()
        this.progress = progress.coerceIn(0, 100).toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val startAngle = -90f
        val sweep = progress / 100f * 360f
        canvas.drawOval(arcBounds, trackPaint)
        val sweepAngle = if (clockwise) sweep else -sweep
        canvas.drawArc(arcBounds, startAngle, sweepAngle, false, progressPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        arcBounds.set(inset, inset, max(0f, w - inset), max(0f, h - inset))
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEFAULT_ANIM_DURATION_MS = 420L
    }
}
