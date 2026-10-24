package com.xmrip.app.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xmrip.app.GestureActions
import com.xmrip.app.GestureWatcher
import com.xmrip.app.HeadsetController
import com.xmrip.app.HeadsetController.Part
import com.xmrip.app.HeadsetWidget
import com.xmrip.app.MainActivity
import com.xmrip.app.NcTileService
import com.xmrip.app.NotificationReader
import com.xmrip.app.R
import com.xmrip.app.protocol.FrameLog
import com.xmrip.app.protocol.MdrConstants

/** Réglages du casque (conversation, port, guidage vocal, bouton, gestes, alimentation), outils et à propos. */
class SettingsPage(private val activity: MainActivity, private val ui: Ui, private val ctrl: HeadsetController) : Page {
    override val view: LinearLayout = ui.column()

    private val chat = ui.toggleRow("Speak-to-Chat", "La musique se met en pause quand tu parles") { on ->
        ctrl.changeSetting("Speak-to-Chat…", { it.copy(speakToChat = on) }) { it.setSpeakToChat(on) }
    }
    private val chatDelay = ui.segmented(listOf("Court", "Standard", "Long", "Jamais"), 12.5f)
    private val chatDelays = listOf(
        MdrConstants.STC_OFF_TIME_FAST, MdrConstants.STC_OFF_TIME_MID, MdrConstants.STC_OFF_TIME_SLOW, MdrConstants.STC_OFF_TIME_NONE,
    )
    private val chatSensitivity = ui.text("", 12f, Colors.TEXT_FAINT)

    private val wear = ui.toggleRow("Pause quand tu retires le casque", "La lecture reprend quand tu le remets") { on ->
        ctrl.changeSetting("Pause au retrait…", { it.copy(pauseWhenRemoved = on) }) { it.setPauseWhenRemoved(on) }
    }

    private val voice = ui.toggleRow("Annonces vocales", "« Bluetooth connecté », niveau de batterie, changement de mode…") { on ->
        ctrl.changeSetting("Guidage vocal…", { it.copy(voiceGuidance = on) }) { it.setVoiceGuidance(on) }
    }
    private val voiceVolume = ui.segmented(listOf("−2", "−1", "0", "+1", "+2"))

    private val ncButton = ui.segmented(listOf("Les trois", "Bruit et ambiant"), 12.5f)
    private val ncButton2 = ui.segmented(listOf("Bruit et normal", "Ambiant et normal"), 12.5f)
    private val ncFunctions = listOf(MdrConstants.NC_BUTTON_ALL, MdrConstants.NC_BUTTON_NC_ASM, MdrConstants.NC_BUTTON_NC_OFF, MdrConstants.NC_BUTTON_ASM_OFF)
    private val ncSummary = ui.dim("", 12.5f)

    private val gestureRows = LinkedHashMap<String, NavRow>()
    private val gestureNote = ui.dim("", 12.5f)
    private val overlayButton = ui.ghostButton("Autoriser l'ouverture d'applications")

    private val power = ui.segmented(listOf("Au retrait du casque", "Jamais"), 12.5f)
    private val powerSummary = ui.dim("", 12.5f)

    private val about = ui.dim("", 12.5f)

    init {
        view.addView(pageTitle(ui, "Réglages", "Casque, gestes et outils"), ui.matchLp(bottom = 16))
        buildAppearance()
        buildConversation()
        buildVoice()
        buildButton()
        buildGestures()
        buildPower()
        buildTools()
        buildAbout()
        render()
        refreshGestures()
    }

