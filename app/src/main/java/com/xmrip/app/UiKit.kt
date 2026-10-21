package com.xmrip.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Couleurs de l'interface, claires ou sombres selon le thème du téléphone. */
class Palette(night: Boolean) {
    val background = if (night) 0xFF0F1318.toInt() else 0xFFF2F4F7.toInt()
    val card = if (night) 0xFF1A2028.toInt() else 0xFFFFFFFF.toInt()
    val neutral = if (night) 0xFF2A3440.toInt() else 0xFFE7ECF1.toInt()
    val text = if (night) 0xFFE8EEF3.toInt() else 0xFF14202B.toInt()
    val textSecondary = if (night) 0xFF9AA9B8.toInt() else 0xFF5B6B7A.toInt()
    val accent = if (night) 0xFF64B5F6.toInt() else 0xFF1565C0.toInt()
    val onAccent = if (night) 0xFF0B1B2B.toInt() else 0xFFFFFFFF.toInt()
    val danger = if (night) 0xFFEF9A9A.toInt() else 0xFFC62828.toInt()
    val dangerSoft = if (night) 0xFF3A2224.toInt() else 0xFFFDECEA.toInt()
    val accentSoft = if (night) 0xFF1B2D40.toInt() else 0xFFE3F0FD.toInt()
    val ok = if (night) 0xFF81C784.toInt() else 0xFF2E7D32.toInt()
}

/** Petits éléments d'interface réutilisables, dessinés en code (pas de layout XML ni de bibliothèque). */
class UiKit(private val context: Context) {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val p = Palette(night)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    fun lp(width: Int, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight)

    /** Largeur pleine, avec marges en dp. */
    fun matchLp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    fun text(value: String, sizeSp: Float = 14f, bold: Boolean = false, secondary: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(if (secondary) p.textSecondary else p.text)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Carte blanche (ou sombre) avec un titre facultatif. Les contrôles s'ajoutent ensuite avec addView. */
    fun card(title: String? = null, subtitle: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 20)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            if (title != null) addView(text(title, 16f, bold = true))
            if (subtitle != null) addView(text(subtitle, 13f, secondary = true), matchLp(top = 2))
        }

    /**
     * Applique le style « choix » : fond [idle] au repos, fond d'accent quand le bouton est sélectionné
     * (`isSelected`), texte grisé quand il est désactivé.
     */
    fun styleChoice(button: Button, idle: Int, radiusDp: Int, sizeSp: Float = 14f): Button {
        val background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), rounded(p.accent, radiusDp))
            addState(intArrayOf(), rounded(idle, radiusDp))
        }
        button.background = background
        button.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(),
                ),
                intArrayOf(p.onAccent, (p.textSecondary and 0x00FFFFFF) or 0x80000000.toInt(), p.text),
            ),
        )
        button.isAllCaps = false
        button.textSize = sizeSp
        button.stateListAnimator = null
        button.minHeight = 0
        button.minimumHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(10), dp(12), dp(10), dp(12))
        button.gravity = Gravity.CENTER
        return button
    }

    /** Bouton d'action (pas de sélection) : fond neutre arrondi. */
    fun actionButton(label: String, sizeSp: Float = 14f): Button =
        styleChoice(Button(context).apply { text = label }, p.neutral, 14, sizeSp)

    /** Bouton à icône vectorielle (teintée avec la couleur du texte), fond neutre arrondi. */
    fun iconButton(drawableRes: Int, description: String): ImageButton =
        ImageButton(context).apply {
            setImageResource(drawableRes)
            imageTintList = ColorStateList.valueOf(p.text)
            contentDescription = description
            background = rounded(p.neutral, 14)
            scaleType = ImageView.ScaleType.CENTER
            stateListAnimator = null
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }

    /** Puce de choix (préréglage, pas de volume…) : fond neutre, accent quand elle est sélectionnée. */
    fun chip(label: String): Button =
        styleChoice(Button(context).apply { text = label }, p.neutral, 14)

    /** Sélecteur segmenté : une seule option active, les boutons se partagent la largeur. */
    fun segmented(labels: List<String>, sizeSp: Float = 13f): Pair<LinearLayout, List<Button>> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.neutral, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val buttons = labels.map { label ->
            styleChoice(Button(context).apply { text = label }, 0x00000000, 11, sizeSp).also {
                container.addView(it, lp(0, weight = 1f))
            }
        }
        return container to buttons
    }

    fun tint(seekBar: SeekBar): SeekBar {
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        val colors = intArrayOf(p.neutral, p.accent)
        seekBar.progressTintList = ColorStateList(states, colors)
        seekBar.thumbTintList = ColorStateList(states, colors)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(p.neutral)
        return seekBar
    }

    /** Petite pastille de texte (état d'un appareil, par exemple). */
    fun pill(label: String, highlighted: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextColor(if (highlighted) p.onAccent else p.textSecondary)
            background = rounded(if (highlighted) p.accent else p.card, 10)
        }

    fun spacer(heightDp: Int): View = View(context).apply { minimumHeight = dp(heightDp) }
}
