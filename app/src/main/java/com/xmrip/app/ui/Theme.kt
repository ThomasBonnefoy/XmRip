package com.xmrip.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Couleurs de l'interface, en deux palettes : sombre « premium » (fond presque noir, surfaces graphite) et claire
 * (fond gris très pâle, surfaces blanches), avec le même accent violet. [apply] choisit la palette au démarrage de
 * l'écran, avant la construction des vues.
 */
object Colors {
    var light = false
        private set
    var BG = 0
        private set
    var SURFACE = 0
        private set
    var SURFACE_HI = 0
        private set
    var SURFACE_TOP = 0
        private set
    var STROKE = 0
        private set
    var TEXT = 0
        private set
    var TEXT_DIM = 0
        private set
    var TEXT_FAINT = 0
        private set
    var ACCENT = 0
        private set
    var ACCENT_2 = 0
        private set
    var OK = 0
        private set
    var WARN = 0
        private set
    var DANGER = 0
        private set
    var DANGER_SOFT = 0
        private set
    var RIPPLE = 0
        private set

    /** Fond des messages d'erreur. */
    var ERROR_BG = 0
        private set

    init {
        apply(false)
    }

    fun apply(light: Boolean) {
        this.light = light
        if (light) {
            BG = 0xFFF3F4F8.toInt()
            SURFACE = 0xFFFFFFFF.toInt()
            SURFACE_HI = 0xFFF6F7FA.toInt()
            SURFACE_TOP = 0xFFE8EAF0.toInt()
            STROKE = 0x14000000
            TEXT = 0xFF13151C.toInt()
            TEXT_DIM = 0xFF5D6475.toInt()
            TEXT_FAINT = 0xFF959CAB.toInt()
            ACCENT = 0xFF6F5BFF.toInt()
            ACCENT_2 = 0xFF3D5AFE.toInt()
            OK = 0xFF16A34A.toInt()
            WARN = 0xFFD97706.toInt()
            DANGER = 0xFFDC2626.toInt()
            DANGER_SOFT = 0x1FDC2626
            RIPPLE = 0x1A000000
            ERROR_BG = 0xFFFDECEC.toInt()
        } else {
            BG = 0xFF07080C.toInt()
            SURFACE = 0xFF111319.toInt()
            SURFACE_HI = 0xFF191C25.toInt()
            SURFACE_TOP = 0xFF242835.toInt()
            STROKE = 0x16FFFFFF
            TEXT = 0xFFF4F5F8.toInt()
            TEXT_DIM = 0xFF9EA4B3.toInt()
            TEXT_FAINT = 0xFF626979.toInt()
            ACCENT = 0xFF8C7DFF.toInt()
            ACCENT_2 = 0xFF4E6CFF.toInt()
            OK = 0xFF4ADE80.toInt()
            WARN = 0xFFFBBF24.toInt()
            DANGER = 0xFFF87171.toInt()
            DANGER_SOFT = 0x33F87171
            RIPPLE = 0x26FFFFFF
            ERROR_BG = 0xFF2A1618.toInt()
        }
    }
}

/** Choix de l'apparence (Réglages) : suivre le téléphone, ou forcer clair ou sombre. */
object Appearance {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    private const val PREFS = "appearance"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", SYSTEM) ?: SYSTEM

    fun set(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
    }

    fun isLight(context: Context): Boolean = when (get(context)) {
        LIGHT -> true
        DARK -> false
        else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

/** Ambiance d'un mode de bruit : dégradé de la grande carte et lueur du fond d'écran. */
enum class Mood(val start: Int, val end: Int, val glow: Int) {
    NC(0xFF7B4DFF.toInt(), 0xFF2F4BFF.toInt(), 0xFF5B3DF5.toInt()),
    AMBIENT(0xFF00C2A0.toInt(), 0xFF0077E6.toInt(), 0xFF00A3B8.toInt()),
    OFF(0xFF4A5164.toInt(), 0xFF23272F.toInt(), 0xFF3A4152.toInt()),
    UNKNOWN(0xFF2A2E3A.toInt(), 0xFF181B23.toInt(), 0xFF2A2E3A.toInt()),
}

/** Mélange deux couleurs : [t] = 0 donne [a], 1 donne [b]. */
fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
    )
}

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/**
 * Briques d'interface dessinées en code (pas de layout XML ni de bibliothèque) : panneaux, textes, boutons,
 * interrupteurs et sélecteurs, tous dans le même style sombre.
 */
