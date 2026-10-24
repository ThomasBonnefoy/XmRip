package com.xmrip.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Illustration animée du casque (dessinée, pas une photo : les visuels de Sony sont protégés) au centre de l'anneau
 * de batterie. L'animation suit le mode de bruit : en réduction, des ondes arrivent sur les oreillettes et s'y
 * éteignent ; en ambiant, des ondes en partent ; désactivé, le casque flotte simplement. Pendant la connexion, l'anneau
 * pulse. L'animation s'arrête quand la vue n'est pas visible.
 */
class HeroArt(context: Context, private val ui: Ui) : View(context) {
    private var level: Int? = null
    private var shownLevel = 0f
    private var charging = false
    private var mood = Mood.UNKNOWN
    private var connecting = false
    private var phase = 0f
    private var levelAnim: ValueAnimator? = null
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private val r = RectF()
    private val matrix = Matrix()

    fun setBattery(level: Int?, charging: Boolean) {
        this.charging = charging
        if (level == this.level) {
            invalidate()
            return
        }
        this.level = level
        levelAnim?.cancel()
        levelAnim = ValueAnimator.ofFloat(shownLevel, (level ?: 0).toFloat()).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        contentDescription = if (level == null) "Casque, batterie inconnue" else "Casque, batterie $level %"
    }

    fun setMood(mood: Mood) {
        this.mood = mood
        invalidate()
    }

    fun setConnecting(connecting: Boolean) {
        this.connecting = connecting
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (!loop.isStarted) loop.start() else loop.resume()
        } else {
            loop.pause()
        }
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val stroke = w * 0.055f
        val wave = sin(2 * PI * phase).toFloat()

