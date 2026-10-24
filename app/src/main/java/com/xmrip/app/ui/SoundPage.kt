package com.xmrip.app.ui

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xmrip.app.HeadsetController
import com.xmrip.app.HeadsetController.Part
import com.xmrip.app.MainActivity
import com.xmrip.app.protocol.ListeningMode
import com.xmrip.app.protocol.MdrConstants

/** Son : égaliseur (préréglages et courbe à 10 bandes), mode d'écoute, DSEE et priorité de la liaison. */
class SoundPage(private val activity: MainActivity, private val ui: Ui, private val ctrl: HeadsetController) : Page {
    override val view: LinearLayout = ui.column()

    private data class Preset(val code: Int, val name: String, val hint: String)

    private val presets = listOf(
        Preset(MdrConstants.EQ_OFF, "Neutre", "Sans effet"),
        Preset(MdrConstants.EQ_XM6_HEAVY, "Heavy", "Basses"),
        Preset(MdrConstants.EQ_XM6_CLEAR, "Clear", "Voix"),
        Preset(MdrConstants.EQ_XM6_HARD, "Hard", "Punch"),
        Preset(MdrConstants.EQ_XM6_SOFT, "Soft", "Douceur"),
        Preset(MdrConstants.EQ_XM6_CUSTOM, "Perso", "Ta courbe"),
    )
    private val presetTiles = HashMap<Int, Pair<LinearLayout, List<TextView>>>()
    private val eqCurrent = ui.text("", 13f, Colors.ACCENT, ui.medium)
    private val eqGraph = EqGraph(
        activity, ui,
        listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"),
        MdrConstants.EQ_BAND_GAIN_MIN, MdrConstants.EQ_BAND_GAIN_MAX,
    )
    private val eqHint = ui.dim("", 12.5f)
    private val flatButton = ui.ghostButton("Remettre à plat")

    private val listening = ui.segmented(listOf("Standard", "Musique de fond", "Cinéma"))
    private val listenNote = ui.dim("", 12.5f)
    private val roomBlock = ui.column()
    private val room = ui.segmented(listOf("Chambre", "Salon", "Café"))
    private val modes = listOf(ListeningMode.STANDARD, ListeningMode.BGM, ListeningMode.CINEMA)
    private val rooms = listOf(MdrConstants.ROOM_SMALL, MdrConstants.ROOM_MIDDLE, MdrConstants.ROOM_LARGE)
    private var lastRoom = MdrConstants.ROOM_SMALL

    private val dsee = ui.toggleRow("DSEE Extreme", "Recrée les aigus perdus par la compression des fichiers") { on ->
        ctrl.changeSetting("DSEE…", { it.copy(dsee = on) }) { it.setDsee(on) }
    }
    private val priority = ui.segmented(listOf("Qualité du son", "Connexion stable"))

    init {
        view.addView(pageTitle(ui, "Son", "Égaliseur et rendu du casque"), ui.matchLp(bottom = 16))
        buildEq()
        buildListening()
        buildQuality()
        render()
    }