class Ui(val context: Context) {
    private val density = context.resources.displayMetrics.density

    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    fun dpF(value: Float): Float = value * density

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpF(radiusDp.toFloat())
            if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
        }

    fun gradient(start: Int, end: Int, radiusDp: Int, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR) =
        GradientDrawable(orientation, intArrayOf(start, end)).apply { cornerRadius = dpF(radiusDp.toFloat()) }

    /** Fond avec retour visuel au toucher, limité à la forme de [content]. */
    fun pressable(content: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(Colors.RIPPLE), content, rounded(Color.WHITE, radiusDp))

    fun lp(width: Int, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)

    fun matchLp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    fun wrapLp(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }

    fun frameLp(width: Int, height: Int, gravity: Int) = FrameLayout.LayoutParams(width, height, gravity)

    fun text(value: String, sizeSp: Float = 14f, color: Int = Colors.TEXT, face: Typeface? = null): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (face != null) typeface = face
            includeFontPadding = false
            setLineSpacing(dpF(2f), 1f)
        }

    fun dim(value: String, sizeSp: Float = 13f) = text(value, sizeSp, Colors.TEXT_DIM)

    /** Petit titre de section en capitales espacées. */
    fun label(value: String, color: Int = Colors.TEXT_DIM): TextView = text(value.uppercase(), 11f, color, bold).apply {
        letterSpacing = 0.14f
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        isBaselineAligned = false
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Panneau graphite à coins arrondis ; [title] s'affiche en petites capitales au-dessus du contenu. */
    fun panel(title: String? = null, subtitle: String? = null): LinearLayout = column().apply {
        background = rounded(Colors.SURFACE, 26, Colors.STROKE)
        setPadding(dp(18), dp(16), dp(18), dp(18))
        if (title != null) addView(label(title))
        if (subtitle != null) addView(dim(subtitle, 13f), matchLp(top = 6))
    }

    fun spacer(heightDp: Int) = View(context).apply { minimumHeight = dp(heightDp) }

    fun divider() = View(context).apply { setBackgroundColor(Colors.STROKE) }

    /** Bouton plein en dégradé d'accent. */
    fun primaryButton(label: String, start: Int = Colors.ACCENT, end: Int = Colors.ACCENT_2): TextView =
        text(label, 15f, Color.WHITE, medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = pressable(gradient(start, end, 18, GradientDrawable.Orientation.LEFT_RIGHT), 18)
            isClickable = true
            isFocusable = true
        }

    /** Bouton discret sur fond de surface ; [danger] le colore en rouge. */
    fun ghostButton(label: String, danger: Boolean = false): TextView =
        text(label, 14f, if (danger) Colors.DANGER else Colors.TEXT, medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            val fill = if (danger) Colors.DANGER_SOFT else Colors.SURFACE_TOP
            background = pressable(rounded(fill, 16), 16)
            isClickable = true
            isFocusable = true
        }

    /** Bouton rond à icône (vecteur teinté). */
    fun iconButton(drawable: Int, description: String, sizeDp: Int = 44, fill: Int = Colors.SURFACE_TOP, tint: Int = Colors.TEXT): ImageButton =
        ImageButton(context).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            background = pressable(rounded(fill, sizeDp / 2), sizeDp / 2)
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    fun icon(drawable: Int, tint: Int = Colors.TEXT, sizeDp: Int = 22): ImageView = ImageView(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Pastille de texte (état d'un appareil, par exemple). */
    fun pill(label: String, color: Int = Colors.TEXT_DIM, fill: Int = Colors.SURFACE_TOP): TextView =
        text(label, 11f, color, medium).apply {
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = rounded(fill, 10)
        }

    fun switch(): Switch = Switch(context).apply {
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
        val idleThumb = if (Colors.light) Color.WHITE else 0xFFC9CDD6.toInt()
        val idleTrack = if (Colors.light) 0xFFD3D6DF.toInt() else Colors.SURFACE_TOP
        thumbTintList = ColorStateList(states, intArrayOf(Colors.TEXT_FAINT, Color.WHITE, idleThumb))
        trackTintList = ColorStateList(states, intArrayOf(Colors.SURFACE_TOP, Colors.ACCENT, idleTrack))
        showText = false
    }

    /**
     * Ligne « titre, explication, interrupteur ». [onChange] n'est appelé que par l'utilisateur (pas par [ToggleRow.set]).
     */
    fun toggleRow(title: String, summary: String? = null, onChange: (Boolean) -> Unit): ToggleRow {
        val row = row()
        val texts = column()
        texts.addView(text(title, 15f, Colors.TEXT, medium))
        val sub = dim(summary ?: "", 12.5f)
        if (summary != null) texts.addView(sub, matchLp(top = 4))
        row.addView(texts, lp(0, weight = 1f).apply { rightMargin = dp(12) })
        val toggle = switch()
        row.addView(toggle)
        val result = ToggleRow(row, toggle, sub)
        toggle.setOnCheckedChangeListener { _, checked -> if (!result.silent) onChange(checked) }
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        return result
    }

    /** Ligne cliquable « titre … valeur › ». */
    fun navRow(title: String, value: String = "", iconRes: Int? = null, onClick: () -> Unit): NavRow {
        val row = row().apply {
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = pressable(rounded(Color.TRANSPARENT, 14), 14)
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (iconRes != null) row.addView(icon(iconRes, Colors.TEXT_DIM, 20), lp(dp(20), dp(20)).apply { rightMargin = dp(14) })
        val titleView = text(title, 15f, Colors.TEXT, medium)
        row.addView(titleView, lp(0, weight = 1f))
        val valueView = dim(value, 13.5f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(180)
        }
        row.addView(valueView, wrapLp(left = 8))
        row.addView(icon(com.xmrip.app.R.drawable.ic_ui_chevron, Colors.TEXT_FAINT, 18), lp(dp(18), dp(18)).apply { leftMargin = dp(4) })
        return NavRow(row, titleView, valueView)
    }

    fun segmented(labels: List<String>, sizeSp: Float = 13f): Segmented = Segmented(this, labels, sizeSp)

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(context)
}

class ToggleRow(val view: LinearLayout, val toggle: Switch, val summary: TextView) {
    var silent = false

    /** Affiche [value] sans prévenir l'écouteur ; null grise l'interrupteur (valeur non lue). */
    fun set(value: Boolean?) {
        silent = true
        toggle.isEnabled = value != null
        toggle.isChecked = value == true
        view.alpha = if (value == null) 0.55f else 1f
        silent = false
    }
}

class NavRow(val view: LinearLayout, val title: TextView, val value: TextView)

/** Sélecteur segmenté : une seule option active, surlignée par un dégradé d'accent. */
class Segmented(private val ui: Ui, labels: List<String>, sizeSp: Float) {
    val view: LinearLayout = ui.row().apply {
        background = ui.rounded(Colors.SURFACE_TOP, 16)
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
    }
    val items: List<TextView>
    var onSelect: ((Int) -> Unit)? = null
    var selected: Int? = null
        private set

    init {
        items = labels.mapIndexed { index, label ->
            ui.text(label, sizeSp, Colors.TEXT_DIM, ui.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(ui.dp(6), ui.dp(11), ui.dp(6), ui.dp(11))
                isClickable = true
                setOnClickListener { if (isEnabled) onSelect?.invoke(index) }
            }.also { view.addView(it, ui.lp(0, weight = 1f)) }
        }
        select(null)
    }

    fun select(index: Int?) {
        selected = index
        items.forEachIndexed { i, item ->
            val on = i == index
            item.background = if (on) {
                ui.gradient(Colors.ACCENT, Colors.ACCENT_2, 13, android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT)
            } else {
                ui.pressable(ui.rounded(Color.TRANSPARENT, 13), 13)
            }
            item.setTextColor(if (on) Color.WHITE else Colors.TEXT_DIM)
        }
    }

    fun setEnabled(enabled: Boolean) {
        items.forEach { it.isEnabled = enabled }
        view.alpha = if (enabled) 1f else 0.5f
    }
}
