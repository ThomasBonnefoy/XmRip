package com.xmrip.app.ui

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.xmrip.app.HeadsetController
import com.xmrip.app.HeadsetController.Part
import com.xmrip.app.MainActivity
import com.xmrip.app.NotificationReader
import com.xmrip.app.PcQuiet
import com.xmrip.app.PcWatcher
import com.xmrip.app.R
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.PairedDevice

/**
 * Appareils : gestion multipoint (appareils appairés au casque, connexion, lecture, oubli, mode appairage), puis les
 * automatismes du téléphone qui dépendent de ces appareils (son des notifications coupé quand le PC est connecté,
 * lecture des notifications dans le casque).
 */
class DevicesPage(private val activity: MainActivity, private val ui: Ui, private val ctrl: HeadsetController) : Page {
    override val view: LinearLayout = ui.column()

    private val list = ui.column()
    private val pairing = ui.ghostButton("Mode appairage")
    private val listNote = ui.dim("", 12.5f)

    private val pcRow = ui.toggleRow("Couper le son des notifications", "Tant que ton ordinateur est connecté au casque") { on ->
        if (on) enablePcQuiet() else disablePcQuiet()
    }
    private val pcDevice = ui.navRow("Ordinateur", "aucun", R.drawable.ic_ui_laptop) { chooseWatchedDevice(null) }
    private val pcStatus = ui.dim("", 12.5f)
    private val pcAccess = ui.ghostButton("Autoriser « Ne pas déranger »")

    private val readerRow = ui.toggleRow("Lire les notifications", "Le casque te les lit à voix haute") { on ->
        if (on) enableReader() else disableReader()
    }
    private val readerApps = ui.navRow("Applications lues", "aucune", R.drawable.ic_ui_grid) { chooseReaderApps() }
    private val readerContent = ui.toggleRow("Lire aussi le texte", "Sinon seulement « Notification de … »") {
        NotificationReader.setReadsContent(activity, it)
    }
    private val readerAsleep = ui.toggleRow("Écran éteint seulement", "Rien n'est lu tant que l'écran est allumé") {
        NotificationReader.setOnlyWhenAsleep(activity, it)
    }
    private val readerMode = ui.segmented(listOf("Baissée, voix forte", "En pause"), 12.5f)
    private val readerStatus = ui.dim("", 12.5f)
    private val readerAccess = ui.ghostButton("Autoriser l'accès aux notifications")
    private var speaker: NotificationReader.Speaker? = null
    private val readerVoice = ui.navRow("Voix", "automatique", R.drawable.ic_ui_voice) { chooseVoice() }

    init {
        view.addView(pageTitle(ui, "Appareils", "Multipoint et automatismes du téléphone"), ui.matchLp(bottom = 16))
        buildMultipoint()
        buildPcQuiet()
        buildReader()
        renderDevices()
        refreshAutomations()
    }