    private fun buildEq() {
        val panel = ui.panel()
        val head = ui.row()
        head.addView(ui.label("Égaliseur"), ui.lp(0, weight = 1f))
        head.addView(eqCurrent)
        panel.addView(head)
        presets.chunked(3).forEachIndexed { r, chunk ->
            val row = ui.row()
            chunk.forEachIndexed { i, p ->
                val tile = ui.column().apply {
                    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                    isClickable = true
                    setOnClickListener { ctrl.setEqPreset(p.code) }
                }
                val name = ui.text(p.name, 15f, Colors.TEXT, ui.bold).apply { maxLines = 1 }
                val hint = ui.text(p.hint, 11.5f, Colors.TEXT_DIM).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                tile.addView(name)
                tile.addView(hint, ui.matchLp(top = 4))
                presetTiles[p.code] = tile to listOf(name, hint)
                // Hauteur de la rangée pour toutes les tuiles, même si un texte est plus long.
                row.addView(tile, ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
            }
            panel.addView(row, ui.matchLp(top = if (r == 0) 14 else 8))
        }
        eqGraph.onChange = { gains -> ctrl.setEqBands(gains.toList()) }
        panel.addView(eqGraph, ui.matchLp(top = 18))
        activity.keepHorizontalGestures(eqGraph)
        panel.addView(eqHint, ui.matchLp(top = 6))
        val buttons = ui.row()
        flatButton.setOnClickListener { ctrl.setEqBands(List(MdrConstants.EQ_XM6_BAND_COUNT) { 0 }) }
        val reread = ui.ghostButton("Relire").apply { setOnClickListener { ctrl.readEq() } }
        buttons.addView(flatButton, ui.lp(0, weight = 1f))
        buttons.addView(reread, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) })
        panel.addView(buttons, ui.matchLp(top = 12))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildListening() {
        val panel = ui.panel("Mode d'écoute")
        listening.onSelect = { i ->
            val mode = modes[i]
            ctrl.changeSetting("Mode d'écoute…", {
                it.copy(bgm = mode == ListeningMode.BGM, cinema = mode == ListeningMode.CINEMA)
            }) { it.setListeningMode(mode, lastRoom) }
        }
        panel.addView(listening.view, ui.matchLp(top = 14))
        panel.addView(listenNote, ui.matchLp(top = 10))
        room.onSelect = { i ->
            lastRoom = rooms[i]
            ctrl.changeSetting("Ambiance…", { it.copy(bgmRoom = rooms[i]) }) { it.setListeningMode(ListeningMode.BGM, rooms[i]) }
        }
        roomBlock.addView(ui.label("Ambiance de la pièce"), ui.matchLp(top = 16))
        roomBlock.addView(room.view, ui.matchLp(top = 10))
        panel.addView(roomBlock, ui.matchLp())
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildQuality() {
        val panel = ui.panel("Qualité")
        panel.addView(dsee.view, ui.matchLp(top = 12))
        panel.addView(ui.divider(), ui.matchLp(top = 14).apply { height = ui.dp(1) })
        panel.addView(ui.text("Priorité de la liaison Bluetooth", 15f, Colors.TEXT, ui.medium), ui.matchLp(top = 14))
        panel.addView(
            ui.dim("« Qualité » garde le meilleur codec (LDAC si le téléphone le permet) ; « Connexion stable » évite les coupures dans les endroits encombrés.", 12.5f),
            ui.matchLp(top = 4),
        )
        priority.onSelect = { i ->
            val value = if (i == 0) MdrConstants.PRIORITY_SOUND_QUALITY else MdrConstants.PRIORITY_CONNECTION
            ctrl.changeSetting("Priorité…", { it.copy(connectionPriority = value) }) { it.setConnectionPriority(value) }
        }
        panel.addView(priority.view, ui.matchLp(top = 12))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    override fun shown() {
        if (!ctrl.connected) return
        if (!ctrl.eqLoaded) ctrl.readEq()
        if (!ctrl.settingsLoaded) ctrl.readSettings()
    }

    override fun changed(part: Part) {
        if (part == Part.EQ || part == Part.SETTINGS || part == Part.CONNECTION) render()
    }

    private fun render() {
        val eq = ctrl.eq
        presets.forEach { p ->
            val (tile, texts) = presetTiles[p.code] ?: return@forEach
            val on = eq?.preset == p.code
            tile.background = if (on) ui.pressable(ui.gradient(Colors.ACCENT, Colors.ACCENT_2, 18), 18)
            else ui.pressable(ui.rounded(Colors.SURFACE_HI, 18, Colors.STROKE), 18)
            texts[0].setTextColor(if (on) Color.WHITE else Colors.TEXT)
            texts[1].setTextColor(if (on) withAlpha(Color.WHITE, 0xC8) else Colors.TEXT_DIM)
        }
        val custom = eq?.preset == MdrConstants.EQ_XM6_CUSTOM
        eqCurrent.text = eq?.label ?: if (ctrl.eqLoaded) "" else "non lu"
        eqGraph.isEnabled = custom
        if (eq != null && eq.bands.size == MdrConstants.EQ_XM6_BAND_COUNT) eqGraph.set(eq.bands)
        eqGraph.invalidate()
        eqHint.text = when {
            eq == null -> "Ouvre cette page casque connecté pour lire l'égaliseur."
            custom -> "Fais glisser une bande vers le haut ou le bas : la courbe part au relâché."
            else -> "Courbe du préréglage « ${eq.label} ». Choisis « Perso » pour la modifier."
        }
        flatButton.visibility = if (custom) View.VISIBLE else View.GONE

        val s = ctrl.settings
        val mode = s?.listeningMode
        listening.select(mode?.let { modes.indexOf(it) })
        listening.setEnabled(mode != null)
        s?.bgmRoom?.let { lastRoom = it }
        roomBlock.visibility = if (mode == ListeningMode.BGM) View.VISIBLE else View.GONE
        room.select(rooms.indexOf(s?.bgmRoom).takeIf { it >= 0 })
        listenNote.text = when (mode) {
            ListeningMode.BGM -> "La musique semble venir d'enceintes dans la pièce, en arrière-plan."
            ListeningMode.CINEMA -> "Son spatialisé pour les films et les séries."
            ListeningMode.STANDARD -> "Rendu habituel du casque. L'égaliseur s'applique."
            null -> "Pas encore lu."
        }
        dsee.set(s?.dsee)
        val p = s?.connectionPriority
        priority.select(when (p) {
            MdrConstants.PRIORITY_SOUND_QUALITY -> 0
            MdrConstants.PRIORITY_CONNECTION -> 1
            else -> null
        })
        priority.setEnabled(p != null)
    }
}

/** Titre de page : grand titre et sous-titre discret. */
fun pageTitle(ui: Ui, title: String, subtitle: String): View {
    val col = ui.column()
    col.addView(ui.text(title, 30f, Colors.TEXT, ui.bold))
    col.addView(ui.dim(subtitle, 13.5f), ui.matchLp(top = 6))
    return col
}
