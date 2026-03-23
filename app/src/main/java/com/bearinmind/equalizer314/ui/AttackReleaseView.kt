package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class AttackReleaseView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var attackMs: Float = 1f
        set(value) { field = value.coerceIn(0.01f, 500f); invalidate() }
    var releaseMs: Float = 60f
        set(value) { field = value.coerceIn(1f, 5000f); invalidate() }

    var onAttackChanged: ((Float) -> Unit)? = null
    var onReleaseChanged: ((Float) -> Unit)? = null

    private var draggingAttack = false
    private var draggingRelease = false

    private var lastTapTime = 0L
    private var lastTapType = 0  // 1=attack, 2=release
    private val doubleTapTimeout = 300L

    companion object {
        const val DEFAULT_ATTACK = 1f
        const val DEFAULT_RELEASE = 100f
    }

    private val bgPaint = Paint().apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38AAAAAA.toInt(); style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt(); textSize = 14f; textAlign = Paint.Align.CENTER
    }
    private val dotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val dotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private fun msToX(ms: Float, isAttack: Boolean, w: Float): Float {
        val halfW = w / 2f
        return if (isAttack) {
            val norm = (ms / 500f).coerceIn(0f, 1f)
            halfW - norm * (halfW - 10f)
        } else {
            val norm = (ms / 5000f).coerceIn(0f, 1f)
            halfW + norm * (halfW - 10f)
        }
    }

    private fun attackFootX(w: Float): Float = msToX(attackMs, true, w)
    private fun releaseFootX(w: Float): Float = msToX(releaseMs, false, w)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val halfW = w / 2f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val topY = 6f
        val botY = h - 2f

        // Horizontal grid lines
        for (i in 1..3) {
            val y = topY + (botY - topY) * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Vertical grid lines — attack side every 100ms
        for (ms in intArrayOf(100, 200, 300, 400)) {
            val x = msToX(ms.toFloat(), true, w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        // Center line (0ms)
        canvas.drawLine(halfW, 0f, halfW, h, gridPaint)
        // Release side every 1000ms
        for (ms in intArrayOf(1000, 2000, 3000, 4000)) {
            val x = msToX(ms.toFloat(), false, w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        // Dot and line setup
        val peakX = halfW
        val leftFootX = attackFootX(w)
        val rightFootX = releaseFootX(w)
        val dotR = 20f
        val dotCY = botY - dotR - 2f
        val minOffset = dotR + 4f

        // Where the slope crosses dotCY
        val slopeH = botY - topY
        val dotFrac = (dotCY - topY) / slopeH
        val aSlopeAtDotY = peakX + (leftFootX - peakX) * dotFrac
        val rSlopeAtDotY = peakX + (rightFootX - peakX) * dotFrac

        // Dots: stay offset from center, but follow slope when it reaches them
        val aDotX = aSlopeAtDotY.coerceAtMost(peakX - minOffset)
        val rDotX = rSlopeAtDotY.coerceAtLeast(peakX + minOffset)

        // Triangle — line goes from peak down to botY at the foot X
        // but only draw down to dotCY where it meets the dot (if it reaches the dot)
        val triPath = Path()
        // Attack side: if slope reaches dot, end at dot. Otherwise end at botY.
        if (aSlopeAtDotY <= aDotX) {
            // Slope reaches past the dot — line goes through dot center
            triPath.moveTo(aDotX, dotCY)
        } else {
            // Slope is steep, foot is closer to center than dot — line goes to botY
            triPath.moveTo(leftFootX, botY)
        }
        triPath.lineTo(peakX, topY)
        // Release side
        if (rSlopeAtDotY >= rDotX) {
            triPath.lineTo(rDotX, dotCY)
        } else {
            triPath.lineTo(rightFootX, botY)
        }
        canvas.drawPath(triPath, linePaint)

        // Halos when dragging
        val haloDp = 24f * resources.displayMetrics.density
        val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x38AAAAAA.toInt(); strokeWidth = haloDp * 2f
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        if (draggingAttack) {
            val p = Path()
            if (aSlopeAtDotY <= aDotX) p.moveTo(aDotX, dotCY) else p.moveTo(leftFootX, botY)
            p.lineTo(peakX, topY)
            canvas.drawPath(p, haloStrokePaint)
        }
        if (draggingRelease) {
            val p = Path(); p.moveTo(peakX, topY)
            if (rSlopeAtDotY >= rDotX) p.lineTo(rDotX, dotCY) else p.lineTo(rightFootX, botY)
            canvas.drawPath(p, haloStrokePaint)
        }

        // Draw A and R dots with halos
        if (draggingAttack) {
            canvas.drawCircle(aDotX, dotCY, haloDp, haloPaint)
        }
        canvas.drawCircle(aDotX, dotCY, dotR, dotBgPaint)
        canvas.drawCircle(aDotX, dotCY, dotR, dotRingPaint)
        canvas.drawText("A", aDotX, dotCY + dotTextPaint.textSize / 3f, dotTextPaint)

        if (draggingRelease) {
            canvas.drawCircle(rDotX, dotCY, haloDp, haloPaint)
        }
        canvas.drawCircle(rDotX, dotCY, dotR, dotBgPaint)
        canvas.drawCircle(rDotX, dotCY, dotR, dotRingPaint)
        canvas.drawText("R", rDotX, dotCY + dotTextPaint.textSize / 3f, dotTextPaint)

        // Bottom ms labels
        canvas.drawText("0", halfW, botY - 6f, markerPaint)
        for (ms in intArrayOf(200, 400)) {
            canvas.drawText("${ms} ms", msToX(ms.toFloat(), true, w), botY - 6f, markerPaint)
        }
        for (ms in intArrayOf(2000, 4000)) {
            canvas.drawText("${ms} ms", msToX(ms.toFloat(), false, w), botY - 6f, markerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val topY = 6f
        val botY = h - 2f
        val peakX = w / 2f
        val leftFootX = attackFootX(w)
        val rightFootX = releaseFootX(w)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                // Check dots first
                val dotR = 20f
                val dotCY = botY - dotR - 2f
                val slopeH = botY - topY
                val dotFrac = (dotCY - topY) / slopeH
                val aSlopeX = peakX + (leftFootX - peakX) * dotFrac
                val rSlopeX = peakX + (rightFootX - peakX) * dotFrac
                val aDX = aSlopeX.coerceAtMost(peakX - dotR - 4f)
                val rDX = rSlopeX.coerceAtLeast(peakX + dotR + 4f)
                val aDotDist = sqrt((event.x - aDX) * (event.x - aDX) + (event.y - dotCY) * (event.y - dotCY))
                val rDotDist = sqrt((event.x - rDX) * (event.x - rDX) + (event.y - dotCY) * (event.y - dotCY))

                val now = System.currentTimeMillis()

                if (aDotDist < 50f && aDotDist <= rDotDist) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 1) {
                        attackMs = DEFAULT_ATTACK; onAttackChanged?.invoke(DEFAULT_ATTACK)
                        lastTapTime = 0L; invalidate(); return true
                    }
                    lastTapTime = now; lastTapType = 1
                    draggingAttack = true; invalidate(); return true
                }
                if (rDotDist < 50f) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 2) {
                        releaseMs = DEFAULT_RELEASE; onReleaseChanged?.invoke(DEFAULT_RELEASE)
                        lastTapTime = 0L; invalidate(); return true
                    }
                    lastTapTime = now; lastTapType = 2
                    draggingRelease = true; invalidate(); return true
                }

                // Then check lines
                val attackDist = distToLine(event.x, event.y, leftFootX, botY, peakX, topY)
                val releaseDist = distToLine(event.x, event.y, peakX, topY, rightFootX, botY)
                if (attackDist < 50f && attackDist <= releaseDist) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 1) {
                        attackMs = DEFAULT_ATTACK; onAttackChanged?.invoke(DEFAULT_ATTACK)
                        lastTapTime = 0L; invalidate(); return true
                    }
                    lastTapTime = now; lastTapType = 1
                    draggingAttack = true; invalidate(); return true
                }
                if (releaseDist < 50f) {
                    if (now - lastTapTime < doubleTapTimeout && lastTapType == 2) {
                        releaseMs = DEFAULT_RELEASE; onReleaseChanged?.invoke(DEFAULT_RELEASE)
                        lastTapTime = 0L; invalidate(); return true
                    }
                    lastTapTime = now; lastTapType = 2
                    draggingRelease = true; invalidate(); return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val halfW = w / 2f
                if (draggingAttack) {
                    val ty = event.y.coerceIn(topY + 1f, botY - 1f)
                    val footX = (halfW - (halfW - event.x) * (botY - topY) / (ty - topY)).coerceIn(10f, halfW)
                    val norm = (halfW - footX) / (halfW - 10f)
                    attackMs = (norm * 500f).coerceIn(0.01f, 500f)
                    onAttackChanged?.invoke(attackMs)
                    return true
                }
                if (draggingRelease) {
                    val ty = event.y.coerceIn(topY + 1f, botY - 1f)
                    val footX = (halfW + (event.x - halfW) * (botY - topY) / (ty - topY)).coerceIn(halfW, w - 10f)
                    val norm = (footX - halfW) / (halfW - 10f)
                    releaseMs = (norm * 5000f).coerceIn(1f, 5000f)
                    onReleaseChanged?.invoke(releaseMs)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingAttack = false; draggingRelease = false; invalidate()
            }
        }
        return true
    }

    private fun distToLine(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 0.01f) return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1))
        val t = ((px - x1) * dx + (py - y1) * dy) / len2
        val ct = t.coerceIn(0f, 1f)
        val projX = x1 + ct * dx; val projY = y1 + ct * dy
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
    }
}