        // Halo coloré du mode derrière le casque.
        val glowAlpha = if (mood == Mood.UNKNOWN || mood == Mood.OFF) 0x22 else 0x50
        fill.shader = RadialGradient(cx, cy, w * 0.46f, intArrayOf(withAlpha(mood.glow, glowAlpha), withAlpha(mood.glow, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, w * 0.46f, fill)
        fill.shader = null

        // Anneau de batterie (il pulse pendant la connexion).
        val pad = stroke / 2 + 2
        oval.set(pad, pad, w - pad, h - pad)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        track.color = Colors.SURFACE_TOP
        track.alpha = if (connecting) (150 + 105 * (0.5f + 0.5f * wave)).toInt() else 255
        canvas.drawArc(oval, 135f, 270f, false, track)
        val lv = level
        val color = when {
            lv == null -> Colors.TEXT_FAINT
            charging -> Colors.OK
            lv <= 15 -> Colors.DANGER
            lv <= 30 -> Colors.WARN
            else -> Colors.ACCENT
        }
        val end = if (color == Colors.ACCENT) Colors.ACCENT_2 else blend(color, 0xFFFFFFFF.toInt(), 0.25f)
        arc.shader = SweepGradient(cx, cy, intArrayOf(end, color, end), floatArrayOf(0f, 0.375f, 1f)).also {
            matrix.setRotate(90f, cx, cy)
            it.setLocalMatrix(matrix)
        }
        if (lv != null && shownLevel > 0.5f) canvas.drawArc(oval, 135f, 270f * (shownLevel / 100f), false, arc)

        // Le casque, dans une boîte de 100 unités centrée, avec un léger flottement.
        val box = (w - 2 * stroke) * 0.66f
        val u = box / 100f
        val ox = cx - box / 2f
        val oy = cy - box / 2f + wave * u * 1.6f - u * 4f
        fun x(v: Float) = ox + v * u
        fun y(v: Float) = oy + v * u

        drawWaves(canvas, ::x, ::y, u)
        drawHeadset(canvas, ::x, ::y, u)
    }

    /** Ondes : vers l'extérieur en ambiant, vers les oreillettes (et qui s'y éteignent) en réduction de bruit. */
    private fun drawWaves(canvas: Canvas, x: (Float) -> Float, y: (Float) -> Float, u: Float) {
        if (mood != Mood.NC && mood != Mood.AMBIENT) return
        line.strokeWidth = 2.2f * u
        line.shader = null
        val inward = mood == Mood.NC
        for (side in listOf(-1, 1)) {
            val cx = x(if (side < 0) 21f else 79f)
            val cy = y(66f)
            for (k in 0 until 3) {
                val t = (phase + k / 3f) % 1f
                val progress = if (inward) 1f - t else t
                val radius = (12f + progress * 22f) * u
                // Les ondes naissent transparentes, s'affirment puis s'effacent (en réduction, juste avant l'oreillette).
                val alpha = (sin(PI * t).toFloat() * 200).toInt().coerceIn(0, 255)
                val tone = if (Colors.light) mood.start else blend(mood.start, 0xFFFFFFFF.toInt(), 0.25f)
                line.color = withAlpha(tone, alpha)
                r.set(cx - radius, cy - radius, cx + radius, cy + radius)
                if (side > 0) canvas.drawArc(r, -42f, 84f, false, line) else canvas.drawArc(r, 138f, 84f, false, line)
            }
        }
    }

    private fun drawHeadset(canvas: Canvas, x: (Float) -> Float, y: (Float) -> Float, u: Float) {
        val light = Colors.light
        val bandTop = if (light) 0xFFD6D0C7.toInt() else 0xFF464B57.toInt()
        val bandBottom = if (light) 0xFFA9A296.toInt() else 0xFF22252D.toInt()
        val cupTop = if (light) 0xFFEAE6DF.toInt() else 0xFF4A4F5C.toInt()
        val cupBottom = if (light) 0xFFB3AC9F.toInt() else 0xFF1C1F26.toInt()
        val cushion = if (light) 0xFF8A8378.toInt() else 0xFF121419.toInt()
        val rim = if (light) 0x26000000 else 0x30FFFFFF
        val dim = if (connecting) 0.75f else 1f

        // Arceau : un trait épais en dégradé, avec un reflet fin.
        r.set(x(19f), y(16f), x(81f), y(88f))
        line.shader = LinearGradient(0f, y(16f), 0f, y(52f), bandTop, bandBottom, Shader.TileMode.CLAMP)
        line.strokeWidth = 6.5f * u
        line.alpha = (255 * dim).toInt()
        canvas.drawArc(r, 198f, 144f, false, line)
        line.shader = null
        line.strokeWidth = 1.1f * u
        line.color = withAlpha(0xFFFFFFFF.toInt(), if (light) 0x90 else 0x38)
        r.set(x(20.5f), y(17.5f), x(79.5f), y(86.5f))
        canvas.drawArc(r, 215f, 110f, false, line)

        // Coulisses entre l'arceau et les oreillettes.
        line.strokeWidth = 3.2f * u
        line.color = bandBottom
        canvas.drawLine(x(21.5f), y(40f), x(21.5f), y(50f), line)
        canvas.drawLine(x(78.5f), y(40f), x(78.5f), y(50f), line)

        // Oreillettes : forme arrondie en dégradé, coussinet côté intérieur, contour et reflet discrets.
        for (left in listOf(true, false)) {
            val l = if (left) 12f else 70f
            r.set(x(l), y(47f), x(l + 18f), y(84f))
            fill.shader = LinearGradient(0f, y(47f), 0f, y(84f), cupTop, cupBottom, Shader.TileMode.CLAMP)
            fill.alpha = (255 * dim).toInt()
            canvas.drawRoundRect(r, 9f * u, 9f * u, fill)
            fill.shader = null
            line.strokeWidth = 0.9f * u
            line.color = rim
            canvas.drawRoundRect(r, 9f * u, 9f * u, line)
            val cl = if (left) 27f else 66f
            r.set(x(cl), y(51f), x(cl + 7f), y(80f))
            fill.color = cushion
            fill.alpha = (255 * dim).toInt()
            canvas.drawRoundRect(r, 3.5f * u, 3.5f * u, fill)
            line.strokeWidth = 1.2f * u
            line.color = withAlpha(0xFFFFFFFF.toInt(), if (light) 0x80 else 0x2C)
            val hx = if (left) 16f else 74f
            canvas.drawLine(x(hx), y(54f), x(hx), y(70f), line)
            // Liseré de la couleur du mode sur le bord extérieur.
            if (mood == Mood.NC || mood == Mood.AMBIENT) {
                line.strokeWidth = 1.4f * u
                line.color = withAlpha(mood.start, 0xB0)
                r.set(x(l), y(47f), x(l + 18f), y(84f))
                canvas.drawArc(r, if (left) 120f else -60f, 120f, false, line)
            }
        }
    }
}