    private fun buildMultipoint() {
        val panel = ui.panel("Appareils du casque", "Touche un appareil pour le connecter, lui passer la lecture ou l'oublier.")
        val actions = ui.row()
        val reload = ui.ghostButton("Actualiser").apply { setOnClickListener { ctrl.readDevices() } }
        pairing.setOnClickListener {
            val enable = ctrl.devices?.pairingMode != true
            ctrl.deviceAction(if (enable) "Mode appairage…" else "Fin du mode appairage…") {
                it.setPairingMode(enable)
                if (enable) "Casque visible : appaire le nouvel appareil depuis ses réglages Bluetooth." else null
            }
        }
        actions.addView(reload, ui.lp(0, weight = 1f))
        actions.addView(pairing, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) })
        panel.addView(actions, ui.matchLp(top = 14))
        panel.addView(list, ui.matchLp(top = 6))
        panel.addView(listNote, ui.matchLp(top = 8))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildPcQuiet() {
        val panel = ui.panel(
            "Notifications et ordinateur",
            "Quand tu écoutes ton PC dans le casque, les sons de notification du téléphone n'y passent plus. Les " +
                "vibrations et les pop-ups restent, et si c'est le téléphone qui joue du son, les notifications passent.",
        )
        panel.addView(pcRow.view, ui.matchLp(top = 14))
        panel.addView(pcDevice.view, ui.matchLp(top = 8))
        panel.addView(pcStatus, ui.matchLp(top = 4))
        pcAccess.setOnClickListener {
            runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        panel.addView(pcAccess, ui.matchLp(top = 10))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    private fun buildReader() {
        val panel = ui.panel(
            "Lecture des notifications",
            "Le casque lit les notifications des applications choisies, tant que ton ordinateur n'y est pas connecté. " +
                "Le son habituel de la notification reste.",
        )
        panel.addView(readerRow.view, ui.matchLp(top = 14))
        panel.addView(readerApps.view, ui.matchLp(top = 8))
        panel.addView(readerContent.view, ui.matchLp(top = 10))
        panel.addView(readerAsleep.view, ui.matchLp(top = 14))
        panel.addView(ui.label("Pendant la voix, la musique est"), ui.matchLp(top = 18))
        readerMode.onSelect = { i ->
            NotificationReader.setMode(activity, if (i == 0) NotificationReader.MODE_LOUD else NotificationReader.MODE_PAUSE)
            refreshAutomations()
        }
        panel.addView(readerMode.view, ui.matchLp(top = 10))
        panel.addView(readerStatus, ui.matchLp(top = 12))
        readerAccess.setOnClickListener {
            runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        panel.addView(readerAccess, ui.matchLp(top = 10))
        panel.addView(readerVoice.view, ui.matchLp(top = 8))
        val test = ui.ghostButton("Tester la voix").apply {
            setOnClickListener {
                speaker?.shutdown()
                speaker = NotificationReader.test(activity)
            }
        }
        panel.addView(test, ui.matchLp(top = 8))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    override fun shown() {
        refreshAutomations()
        if (ctrl.connected && !ctrl.devicesLoaded) ctrl.readDevices()
    }

    override fun changed(part: Part) {
        if (part == Part.DEVICES || part == Part.CONNECTION) renderDevices()
    }

    // --- Multipoint ---------------------------------------------------------------------------

    @Suppress("MissingPermission")
    private fun phoneName(): String? = runCatching { ctrl.adapter?.name }.getOrNull()

    private fun renderDevices() {
        val info = ctrl.devices
        list.removeAllViews()
        val pairingOn = info?.pairingMode == true
        pairing.text = if (pairingOn) "Appairage actif" else "Mode appairage"
        pairing.background = if (pairingOn) ui.pressable(ui.gradient(Colors.ACCENT, Colors.ACCENT_2, 16), 16)
        else ui.pressable(ui.rounded(Colors.SURFACE_TOP, 16), 16)
        pairing.setTextColor(if (pairingOn) Color.WHITE else Colors.TEXT)
        if (info == null) {
            listNote.text = if (ctrl.connected) "Liste pas encore lue." else "Connecte le casque pour voir ses appareils."
            return
        }
        listNote.text = "Le n°1 est l'appareil connecté depuis le plus longtemps ; le casque n'en garde que deux à la fois."
        val phone = phoneName()
        val devices = info.devices.sortedWith(compareBy<PairedDevice>({ !it.connected }, { if (it.connected) it.linkStatus else 0 }))
        if (devices.isEmpty()) {
            list.addView(ui.dim("Aucun appareil appairé au casque.", 13f), ui.matchLp(top = 10))
            return
        }
        for (d in devices) {
            val isPhone = phone != null && d.name == phone
            val item = ui.row().apply {
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = ui.pressable(ui.rounded(Colors.SURFACE_HI, 18, Colors.STROKE), 18)
                isClickable = true
                setOnClickListener { showDeviceActions(d, isPhone) }
            }
            val badge = ui.icon(if (isPhone) R.drawable.ic_ui_phone else R.drawable.ic_ui_bluetooth, if (d.connected) Color.WHITE else Colors.TEXT_DIM, 20)
            val badgeBox = android.widget.FrameLayout(activity).apply {
                background = if (d.connected) ui.gradient(Colors.ACCENT, Colors.ACCENT_2, 14) else ui.rounded(Colors.SURFACE_TOP, 14)
                addView(badge, ui.frameLp(ui.dp(20), ui.dp(20), Gravity.CENTER))
            }
            item.addView(badgeBox, ui.lp(ui.dp(42), ui.dp(42)))
            val texts = ui.column()
            texts.addView(ui.text(d.name.ifBlank { "(sans nom)" }, 15f, Colors.TEXT, ui.medium).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val pills = ui.row()
            pills.addView(ui.pill(if (d.connected) "Connecté n°${d.linkStatus}" else "Non connecté", if (d.connected) Colors.OK else Colors.TEXT_DIM))
            if (d.isPlaybackDevice) {
                pills.addView(ui.pill("Lecture", Color.WHITE, if (Colors.light) Colors.ACCENT else withAlpha(Colors.ACCENT, 0x66)), ui.wrapLp(left = 6))
            }
            if (isPhone) pills.addView(ui.pill("Ce téléphone"), ui.wrapLp(left = 6))
            texts.addView(pills, ui.matchLp(top = 6))
            item.addView(texts, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
            item.addView(ui.icon(R.drawable.ic_ui_chevron, Colors.TEXT_FAINT, 18))
            list.addView(item, ui.matchLp(top = 8))
        }
    }

    private fun showDeviceActions(dev: PairedDevice, isPhone: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val name = dev.name.ifBlank { dev.address }
        if (dev.connected) {
            if (dev.linkStatus != 1) {
                labels.add("Rendre prioritaire (n°1)")
                actions.add { makePriority(dev, name) }
            }
            if (!dev.isPlaybackDevice) {
                labels.add("Lire sur cet appareil")
                actions.add { ctrl.deviceAction("Bascule vers $name…") { describeSourceSwitch(it.switchSource(dev.address)) } }
            }
            labels.add(if (isPhone) "Déconnecter (coupe aussi ce contrôle)" else "Déconnecter")
            actions.add { ctrl.deviceAction("Déconnexion de $name…") { describeResult("Déconnexion", it.disconnectPairedDevice(dev.address)) } }
        } else {
            labels.add("Connecter")
            actions.add { ctrl.deviceAction("Connexion de $name…") { describeResult("Connexion", it.connectPairedDevice(dev.address)) } }
        }
        labels.add("Oublier cet appareil…")
        actions.add { confirmUnpair(dev, name) }
        ui.dialog()
            .setTitle(name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Le n°1 du casque est un emplacement fixe. Hypothèse [déduite] : il revient à l'appareil connecté depuis le plus
     * longtemps. Pour passer [dev] en n°1, on déconnecte le n°1 actuel puis on le reconnecte : il repasse n°2.
     */
    private fun makePriority(dev: PairedDevice, name: String) {
        val current = ctrl.devices?.devices?.firstOrNull { it.connected && it.linkStatus == 1 && it.address != dev.address }
        if (current == null) {
            activity.showMessage("$name est déjà prioritaire.", false)
            return
        }
        val phone = phoneName()
        val currentIsPhone = phone != null && current.name == phone
        val currentName = current.name.ifBlank { current.address }
        val message = if (currentIsPhone) {
            "Ce téléphone est n°1. L'app va le déconnecter du casque ; $name passera n°1. Reconnecte ensuite le " +
                "téléphone depuis les réglages Bluetooth (il deviendra n°2). La lecture sur ce téléphone sera coupée."
        } else {
            "$currentName est n°1. Il va être déconnecté puis reconnecté pour passer n°2 ; $name deviendra n°1. " +
                "La lecture sur $currentName sera brièvement coupée."
        }
        ui.dialog()
            .setTitle("$name prioritaire ?")
            .setMessage(message)
            .setPositiveButton("Continuer") { _, _ ->
                ctrl.deviceAction("$name devient prioritaire…") { s ->
                    if (currentIsPhone) {
                        runCatching { s.disconnectPairedDevice(current.address) }
                        "Téléphone déconnecté. Reconnecte-le au casque (réglages Bluetooth), puis actualise la liste."
                    } else {
                        val off = s.disconnectPairedDevice(current.address)
                        if (off and 0x0F != 0) {
                            describeResult("Déconnexion de $currentName", off)
                        } else {
                            val on = s.connectPairedDevice(current.address)
                            if (on and 0x0F == 0) "$name est maintenant prioritaire (vérifie les numéros)."
                            else describeResult("Reconnexion de $currentName", on)
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmUnpair(dev: PairedDevice, name: String) {
        ui.dialog()
            .setTitle("Oublier $name ?")
            .setMessage("L'appairage sera supprimé du casque ; il faudra le refaire pour réutiliser cet appareil.")
            .setPositiveButton("Oublier") { _, _ ->
                ctrl.deviceAction("Oubli de $name…") { describeResult("Suppression de l'appairage", it.unpairDevice(dev.address)) }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Résultat PeripheralResult : le quartet haut donne l'opération, le bas l'issue (0 = succès). */
    private fun describeResult(what: String, code: Int): String = when (code and 0x0F) {
        0x00 -> "$what réussie."
        0x01 -> "$what : erreur du casque."
        MdrConstants.PERIPHERAL_BUSY -> "$what : le casque est occupé, réessaie dans un instant."
        else -> "$what : réponse inattendue (0x${code.toString(16)})."
    }

    private fun describeSourceSwitch(code: Int): String = when (code) {
        MdrConstants.SOURCE_SWITCH_SUCCESS -> "Lecture basculée."
        MdrConstants.SOURCE_SWITCH_FAIL_CALLING -> "Bascule impossible : un appel est en cours."
        MdrConstants.SOURCE_SWITCH_FAIL_A2DP_NOT_CONNECT -> "Bascule impossible : l'appareil n'a pas de lien audio actif."
        MdrConstants.SOURCE_SWITCH_FAIL_VOICE_ASSISTANT -> "Bascule impossible : l'assistant vocal est prioritaire."
        else -> "Bascule refusée par le casque (code $code)."
    }

    // --- Automatismes (PcQuiet, NotificationReader) -------------------------------------------

    fun refreshAutomations() {
        val enabled = PcQuiet.isEnabled(activity)
        pcRow.set(enabled)
        pcDevice.value.text = PcQuiet.watchedName(activity) ?: "aucun"
        val needsAccess = enabled && PcQuiet.accessNeeded(activity)
        pcAccess.visibility = if (needsAccess) View.VISIBLE else View.GONE
        pcStatus.text = when {
            !enabled -> "Désactivé."
            needsAccess -> "Android demande l'accès « Ne pas déranger » pour changer ce volume : autorise XmRip."
            else -> PcQuiet.note(activity)?.takeIf { it.isNotBlank() } ?: "Surveillance en cours…"
        }

        val reading = NotificationReader.isEnabled(activity)
        readerRow.set(reading)
        readerContent.set(NotificationReader.readsContent(activity))
        readerAsleep.set(NotificationReader.onlyWhenAsleep(activity))
        readerMode.select(if (NotificationReader.mode(activity) == NotificationReader.MODE_LOUD) 0 else 1)
        val apps = NotificationReader.apps(activity)
        readerApps.value.text = if (apps.isEmpty()) "aucune" else "${apps.size} choisie(s)"
        readerVoice.value.text = NotificationReader.voiceName(activity)
            ?.let { Regex("-x-([a-z]+)").find(it)?.groupValues?.get(1)?.lastOrNull()?.uppercaseChar()?.let { l -> "voix $l" + if (it.endsWith("network")) " (en ligne)" else "" } ?: "choisie" }
            ?: "automatique"
        val noAccess = reading && !NotificationReader.hasListenerAccess(activity)
        readerAccess.visibility = if (noAccess) View.VISIBLE else View.GONE
        val last = NotificationReader.lastDecision(activity)
        readerStatus.text = when {
            !reading -> "Désactivé."
            noAccess -> "Autorise l'accès aux notifications pour XmRip, sinon rien ne peut être lu."
            apps.isEmpty() -> "Choisis au moins une application."
            PcQuiet.watchedAddress(activity) == null -> "Ordinateur non choisi : les notifications sont lues même si le PC est connecté."
            else -> (PcQuiet.note(activity)?.takeIf { it.isNotBlank() } ?: "Actif.") + (last?.let { "\nDernière notification : $it" } ?: "")
        }
    }

    private fun enablePcQuiet() {
        if (PcQuiet.watchedAddress(activity) == null) {
            chooseWatchedDevice { enablePcQuiet() }
            return
        }
        PcQuiet.setEnabled(activity, true)
        PcWatcher.start(activity)
        refreshAutomations()
    }

    private fun disablePcQuiet() {
        PcQuiet.setEnabled(activity, false)
        PcQuiet.setSilent(activity, false)
        PcWatcher.stop(activity)
        refreshAutomations()
    }

    /** Liste des appareils connus du casque (hors ce téléphone) ; [then] est appelé une fois le choix fait. */
    private fun chooseWatchedDevice(then: (() -> Unit)?) {
        val phone = phoneName()
        val candidates = ctrl.devices?.devices.orEmpty().filter { phone == null || it.name != phone }
        if (candidates.isEmpty()) {
            activity.showMessage("Charge d'abord la liste des appareils du casque (Actualiser), puis réessaie.", true)
            if (ctrl.connected && !ctrl.devicesLoaded) ctrl.readDevices()
            refreshAutomations()
            return
        }
        val labels = candidates.map { it.name.ifBlank { it.address } + if (it.connected) "  ·  connecté" else "" }
        ui.dialog()
            .setTitle("Quel appareil est ton ordinateur ?")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = candidates[which]
                PcQuiet.setWatched(activity, chosen.address, chosen.name.ifBlank { chosen.address })
                refreshAutomations()
                then?.invoke()
            }
            .setNegativeButton("Annuler") { _, _ -> refreshAutomations() }
            .setOnCancelListener { refreshAutomations() }
            .show()
    }

    /**
     * Choix de la voix : « automatique » prend la plus naturelle disponible (en ligne si internet est là). Chaque choix
     * est dit tout de suite pour l'entendre. Les voix supplémentaires s'installent dans les réglages Android.
     */
    private fun chooseVoice() {
        readerVoice.value.text = "chargement…"
        NotificationReader.listVoices(activity) { voices ->
            refreshAutomations()
            if (voices.isEmpty()) {
                activity.showMessage("Aucune voix française trouvée : installe-la dans les réglages Android (Synthèse vocale).", true)
                return@listVoices
            }
            val names = listOf<String?>(null) + voices.map { it.name }
            val labels = listOf("Automatique (la plus naturelle)") + voices.map { NotificationReader.voiceLabel(it) }
            val current = names.indexOf(NotificationReader.voiceName(activity)).coerceAtLeast(0)
            ui.dialog()
                .setTitle("Voix de lecture")
                .setSingleChoiceItems(labels.toTypedArray(), current) { _, which ->
                    NotificationReader.setVoiceName(activity, names[which])
                    refreshAutomations()
                    speaker?.shutdown()
                    speaker = NotificationReader.test(activity)
                }
                .setPositiveButton("OK", null)
                .setNeutralButton("Plus de voix…") { _, _ ->
                    runCatching { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure { activity.showMessage("Ouvre Réglages, puis Synthèse vocale.", true) }
                }
                .show()
        }
    }

    private fun enableReader() {
        NotificationReader.setEnabled(activity, true)
        if (NotificationReader.apps(activity).isEmpty()) chooseReaderApps()
        PcWatcher.start(activity)
        refreshAutomations()
    }

    private fun disableReader() {
        NotificationReader.setEnabled(activity, false)
        PcWatcher.stop(activity)
        refreshAutomations()
    }

    private fun chooseReaderApps() {
        Thread {
            val apps = AppPicker.load(activity)
            activity.runOnUiThread {
                AppPicker.show(activity, ui, "Applications à lire", apps, NotificationReader.apps(activity), multi = true) { chosen ->
                    NotificationReader.setApps(activity, chosen)
                    // Choisir des applications, c'est vouloir la lecture : on allume l'interrupteur.
                    if (chosen.isNotEmpty() && !NotificationReader.isEnabled(activity)) {
                        NotificationReader.setEnabled(activity, true)
                        PcWatcher.start(activity)
                    }
                    refreshAutomations()
                }
            }
        }.start()
    }
}
