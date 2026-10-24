package com.xmrip.app.ui

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.xmrip.app.HeadsetCache
import com.xmrip.app.HeadsetController
import com.xmrip.app.HeadsetController.Conn
import com.xmrip.app.HeadsetController.Part
import com.xmrip.app.MainActivity
import com.xmrip.app.MediaListenerService
import com.xmrip.app.R
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import kotlin.math.abs
import kotlin.math.roundToInt

/** Une page de l'app : sa vue, et ce qu'elle fait quand l'état du casque change ou qu'elle s'affiche. */
interface Page {
    val view: View
    fun changed(part: Part)
    fun shown() {}
    fun tick() {}
}

/**
 * Cadre qui reconnaît un glissement horizontal (au-dessus de ses boutons) : [onSwipe] reçoit +1 pour « suivant »
 * (glissé vers la gauche) et -1 pour « précédent ». Un simple appui hors des boutons déclenche son clic.
 */
class SwipeFrame(context: Context) : FrameLayout(context) {
    var onSwipe: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (isHorizontal(ev)) {
                swiping = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    private fun isHorizontal(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        return dx > slop * 2 && dx > abs(ev.y - downY) * 1.5f
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && isHorizontal(ev)) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (swiping) translationX = (ev.x - downX) * 0.3f
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                animate().translationX(0f).setDuration(220).start()
                if (swiping && abs(dx) > width * 0.15f) onSwipe?.invoke(if (dx < 0) 1 else -1)
                else if (!swiping) performClick()
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().translationX(0f).setDuration(220).start()
                swiping = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Accueil : batterie, grande carte du mode de bruit, son ambiant, raccourcis, lecture en cours et volume. */
class HomePage(private val activity: MainActivity, private val ui: Ui, private val ctrl: HeadsetController) : Page {
    override val view: LinearLayout = ui.column()

    private val hero = HeroArt(activity, ui)
    private val battery = ui.text("— %", 30f, Colors.TEXT, ui.light)
    private val batteryNote = ui.dim("batterie", 12.5f)
    private val title = ui.text("XmRip", 22f, Colors.TEXT, ui.bold).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val dot = Dot(activity)
    private val status = ui.dim("", 13f)
    private val detail = ui.text("", 12.5f, Colors.TEXT_FAINT)

    private val statePanel = ui.panel()
    private val stateTitle = ui.text("", 17f, Colors.TEXT, ui.bold)
    private val stateText = ui.dim("", 13.5f)
    private val stateSpinner = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
        indeterminateTintList = ColorStateList.valueOf(Colors.ACCENT)
    }
    private val stateActions = ui.column()

    private val modeCard = SwipeFrame(activity)
    private val modeBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Mood.UNKNOWN.start, Mood.UNKNOWN.end))
    private val rings = RingsDrawable(activity.resources.displayMetrics.density)
    private val modeTitle = ui.text("", 28f, Color.WHITE, ui.bold)
    private val modeSubtitle = ui.text("", 14f, withAlpha(Color.WHITE, 0xD0))
    private val modeItems = ArrayList<Triple<LinearLayout, ImageView, TextView>>()
    private var mood = Mood.UNKNOWN
    private var moodAnim: ValueAnimator? = null

    private val ambientPanel = ui.panel("Son ambiant")
    private val ambientSlider = LevelSlider(activity, ui, 1, MdrConstants.AMBIENT_LEVEL_MAX)
    private val autoRow = ui.toggleRow("Automatique", "Le casque règle le niveau selon le bruit autour de toi") { ctrl.setAutoAmbient(it) }

    private val chips = LinkedHashMap<String, TextView>()

    private val volumeSlider = LevelSlider(activity, ui, 0, MdrConstants.PLAY_VOLUME_MAX)
    private val muteButton = ui.iconButton(R.drawable.ic_ui_volume, "Couper le son", 52)
    private var unmuteVolume = 10

    // Lecture en cours
    private val playerPanel = ui.panel()
    private val art = ImageView(activity)
    private val trackTitle = ui.text("Rien en lecture", 16.5f, Colors.TEXT, ui.medium)
    private val trackArtist = ui.dim("", 13.5f)
    private val trackSource = ui.text("", 11.5f, Colors.TEXT_FAINT)
    private val seekGroup = ui.column()
    private val seek = SeekBar(activity)
    private val timeNow = ui.text("0:00", 11.5f, Colors.TEXT_FAINT)
    private val timeTotal = ui.text("0:00", 11.5f, Colors.TEXT_FAINT).apply { gravity = Gravity.END }
    private val playButton = ui.iconButton(R.drawable.ic_ui_play, "Lecture", 62, Colors.ACCENT, Color.WHITE)
    private val accessGroup = ui.column()
    private var isSeeking = false
    private var isPlaying = false
    private var controller: MediaController? = null
    private var shownArt: Bitmap? = null
    private var lastPhoneVolume = -1

    init {
        buildHero()
        buildStatePanel()
        buildModeCard()
        buildAmbient()
        buildChips()
        buildPlayer()
        buildVolume()
        renderAll()
    }

    // --- Construction ------------------------------------------------------------------------

    /** Casque animé dans l'anneau de batterie, et à côté : nom, état de la liaison, batterie, autonomie et firmware. */
    private fun buildHero() {
        val row = ui.row()
        row.addView(hero, ui.lp(ui.dp(150), ui.dp(150)))
        val texts = ui.column()
        texts.addView(title)
        val line = ui.row()
        line.addView(dot, ui.lp(ui.dp(14), ui.dp(14)).apply { rightMargin = ui.dp(6) })
        line.addView(status, ui.lp(0, weight = 1f))
        texts.addView(line, ui.matchLp(top = 8))
        val batteryRow = ui.row(Gravity.BOTTOM)
        batteryRow.addView(battery)
        batteryRow.addView(batteryNote, ui.wrapLp(left = 8).apply { bottomMargin = ui.dp(5) })
        texts.addView(batteryRow, ui.matchLp(top = 12))
        texts.addView(detail, ui.matchLp(top = 4))
        row.addView(texts, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(16) })
        view.addView(row, ui.matchLp(top = 2, bottom = 16))
    }

    private fun buildStatePanel() {
        val head = ui.row()
        head.addView(stateTitle, ui.lp(0, weight = 1f))
        head.addView(stateSpinner, ui.lp(ui.dp(22), ui.dp(22)))
        statePanel.addView(head)
        statePanel.addView(stateText, ui.matchLp(top = 8))
        statePanel.addView(stateActions, ui.matchLp(top = 6))
        view.addView(statePanel, ui.matchLp(bottom = 14))
    }

    private fun buildModeCard() {
        modeBg.cornerRadius = ui.dpF(30f)
        modeCard.background = LayerDrawable(arrayOf(modeBg, rings))
        // Les cercles décoratifs débordent du dessin : on coupe à la forme arrondie de la carte.
        modeCard.clipToOutline = true
        modeCard.isClickable = true
        modeCard.setOnClickListener { ctrl.setMode(com.xmrip.app.QuickMode.next(currentMode())) }
        modeCard.onSwipe = { dir ->
            val order = listOf(MdrSession.MODE_NC, MdrSession.MODE_AMBIENT, MdrSession.MODE_OFF)
            val i = order.indexOf(currentMode()).coerceAtLeast(0)
            ctrl.setMode(order[(i + dir + order.size) % order.size])
        }
        val content = ui.column().apply { setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(20)) }
        val top = ui.row()
        top.addView(ui.label("Contrôle du bruit", withAlpha(Color.WHITE, 0xB8)), ui.lp(0, weight = 1f))
        top.addView(ui.text("‹ glisse ›", 11.5f, withAlpha(Color.WHITE, 0x80), ui.medium))
        content.addView(top)
        content.addView(modeTitle, ui.matchLp(top = 18))
        content.addView(modeSubtitle, ui.matchLp(top = 6))
        val buttons = ui.row()
        listOf(
            Triple(MdrSession.MODE_NC, R.drawable.ic_ui_nc, "Réduction"),
            Triple(MdrSession.MODE_AMBIENT, R.drawable.ic_ui_ambient, "Ambiant"),
            Triple(MdrSession.MODE_OFF, R.drawable.ic_ui_off, "Désactivé"),
        ).forEachIndexed { i, (mode, iconRes, label) ->
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                isClickable = true
                setOnClickListener { ctrl.setMode(mode) }
            }
            val icon = ui.icon(iconRes, Color.WHITE, 22)
            val text = ui.text(label, 12f, Color.WHITE, ui.medium).apply { gravity = Gravity.CENTER }
            item.addView(icon)
            item.addView(text, ui.matchLp(top = 6))
            modeItems.add(Triple(item, icon, text))
            buttons.addView(item, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        content.addView(buttons, ui.matchLp(top = 24))
        modeCard.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        activity.keepHorizontalGestures(modeCard)
        view.addView(modeCard, ui.matchLp(bottom = 14))
    }

    private fun buildAmbient() {
        ambientSlider.title = "Niveau"
        ambientSlider.setColors(Mood.AMBIENT.start, Mood.AMBIENT.end)
        ambientSlider.onChange = { v, final -> if (final) ctrl.setAmbientLevel(v) }
        ambientPanel.addView(ambientSlider, ui.matchLp(top = 14))
        activity.keepHorizontalGestures(ambientSlider)
        ambientPanel.addView(autoRow.view, ui.matchLp(top = 14))
        view.addView(ambientPanel, ui.matchLp(bottom = 14))
    }

    private fun buildChips() {
        val row = ui.row()
        listOf("chat" to "Speak-to-Chat", "dsee" to "DSEE Extreme", "wear" to "Pause au retrait").forEachIndexed { i, (key, label) ->
            val chip = ui.text(label, 12.5f, Colors.TEXT_DIM, ui.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
                isClickable = true
                setOnClickListener { toggleChip(key) }
            }
            chips[key] = chip
            row.addView(chip, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        view.addView(row, ui.matchLp(bottom = 14))
    }

    private fun toggleChip(key: String) {
        val s = ctrl.settings ?: return
        when (key) {
            "chat" -> s.speakToChat?.let { on -> ctrl.changeSetting("Speak-to-Chat…", { it.copy(speakToChat = !on) }) { it.setSpeakToChat(!on) } }
            "dsee" -> s.dsee?.let { on -> ctrl.changeSetting("DSEE…", { it.copy(dsee = !on) }) { it.setDsee(!on) } }
            "wear" -> s.pauseWhenRemoved?.let { on -> ctrl.changeSetting("Pause au retrait…", { it.copy(pauseWhenRemoved = !on) }) { it.setPauseWhenRemoved(!on) } }
        }
    }

    private fun buildPlayer() {
        val info = ui.row()
        val size = ui.dp(72)
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.background = ui.rounded(Colors.SURFACE_TOP, 16)
        art.clipToOutline = true
        art.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ui.dpF(16f))
            }
        }
        info.addView(art, ui.lp(size, size))
        val texts = ui.column()
        trackTitle.maxLines = 2
        trackTitle.ellipsize = TextUtils.TruncateAt.END
        trackArtist.maxLines = 1
        trackArtist.ellipsize = TextUtils.TruncateAt.END
        texts.addView(ui.label("Lecture en cours"))
        texts.addView(trackTitle, ui.matchLp(top = 8))
        texts.addView(trackArtist, ui.matchLp(top = 4))
        texts.addView(trackSource, ui.matchLp(top = 4))
        info.addView(texts, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(16) })
        playerPanel.addView(info)

        seek.progressBackgroundTintList = ColorStateList.valueOf(if (Colors.light) 0xFFC9CDD8.toInt() else Colors.SURFACE_TOP)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                controller?.transportControls?.seekTo((seekBar?.progress ?: return).toLong())
            }
        })
        seekGroup.addView(seek, ui.matchLp(top = 10))
        val times = ui.row()
        times.addView(timeNow, ui.lp(0, weight = 1f))
        times.addView(timeTotal, ui.lp(0, weight = 1f))
        seekGroup.addView(times, ui.matchLp().apply {
            leftMargin = ui.dp(14)
            rightMargin = ui.dp(14)
        })
        seekGroup.visibility = View.GONE
        playerPanel.addView(seekGroup, ui.matchLp())

        val controls = ui.row(Gravity.CENTER)
        val prev = ui.iconButton(R.drawable.ic_ui_prev, "Piste précédente", 50, Color.TRANSPARENT).apply {
            setOnClickListener { transport(MdrConstants.PLAY_CTRL_TRACK_DOWN) { it.skipToPrevious() } }
        }
        val next = ui.iconButton(R.drawable.ic_ui_next, "Piste suivante", 50, Color.TRANSPARENT).apply {
            setOnClickListener { transport(MdrConstants.PLAY_CTRL_TRACK_UP) { it.skipToNext() } }
        }
        playButton.setOnClickListener { togglePlay() }
        controls.addView(prev)
        controls.addView(playButton, ui.lp(ui.dp(62), ui.dp(62)).apply {
            leftMargin = ui.dp(28)
            rightMargin = ui.dp(28)
        })
        controls.addView(next)
        playerPanel.addView(controls, ui.matchLp(top = 12))

        accessGroup.addView(
            ui.dim("Pour voir le titre et la pochette, autorise XmRip dans « Accès aux notifications ».", 12.5f),
            ui.matchLp(top = 12),
        )
        accessGroup.addView(ui.ghostButton("Autoriser l'accès").apply {
            setOnClickListener {
                runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }, ui.matchLp(top = 10))
        accessGroup.visibility = View.GONE
        playerPanel.addView(accessGroup, ui.matchLp())
        showArt(null)
        activity.keepHorizontalGestures(seek)
        view.addView(playerPanel, ui.matchLp(bottom = 14))
    }

    private fun buildVolume() {
        val panel = ui.panel("Volume du casque")
        val row = ui.row()
        volumeSlider.title = "Volume"
        volumeSlider.format = { if (it == 0) "Muet" else "$it" }
        volumeSlider.onChange = { v, final ->
            if (v > 0) unmuteVolume = v
            if (final || abs(v - (ctrl.report?.musicVolume ?: v)) >= 1) ctrl.setVolume(v)
        }
        row.addView(volumeSlider, ui.lp(0, weight = 1f))
        activity.keepHorizontalGestures(volumeSlider)
        muteButton.setOnClickListener {
            val current = ctrl.report?.musicVolume ?: 0
            if (current > 0) {
                unmuteVolume = current
                ctrl.setVolume(0)
            } else {
                ctrl.setVolume(unmuteVolume.coerceAtLeast(1))
            }
        }
        row.addView(muteButton, ui.lp(ui.dp(52), ui.dp(52)).apply { leftMargin = ui.dp(10) })
        panel.addView(row, ui.matchLp(top = 14))
        view.addView(panel, ui.matchLp(bottom = 14))
    }

    // --- Affichage ---------------------------------------------------------------------------

    override fun changed(part: Part) {
        when (part) {
            Part.CONNECTION -> {
                renderState()
                renderHero()
            }
            Part.REPORT -> {
                renderHero()
                renderMode()
                renderVolume()
            }
            Part.SETTINGS -> renderChips()
            else -> Unit
        }
    }

    private fun renderAll() {
        renderState()
        renderHero()
        renderMode()
        renderChips()
        renderVolume()
    }

    private fun currentMode(): Int? = HeadsetCache.modeOf(ctrl.report?.ncAsm) ?: HeadsetCache.load(activity).mode

    private fun renderHero() {
        val r = ctrl.report
        val cache = HeadsetCache.load(activity)
        title.text = ctrl.deviceName.takeIf { ctrl.device != null || cache.name == null } ?: cache.name ?: "XmRip"
        val level = r?.levelPercent ?: cache.battery
        val charging = if (r != null) r.chargingStatus == MdrConstants.CHARGING_CHARGING else cache.charging
        hero.setBattery(level, charging)
        hero.alpha = if (r == null && cache.battery != null) 0.6f else 1f
        hero.setConnecting(ctrl.conn is Conn.Connecting || ctrl.conn == Conn.Searching)
        battery.text = level?.let { "$it %" } ?: "— %"
        battery.alpha = if (r == null) 0.55f else 1f
        batteryNote.text = when {
            level == null -> "batterie"
            charging -> "en charge"
            r?.chargingStatus == MdrConstants.CHARGING_CHARGED -> "chargé"
            else -> "batterie"
        }
        batteryNote.setTextColor(if (charging) Colors.OK else Colors.TEXT_DIM)
        val (color, text) = when (val c = ctrl.conn) {
            Conn.Connected -> Colors.OK to "Connecté"
            is Conn.Connecting -> Colors.WARN to "Connexion…"
            Conn.Searching -> Colors.WARN to "Recherche…"
            is Conn.Failed -> Colors.DANGER to "Non connecté"
            is Conn.Choose -> Colors.WARN to "Choix du casque"
            else -> Colors.TEXT_FAINT to if (c is Conn.BluetoothOff) "Bluetooth désactivé" else "Non connecté"
        }
        dot.color = color
        status.text = text
        detail.text = if (r != null) {
            val nc = r.ncAsm?.enabled == true && r.ncAsm.mode == MdrConstants.NCASM_MODE_NC
            val hours = (r.levelPercent / 100f * if (nc) 30f else 40f).roundToInt()
            listOfNotNull(
                "≈ $hours h d'écoute".takeIf { r.chargingStatus != MdrConstants.CHARGING_CHARGING },
                r.firmwareVersion?.let { "firmware $it" },
            ).joinToString(" · ")
        } else if (cache.at > 0) {
            "Dernier état connu · ${ago(cache.at)}"
        } else {
            ""
        }
    }

    private fun ago(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000).toInt()
        return when {
            minutes < 1 -> "à l'instant"
            minutes < 60 -> "il y a $minutes min"
            minutes < 48 * 60 -> "il y a ${minutes / 60} h"
            else -> "il y a ${minutes / 1440} j"
        }
    }

    private fun renderState() {
        val c = ctrl.conn
        statePanel.visibility = if (c == Conn.Connected) View.GONE else View.VISIBLE
        stateActions.removeAllViews()
        stateSpinner.visibility = if (c is Conn.Connecting || c == Conn.Searching) View.VISIBLE else View.GONE
        fun action(label: String, primary: Boolean = false, onClick: () -> Unit) {
            val b = if (primary) ui.primaryButton(label) else ui.ghostButton(label)
            b.setOnClickListener { onClick() }
            stateActions.addView(b, ui.matchLp(top = 8))
        }
        when (c) {
            Conn.NoPermission -> {
                stateTitle.text = "Autorisation Bluetooth"
                stateText.text = "XmRip a besoin de l'accès « Appareils à proximité » pour parler au casque."
                action("Autoriser", primary = true) { activity.requestBluetoothPermission() }
            }
            Conn.NoBluetooth -> {
                stateTitle.text = "Bluetooth indisponible"
                stateText.text = "Ce téléphone n'a pas de Bluetooth utilisable."
            }
            Conn.BluetoothOff -> {
                stateTitle.text = "Bluetooth désactivé"
                stateText.text = "Active le Bluetooth, puis réessaie."
                action("Réglages Bluetooth", primary = true) { activity.openBluetoothSettings() }
                action("Réessayer") { ctrl.discover() }
            }
            Conn.NoHeadset -> {
                stateTitle.text = "Aucun casque appairé"
                stateText.text = "Appaire ton WH-1000XM6 dans les réglages Bluetooth du téléphone."
                action("Réglages Bluetooth", primary = true) { activity.openBluetoothSettings() }
                action("Réessayer") { ctrl.discover() }
            }
            Conn.Searching -> {
                stateTitle.text = "Recherche du casque"
                stateText.text = "Lecture des appareils Bluetooth appairés…"
            }
            is Conn.Choose -> {
                stateTitle.text = "Choisis ton casque"
                stateText.text = "Plusieurs appareils peuvent convenir. Seul le WH-1000XM6 est pris en charge."
                @Suppress("MissingPermission")
                for (d in c.devices) {
                    val row = ui.navRow(runCatching { d.name }.getOrNull() ?: d.address, d.address.uppercase(), R.drawable.ic_ui_bluetooth) { ctrl.select(d) }
                    stateActions.addView(row.view, ui.matchLp(top = 4))
                }
            }
            is Conn.Connecting -> {
                stateTitle.text = "Connexion à ${c.name}"
                stateText.text = "Le casque doit être allumé et à portée."
            }
            is Conn.Failed -> {
                stateTitle.text = "Connexion impossible"
                stateText.text = "${c.message}\n\nUne seule appli peut piloter le casque à la fois : ferme Sound Connect " +
                    "ou l'appli Windows s'ils sont ouverts."
                action("Réessayer", primary = true) { ctrl.connect() }
                action("Choisir un autre casque") { ctrl.discover(choose = true) }
            }
            Conn.Connected -> Unit
        }
        val dim = if (c == Conn.Connected) 1f else 0.6f
        modeCard.alpha = dim
        playerPanel.alpha = 1f
    }

    private fun renderMode() {
        val nc = ctrl.report?.ncAsm
        val mode = currentMode()
        val newMood = when (mode) {
            MdrSession.MODE_NC -> Mood.NC
            MdrSession.MODE_AMBIENT -> Mood.AMBIENT
            MdrSession.MODE_OFF -> Mood.OFF
            else -> Mood.UNKNOWN
        }
        modeTitle.text = HeadsetCache.modeLabel(mode)
        modeSubtitle.text = when (mode) {
            MdrSession.MODE_NC -> "Le monde s'efface autour de toi."
            MdrSession.MODE_AMBIENT -> when {
                nc == null -> "Tu entends ce qui t'entoure."
                nc.autoEnabled -> "Niveau automatique, selon le bruit autour de toi."
                else -> "Tu entends ce qui t'entoure · niveau ${nc.ambientLevel}."
            }
            MdrSession.MODE_OFF -> "Ni réduction de bruit ni son ambiant."
            else -> "Le casque n'a pas encore répondu."
        }
        modeItems.forEachIndexed { i, (item, icon, text) ->
            val m = listOf(MdrSession.MODE_NC, MdrSession.MODE_AMBIENT, MdrSession.MODE_OFF)[i]
            val on = m == mode
            item.background = ui.pressable(ui.rounded(if (on) Color.WHITE else withAlpha(Color.WHITE, 0x1E), 18), 18)
            val tint = if (on) blend(newMood.end, Color.BLACK, 0.35f) else Color.WHITE
            icon.imageTintList = ColorStateList.valueOf(tint)
            text.setTextColor(tint)
        }
        setMood(newMood)

        val ambient = mode == MdrSession.MODE_AMBIENT
        ambientPanel.visibility = if (ambient) View.VISIBLE else View.GONE
        val auto = nc?.autoEnabled == true
        autoRow.set(if (nc != null && ambient) auto else null)
        ambientSlider.isEnabled = !auto
        ambientSlider.set(if (nc != null && ambient && !auto) nc.ambientLevel else ctrl.ambientLevel)
        ambientSlider.invalidate()
    }

    private fun setMood(target: Mood) {
        if (target == mood) return
        val from = mood
        mood = target
        activity.setGlow(target.glow)
        hero.setMood(target)
        volumeSlider.setColors(if (target == Mood.UNKNOWN || target == Mood.OFF) Colors.ACCENT else target.start, if (target == Mood.UNKNOWN || target == Mood.OFF) Colors.ACCENT_2 else target.end)
        moodAnim?.cancel()
        moodAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener {
                val t = it.animatedValue as Float
                modeBg.colors = intArrayOf(blend(from.start, target.start, t), blend(from.end, target.end, t))
            }
            start()
        }
        rings.strength = if (target == Mood.NC) 1f else if (target == Mood.AMBIENT) 0.8f else 0.4f
    }

    private fun renderChips() {
        val s = ctrl.settings
        mapOf("chat" to s?.speakToChat, "dsee" to s?.dsee, "wear" to s?.pauseWhenRemoved).forEach { (key, value) ->
            val chip = chips[key] ?: return@forEach
            val on = value == true
            chip.background = if (on) ui.pressable(ui.gradient(Colors.ACCENT, Colors.ACCENT_2, 16, GradientDrawable.Orientation.LEFT_RIGHT), 16)
            else ui.pressable(ui.rounded(Colors.SURFACE, 16, Colors.STROKE), 16)
            chip.setTextColor(if (on) Color.WHITE else Colors.TEXT_DIM)
            chip.alpha = if (value == null) 0.45f else 1f
        }
    }

    private fun renderVolume() {
        val v = ctrl.report?.musicVolume
        volumeSlider.isEnabled = ctrl.report != null
        if (v != null) volumeSlider.set(v)
        volumeSlider.invalidate()
        muteButton.setImageResource(if (v == 0) R.drawable.ic_ui_mute else R.drawable.ic_ui_volume)
        muteButton.contentDescription = if (v == 0) "Rétablir le son" else "Couper le son"
    }

    // --- Lecture en cours (session média du téléphone) -------------------------------------

    override fun tick() {
        syncPhoneVolume()
        syncPlayback()
    }

    /** Le volume du téléphone suit celui du casque (AVRCP) : on le répercute à l'écran sans rien envoyer. */
    private fun syncPhoneVolume() {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val phone = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (phone == lastPhoneVolume) return
        val first = lastPhoneVolume < 0
        lastPhoneVolume = phone
        if (!first && max > 0) ctrl.showVolume((phone.toFloat() * MdrConstants.PLAY_VOLUME_MAX / max).roundToInt())
    }

    private fun syncPlayback() {
        val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val sessions = try {
            manager?.getActiveSessions(ComponentName(activity, MediaListenerService::class.java))
        } catch (e: SecurityException) {
            null
        }
        if (sessions == null) {
            controller = null
            seekGroup.visibility = View.GONE
            accessGroup.visibility = View.VISIBLE
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            setPlaying(am?.isMusicActive == true)
            showTrack(null, null, null, null)
            return
        }
        accessGroup.visibility = View.GONE
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        controller = c
        if (c == null) {
            setPlaying(false)
            seekGroup.visibility = View.GONE
            showTrack(null, null, null, null)
            return
        }
        setPlaying(c.playbackState?.state == PlaybackState.STATE_PLAYING)
        val meta = c.metadata
        syncSeek(c.playbackState, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        val t = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val a = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val source = runCatching {
            val pm = activity.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrNull()
        showTrack(t, a, bmp, source)
    }

    private fun syncSeek(state: PlaybackState?, duration: Long) {
        if (state == null || duration <= 0L) {
            seekGroup.visibility = View.GONE
            return
        }
        seekGroup.visibility = View.VISIBLE
        seek.isEnabled = state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        timeTotal.text = formatTime(duration)
        if (isSeeking) return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        seek.max = duration.toInt()
        seek.progress = position.toInt()
        timeNow.text = formatTime(position)
    }

    private fun formatTime(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun setPlaying(playing: Boolean) {
        isPlaying = playing
        playButton.setImageResource(if (playing) R.drawable.ic_ui_pause else R.drawable.ic_ui_play)
        playButton.contentDescription = if (playing) "Pause" else "Lecture"
    }

    private fun showTrack(t: String?, a: String?, bmp: Bitmap?, source: String?) {
        val shown = t ?: if (isPlaying) "Lecture en cours" else "Rien en lecture"
        if (trackTitle.text.toString() != shown) trackTitle.text = shown
        if (trackArtist.text.toString() != (a ?: "")) trackArtist.text = a ?: ""
        trackArtist.visibility = if (a.isNullOrEmpty()) View.GONE else View.VISIBLE
        val src = source?.let { "via $it" } ?: ""
        if (trackSource.text.toString() != src) trackSource.text = src
        trackSource.visibility = if (src.isEmpty()) View.GONE else View.VISIBLE
        if (bmp !== shownArt) showArt(bmp)
    }

    /**
     * Pochette, et teinte légère du panneau tirée de sa couleur moyenne (Thomas préfère cette version discrète au fond
     * flou de toute la pochette, essayé le 2026-10-25). Les commandes gardent l'accent de l'app.
     */
    private fun showArt(bmp: Bitmap?) {
        shownArt = bmp
        if (bmp != null) {
            art.imageTintList = null
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(bmp)
            val average = runCatching { Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0) }.getOrDefault(Colors.SURFACE)
            playerPanel.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blend(average, Colors.BG, 0.55f), Colors.SURFACE),
            ).apply {
                cornerRadius = ui.dpF(26f)
                setStroke(ui.dp(1), Colors.STROKE)
            }
        } else {
            art.setImageResource(R.drawable.ic_ui_music)
            art.scaleType = ImageView.ScaleType.CENTER
            art.imageTintList = ColorStateList.valueOf(Colors.TEXT_FAINT)
            playerPanel.background = ui.rounded(Colors.SURFACE, 26, Colors.STROKE)
        }
        tintPlayer(Colors.ACCENT, Colors.ACCENT_2)
    }

    private fun tintPlayer(start: Int, end: Int) {
        playButton.background = ui.pressable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            shape = GradientDrawable.OVAL
        }, 31)
        seek.progressTintList = ColorStateList.valueOf(start)
        seek.thumbTintList = ColorStateList.valueOf(if (Colors.light) end else Color.WHITE)
    }

    private fun togglePlay() {
        val target = !isPlaying
        setPlaying(target)
        val c = controller
        if (c != null) {
            if (target) c.transportControls.play() else c.transportControls.pause()
        } else {
            ctrl.playControl(if (target) MdrConstants.PLAY_CTRL_PLAY else MdrConstants.PLAY_CTRL_PAUSE)
        }
    }

    private fun transport(mdr: Int, viaSession: (MediaController.TransportControls) -> Unit) {
        val c = controller
        if (c != null) viaSession(c.transportControls) else ctrl.playControl(mdr)
    }
}