    /** Thème de l'app : comme le téléphone, clair ou sombre. L'écran se reconstruit aussitôt. */
    private fun buildAppearance() {
        val panel = ui.panel("Apparence")
        val modes = listOf(Appearance.SYSTEM, Appearance.LIGHT, Appearance.DARK)
        val seg = ui.segmented(listOf("Comme le téléphone", "Clair", "Sombre"), 12.5f)
        seg.select(modes.indexOf(Appearance.get(activity)).coerceAtLeast(0))
        seg.onSelect = { i ->
            if (modes[i] != Appearance.get(activity)) {
                Appearance.set(activity, modes[i])
                activity.recreate()
            }
        }
        panel.addView(seg.view, ui.matchLp(top = 14))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildConversation() {
        val panel = ui.panel("Conversation et port")
        panel.addView(chat.view, ui.matchLp(top = 12))
        panel.addView(ui.text("Reprise de la musique après une conversation", 14f, Colors.TEXT, ui.medium), ui.matchLp(top = 16))
        chatDelay.onSelect = { i ->
            val t = chatDelays[i]
            ctrl.changeSetting("Délai de reprise…", { it.copy(chatTimeout = t) }) { it.setChatTimeout(t) }
        }
        panel.addView(chatDelay.view, ui.matchLp(top = 10))
        panel.addView(chatSensitivity, ui.matchLp(top = 8))
        panel.addView(ui.divider(), ui.matchLp(top = 14).apply { height = ui.dp(1) })
        panel.addView(wear.view, ui.matchLp(top = 14))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildVoice() {
        val panel = ui.panel("Guidage vocal")
        panel.addView(voice.view, ui.matchLp(top = 12))
        panel.addView(ui.text("Volume des annonces", 14f, Colors.TEXT, ui.medium), ui.matchLp(top = 16))
        voiceVolume.onSelect = { i ->
            val v = i + MdrConstants.VOICE_GUIDANCE_VOLUME_MIN
            ctrl.changeSetting("Volume des annonces…", { it.copy(voiceGuidanceVolume = v) }) { it.setVoiceGuidanceVolume(v) }
        }
        panel.addView(voiceVolume.view, ui.matchLp(top = 10))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildButton() {
        val panel = ui.panel("Bouton de bruit", "Modes que le bouton de l'oreillette gauche fait défiler.")
        listOf(ncButton, ncButton2).forEachIndexed { row, seg ->
            seg.onSelect = { i ->
                val f = ncFunctions[row * 2 + i]
                ctrl.changeSetting("Bouton de bruit…", { it.copy(ncButton = f) }) { it.setNcButton(f) }
            }
            panel.addView(seg.view, ui.matchLp(top = if (row == 0) 14 else 8))
        }
        panel.addView(ncSummary, ui.matchLp(top = 10))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildGestures() {
        val panel = ui.panel(
            "Gestes du bouton de bruit",
            "Ce que fait le téléphone quand tu appuies deux ou trois fois de suite sur le bouton de bruit. Une action " +
                "choisie garde la liaison ouverte : l'appli Windows ne peut pas se connecter en même temps.",
        )
        listOf(GestureActions.DOUBLE to "Double appui", GestureActions.TRIPLE to "Triple appui").forEach { (gesture, title) ->
            val row = ui.navRow(title, "", R.drawable.ic_ui_touch) { chooseGestureAction(gesture, title) }
            gestureRows[gesture] = row
            panel.addView(row.view, ui.matchLp(top = 8))
        }
        panel.addView(gestureNote, ui.matchLp(top = 8))
        overlayButton.setOnClickListener {
            runCatching {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            }
        }
        panel.addView(overlayButton, ui.matchLp(top = 10))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildPower() {
        val panel = ui.panel("Alimentation")
        panel.addView(ui.text("Extinction automatique", 14f, Colors.TEXT, ui.medium), ui.matchLp(top = 12))
        val values = listOf(MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED, MdrConstants.AUTO_POWER_OFF_DISABLE)
        power.onSelect = { i ->
            ctrl.changeSetting("Extinction automatique…", { it.copy(autoPowerOff = values[i]) }) { it.setAutoPowerOff(values[i]) }
        }
        panel.addView(power.view, ui.matchLp(top = 10))
        panel.addView(powerSummary, ui.matchLp(top = 8))
        val off = ui.ghostButton("Éteindre le casque", danger = true).apply {
            setOnClickListener {
                ui.dialog()
                    .setTitle("Éteindre le casque ?")
                    .setMessage("Le casque s'éteint tout de suite et la musique s'arrête. Rallume-le avec son bouton d'alimentation.")
                    .setPositiveButton("Éteindre") { _, _ -> ctrl.powerOff() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
        panel.addView(off, ui.matchLp(top = 16))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildTools() {
        val panel = ui.panel("Outils")
        panel.addView(ui.navRow("Journal des échanges", "", R.drawable.ic_ui_log) { showLog() }.view, ui.matchLp(top = 6))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            panel.addView(ui.navRow("Ajouter la tuile aux réglages rapides", "", R.drawable.ic_ui_nc) { addTile() }.view)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            panel.addView(ui.navRow("Ajouter le widget à l'accueil", "", R.drawable.ic_ui_grid) { addWidget() }.view)
        }
        panel.addView(
            ui.dim("Raccourcis : appui long sur l'icône de XmRip pour changer de mode sans ouvrir l'app.", 12.5f),
            ui.matchLp(top = 8),
        )
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildAbout() {
        val panel = ui.panel("À propos")
        panel.addView(about, ui.matchLp(top = 10))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    // --- Affichage ---------------------------------------------------------------------------

    override fun shown() {
        refreshGestures()
        if (ctrl.connected && !ctrl.settingsLoaded) ctrl.readSettings()
    }

    override fun changed(part: Part) {
        if (part == Part.SETTINGS || part == Part.REPORT || part == Part.CONNECTION) render()
    }

    private fun render() {
        val s = ctrl.settings
        chat.set(s?.speakToChat)
        chatDelay.select(chatDelays.indexOf(s?.chatTimeout).takeIf { it >= 0 })
        chatDelay.setEnabled(s?.chatTimeout != null)
        chatSensitivity.text = when (s?.chatSensitivity) {
            MdrConstants.STC_DETECT_AUTO -> "Sensibilité de détection : automatique"
            MdrConstants.STC_DETECT_HIGH -> "Sensibilité de détection : haute"
            MdrConstants.STC_DETECT_LOW -> "Sensibilité de détection : basse"
            else -> ""
        }
        chatSensitivity.visibility = if (chatSensitivity.text.isEmpty()) View.GONE else View.VISIBLE
        wear.set(s?.pauseWhenRemoved)
        voice.set(s?.voiceGuidance)
        val vv = s?.voiceGuidanceVolume
        voiceVolume.select(vv?.let { it - MdrConstants.VOICE_GUIDANCE_VOLUME_MIN }?.takeIf { it in 0..4 })
        voiceVolume.setEnabled(vv != null)

        val f = s?.ncButton
        val index = ncFunctions.indexOf(f)
        ncButton.select(index.takeIf { it in 0..1 })
        ncButton2.select((index - 2).takeIf { it in 0..1 })
        ncButton.setEnabled(f != null)
        ncButton2.setEnabled(f != null)
        ncSummary.text = when (f) {
            null -> "Pas encore lu."
            MdrConstants.NC_BUTTON_ALL -> "Le bouton fait défiler : réduction, ambiant, désactivé."
            MdrConstants.NC_BUTTON_NC_ASM -> "Le bouton alterne réduction et ambiant."
            MdrConstants.NC_BUTTON_NC_OFF -> "Le bouton alterne réduction et désactivé."
            MdrConstants.NC_BUTTON_ASM_OFF -> "Le bouton alterne ambiant et désactivé."
            else -> "Code 0x%02X inconnu.".format(f)
        }

        val p = s?.autoPowerOff
        power.select(when (p) {
            MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED -> 0
            MdrConstants.AUTO_POWER_OFF_DISABLE -> 1
            else -> null
        })
        power.setEnabled(p != null)
        powerSummary.text = when (p) {
            null -> "Pas encore lu."
            MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED -> "Le casque s'éteint un moment après que tu l'as retiré."
            MdrConstants.AUTO_POWER_OFF_DISABLE -> "Le casque ne s'éteint jamais tout seul."
            else -> "Code 0x%02X (durée choisie ailleurs).".format(p)
        }

        val r = ctrl.report
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"
        about.text = listOfNotNull(
            "XmRip $version",
            r?.let { "${it.modelName ?: ctrl.deviceName} · firmware ${it.firmwareVersion ?: "?"}" },
            "Remplace Sony Sound Connect pour le WH-1000XM6. Une seule appli peut piloter le casque à la fois.",
            "Code sous licence MIT ; protocole d'après libmdr (MIT).",
        ).joinToString("\n")
    }

    // --- Gestes --------------------------------------------------------------------------------

    fun refreshGestures() {
        gestureRows.forEach { (gesture, row) -> row.value.text = GestureActions.label(activity, gesture) }
        val launches = gestureRows.keys.any { GestureActions.action(activity, it) == GestureActions.LAUNCH }
        val blocked = launches && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !android.provider.Settings.canDrawOverlays(activity)
        overlayButton.visibility = if (blocked) View.VISIBLE else View.GONE
        val note = GestureActions.note(activity)
        gestureNote.text = when {
            note.isNotEmpty() -> "Dernier geste : $note"
            GestureActions.anyEnabled(activity) -> "En attente d'un geste. Garde l'accès aux notifications accordé à XmRip."
            else -> "Aucune action choisie : la liaison n'est pas gardée ouverte."
        }
    }

    private fun chooseGestureAction(gesture: String, title: String) {
        val labels = arrayOf("Aucune action", "Lecture / pause", "Piste suivante", "Piste précédente", "Ouvrir une application…")
        val actions = listOf(GestureActions.NONE, GestureActions.PLAY_PAUSE, GestureActions.NEXT, GestureActions.PREVIOUS, GestureActions.LAUNCH)
        val current = actions.indexOf(GestureActions.action(activity, gesture)).coerceAtLeast(0)
        ui.dialog()
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (actions[which] == GestureActions.LAUNCH) chooseGestureApp(gesture) else applyGesture(gesture, actions[which], "")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseGestureApp(gesture: String) {
        Thread {
            val apps = AppPicker.load(activity)
            activity.runOnUiThread {
                val current = GestureActions.target(activity, gesture).takeIf { it.isNotEmpty() }
                AppPicker.show(activity, ui, "Application à ouvrir", apps, setOfNotNull(current), multi = false) { chosen ->
                    chosen.firstOrNull()?.let { applyGesture(gesture, GestureActions.LAUNCH, it) }
                }
            }
        }.start()
    }

    private fun applyGesture(gesture: String, action: String, target: String) {
        GestureActions.set(activity, gesture, action, target)
        GestureActions.setNote(activity, "")
        if (GestureActions.anyEnabled(activity)) {
            GestureWatcher.start(activity)
            GestureWatcher.retryNow()
        } else {
            GestureWatcher.stop(activity)
        }
        refreshGestures()
    }

    // --- Outils ----------------------------------------------------------------------------------

    private fun showLog() {
        val lines = FrameLog.snapshot()
        val text = ui.text(if (lines.isEmpty()) "Aucun échange pour l'instant." else lines.joinToString("\n"), 10.5f, Colors.TEXT_DIM).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        ui.dialog()
            .setTitle("Journal des échanges (${lines.size})")
            .setView(scroll)
            .setPositiveButton("Copier") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Journal XmRip", lines.joinToString("\n")))
                activity.showMessage("Journal copié.", false)
            }
            .setNeutralButton("Effacer") { _, _ -> FrameLog.clear() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val sbm = activity.getSystemService(StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            ComponentName(activity, NcTileService::class.java),
            activity.getString(R.string.tile_label),
            Icon.createWithResource(activity, R.drawable.ic_ui_nc),
            activity.mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tuile ajoutée aux réglages rapides."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "La tuile est déjà dans les réglages rapides."
                else -> null
            }
            if (msg != null) activity.showMessage(msg, false)
        }
    }

    private fun addWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = activity.getSystemService(AppWidgetManager::class.java)
        if (manager == null || !manager.isRequestPinAppWidgetSupported) {
            activity.showMessage("Ton écran d'accueil ne permet pas d'ajouter un widget depuis l'app : fais un appui long sur l'écran d'accueil, puis Widgets.", true)
            return
        }
        manager.requestPinAppWidget(ComponentName(activity, HeadsetWidget::class.java), null, null)
    }
}
