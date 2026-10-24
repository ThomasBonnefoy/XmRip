package com.xmrip.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.EqState
import com.xmrip.app.protocol.ListeningMode
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.PairedDevice
import com.xmrip.app.protocol.PairedDevicesInfo
import com.xmrip.app.protocol.SettingsState
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_BLUETOOTH_CONNECT = 1001
        private const val UUID_SCAN_TIMEOUT_MS = 4_000L
        private const val ACTION_SET_NC_ASM = "com.xmrip.app.action.SET_NC_ASM"
        private const val EXTRA_NC_ASM_MODE = "com.xmrip.app.extra.NC_ASM_MODE"
        private const val TAB_SOUND = 0
        private const val TAB_EQ = 1
        private const val TAB_SETTINGS = 2
        private const val TAB_DEVICES = 3
    }

    private lateinit var kit: UiKit

    // En-tête et bandeau d'état
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var batteryBig: TextView
    private lateinit var refreshButton: ImageButton
    private lateinit var statusText: TextView

    // Onglet Son
    private lateinit var ncButton: Button
    private lateinit var ambButton: Button
    private lateinit var offButton: Button
    private lateinit var ncSummary: TextView
    private lateinit var ambientGroup: LinearLayout
    private lateinit var ambientSeekBar: SeekBar
    private lateinit var ambientLevelText: TextView
    private lateinit var autoAmbientCheck: Switch
    private lateinit var volumeValue: TextView
    private lateinit var volumeFill: View
    private lateinit var volumeRest: View
    private lateinit var muteButton: Button
    private val stepButtons = mutableListOf<Button>()

    // Choix du casque (affiché seulement s'il y en a plusieurs)
    private lateinit var deviceCard: LinearLayout
    private lateinit var deviceList: ListView

    // Navigation
    private val pages = mutableListOf<View>()
    private val tabLabels = mutableListOf<TextView>()
    private lateinit var pageScroll: ScrollView
    private var currentTab = TAB_SOUND
    private var eqLoaded = false
    private var settingsLoaded = false
    private var devicesLoaded = false

    private var lastVolume: Int = 20
    private var muted: Boolean = false
    private var volumeStep: Int = 1
    private var lastPhoneVolume: Int = -1
    private val batteryPollMs = 30_000L
    private val phoneVolumePoller = android.os.Handler(android.os.Looper.getMainLooper())
    private val phoneVolumeRunnable = object : Runnable {
        override fun run() {
            syncPhoneVolume()
            syncPlayback()
            phoneVolumePoller.postDelayed(this, 1000)
        }
    }
    private val batteryPoller = android.os.Handler(android.os.Looper.getMainLooper())
    private val batteryRunnable = object : Runnable {
        override fun run() {
            if (selectedDevice != null && !busy) refreshBattery(silent = true)
            batteryPoller.postDelayed(this, batteryPollMs)
        }
    }

    private var ncAsmState: com.xmrip.app.protocol.NcAsmState? = null
    private var lastAmbientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT
    private var suppressSeekBar = false
    private var suppressAutoCheck = false

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var selectedDevice: BluetoothDevice? = null
    private var requestedMode: Int? = null
    private var busy = false

    private val pendingDevices = mutableListOf<BluetoothDevice>()
    private val discoveredUuids = HashMap<String, List<ParcelUuid>>()
    private val uuidScanRunnable = Runnable { finishUuidScan() }

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val rawUuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            val uuids = rawUuids?.filterIsInstance<ParcelUuid>() ?: return
            discoveredUuids[device.address] = uuids
            if (pendingDevices.isNotEmpty() &&
                pendingDevices.all { discoveredUuids.containsKey(it.address) }
            ) {
                finishUuidScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestedMode = parseModeFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val mode = parseModeFromIntent(intent)
        if (mode != null) {
            requestedMode = mode
            val device = selectedDevice
            if (device != null && !busy) {
                setNcAsmMode(device, mode)
            } else {
                listDevices()
            }
        }
    }

    private fun parseModeFromIntent(intent: Intent?): Int? {
        if (intent?.action != ACTION_SET_NC_ASM) return null
        return when (intent.getStringExtra(EXTRA_NC_ASM_MODE)) {
            "noise_cancelling" -> MdrSession.MODE_NC
            "ambient" -> MdrSession.MODE_AMBIENT
            "off" -> MdrSession.MODE_OFF
            else -> null
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID))
        requestPermissionIfNeeded()
        phoneVolumePoller.post(phoneVolumeRunnable)
        batteryPoller.post(batteryRunnable)
        HeadphoneClient.addListener(liveListener)
        // Relance la surveillance si elle devait tourner (elle s'arrête après une mise à jour de l'app ou un arrêt forcé).
        if (PcQuiet.watchNeeded(this)) runCatching { PcWatcher.start(this) }
        if (GestureActions.anyEnabled(this)) runCatching { GestureWatcher.start(this) }
        runCatching { refreshGestureCard() }
        refreshPcQuiet()
        refreshReader()
    }

    override fun onPause() {
        super.onPause()
        HeadphoneClient.removeListener(liveListener)
        phoneVolumePoller.removeCallbacks(phoneVolumeRunnable)
        batteryPoller.removeCallbacks(batteryRunnable)
        runOnUiThread { deviceList.removeCallbacks(uuidScanRunnable) }
        runCatching { unregisterReceiver(uuidReceiver) }
    }

    /** Répercute le volume musique du téléphone (mis à jour par AVRCP, y compris quand le casque bouge son propre volume). */
    private fun syncPhoneVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val phone = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val maxPhone = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (phone == lastPhoneVolume) return
        lastPhoneVolume = phone
        if (maxPhone > 0 && !muted) {
            val headset = Math.round(phone.toFloat() * MdrConstants.PLAY_VOLUME_MAX / maxPhone)
                .coerceIn(0, MdrConstants.PLAY_VOLUME_MAX)
            lastVolume = headset
        }
        updateVolumeLabel()
    }

    // ---------------------------------------------------------------------------------------
    // Interface : en-tête, trois onglets (Son, Égaliseur, Appareils) et barre d'onglets
    // ---------------------------------------------------------------------------------------

    private fun buildUi() {
        kit = UiKit(this)
        val p = kit.p
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !kit.night) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
            // targetSdk récent : l'appli dessine sous les barres système, on décale le contenu.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }

        root.addView(buildHeader())

        statusText = TextView(this).apply {
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(statusText, kit.matchLp().apply {
            leftMargin = kit.dp(16)
            rightMargin = kit.dp(16)
            bottomMargin = kit.dp(8)
        })

        // Contenu : choix du casque (si besoin) puis la page de l'onglet courant.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(4), kit.dp(16), kit.dp(16))
        }
        deviceCard = kit.card("Choisis ton casque").apply { visibility = View.GONE }
        deviceList = ListView(this).apply {
            divider = null
            setBackgroundColor(0x00000000)
        }
        deviceCard.addView(deviceList, kit.matchLp(top = 8))
        content.addView(deviceCard, kit.matchLp(bottom = 12))

        val soundPage = buildSoundPage()
        val eqPage = buildEqPage()
        val settingsPage = buildSettingsPage()
        val devicesPage = buildDevicesPage()
        pages.addAll(listOf(soundPage, eqPage, settingsPage, devicesPage))
        pages.forEach { content.addView(it, kit.matchLp()) }

        pageScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        root.addView(pageScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildTabBar())

        setContentView(root)
        selectTab(TAB_SOUND)
        setStatus("Recherche du casque…")
    }

    private fun buildHeader(): View {
        val p = kit.p
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(20), kit.dp(14), kit.dp(16), kit.dp(10))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = kit.text("XmRip", 22f, bold = true)
        detailText = kit.text("Casque non connecté", 13f, secondary = true)
        titles.addView(titleText)
        titles.addView(detailText)
        header.addView(titles, kit.lp(0, weight = 1f))

        batteryBig = kit.text("—", 15f, bold = true).apply {
            background = kit.rounded(p.neutral, 18)
            setPadding(kit.dp(12), kit.dp(7), kit.dp(12), kit.dp(7))
        }
        header.addView(batteryBig, kit.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = kit.dp(8) })

        refreshButton = kit.iconButton(R.drawable.ic_ui_refresh, "Actualiser").apply {
            setOnClickListener {
                // Actualiser relit aussi l'onglet affiché (égaliseur, réglages ou appareils).
                eqLoaded = false
                settingsLoaded = false
                devicesLoaded = false
                if (selectedDevice != null) refreshBattery() else listDevices()
            }
        }
        header.addView(refreshButton, kit.lp(kit.dp(44), kit.dp(40)))
        return header
    }

    private fun buildTabBar(): View {
        val p = kit.p
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.card)
            setPadding(0, kit.dp(6), 0, kit.dp(6))
        }
        listOf(
            "Son" to R.drawable.ic_ui_sound,
            "Égaliseur" to R.drawable.ic_ui_eq,
            "Réglages" to R.drawable.ic_ui_settings,
            "Appareils" to R.drawable.ic_ui_devices,
        ).forEachIndexed { index, (label, icon) ->
            val tab = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                compoundDrawablePadding = kit.dp(2)
                setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
                setPadding(0, kit.dp(6), 0, kit.dp(6))
                setOnClickListener { selectTab(index) }
            }
            tabLabels.add(tab)
            bar.addView(tab, kit.lp(0, weight = 1f))
        }
        return bar
    }

    private fun selectTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { i, tab ->
            val selected = i == index
            val color = if (selected) kit.p.accent else kit.p.textSecondary
            tab.setTextColor(color)
            tab.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
            tab.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        pageScroll.scrollTo(0, 0)
        autoLoadTried[index] = false
        loadCurrentTabIfNeeded()
    }

    /**
     * Première ouverture d'un onglet : on charge son état une fois, sans que Thomas ait à appuyer. Si l'app est
     * occupée à ce moment, [showBattery] rappelle cette fonction dès la fin de la commande en cours.
     */
    private fun loadCurrentTabIfNeeded() {
        if (selectedDevice == null || busy || autoLoadTried[currentTab]) return
        // Une seule tentative automatique par ouverture d'onglet : si le casque ne répond pas, [showBattery] rappelle
        // cette fonction après chaque commande et la lecture se relançait sans fin (une demande toutes les 3 s).
        // Les boutons « Lire » et « Actualiser », eux, restent disponibles.
        when (currentTab) {
            TAB_EQ -> if (!eqLoaded) {
                autoLoadTried[currentTab] = true
                runEqCmd("Lecture de l'égaliseur…") { it.readEq() }
            }
            TAB_SETTINGS -> if (!settingsLoaded) {
                autoLoadTried[currentTab] = true
                runSettingsCmd("Lecture des réglages…") { it.readSettings() }
            }
            TAB_DEVICES -> if (!devicesLoaded) {
                autoLoadTried[currentTab] = true
                runPairedCmd("Lecture des appareils…")
            }
        }
    }

    private val autoLoadTried = BooleanArray(4)

    /** Changements annoncés par le casque (voir [HeadphoneClient.listener]) : l'écran suit sans qu'on actualise. */
    private val liveListener = object : MdrSession.StateListener {
        override fun onReport(report: BatteryReport) {
            runOnUiThread { if (!busy) showLiveReport(report) }
        }

        override fun onSettings(settings: SettingsState) {
            runOnUiThread { if (!busy && settingsLoaded) showSettings(settings) }
        }

        override fun onDevices(info: PairedDevicesInfo) {
            runOnUiThread { if (!busy && devicesLoaded) showPairedDevices(info) }
        }    }

    /** Met à jour batterie, bruit et volume sans toucher au reste de l'écran (message d'état, onglet chargé). */
    private fun showLiveReport(report: BatteryReport) {
        val charging = report.chargingStatus == MdrConstants.CHARGING_CHARGING
        batteryBig.text = "${report.levelPercent} %" + if (charging) " ⚡" else ""
        batteryBig.setTextColor(if (report.levelPercent <= 15 && !charging) kit.p.danger else kit.p.text)
        report.musicVolume?.let { lastVolume = it.coerceIn(0, MdrConstants.PLAY_VOLUME_MAX) }
        updateVolumeLabel()
        updateNcAsmButtons(report)
    }

    // --- Onglet Son ------------------------------------------------------------------------

    private fun buildSoundPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Contrôle du bruit
        val ncCard = kit.card("Contrôle du bruit")
        ncSummary = kit.text("Pas encore connecté", 13f, secondary = true)
        ncCard.addView(ncSummary, kit.matchLp(top = 2))
        val (segmented, buttons) = kit.segmented(listOf("Réduction de bruit", "Son ambiant", "Désactivé"))
        ncButton = buttons[0]
        ambButton = buttons[1]
        offButton = buttons[2]
        buttons.forEach { it.isEnabled = false }
        ncCard.addView(segmented, kit.matchLp(top = 12))
        ncButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_NC) } }
        ambButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_AMBIENT) } }
        offButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_OFF) } }

        ambientGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        ambientLevelText = kit.text("Niveau ambiant : ${MdrConstants.AMBIENT_LEVEL_DEFAULT}", 13f, secondary = true)
        ambientGroup.addView(ambientLevelText, kit.matchLp(top = 14))
        ambientSeekBar = kit.tint(SeekBar(this)).apply {
            max = MdrConstants.AMBIENT_LEVEL_MAX - 1
            progress = MdrConstants.AMBIENT_LEVEL_DEFAULT - 1
        }
        ambientGroup.addView(ambientSeekBar, kit.matchLp(top = 2))
        autoAmbientCheck = Switch(this).apply {
            text = "Son ambiant automatique"
            textSize = 14f
            setTextColor(kit.p.text)
        }
        ambientGroup.addView(autoAmbientCheck, kit.matchLp(top = 10))
        ncCard.addView(ambientGroup, kit.matchLp())
        page.addView(ncCard, kit.matchLp(bottom = 12))

        ambientSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!suppressSeekBar) ambientLevelText.text = "Niveau ambiant : ${progress + 1}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (suppressSeekBar) return
                val level = seekBar?.progress?.plus(1) ?: return
                selectedDevice?.let { setAmbientLevel(it, level) }
            }
        })

        autoAmbientCheck.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoCheck) return@setOnCheckedChangeListener
            val device = selectedDevice
            if (device != null && ncAsmState?.enabled == true && ncAsmState?.mode == MdrConstants.NCASM_MODE_ASM) {
                setAutoAmbient(device, isChecked)
            } else {
                suppressAutoCheck = true
                autoAmbientCheck.isChecked = ncAsmState?.autoEnabled == true
                suppressAutoCheck = false
            }
        }

        // Volume
        val volCard = kit.card("Volume")
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        volumeValue = kit.text("$lastVolume", 40f, bold = true)
        volRow.addView(volumeValue)
        volRow.addView(kit.text(" / ${MdrConstants.PLAY_VOLUME_MAX}", 16f, secondary = true), kit.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = kit.dp(6) })
        volCard.addView(volRow, kit.matchLp(top = 4))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = MdrConstants.PLAY_VOLUME_MAX.toFloat()
            background = kit.rounded(kit.p.neutral, 4)
        }
        volumeFill = View(this).apply { background = kit.rounded(kit.p.accent, 4) }
        volumeRest = View(this)
        bar.addView(volumeFill, kit.lp(0, kit.dp(8), lastVolume.toFloat()))
        bar.addView(volumeRest, kit.lp(0, kit.dp(8), (MdrConstants.PLAY_VOLUME_MAX - lastVolume).toFloat()))
        volCard.addView(bar, kit.matchLp(top = 6, bottom = 12))

        val volMinus = kit.actionButton("−", 22f)
        val volPlus = kit.actionButton("+", 22f)
        muteButton = kit.actionButton("Muet")
        // Sans cela, Android aligne les textes des boutons (22 sp contre 14 sp) et décale « Muet ».
        val volRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        listOf(volMinus, volPlus, muteButton).forEachIndexed { i, b ->
            volRow2.addView(b, kit.lp(0, kit.dp(52), 1f).apply { if (i > 0) leftMargin = kit.dp(8) })
        }
        volCard.addView(volRow2, kit.matchLp())
        volMinus.setOnClickListener { changeVolume(-1) }
        volPlus.setOnClickListener { changeVolume(+1) }
        muteButton.setOnClickListener {
            muted = !muted
            updateVolumeLabel()
            runBluetoothCmd(if (muted) "Muet…" else "Rétablir le son…") { it.setMusicMute(muted, lastVolume) }
        }

        volCard.addView(kit.text("Pas de réglage", 12f, secondary = true), kit.matchLp(top = 14))
        val steps = intArrayOf(1, 2, 3, 5)
        val (stepRow, stepBtns) = kit.segmented(steps.map { "$it" })
        stepBtns.forEachIndexed { i, b ->
            stepButtons.add(b)
            b.setOnClickListener {
                volumeStep = steps[i]
                updateStepButtons()
            }
        }
        updateStepButtons()
        volCard.addView(stepRow, kit.matchLp(top = 4))
        page.addView(volCard, kit.matchLp(bottom = 12))

        page.addView(buildNowPlayingCard(), 0, kit.matchLp(bottom = 12))
        return page
    }

    // --- Lecture en cours ------------------------------------------------------------------

    private lateinit var artView: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var mediaAccessGroup: LinearLayout
    private lateinit var seekGroup: LinearLayout
    private lateinit var trackSeek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private var isSeeking = false
    private var isPlaying = false
    private var mediaController: MediaController? = null
    private var shownTitle: String? = null
    private var shownArtist: String? = null
    private var shownArt: Bitmap? = null

    /** Titre, pochette et contrôles de lecture ; un seul bouton alterne lecture et pause selon l'état réel. */
    private fun buildNowPlayingCard(): View {
        val card = kit.card()

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val size = kit.dp(76)
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = kit.rounded(kit.p.neutral, 12)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, kit.dp(12).toFloat())
                }
            }
            setImageResource(R.drawable.ic_ui_music)
            imageTintList = android.content.res.ColorStateList.valueOf(kit.p.textSecondary)
        }
        info.addView(artView, kit.lp(size, size))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trackTitle = kit.text("Rien en lecture", 17f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        trackArtist = kit.text("", 14f, secondary = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(trackTitle)
        texts.addView(trackArtist, kit.matchLp(top = 2))
        info.addView(texts, kit.lp(0, weight = 1f).apply { leftMargin = kit.dp(14) })
        card.addView(info, kit.matchLp())

        // Barre de progression : visible seulement quand la session média donne la durée du morceau.
        seekGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        trackSeek = kit.tint(SeekBar(this))
        trackSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val position = seekBar?.progress?.toLong() ?: return
                mediaController?.transportControls?.seekTo(position)
            }
        })
        seekGroup.addView(trackSeek, kit.matchLp(top = 8))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeNow = kit.text("0:00", 12f, secondary = true)
        timeTotal = kit.text("0:00", 12f, secondary = true).apply { gravity = Gravity.END }
        times.addView(timeNow, kit.lp(0, weight = 1f))
        times.addView(timeTotal, kit.lp(0, weight = 1f))
        seekGroup.addView(times, kit.matchLp())
        card.addView(seekGroup, kit.matchLp(top = 6))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        val prev = kit.iconButton(R.drawable.ic_ui_prev, "Piste précédente").apply {
            setOnClickListener { sendTransport(MdrConstants.PLAY_CTRL_TRACK_DOWN, "Piste précédente…") { it.skipToPrevious() } }
        }
        playPauseButton = kit.iconButton(R.drawable.ic_ui_play, "Lecture").apply {
            background = kit.rounded(kit.p.accent, 14)
            imageTintList = android.content.res.ColorStateList.valueOf(kit.p.onAccent)
            setOnClickListener { togglePlayPause() }
        }
        val next = kit.iconButton(R.drawable.ic_ui_next, "Piste suivante").apply {
            setOnClickListener { sendTransport(MdrConstants.PLAY_CTRL_TRACK_UP, "Piste suivante…") { it.skipToNext() } }
        }
        controls.addView(prev, kit.lp(0, kit.dp(56), 1f))
        controls.addView(playPauseButton, kit.lp(0, kit.dp(56), 1.6f).apply {
            leftMargin = kit.dp(8)
            rightMargin = kit.dp(8)
        })
        controls.addView(next, kit.lp(0, kit.dp(56), 1f))
        card.addView(controls, kit.matchLp(top = 14))

        // Sans accès aux notifications, Android ne donne ni titre ni pochette : on explique et on propose d'autoriser.
        mediaAccessGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        mediaAccessGroup.addView(
            kit.text(
                "Pour afficher le titre et la pochette, autorise XmRip dans « Accès aux notifications ». " +
                    "XmRip ne lit pas tes notifications : il sert seulement à voir ce qui est en lecture.",
                12f,
                secondary = true,
            ),
            kit.matchLp(top = 12),
        )
        val allow = kit.actionButton("Autoriser l'accès", 13f).apply {
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        mediaAccessGroup.addView(allow, kit.matchLp(top = 8))
        card.addView(mediaAccessGroup, kit.matchLp())
        return card
    }

    /** Lit la session média active (titre, pochette, état) ; sans autorisation, seul l'état de lecture est connu. */
    private fun syncPlayback() {
        if (!::playPauseButton.isInitialized) return
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val sessions = try {
            manager?.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (e: SecurityException) {
            null
        }
        if (sessions == null) {
            mediaController = null
            seekGroup.visibility = View.GONE
            mediaAccessGroup.visibility = View.VISIBLE
            val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            setPlaying(am?.isMusicActive == true)
            showTrack(null, null, null)
            return
        }
        mediaAccessGroup.visibility = View.GONE
        val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        mediaController = controller
        if (controller == null) {
            setPlaying(false)
            seekGroup.visibility = View.GONE
            showTrack(null, null, null)
            return
        }
        setPlaying(controller.playbackState?.state == PlaybackState.STATE_PLAYING)
        val meta = controller.metadata
        syncSeek(controller.playbackState, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        val title = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        showTrack(title, artist, art)
    }

    /** Position estimée entre deux mises à jour de la session : position connue + temps écoulé × vitesse. */
    private fun syncSeek(state: PlaybackState?, duration: Long) {
        if (state == null || duration <= 0L) {
            seekGroup.visibility = View.GONE
            return
        }
        seekGroup.visibility = View.VISIBLE
        trackSeek.isEnabled = state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        timeTotal.text = formatTime(duration)
        if (isSeeking) return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        trackSeek.max = duration.toInt()
        trackSeek.progress = position.toInt()
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
        playPauseButton.setImageResource(if (playing) R.drawable.ic_ui_pause else R.drawable.ic_ui_play)
        playPauseButton.contentDescription = if (playing) "Pause" else "Lecture"
    }

    private fun showTrack(title: String?, artist: String?, art: Bitmap?) {
        // Sans titre connu (pas d'autorisation, ou rien dans la session), on dit au moins si de la musique joue.
        val fallback = if (isPlaying) "Lecture en cours" else "Rien en lecture"
        val shownText = title ?: fallback
        if (trackTitle.text.toString() != shownText) trackTitle.text = shownText
        if (trackArtist.text.toString() != (artist ?: "")) trackArtist.text = artist ?: ""
        if (art === shownArt && shownArt != null || art == null && shownArt == null && shownTitle == title) {
            shownTitle = title
            shownArtist = artist
            return
        }
        shownTitle = title
        shownArtist = artist
        shownArt = art
        if (art != null) {
            artView.imageTintList = null
            artView.setImageBitmap(art)
        } else {
            artView.setImageResource(R.drawable.ic_ui_music)
            artView.imageTintList = android.content.res.ColorStateList.valueOf(kit.p.textSecondary)
        }
    }

    private fun togglePlayPause() {
        val target = !isPlaying
        setPlaying(target)  // affichage immédiat ; la lecture réelle le corrige à la seconde suivante si besoin
        val controller = mediaController
        if (controller != null) {
            if (target) controller.transportControls.play() else controller.transportControls.pause()
        } else {
            val label = if (target) "Lecture…" else "Pause…"
            runBluetoothCmd(label) { it.playControl(if (target) MdrConstants.PLAY_CTRL_PLAY else MdrConstants.PLAY_CTRL_PAUSE) }
        }
    }

    /** Piste suivante ou précédente : directement par la session média si on y a accès, sinon par le casque. */
    private fun sendTransport(mdrControl: Int, statusMsg: String, viaController: (MediaController.TransportControls) -> Unit) {
        val controller = mediaController
        if (controller != null) viaController(controller.transportControls)
        else runBluetoothCmd(statusMsg) { it.playControl(mdrControl) }
    }

    private fun updateStepButtons() {
        val steps = intArrayOf(1, 2, 3, 5)
        stepButtons.forEachIndexed { i, b -> b.isSelected = steps[i] == volumeStep }
    }

    // --- Onglet Égaliseur ------------------------------------------------------------------

    private lateinit var eqStateText: TextView
    private val eqPresets = listOf(
        "Désactivé" to MdrConstants.EQ_OFF,
        "Heavy" to MdrConstants.EQ_XM6_HEAVY,
        "Clear" to MdrConstants.EQ_XM6_CLEAR,
        "Hard" to MdrConstants.EQ_XM6_HARD,
        "Soft" to MdrConstants.EQ_XM6_SOFT,
        "Perso" to MdrConstants.EQ_XM6_CUSTOM,
    )
    private val eqButtons = HashMap<Int, Button>()
    private val eqBandFrequencies = listOf("31 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
    private val eqSeekBars = mutableListOf<SeekBar>()
    private val eqValueTexts = mutableListOf<TextView>()
    private lateinit var eqHint: TextView
    private var suppressEqSeek = false
    private var eqBandsDirty = false

    /** Égaliseur XM6 : préréglages en haut, courbe à 10 bandes en dessous (modifiable seulement sur « Perso »). */
    private fun buildEqPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val presetCard = kit.card("Préréglage")
        eqStateText = kit.text("Pas encore lu", 13f, secondary = true)
        presetCard.addView(eqStateText, kit.matchLp(top = 2))
        eqPresets.chunked(3).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, (label, code) ->
                val b = kit.chip(label).also { eqButtons[code] = it }
                b.setOnClickListener { runEqCmd("Égaliseur : $label…") { it.setEqPreset(code) } }
                row.addView(b, kit.lp(0, weight = 1f).apply { if (i > 0) leftMargin = kit.dp(8) })
            }
            presetCard.addView(row, kit.matchLp(top = if (rowIndex == 0) 12 else 8))
        }
        val readBtn = kit.actionButton("Relire l'état du casque", 13f)
        readBtn.setOnClickListener { runEqCmd("Lecture de l'égaliseur…") { it.readEq() } }
        presetCard.addView(readBtn, kit.matchLp(top = 12))
        page.addView(presetCard, kit.matchLp(bottom = 12))

        val curveCard = kit.card("Courbe (10 bandes)")
        eqHint = kit.text("Choisis « Perso » pour régler les bandes.", 13f, secondary = true)
        curveCard.addView(eqHint, kit.matchLp(top = 2, bottom = 6))
        eqBandFrequencies.forEach { freq ->
            val valueText = kit.text("0 dB", 13f, bold = true).apply {
                gravity = Gravity.END
                minWidth = kit.dp(54)
            }
            val seek = kit.tint(SeekBar(this)).apply {
                max = MdrConstants.EQ_BAND_GAIN_MAX - MdrConstants.EQ_BAND_GAIN_MIN
                progress = -MdrConstants.EQ_BAND_GAIN_MIN
                isEnabled = false
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = "%+d dB".format(progress + MdrConstants.EQ_BAND_GAIN_MIN)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!suppressEqSeek) writeEqBands()
                }
            })
            eqSeekBars.add(seek)
            eqValueTexts.add(valueText)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(kit.text(freq, 13f, secondary = true).apply { minWidth = kit.dp(58) })
            row.addView(seek, kit.lp(0, weight = 1f))
            row.addView(valueText)
            curveCard.addView(row, kit.matchLp())
        }
        page.addView(curveCard, kit.matchLp())
        return page
    }

    /** Envoie la courbe des 10 curseurs ; si une commande est en cours, l'envoi est repris à sa fin. */
    private fun writeEqBands() {
        if (busy) {
            eqBandsDirty = true
            return
        }
        val gains = eqSeekBars.map { it.progress + MdrConstants.EQ_BAND_GAIN_MIN }
        runEqCmd("Égaliseur : bandes…") { it.setEqBands(gains) }
    }

    /** Connexion au casque, lecture ou changement de l'égaliseur (une seule tentative), puis affichage de l'état confirmé. */
    @Suppress("MissingPermission")
    private fun runEqCmd(statusMsg: String, action: (MdrSession) -> EqState?) {
        val device = selectedDevice
        if (device == null) {
            setStatus("Aucun casque sélectionné.")
            return
        }
        if (busy) return
        busy = true
        setStatus(statusMsg)
        Thread {
            try {
                var eq: EqState? = null
                val report = HeadphoneClient.connectAndApply(device, 1) { session -> eq = action(session) }
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                    // Un curseur bougé pendant la commande : on garde sa position et on renvoie la courbe.
                    showEq(eq, updateBands = !eqBandsDirty)
                    if (eqBandsDirty) {
                        eqBandsDirty = false
                        writeEqBands()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    private fun showEq(eq: EqState?, updateBands: Boolean = true) {
        if (eq == null) {
            eqStateText.text = "Le casque n'a pas répondu"
            return
        }
        eqLoaded = true
        eqStateText.text = "Actuel : ${eq.label}"
        eqPresets.forEach { (_, code) -> eqButtons[code]?.isSelected = code == eq.preset }

        val editable = eq.preset == MdrConstants.EQ_XM6_CUSTOM
        eqHint.text = if (editable) "Fais glisser une bande pour modifier ta courbe."
        else "Courbe du préréglage « ${eq.label} ». Choisis « Perso » pour la modifier."
        eqSeekBars.forEach { it.isEnabled = editable }
        if (updateBands && eq.bands.size == eqSeekBars.size) {
            suppressEqSeek = true
            eq.bands.forEachIndexed { i, g ->
                eqSeekBars[i].progress = g.coerceIn(MdrConstants.EQ_BAND_GAIN_MIN, MdrConstants.EQ_BAND_GAIN_MAX) - MdrConstants.EQ_BAND_GAIN_MIN
            }
            suppressEqSeek = false
        }
    }

    // --- Onglet Réglages -------------------------------------------------------------------

    private lateinit var listenButtons: List<Button>
    private lateinit var roomRow: View
    private lateinit var roomButtons: List<Button>
    private lateinit var dseeSwitch: Switch
    private lateinit var chatSwitch: Switch
    private lateinit var wearSwitch: Switch
    private lateinit var powerButtons: List<Button>
    private lateinit var powerSummary: TextView
    private lateinit var ncButtonButtons: List<Button>
    private lateinit var ncButtonSummary: TextView
    private var suppressSettings = false
    private var lastListening: ListeningMode = ListeningMode.STANDARD
    private var lastRoom = MdrConstants.ROOM_SMALL

    private val listeningModes = listOf(ListeningMode.STANDARD, ListeningMode.BGM, ListeningMode.CINEMA)
    private val rooms = listOf(MdrConstants.ROOM_SMALL, MdrConstants.ROOM_MIDDLE, MdrConstants.ROOM_LARGE)
    private val ncButtonFunctions = listOf(
        MdrConstants.NC_BUTTON_ALL, MdrConstants.NC_BUTTON_NC_ASM, MdrConstants.NC_BUTTON_NC_OFF, MdrConstants.NC_BUTTON_ASM_OFF,
    )

    /** Réglages du casque : écoute, conversation, port du casque et alimentation. */
    private fun buildSettingsPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val listenCard = kit.card("Écoute")
        listenCard.addView(kit.text("Mode d'écoute", 13f, secondary = true), kit.matchLp(top = 10))
        val (listenRow, listen) = kit.segmented(listOf("Standard", "Musique de fond", "Cinéma"))
        listenButtons = listen
        listen.forEachIndexed { i, b ->
            b.isEnabled = false
            b.setOnClickListener {
                val mode = listeningModes[i]
                runSettingsCmd("Mode d'écoute…") { s ->
                    s.setListeningMode(mode, lastRoom)
                    s.settings
                }
            }
        }
        listenCard.addView(listenRow, kit.matchLp(top = 4))
        val (roomContainer, room) = kit.segmented(listOf("Chambre", "Salon", "Café"))
        roomButtons = room
        roomRow = roomContainer
        room.forEachIndexed { i, b ->
            b.setOnClickListener {
                lastRoom = rooms[i]
                runSettingsCmd("Taille de la pièce…") { s ->
                    s.setListeningMode(ListeningMode.BGM, rooms[i])
                    s.settings
                }
            }
        }
        val roomBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        roomBlock.addView(kit.text("Ambiance de la musique de fond", 13f, secondary = true), kit.matchLp(top = 12))
        roomBlock.addView(roomContainer, kit.matchLp(top = 4))
        listenCard.addView(roomBlock, kit.matchLp())
        roomRow = roomBlock
        dseeSwitch = settingSwitch(listenCard, "DSEE Extreme", "Restitue les aigus perdus par la compression") { on ->
            runSettingsCmd("DSEE…") { s ->
                s.setDsee(on)
                s.settings
            }
        }
        page.addView(listenCard, kit.matchLp(bottom = 12))

        val chatCard = kit.card("Conversation et port du casque")
        chatSwitch = settingSwitch(chatCard, "Speak-to-Chat", "Met la musique en pause quand tu parles") { on ->
            runSettingsCmd("Speak-to-Chat…") { s ->
                s.setSpeakToChat(on)
                s.settings
            }
        }
        wearSwitch = settingSwitch(chatCard, "Pause quand le casque est retiré", "La lecture reprend quand tu le remets") { on ->
            runSettingsCmd("Pause au retrait…") { s ->
                s.setPauseWhenRemoved(on)
                s.settings
            }
        }
        page.addView(chatCard, kit.matchLp(bottom = 12))

        val buttonCard = kit.card("Bouton de bruit", "Modes que le bouton de l'oreillette gauche fait défiler.")
        ncButtonSummary = kit.text("Bouton de bruit : non rapporté par le casque", 13f, secondary = true)
        buttonCard.addView(ncButtonSummary, kit.matchLp(top = 10))
        val (ncRowA, ncA) = kit.segmented(listOf("Les trois", "Bruit et ambiant"))
        val (ncRowB, ncB) = kit.segmented(listOf("Bruit et normal", "Ambiant et normal"))
        ncButtonButtons = ncA + ncB
        ncButtonButtons.forEachIndexed { i, b ->
            b.isEnabled = false
            b.setOnClickListener {
                runSettingsCmd("Bouton de bruit…") { s ->
                    s.setNcButton(ncButtonFunctions[i])
                    s.settings
                }
            }
        }
        buttonCard.addView(ncRowA, kit.matchLp(top = 4))
        buttonCard.addView(ncRowB, kit.matchLp(top = 6))
        page.addView(buttonCard, kit.matchLp(bottom = 12))
        page.addView(buildGestureCard(), kit.matchLp(bottom = 12))

        val powerCard = kit.card("Alimentation")
        powerSummary = kit.text("Extinction automatique", 13f, secondary = true)
        powerCard.addView(powerSummary, kit.matchLp(top = 10))
        val (powerRow, power) = kit.segmented(listOf("Au retrait du casque", "Jamais"))
        powerButtons = power
        val elements = listOf(MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED, MdrConstants.AUTO_POWER_OFF_DISABLE)
        power.forEachIndexed { i, b ->
            b.isEnabled = false
            b.setOnClickListener {
                runSettingsCmd("Extinction automatique…") { s ->
                    s.setAutoPowerOff(elements[i])
                    s.settings
                }
            }
        }
        powerCard.addView(powerRow, kit.matchLp(top = 4))
        val offButton = kit.actionButton("Éteindre le casque", 14f).apply {
            setTextColor(kit.p.danger)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Éteindre le casque ?")
                    .setMessage("Le casque s'éteint tout de suite et la musique s'arrête. Rallume-le avec son bouton d'alimentation.")
                    .setPositiveButton("Éteindre") { _, _ ->
                        runBluetoothCmd("Extinction du casque…", attempts = 1) { it.powerOff() }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
        powerCard.addView(offButton, kit.matchLp(top = 14))
        page.addView(powerCard, kit.matchLp())
        return page
    }

    // --- Gestes du bouton de bruit -----------------------------------------------------------------

    private lateinit var gestureButtons: Map<String, Button>
    private lateinit var gestureNote: TextView
    private lateinit var gestureOverlay: Button

    /** Actions du téléphone sur le double et le triple appui du bouton de bruit (le casque annonce ces gestes). */
    private fun buildGestureCard(): View {
        val card = kit.card(
            "Gestes du bouton de bruit",
            "Choisis ce que fait le téléphone quand tu appuies deux ou trois fois de suite sur le bouton de bruit. " +
                "Une action choisie garde la liaison avec le casque ouverte : l'appli Windows ne peut pas s'y " +
                "connecter en même temps.",
        )
        val buttons = LinkedHashMap<String, Button>()
        listOf(GestureActions.DOUBLE to "Double appui", GestureActions.TRIPLE to "Triple appui").forEach { (gesture, title) ->
            card.addView(kit.text(title, 13f, secondary = true), kit.matchLp(top = 12))
            val button = kit.actionButton("", 14f)
            button.setOnClickListener { chooseGestureAction(gesture, title) }
            card.addView(button, kit.matchLp(top = 4))
            buttons[gesture] = button
        }
        gestureButtons = buttons
        gestureNote = kit.text("", 12f, secondary = true)
        card.addView(gestureNote, kit.matchLp(top = 12))
        gestureOverlay = kit.actionButton("Autoriser l'ouverture d'applications", 13f)
        gestureOverlay.setOnClickListener {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
        card.addView(gestureOverlay, kit.matchLp(top = 8))
        refreshGestureCard()
        return card
    }

    private fun refreshGestureCard() {
        gestureButtons.forEach { (gesture, button) -> button.text = GestureActions.label(this, gesture) }
        val launches = gestureButtons.keys.any { GestureActions.action(this, it) == GestureActions.LAUNCH }
        val blocked = launches && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !android.provider.Settings.canDrawOverlays(this)
        gestureOverlay.visibility = if (blocked) View.VISIBLE else View.GONE
        val note = GestureActions.note(this)
        gestureNote.text = when {
            note.isNotEmpty() -> "Dernier geste : $note"
            GestureActions.anyEnabled(this) -> "En attente d'un geste. Garde l'accès aux notifications accordé à XmRip."
            else -> "Aucune action choisie : la liaison n'est pas gardée ouverte."
        }
    }

    private fun chooseGestureAction(gesture: String, title: String) {
        val labels = arrayOf("Aucune action", "Lecture / pause", "Piste suivante", "Piste précédente", "Ouvrir une application…")
        val actions = listOf(
            GestureActions.NONE, GestureActions.PLAY_PAUSE, GestureActions.NEXT, GestureActions.PREVIOUS, GestureActions.LAUNCH,
        )
        val current = actions.indexOf(GestureActions.action(this, gesture)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (actions[which] == GestureActions.LAUNCH) {
                    chooseGestureApp(gesture)
                } else {
                    applyGestureAction(gesture, actions[which], "")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseGestureApp(gesture: String) {
        Thread {
            val apps = NotificationReader.launchableApps(this)
            runOnUiThread {
                val selected = apps.indexOfFirst { it.first == GestureActions.target(this, gesture) }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Application à ouvrir")
                    .setSingleChoiceItems(apps.map { it.second }.toTypedArray(), selected) { dialog, which ->
                        dialog.dismiss()
                        applyGestureAction(gesture, GestureActions.LAUNCH, apps[which].first)
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }.start()
    }

    private fun applyGestureAction(gesture: String, action: String, target: String) {
        GestureActions.set(this, gesture, action, target)
        GestureActions.setNote(this, "")
        if (GestureActions.anyEnabled(this)) {
            GestureWatcher.start(this)
            GestureWatcher.retryNow()
        } else {
            GestureWatcher.stop(this)
        }
        refreshGestureCard()
    }

    /** Ligne « titre, explication, interrupteur » ajoutée à [card] ; [onChange] n'est appelé que par l'utilisateur. */
    private fun settingSwitch(card: LinearLayout, title: String, summary: String, onChange: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.text(title, 15f, bold = true))
        texts.addView(kit.text(summary, 12f, secondary = true), kit.matchLp(top = 1))
        row.addView(texts, kit.lp(0, weight = 1f))
        val toggle = Switch(this).apply { isEnabled = false }
        toggle.setOnCheckedChangeListener { _, isChecked -> if (!suppressSettings) onChange(isChecked) }
        row.addView(toggle)
        card.addView(row, kit.matchLp(top = 14))
        return toggle
    }

    /** Connexion au casque, action sur un réglage (une seule tentative) puis affichage de l'état relu. */
    @Suppress("MissingPermission")
    private fun runSettingsCmd(statusMsg: String, action: (MdrSession) -> SettingsState?) {
        val device = selectedDevice
        if (device == null) {
            setStatus("Aucun casque sélectionné.")
            return
        }
        if (busy) return
        busy = true
        setStatus(statusMsg)
        Thread {
            try {
                var state: SettingsState? = null
                val report = HeadphoneClient.connectAndApply(device, 1) { session -> state = action(session) }
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                    showSettings(state)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    // Réaffiche l'état connu : un interrupteur déplacé ne doit pas rester sur une valeur non appliquée.
                    lastSettings?.let { showSettings(it) }
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    private var lastSettings: SettingsState? = null

    private fun showSettings(read: SettingsState?) {
        if (read == null) return
        val state = read.fillMissingFrom(lastSettings)
        settingsLoaded = true
        lastSettings = state
        suppressSettings = true
        val mode = state.listeningMode
        mode?.let { lastListening = it }
        state.bgmRoom?.let { lastRoom = it }
        listenButtons.forEachIndexed { i, b ->
            b.isEnabled = mode != null
            b.isSelected = listeningModes[i] == mode
        }
        roomRow.visibility = if (mode == ListeningMode.BGM) View.VISIBLE else View.GONE
        roomButtons.forEachIndexed { i, b -> b.isSelected = rooms[i] == state.bgmRoom }
        bindSwitch(dseeSwitch, state.dsee)
        bindSwitch(chatSwitch, state.speakToChat)
        bindSwitch(wearSwitch, state.pauseWhenRemoved)
        val ncButton = state.ncButton
        ncButtonButtons.forEachIndexed { i, b ->
            b.isEnabled = ncButton != null
            b.isSelected = ncButtonFunctions[i] == ncButton
        }
        ncButtonSummary.text = when (ncButton) {
            null -> "Bouton de bruit : non rapporté par le casque"
            MdrConstants.NC_BUTTON_ALL -> "Le bouton fait défiler : bruit, ambiant, normal"
            MdrConstants.NC_BUTTON_NC_ASM -> "Le bouton alterne : bruit et ambiant"
            MdrConstants.NC_BUTTON_NC_OFF -> "Le bouton alterne : bruit et normal (non essayé)"
            MdrConstants.NC_BUTTON_ASM_OFF -> "Le bouton alterne : ambiant et normal (non essayé)"
            else -> "Bouton de bruit : code 0x%02X inconnu".format(ncButton)
        }
        val power = state.autoPowerOff
        powerButtons.forEach { it.isEnabled = power != null }
        powerButtons[0].isSelected = power == MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED
        powerButtons[1].isSelected = power == MdrConstants.AUTO_POWER_OFF_DISABLE
        powerSummary.text = when (power) {
            null -> "Extinction automatique : non rapportée par le casque"
            MdrConstants.AUTO_POWER_OFF_WHEN_REMOVED -> "Extinction automatique : quand tu retires le casque"
            MdrConstants.AUTO_POWER_OFF_DISABLE -> "Extinction automatique : désactivée"
            else -> "Extinction automatique : code 0x%02X (durée choisie ailleurs)".format(power)
        }
        suppressSettings = false
    }

    private fun bindSwitch(toggle: Switch, value: Boolean?) {
        toggle.isEnabled = value != null
        toggle.isChecked = value == true
    }

    // --- Onglet Appareils ------------------------------------------------------------------

    private lateinit var pairedContainer: LinearLayout
    private lateinit var pairingModeButton: Button
    private var pairingModeOn = false

    /** Gestion multipoint : appareils appairés au casque, connexion/déconnexion, bascule de la lecture. */
    private fun buildDevicesPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = kit.card("Appareils du casque", "Appuie sur un appareil pour le connecter, le déconnecter ou lui passer la lecture.")

        val loadBtn = kit.actionButton("Actualiser", 13f)
        pairingModeButton = kit.chip("Mode appairage").apply { textSize = 13f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(loadBtn, kit.lp(0, weight = 1f))
        row.addView(pairingModeButton, kit.lp(0, weight = 1f).apply { leftMargin = kit.dp(8) })
        card.addView(row, kit.matchLp(top = 12, bottom = 4))
        loadBtn.setOnClickListener { runPairedCmd("Lecture des appareils…") }
        pairingModeButton.setOnClickListener {
            val enable = !pairingModeOn
            runPairedCmd(if (enable) "Activation du mode appairage…" else "Fin du mode appairage…") {
                it.setPairingMode(enable)
                null
            }
        }

        pairedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(pairedContainer, kit.matchLp())
        page.addView(card, kit.matchLp())
        page.addView(buildPcQuietCard(), kit.matchLp(top = 12))
        page.addView(buildReaderCard(), kit.matchLp(top = 12))
        return page
    }

    // --- Lecture des notifications dans le casque ---------------------------------------------------

    private lateinit var readerSwitch: Switch
    private lateinit var readerContentSwitch: Switch
    private lateinit var readerAsleepSwitch: Switch
    private lateinit var readerApps: TextView
    private lateinit var readerStatus: TextView
    private lateinit var readerAccess: Button
    private lateinit var readerModeButtons: List<Button>
    private var suppressReader = false
    private var readerSpeaker: NotificationReader.Speaker? = null

    private fun buildReaderCard(): View {
        val card = kit.card(
            "Lecture des notifications",
            "Le casque te lit à voix haute les notifications des applications que tu choisis, tant que ton " +
                "ordinateur n'est pas connecté au casque. Le son habituel de la notification reste.",
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(kit.text("Lire les notifications", 15f, bold = true), kit.lp(0, weight = 1f))
        readerSwitch = Switch(this)
        readerSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressReader) return@setOnCheckedChangeListener
            if (checked) enableReader() else disableReader()
        }
        row.addView(readerSwitch)
        card.addView(row, kit.matchLp(top = 12))

        readerApps = kit.text("", 13f, secondary = true)
        card.addView(readerApps, kit.matchLp(top = 10))
        val choose = kit.actionButton("Choisir les applications", 13f)
        choose.setOnClickListener { chooseReaderApps(null) }
        card.addView(choose, kit.matchLp(top = 8))

        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentRow.addView(kit.text("Lire aussi le texte du message", 14f), kit.lp(0, weight = 1f))
        readerContentSwitch = Switch(this)
        readerContentSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressReader) NotificationReader.setReadsContent(this, checked)
        }
        contentRow.addView(readerContentSwitch)
        card.addView(contentRow, kit.matchLp(top = 12))

        val asleepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        asleepRow.addView(kit.text("Seulement quand l'écran est éteint", 14f), kit.lp(0, weight = 1f))
        readerAsleepSwitch = Switch(this)
        readerAsleepSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressReader) NotificationReader.setOnlyWhenAsleep(this, checked)
        }
        asleepRow.addView(readerAsleepSwitch)
        card.addView(asleepRow, kit.matchLp(top = 12))

        card.addView(kit.text("Pendant la voix, la musique est…", 13f, secondary = true), kit.matchLp(top = 14))
        val (modeRow, modeButtons) = kit.segmented(listOf("Baissée, voix plus forte", "Mise en pause"))
        readerModeButtons = modeButtons
        modeButtons[0].setOnClickListener {
            NotificationReader.setMode(this, NotificationReader.MODE_LOUD)
            refreshReader()
        }
        modeButtons[1].setOnClickListener {
            NotificationReader.setMode(this, NotificationReader.MODE_PAUSE)
            refreshReader()
        }
        card.addView(modeRow, kit.matchLp(top = 4))

        readerStatus = kit.text("", 13f, secondary = true)
        card.addView(readerStatus, kit.matchLp(top = 10))
        readerAccess = kit.actionButton("Autoriser l'accès aux notifications", 13f)
        readerAccess.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        card.addView(readerAccess, kit.matchLp(top = 8))
        val test = kit.actionButton("Tester la voix", 13f)
        test.setOnClickListener {
            readerSpeaker?.shutdown()
            readerSpeaker = NotificationReader.test(this)
        }
        card.addView(test, kit.matchLp(top = 8))
        refreshReader()
        return card
    }

    private fun refreshReader() {
        if (!::readerSwitch.isInitialized) return
        val enabled = NotificationReader.isEnabled(this)
        suppressReader = true
        readerSwitch.isChecked = enabled
        readerContentSwitch.isChecked = NotificationReader.readsContent(this)
        readerAsleepSwitch.isChecked = NotificationReader.onlyWhenAsleep(this)
        suppressReader = false
        val loud = NotificationReader.mode(this) == NotificationReader.MODE_LOUD
        readerModeButtons[0].isSelected = loud
        readerModeButtons[1].isSelected = !loud
        val apps = NotificationReader.apps(this)
        readerApps.text = if (apps.isEmpty()) "Applications lues : aucune" else "Applications lues : ${apps.size} choisie(s)"
        val needsAccess = enabled && !NotificationReader.hasListenerAccess(this)
        readerAccess.visibility = if (needsAccess) View.VISIBLE else View.GONE
        val last = NotificationReader.lastDecision(this)
        val lastLine = if (enabled && last != null) "\nDernière notification : $last" else ""
        readerStatus.text = when {
            !enabled -> "Désactivé : l'interrupteur « Lire les notifications » est éteint."
            needsAccess -> "Autorise l'accès aux notifications pour XmRip, sinon rien ne peut être lu."
            apps.isEmpty() -> "Choisis au moins une application."
            PcQuiet.watchedAddress(this) == null -> "Ordinateur non choisi : les notifications sont lues même si le PC est connecté."
            else -> (PcQuiet.note(this)?.takeIf { it.isNotBlank() } ?: "Actif.") + lastLine
        }
    }

    private fun enableReader() {
        NotificationReader.setEnabled(this, true)
        if (NotificationReader.apps(this).isEmpty()) chooseReaderApps(null)
        PcWatcher.start(this)
        refreshReader()
    }

    private fun disableReader() {
        NotificationReader.setEnabled(this, false)
        PcWatcher.stop(this)
        refreshReader()
    }

    /** Liste des applications du lanceur avec cases à cocher ; [then] est appelé une fois le choix validé. */
    private fun chooseReaderApps(then: (() -> Unit)?) {
        Thread {
            val apps = NotificationReader.launchableApps(this)
            runOnUiThread {
                val chosen = NotificationReader.apps(this).toMutableSet()
                val checked = BooleanArray(apps.size) { apps[it].first in chosen }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Applications à lire dans le casque")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, on ->
                        if (on) chosen.add(apps[which].first) else chosen.remove(apps[which].first)
                    }
                    .setPositiveButton("Valider") { _, _ ->
                        NotificationReader.setApps(this, chosen)
                        // Choisir des applications, c'est vouloir la lecture : on allume l'interrupteur à ta place.
                        if (chosen.isNotEmpty() && !NotificationReader.isEnabled(this)) {
                            NotificationReader.setEnabled(this, true)
                            PcWatcher.start(this)
                        }
                        refreshReader()
                        then?.invoke()
                    }
                    .setNegativeButton("Annuler") { _, _ -> refreshReader() }
                    .show()
            }
        }.start()
    }

    // --- Notifications silencieuses quand l'ordinateur est connecté au casque -----------------------

    private lateinit var pcQuietSwitch: Switch
    private lateinit var pcQuietDevice: TextView
    private lateinit var pcQuietStatus: TextView
    private lateinit var pcQuietAccess: Button
    private var suppressPcQuiet = false

    private fun buildPcQuietCard(): View {
        val card = kit.card(
            "Notifications et ordinateur",
            "Coupe le son des notifications du téléphone tant que ton ordinateur est connecté au casque, pour " +
                "qu'elles ne passent pas dans le casque pendant que tu écoutes le PC. Les vibrations et les " +
                "pop-ups restent. Si c'est le téléphone qui lit du son dans le casque, les notifications passent. " +
                "Garde l'accès aux notifications accordé à XmRip : c'est lui qui maintient la surveillance, sans " +
                "notification permanente.",
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(kit.text("Couper le son des notifications", 15f, bold = true), kit.lp(0, weight = 1f))
        pcQuietSwitch = Switch(this)
        pcQuietSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressPcQuiet) return@setOnCheckedChangeListener
            if (checked) enablePcQuiet() else disablePcQuiet()
        }
        row.addView(pcQuietSwitch)
        card.addView(row, kit.matchLp(top = 12))

        pcQuietDevice = kit.text("Ordinateur surveillé : aucun", 13f, secondary = true)
        card.addView(pcQuietDevice, kit.matchLp(top = 10))
        val choose = kit.actionButton("Choisir l'ordinateur", 13f)
        choose.setOnClickListener { chooseWatchedDevice(null) }
        card.addView(choose, kit.matchLp(top = 8))

        pcQuietStatus = kit.text("", 13f, secondary = true)
        card.addView(pcQuietStatus, kit.matchLp(top = 10))
        pcQuietAccess = kit.actionButton("Autoriser « Ne pas déranger »", 13f)
        pcQuietAccess.setOnClickListener { openPolicyAccessSettings() }
        card.addView(pcQuietAccess, kit.matchLp(top = 8))
        refreshPcQuiet()
        return card
    }

    private fun refreshPcQuiet() {
        if (!::pcQuietSwitch.isInitialized) return
        val enabled = PcQuiet.isEnabled(this)
        suppressPcQuiet = true
        pcQuietSwitch.isChecked = enabled
        suppressPcQuiet = false
        pcQuietDevice.text = "Ordinateur surveillé : " + (PcQuiet.watchedName(this) ?: "aucun")
        // Sur certains téléphones, Android n'autorise le changement de ce volume qu'avec l'accès « Ne pas déranger ».
        val needsAccess = enabled && PcQuiet.accessNeeded(this)
        pcQuietAccess.visibility = if (needsAccess) View.VISIBLE else View.GONE
        pcQuietStatus.text = when {
            !enabled -> "Désactivé."
            needsAccess -> "Android demande l'accès « Ne pas déranger » pour changer ce volume : autorise XmRip."
            else -> PcQuiet.note(this)?.takeIf { it.isNotBlank() } ?: "Surveillance en cours…"
        }
    }

    private fun enablePcQuiet() {
        if (PcQuiet.watchedAddress(this) == null) {
            chooseWatchedDevice { enablePcQuiet() }
            return
        }
        PcQuiet.setEnabled(this, true)
        PcWatcher.start(this)
        refreshPcQuiet()
    }

    private fun disablePcQuiet() {
        PcQuiet.setEnabled(this, false)
        PcQuiet.setSilent(this, false) // volume des notifications rétabli tout de suite
        PcWatcher.stop(this)
        refreshPcQuiet()
    }

    private fun openPolicyAccessSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .onFailure { setStatus("Ouvre Réglages, Notifications, « Ne pas déranger », puis autorise XmRip.") }
    }

    /** Liste des appareils connus du casque (hors ce téléphone) ; [then] est appelé une fois le choix fait. */
    private fun chooseWatchedDevice(then: (() -> Unit)?) {
        val phoneName = phoneBluetoothName()
        val candidates = lastPaired.filter { phoneName == null || it.name != phoneName }
        if (candidates.isEmpty()) {
            setStatus("Charge d'abord la liste des appareils (Actualiser), puis réessaie.")
            refreshPcQuiet()
            return
        }
        val labels = candidates.map { it.name.ifBlank { it.address } + if (it.connected) "  ·  connecté" else "" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Quel appareil est ton ordinateur ?")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = candidates[which]
                PcQuiet.setWatched(this, chosen.address, chosen.name.ifBlank { chosen.address })
                refreshPcQuiet()
                then?.invoke()
            }
            .setNegativeButton("Annuler") { _, _ -> refreshPcQuiet() }
            .setOnCancelListener { refreshPcQuiet() }
            .show()
    }

    /**
     * Connexion au casque, action multipoint facultative (renvoie un message à afficher), puis relecture
     * de la liste des appareils. Une seule tentative : une action comme « oublier » ne doit pas être rejouée.
     */
    @Suppress("MissingPermission")
    private fun runPairedCmd(statusMsg: String, action: ((MdrSession) -> String?)? = null) {
        val device = selectedDevice
        if (device == null) {
            setStatus("Aucun casque sélectionné.")
            return
        }
        if (busy) return
        busy = true
        setStatus(statusMsg)
        Thread {
            try {
                var info: PairedDevicesInfo? = null
                var message: String? = null
                var listError: String? = null
                val report = HeadphoneClient.connectAndApply(device, 1) { session ->
                    if (action != null) message = action(session)
                    try {
                        info = session.readPairedDevices()
                    } catch (e: Exception) {
                        listError = e.message ?: e.javaClass.simpleName
                    }
                }
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                    info?.let { showPairedDevices(it) }
                    val note = message ?: listError?.let { "Liste des appareils indisponible : $it" }
                    if (note != null) setStatus(note)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun phoneBluetoothName(): String? = runCatching { adapter?.name }.getOrNull()

    private fun showPairedDevices(info: PairedDevicesInfo) {
        devicesLoaded = true
        pairedContainer.removeAllViews()
        pairingModeOn = info.pairingMode == true
        pairingModeButton.isSelected = pairingModeOn
        pairingModeButton.text = if (pairingModeOn) "Appairage actif" else "Mode appairage"
        val phoneName = phoneBluetoothName()
        lastPaired = info.devices
        android.util.Log.d("XmRip", "Appareils : ${info.devices} brut=${info.rawHex}")
        // Appareils connectés d'abord, par numéro (n°1 = prioritaire), puis les autres.
        val devices = info.devices.sortedWith(
            compareBy<PairedDevice>({ !it.connected }, { if (it.connected) it.linkStatus else 0 }),
        )
        if (devices.isEmpty()) {
            pairedContainer.addView(kit.text("Aucun appareil appairé au casque.", 13f, secondary = true), kit.matchLp(top = 8))
            return
        }
        for (dev in devices) {
            val isPhone = phoneName != null && dev.name == phoneName
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = kit.rounded(kit.p.neutral, 14)
                setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
                setOnClickListener { showDeviceActions(dev, isPhone) }
            }
            item.addView(kit.text(dev.name.ifBlank { "(sans nom)" } + if (isPhone) "  · ce téléphone" else "", 15f, bold = true))
            val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pills.addView(kit.pill(if (dev.connected) "Connecté n°${dev.linkStatus}" else "Non connecté", highlighted = dev.connected))
            if (dev.connected && dev.linkStatus == 1) {
                pills.addView(kit.pill("Prioritaire"), kit.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = kit.dp(6) })
            }
            if (dev.isPlaybackDevice) {
                pills.addView(kit.pill("Lecture en cours", highlighted = true), kit.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = kit.dp(6) })
            }
            item.addView(pills, kit.matchLp(top = 6))
            item.addView(kit.text(dev.address, 11f, secondary = true), kit.matchLp(top = 6))
            pairedContainer.addView(item, kit.matchLp(top = 8))
        }
    }

    private var lastPaired: List<PairedDevice> = emptyList()

    /**
     * Le n°1 du casque est un emplacement fixe (il ne suit pas la lecture). Hypothèse [déduite] : le
     * casque attribue le n°1 à l'appareil connecté depuis le plus longtemps. Pour passer [dev] en n°1,
     * on déconnecte l'appareil n°1 actuel puis on le reconnecte : il repasse en n°2.
     */
    private fun makePriority(dev: PairedDevice, name: String) {
        val current = lastPaired.firstOrNull { it.connected && it.linkStatus == 1 && it.address != dev.address }
        if (current == null) {
            setStatus("$name est déjà prioritaire.")
            return
        }
        val phoneName = phoneBluetoothName()
        val currentIsPhone = phoneName != null && current.name == phoneName
        val currentName = current.name.ifBlank { current.address }
        val message = if (currentIsPhone) {
            "Ce téléphone est actuellement n°1. L'app va le déconnecter du casque ; $name passera n°1. " +
                "Reconnecte ensuite le téléphone au casque depuis les réglages Bluetooth (il deviendra n°2). " +
                "La lecture sur ce téléphone sera coupée."
        } else {
            "$currentName est actuellement n°1. Il va être déconnecté puis reconnecté pour passer n°2 ; " +
                "$name deviendra n°1. La lecture sur $currentName sera brièvement coupée."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$name prioritaire ?")
            .setMessage(message)
            .setPositiveButton("Continuer") { _, _ ->
                runPairedCmd("$name devient prioritaire…") { s ->
                    if (currentIsPhone) {
                        try {
                            s.disconnectPairedDevice(current.address)
                        } catch (_: Exception) {
                            // Le lien de contrôle passe par ce téléphone : la coupure est attendue.
                        }
                        "Téléphone déconnecté. Reconnecte-le au casque (réglages Bluetooth), puis actualise la liste."
                    } else {
                        val off = s.disconnectPairedDevice(current.address)
                        if (off and 0x0F != 0) {
                            describePeripheralResult("Déconnexion de $currentName", off)
                        } else {
                            val on = s.connectPairedDevice(current.address)
                            if (on and 0x0F == 0) "$name est maintenant prioritaire (vérifie les numéros)."
                            else describePeripheralResult("Reconnexion de $currentName", on)
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDeviceActions(dev: PairedDevice, isPhone: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val name = dev.name.ifBlank { dev.address }
        if (dev.connected) {
            if (dev.linkStatus != 1) {
                labels.add("Définir comme prioritaire (n°1)")
                actions.add { makePriority(dev, name) }
            }
            if (!dev.isPlaybackDevice) {
                labels.add("Lire sur cet appareil")
                actions.add {
                    runPairedCmd("Bascule de la lecture vers $name…") { s ->
                        describeSourceSwitch(s.switchSource(dev.address))
                    }
                }
            }
            labels.add(if (isPhone) "Déconnecter (coupe aussi ce contrôle)" else "Déconnecter")
            actions.add {
                runPairedCmd("Déconnexion de $name…") { s ->
                    describePeripheralResult("Déconnexion", s.disconnectPairedDevice(dev.address))
                }
            }
        } else {
            labels.add("Connecter")
            actions.add {
                runPairedCmd("Connexion de $name…") { s ->
                    describePeripheralResult("Connexion", s.connectPairedDevice(dev.address))
                }
            }
        }
        labels.add("Oublier cet appareil…")
        actions.add { confirmUnpair(dev, name) }

        android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmUnpair(dev: PairedDevice, name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Oublier $name ?")
            .setMessage("L'appairage sera supprimé du casque ; il faudra le refaire pour réutiliser cet appareil.")
            .setPositiveButton("Oublier") { _, _ ->
                runPairedCmd("Suppression de l'appairage de $name…") { s ->
                    describePeripheralResult("Suppression de l'appairage", s.unpairDevice(dev.address))
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Résultat PeripheralResult : le quartet haut donne l'opération, le bas l'issue (0 = succès). */
    private fun describePeripheralResult(what: String, code: Int): String {
        return when (code and 0x0F) {
            0x00 -> "$what réussie."
            0x01 -> "$what : erreur du casque."
            MdrConstants.PERIPHERAL_BUSY -> "$what : le casque est occupé, réessaie dans un instant."
            else -> "$what : réponse inattendue (0x${code.toString(16)})."
        }
    }

    private fun describeSourceSwitch(code: Int): String = when (code) {
        MdrConstants.SOURCE_SWITCH_SUCCESS -> "Lecture basculée."
        MdrConstants.SOURCE_SWITCH_FAIL_CALLING -> "Bascule impossible : un appel est en cours."
        MdrConstants.SOURCE_SWITCH_FAIL_A2DP_NOT_CONNECT -> "Bascule impossible : l'appareil n'a pas de lien audio (A2DP) actif."
        MdrConstants.SOURCE_SWITCH_FAIL_VOICE_ASSISTANT -> "Bascule impossible : l'assistant vocal est prioritaire."
        else -> "Bascule refusée par le casque (code $code)."
    }

    // ---------------------------------------------------------------------------------------
    // Commandes et affichage de l'état
    // ---------------------------------------------------------------------------------------

    private fun changeVolume(delta: Int) {
        lastVolume = (lastVolume + volumeStep * delta).coerceIn(0, MdrConstants.PLAY_VOLUME_MAX)
        updateVolumeLabel()
        runBluetoothCmd(if (delta < 0) "Volume −…" else "Volume +…") { it.setMusicVolume(lastVolume) }
    }

    private fun updateVolumeLabel() {
        if (!::volumeValue.isInitialized) return
        volumeValue.text = if (muted) "Muet" else "$lastVolume"
        muteButton.text = if (muted) "Rétablir le son" else "Muet"
        muteButton.isSelected = muted
        val shown = if (muted) 0 else lastVolume
        (volumeFill.layoutParams as LinearLayout.LayoutParams).weight = shown.toFloat()
        (volumeRest.layoutParams as LinearLayout.LayoutParams).weight = (MdrConstants.PLAY_VOLUME_MAX - shown).toFloat()
        volumeFill.requestLayout()
    }

    /** Connexion complète + action Bluetooth, puis rafraîchit l'écran. */
    @Suppress("MissingPermission")
    private fun runBluetoothCmd(statusMsg: String, attempts: Int = 2, block: (MdrSession) -> Unit) {
        val device = selectedDevice ?: return
        if (busy) return
        busy = true
        setStatus(statusMsg)
        Thread {
            try {
                val report = HeadphoneClient.connectAndApply(device, attempts, block)
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun refreshBattery(silent: Boolean = false) {
        val device = selectedDevice ?: return
        if (busy) return
        busy = true
        if (!silent) setStatus("Connexion à ${device.name}…")
        Thread {
            try {
                val report = HeadphoneClient.connectAndReadBattery(device)
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    private fun showBattery(deviceLabel: String, report: BatteryReport) {
        val charging = report.chargingStatus == MdrConstants.CHARGING_CHARGING
        batteryBig.text = "${report.levelPercent} %" + if (charging) " ⚡" else ""
        batteryBig.setTextColor(if (report.levelPercent <= 15 && !charging) kit.p.danger else kit.p.text)
        titleText.text = deviceLabel
        report.musicVolume?.let { lastVolume = it.coerceIn(0, MdrConstants.PLAY_VOLUME_MAX) }
        updateVolumeLabel()
        detailText.text = listOfNotNull(
            "Connecté",
            report.firmwareVersion?.let { "firmware $it" },
            report.chargingLabel.takeIf { charging || report.chargingStatus == MdrConstants.CHARGING_CHARGED },
        ).joinToString(" · ")
        updateNcAsmButtons(report)
        // Après la mise à jour de l'écran en cours, charge l'onglet affiché s'il attendait que l'app soit libre.
        window.decorView.post { loadCurrentTabIfNeeded() }
        val notXm6 = report.modelName?.takeIf { !it.uppercase().contains("XM6") }
        if (notXm6 != null) {
            setStatus("⚠️ $notXm6 n'est pas le WH-1000XM6 : support non garanti.")
        } else {
            setStatus("")
        }
    }

    private fun updateNcAsmButtons(report: BatteryReport) {
        val nc = report.ncAsm
        ncAsmState = nc
        ncButton.isEnabled = nc != null
        ambButton.isEnabled = nc != null
        offButton.isEnabled = nc != null
        val active = when {
            nc != null && !nc.enabled -> MdrSession.MODE_OFF
            nc != null && nc.mode == MdrConstants.NCASM_MODE_ASM -> MdrSession.MODE_AMBIENT
            nc != null -> MdrSession.MODE_NC
            else -> -1
        }
        ncButton.isSelected = active == MdrSession.MODE_NC
        ambButton.isSelected = active == MdrSession.MODE_AMBIENT
        offButton.isSelected = active == MdrSession.MODE_OFF
        ncSummary.text = when (active) {
            MdrSession.MODE_NC -> "Réduction de bruit active"
            MdrSession.MODE_AMBIENT -> if (nc?.autoEnabled == true) "Son ambiant automatique" else "Son ambiant, niveau ${nc?.ambientLevel}"
            MdrSession.MODE_OFF -> "Aucun traitement du bruit"
            else -> "Mode non rapporté par le casque"
        }

        val ambientActive = nc != null && nc.enabled && nc.mode == MdrConstants.NCASM_MODE_ASM
        val manualLevel = ambientActive && !nc.autoEnabled
        if (manualLevel) lastAmbientLevel = nc.ambientLevel
        ambientGroup.visibility = if (ambientActive) View.VISIBLE else View.GONE
        ambientSeekBar.isEnabled = manualLevel
        autoAmbientCheck.isEnabled = ambientActive
        suppressSeekBar = true
        if (manualLevel) {
            ambientSeekBar.progress = (nc.ambientLevel - 1).coerceIn(0, MdrConstants.AMBIENT_LEVEL_MAX - 1)
            ambientLevelText.text = "Niveau ambiant : ${nc.ambientLevel}"
        } else if (ambientActive) {
            ambientLevelText.text = "Niveau géré automatiquement par le casque"
        }
        suppressSeekBar = false
        suppressAutoCheck = true
        autoAmbientCheck.isChecked = nc?.autoEnabled == true
        suppressAutoCheck = false
    }

    @Suppress("MissingPermission")
    private fun setNcAsmMode(device: BluetoothDevice, mode: Int) {
        if (busy) return
        busy = true
        setStatus("Application du mode…")
        Thread {
            try {
                val autoEnabled = ncAsmState?.autoEnabled == true && mode == MdrSession.MODE_AMBIENT
                val report = HeadphoneClient.connectAndSetNcAsm(
                    device,
                    mode,
                    ambientLevel = if (mode == MdrSession.MODE_AMBIENT) lastAmbientLevel else MdrConstants.AMBIENT_LEVEL_DEFAULT,
                    autoEnabled = autoEnabled,
                )
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    requestedMode = null
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun setAmbientLevel(device: BluetoothDevice, level: Int) {
        if (busy) return
        busy = true
        setStatus("Réglage du niveau ambiant…")
        Thread {
            try {
                val autoEnabled = ncAsmState?.autoEnabled == true
                val report = HeadphoneClient.connectAndSetAmbientLevel(device, level, autoEnabled)
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun setAutoAmbient(device: BluetoothDevice, enabled: Boolean) {
        if (busy) return
        busy = true
        setStatus(if (enabled) "Activation du son ambiant automatique…" else "Désactivation du son ambiant automatique…")
        Thread {
            try {
                val report = HeadphoneClient.connectAndSetAutoAmbient(device, enabled, lastAmbientLevel)
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------------------------
    // Permissions et choix du casque
    // ---------------------------------------------------------------------------------------

    private fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH_CONNECT)
                return
            }
        }
        listDevices()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH_CONNECT && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) listDevices()
            else setStatus("Permission Bluetooth requise pour se connecter au casque.")
        }
    }

    @Suppress("MissingPermission")
    private fun listDevices() {
        val btAdapter = adapter
        if (btAdapter == null) {
            setStatus("Bluetooth non disponible sur cet appareil.")
            return
        }
        val bonded = btAdapter.bondedDevices
        if (bonded == null || bonded.isEmpty()) {
            setStatus("Aucun appareil appairé. Appaire ton casque dans les réglages Bluetooth.")
            return
        }
        val pre = bonded.filter { HeadphoneClient.isCandidate(it) }
        if (pre.isEmpty()) {
            setStatus("Aucun appareil Bluetooth classique trouvé.")
            return
        }

        pendingDevices.clear()
        pendingDevices.addAll(pre)
        discoveredUuids.clear()
        deviceList.adapter = null
        deviceCard.visibility = View.GONE
        batteryBig.text = "—"
        setStatus("Détection des casques Sony…")
        var needScan = false
        for (device in pre) {
            val cached = runCatching { device.uuids?.toList() }.getOrNull()
            if (cached != null) {
                discoveredUuids[device.address] = cached
            } else {
                needScan = true
                runCatching { device.fetchUuidsWithSdp() }
            }
        }
        if (needScan) {
            finishUuidScan()
        } else {
            deviceList.postDelayed(uuidScanRunnable, UUID_SCAN_TIMEOUT_MS)
        }
    }

    @Suppress("MissingPermission")
    private fun finishUuidScan() {
        deviceList.removeCallbacks(uuidScanRunnable)
        val sony = pendingDevices.filter { device ->
            discoveredUuids[device.address]
                ?.any { it.uuid == HeadphoneClient.SERVICE_UUID } == true
        }
        if (sony.isEmpty()) {
            listAllFallback()
            return
        }
        showDeviceList(sony)
    }

    @Suppress("MissingPermission")
    private fun listAllFallback() {
        setStatus(
            "Détection SDP sans résultat — liste des appareils appairés.\n" +
                "Seul le WH-1000XM6 est pris en charge : la connexion le confirme.",
        )
        showDeviceList(pendingDevices)
    }

    @Suppress("MissingPermission")
    private fun showDeviceList(devices: List<BluetoothDevice>) {
        val candidates = HeadphoneClient.sortSonyFirst(devices)
        if (candidates.size == 1) {
            autoConnect(candidates[0])
            return
        }
        val names = candidates.map { "${it.name}\n${it.address.uppercase(Locale.ROOT)}" }
        val itemLayout = android.R.layout.simple_list_item_2
        deviceList.adapter = object : android.widget.ArrayAdapter<String>(this, itemLayout, android.R.id.text2, names) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<TextView>(android.R.id.text1)?.apply {
                    text = candidates[position].name
                    setTextColor(kit.p.text)
                }
                view.findViewById<TextView>(android.R.id.text2)?.apply {
                    text = candidates[position].address.uppercase(Locale.ROOT)
                    setTextColor(kit.p.textSecondary)
                }
                return view
            }
        }
        deviceList.layoutParams = deviceList.layoutParams.apply { height = kit.dp(64) * candidates.size.coerceAtMost(4) }
        deviceCard.visibility = View.VISIBLE
        deviceList.setOnItemClickListener { _, _, position, _ ->
            val device = candidates[position]
            autoConnect(device)
        }
        setStatus("Plusieurs appareils trouvés : choisis ton casque.")
    }

    @Suppress("MissingPermission")
    private fun autoConnect(device: BluetoothDevice) {
        selectedDevice = device
        deviceCard.visibility = View.GONE
        refreshButton.isEnabled = true
        eqLoaded = false
        settingsLoaded = false
        devicesLoaded = false
        titleText.text = device.name ?: "Casque"
        batteryBig.text = "—"
        detailText.text = "Connexion…"
        val mode = requestedMode
        if (mode != null) {
            setStatus("Connexion à ${device.name} et application du mode…")
            setNcAsmMode(device, mode)
        } else {
            setStatus("Connexion automatique à ${device.name}…")
            refreshBattery()
        }
    }

    /** Bandeau d'état sous l'en-tête : bleu pendant une action, rouge en cas d'échec, masqué quand tout va bien. */
    private fun setStatus(msg: String) {
        runOnUiThread {
            if (!::statusText.isInitialized) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val p = kit.p
            statusText.text = msg
            statusText.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            val (background, color) = when {
                msg.startsWith("Échec") || msg.startsWith("⚠") -> p.dangerSoft to p.danger
                msg.endsWith("…") -> p.accentSoft to p.accent
                else -> p.neutral to p.text
            }
            statusText.background = kit.rounded(background, 12)
            statusText.setTextColor(color)
            statusText.setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
        }
    }
}
