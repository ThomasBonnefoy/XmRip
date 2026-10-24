package com.xmrip.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fond de l'application : noir profond avec une lueur colorée en haut, qui suit le mode de bruit. */
class GlowDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow = Mood.UNKNOWN.glow
    private var animator: ValueAnimator? = null

    fun setGlow(color: Int, animate: Boolean = true) {
        if (color == glow) return
        animator?.cancel()
        if (!animate) {
            glow = color
            invalidateSelf()
            return
        }
        val from = glow
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener {
                glow = blend(from, color, it.animatedValue as Float)
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Colors.BG)
        val radius = b.width() * 0.95f
        paint.shader = RadialGradient(
            b.exactCenterX(), b.top - b.width() * 0.15f, radius,
            if (Colors.light) intArrayOf(withAlpha(glow, 0x4C), withAlpha(glow, 0x12), withAlpha(glow, 0))
            else intArrayOf(withAlpha(glow, 0x70), withAlpha(glow, 0x18), withAlpha(glow, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Cercles concentriques décoratifs, dessinés dans le coin de la grande carte du mode. */
class RingsDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    var strength = 1f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.right - 34 * density
        val cy = b.top + 46 * density
        paint.strokeWidth = 1.4f * density
        for (i in 0 until 5) {
            paint.color = withAlpha(0xFFFFFFFF.toInt(), ((0x30 - i * 7) * strength).toInt())
            canvas.drawCircle(cx, cy, (26 + i * 26) * density, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Curseur épais (volume, niveau ambiant) : piste arrondie remplie en dégradé, libellé à gauche, valeur à droite.
 * [onChange] reçoit la valeur pendant le glissement (`final` = false) puis au relâché (`final` = true).
 */
class LevelSlider(context: Context, private val ui: Ui, var min: Int, var max: Int) : View(context) {
    var title = ""
        set(value) {
            field = value
            invalidate()
        }
    var format: (Int) -> String = { it.toString() }
    var onChange: ((value: Int, final: Boolean) -> Unit)? = null
    var value = min
        private set
    private var start = Colors.ACCENT
    private var end = Colors.ACCENT_2
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.SURFACE_TOP }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT
        typeface = ui.medium
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT
        typeface = ui.bold
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()
    private var dragging = false

    fun setColors(start: Int, end: Int) {
        this.start = start
        this.end = end
        invalidate()
    }

    /** Affiche [v] sans appeler [onChange] ; ignoré pendant que l'utilisateur fait glisser. */
    fun set(v: Int) {
        if (dragging) return
        value = v.coerceIn(min, max)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(52))
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        trackPaint.alpha = if (isEnabled) 255 else 130
        canvas.drawRoundRect(rect, r * 0.62f, r * 0.62f, trackPaint)
        val fraction = if (max > min) (value - min).toFloat() / (max - min) else 0f
        val fillWidth = (height + (width - height) * fraction)
        rect.set(0f, 0f, fillWidth, height.toFloat())
        fillPaint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, start, end, Shader.TileMode.CLAMP)
        fillPaint.alpha = if (isEnabled) 255 else 90
        canvas.drawRoundRect(rect, r * 0.62f, r * 0.62f, fillPaint)
        textPaint.textSize = ui.dpF(14f)
        valuePaint.textSize = ui.dpF(15f)
        val base = height / 2f + textPaint.textSize * 0.35f
        // Texte blanc sur la partie remplie, couleur du thème ailleurs (lisible en clair comme en sombre).
        for (onFill in listOf(true, false)) {
            canvas.save()
            if (onFill) canvas.clipRect(0f, 0f, fillWidth, height.toFloat()) else canvas.clipRect(fillWidth, 0f, width.toFloat(), height.toFloat())
            val color = if (onFill) 0xFFFFFFFF.toInt() else Colors.TEXT
            textPaint.color = color
            valuePaint.color = color
            canvas.drawText(title, ui.dpF(18f), base, textPaint)
            canvas.drawText(format(value), width - ui.dpF(18f), base, valuePaint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                update(event.x, final = false)
            }
            MotionEvent.ACTION_MOVE -> update(event.x, final = false)
            MotionEvent.ACTION_UP -> {
                update(event.x, final = true)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onChange?.invoke(value, true)
            }
        }
        return true
    }

    private fun update(x: Float, final: Boolean) {
        val usable = (width - height).coerceAtLeast(1)
        val fraction = ((x - height / 2f) / usable).coerceIn(0f, 1f)
        val v = (min + fraction * (max - min)).roundToInt()
        if (v != value) {
            value = v
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            onChange?.invoke(v, final)
        } else if (final) {
            onChange?.invoke(v, true)
        }
    }
}

/**
 * Égaliseur dessiné : une poignée par bande sur une piste verticale, reliées par une courbe lissée et remplie.
 * On fait glisser une poignée verticalement ; [onChange] reçoit les gains au relâché. Désactivé = affichage seul.
 */
class EqGraph(context: Context, private val ui: Ui, private val labels: List<String>, private val minGain: Int, private val maxGain: Int) : View(context) {
    var gains: IntArray = IntArray(labels.size)
        private set
    var onChange: ((IntArray) -> Unit)? = null
    private var active = -1
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.STROKE
        strokeWidth = ui.dpF(1f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.SURFACE_TOP
        strokeCap = Paint.Cap.ROUND
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dpF(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT_FAINT
        textAlign = Paint.Align.CENTER
        typeface = ui.medium
        textSize = ui.dpF(10.5f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT
        textAlign = Paint.Align.CENTER
        typeface = ui.bold
        textSize = ui.dpF(12f)
    }
    private val path = Path()
    private val fill = Path()

    fun set(values: List<Int>) {
        if (active >= 0) return
        gains = IntArray(labels.size) { i -> values.getOrElse(i) { 0 }.coerceIn(minGain, maxGain) }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(230))
    }

    private val top get() = ui.dpF(26f)
    private val bottom get() = height - ui.dpF(30f)
    private fun xOf(i: Int) = ui.dpF(14f) + (width - ui.dpF(28f)) * (i + 0.5f) / labels.size
    private fun yOf(g: Int) = top + (bottom - top) * (maxGain - g) / (maxGain - minGain).toFloat()

    override fun onDraw(canvas: Canvas) {
        val alpha = if (isEnabled) 255 else 120
        for (g in listOf(maxGain, 0, minGain)) {
            gridPaint.alpha = if (g == 0) 60 else 30
            canvas.drawLine(ui.dpF(6f), yOf(g), width - ui.dpF(6f), yOf(g), gridPaint)
        }
        trackPaint.strokeWidth = ui.dpF(4f)
        for (i in labels.indices) {
            canvas.drawLine(xOf(i), top, xOf(i), bottom, trackPaint)
            canvas.drawText(labels[i], xOf(i), height - ui.dpF(8f), labelPaint)
        }
        // Courbe lissée (Catmull-Rom converti en Bézier) à travers les poignées.
        path.reset()
        val pts = labels.indices.map { xOf(it) to yOf(gains[it]) }
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 0 until pts.size - 1) {
            val p0 = pts[(i - 1).coerceAtLeast(0)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
            path.cubicTo(
                p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                p2.first, p2.second,
            )
        }
        fill.reset()
        fill.addPath(path)
        fill.lineTo(pts.last().first, bottom)
        fill.lineTo(pts.first().first, bottom)
        fill.close()
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, withAlpha(Colors.ACCENT, 0x55 * alpha / 255), withAlpha(Colors.ACCENT_2, 0), Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillPaint)
        curvePaint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Colors.ACCENT, Colors.ACCENT_2, Shader.TileMode.CLAMP)
        curvePaint.alpha = alpha
        canvas.drawPath(path, curvePaint)
        for (i in labels.indices) {
            val (x, y) = pts[i]
            knobPaint.color = when {
                Colors.light -> if (i == active) Colors.ACCENT_2 else Colors.ACCENT
                i == active -> 0xFFFFFFFF.toInt()
                else -> blend(Colors.ACCENT, 0xFFFFFFFF.toInt(), 0.35f)
            }
            knobPaint.alpha = alpha
            canvas.drawCircle(x, y, ui.dpF(if (i == active) 9f else 6.5f), knobPaint)
            if (i == active || (isEnabled && gains[i] != 0)) {
                valuePaint.alpha = if (i == active) 255 else 150
                canvas.drawText("%+d".format(gains[i]), x, y - ui.dpF(14f), valuePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = labels.indices.minByOrNull { abs(xOf(it) - event.x) } ?: -1
                move(event.y)
            }
            MotionEvent.ACTION_MOVE -> move(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) {
                    active = -1
                    invalidate()
                    onChange?.invoke(gains.copyOf())
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun move(y: Float) {
        if (active < 0) return
        val g = (maxGain - (y - top) / (bottom - top) * (maxGain - minGain)).roundToInt().coerceIn(minGain, maxGain)
        if (g != gains[active]) {
            gains[active] = g
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }
}

/** Petite pastille ronde de couleur (état de la connexion dans l'en-tête). */
class Dot(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color = Colors.TEXT_FAINT
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = withAlpha(color, 0x40)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f, width / 4f, paint)
    }
}
