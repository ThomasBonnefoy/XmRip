package com.xmrip.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.xmrip.app.R
import com.xmrip.app.protocol.HeadsetLook
import kotlin.math.PI
import kotlin.math.sin

/**
 * Photo animée du casque (`drawable-nodpi/headphones_*.webp`, une par couleur, fond transparent) au centre de l'anneau de batterie.
 * L'animation suit le mode de bruit : en réduction, des ondes arrivent sur l'oreillette et s'y éteignent ; en
 * ambiant, des ondes en partent ; désactivé, le casque flotte simplement. Pendant la connexion, l'anneau
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
    private var look = HeadsetLook.of(forcedColor)
    private var headset = BitmapFactory.decodeResource(context.resources, photoFor(look))
    private var color: Int? = null
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val photoRect = RectF()
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

    /** Photo du casque à la couleur annoncée par le casque (`MdrConstants.MODEL_COLOR_*`) ; noire si inconnue. */
    fun setColor(color: Int?) {
        this.color = color
        val wanted = HeadsetLook.of(forcedColor ?: color)
        if (wanted == look) return
        look = wanted
        headset = BitmapFactory.decodeResource(resources, photoFor(wanted))
        invalidate()
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
        val glowAlpha = if (mood == Mood.UNKNOWN || mood == Mood.OFF) 0x30 else 0x60
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

        // La photo du casque, dans un carré centré, avec un léger flottement.
        // Les photos sont rognées au casque (plus hautes que larges) : on les ajuste en hauteur dans l'anneau.
        val side = (w - 2 * stroke) * 0.86f
        val u = side / 100f
        val ph = side
        val pw = side * headset.width / headset.height
        val ox = cx - pw / 2f
        val oy = cy - ph / 2f + wave * u * 1.6f
        photoRect.set(ox, oy, ox + pw, oy + ph)
        photo.alpha = if (connecting) 190 else 255
        canvas.drawBitmap(headset, null, photoRect, photo)

        drawWaves(canvas, ox + pw * CUP_X, oy + ph * CUP_Y, u)
    }

    /** Ondes : vers l'extérieur en ambiant, vers l'oreillette (et qui s'y éteignent) en réduction de bruit. */
    private fun drawWaves(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        if (mood != Mood.NC && mood != Mood.AMBIENT) return
        line.strokeWidth = 2.2f * u
        line.shader = null
        val inward = mood == Mood.NC
        for (k in 0 until 3) {
            val t = (phase + k / 3f) % 1f
            val progress = if (inward) 1f - t else t
            val radius = (17f + progress * 20f) * u
            // Les ondes naissent transparentes, s'affirment puis s'effacent (en réduction, juste avant l'oreillette).
            val alpha = (sin(PI * t).toFloat() * 200).toInt().coerceIn(0, 255)
            val tone = if (Colors.light) mood.start else blend(mood.start, 0xFFFFFFFF.toInt(), 0.25f)
            line.color = withAlpha(tone, alpha)
            r.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(r, -42f, 84f, false, line)
        }
    }

    companion object {
        /** Centre de l'oreillette visible dans les photos, en fraction de l'image. */
        private const val CUP_X = 0.69f
        private const val CUP_Y = 0.74f

        private fun photoFor(look: HeadsetLook): Int = when (look) {
            HeadsetLook.BLACK -> R.drawable.headphones_black
            HeadsetLook.SILVER -> R.drawable.headphones_silver
            HeadsetLook.BLUE -> R.drawable.headphones_blue
            HeadsetLook.PINK -> R.drawable.headphones_pink
            HeadsetLook.OLIVE -> R.drawable.headphones_olive
            HeadsetLook.SAND -> R.drawable.headphones_sand
        }

        /** Couleur imposée pour essayer chaque photo sur un téléphone (`adb shell am start --ei debug_color N`, build de debug seulement). */
        @Volatile
        var forcedColor: Int? = null
    }
}
