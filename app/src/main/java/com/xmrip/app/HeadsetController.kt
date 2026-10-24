package com.xmrip.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.EqState
import com.xmrip.app.protocol.FrameLog
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.NcAsmState
import com.xmrip.app.protocol.PairedDevicesInfo
import com.xmrip.app.protocol.SettingsState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Cerveau de l'écran principal : choix du casque, file des commandes et dernier état connu (batterie, bruit, volume,
 * réglages, égaliseur, appareils). Les pages observent ses changements et ne parlent jamais au casque elles-mêmes.
 *
 * Les commandes passent une à une dans l'ordre (avant, une action lancée pendant une autre était perdue). L'écran est
 * mis à jour tout de suite avec l'état attendu, puis corrigé par la réponse du casque ; en cas d'échec il revient à
 * l'état d'avant. Curseurs (volume, bandes de l'égaliseur) : seule la dernière valeur part.
 */
class HeadsetController(private val activity: Activity) {

    enum class Part { CONNECTION, REPORT, SETTINGS, EQ, DEVICES, BUSY }

    sealed class Conn {
        object NoBluetooth : Conn()
        object BluetoothOff : Conn()
        object NoPermission : Conn()
        object NoHeadset : Conn()
        object Searching : Conn()
        data class Choose(val devices: List<BluetoothDevice>) : Conn()
        data class Connecting(val name: String) : Conn()
        object Connected : Conn()
        data class Failed(val message: String) : Conn()
    }

    fun interface Observer {
        fun changed(part: Part)
    }

    var conn: Conn = Conn.Searching
        private set
    var device: BluetoothDevice? = null
        private set
    var report: BatteryReport? = null
        private set
    var settings: SettingsState? = null
        private set
    var eq: EqState? = null
        private set
    var devices: PairedDevicesInfo? = null
        private set

    /** Libellé de la commande en cours, null quand la file est vide. */
    var busy: String? = null
        private set
    var settingsLoaded = false
        private set
    var eqLoaded = false
        private set
    var devicesLoaded = false
        private set

    /** Mode demandé par un raccourci avant que la connexion soit prête ; appliqué dès qu'elle l'est. */
    var pendingMode: Int? = null

    /** Message court à montrer (résultat d'une action, erreur) ; `true` = erreur. */
    var onMessage: ((String, Boolean) -> Unit)? = null

    /** Dernier niveau ambiant manuel, gardé pour repasser en ambiant au même niveau. */
    var ambientLevel: Int = HeadsetCache.load(activity).ambientLevel
        private set

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xmrip-commandes").apply { isDaemon = true } }
    private var queued = 0
    private var warnedModel = false

    val adapter: BluetoothAdapter?
        get() = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Suppress("MissingPermission")
    val deviceName: String
        get() = runCatching { device?.name }.getOrNull() ?: report?.modelName ?: HeadsetCache.load(activity).name ?: "Casque"

    val connected: Boolean get() = conn is Conn.Connected

    fun observe(observer: Observer) {
        observers.add(observer)
    }

    private fun emit(part: Part) = observers.forEach { it.changed(part) }

    private fun setConn(c: Conn) {
        conn = c
        emit(Part.CONNECTION)
    }

    // ---------------------------------------------------------------------------------------
    // File des commandes
    // ---------------------------------------------------------------------------------------

    /**
     * Ajoute [work] à la file. [work] tourne hors du thread d'interface avec le casque choisi ; [done] reçoit son
     * résultat sur le thread d'interface. Sans [failed], une erreur est affichée (et marque la connexion en échec si
     * elle n'était pas encore établie).
     */
    private fun <T> enqueue(label: String?, work: (BluetoothDevice) -> T, done: (T) -> Unit, failed: ((Exception) -> Unit)? = null) {
        val d = device
        if (d == null) {
            onMessage?.invoke("Aucun casque choisi.", true)
            return
        }
        queued++
        if (label != null) {
            busy = label
            emit(Part.BUSY)
        }
        worker.execute {
            try {
                val result = work(d)
                main.post {
                    finishOne()
                    if (device?.address == d.address) done(result)
                }
            } catch (e: Exception) {
                FrameLog.note("échec : ${e.message ?: e.javaClass.simpleName}")
                main.post {
                    finishOne()
                    if (device?.address != d.address) return@post
                    if (failed != null) failed(e) else fail(e)
                }
            }
        }
    }

    private fun finishOne() {
        queued = (queued - 1).coerceAtLeast(0)
        if (queued == 0) {
            busy = null
            emit(Part.BUSY)
        }
    }

    private fun fail(e: Exception) {
        val reason = e.message ?: e.javaClass.simpleName
        if (conn !is Conn.Connected) {
            setConn(Conn.Failed(reason))
        } else {
            onMessage?.invoke("Échec : $reason. Le casque est-il allumé et libre (aucune autre appli connectée) ?", true)
        }
    }

    private fun applyReport(r: BatteryReport) {
        report = r
        r.ncAsm?.let { nc ->
            if (nc.enabled && nc.mode == MdrConstants.NCASM_MODE_ASM && !nc.autoEnabled && nc.ambientLevel in 1..MdrConstants.AMBIENT_LEVEL_MAX) {
                ambientLevel = nc.ambientLevel
            }
        }
        if (conn !is Conn.Connected) setConn(Conn.Connected)
        device?.let { LastDevice.save(activity, it) }
        HeadsetCache.save(activity, deviceName, r)
        emit(Part.REPORT)
        val model = r.modelName
        if (!warnedModel && model != null && !model.uppercase().contains("XM6")) {
            warnedModel = true
            onMessage?.invoke("$model n'est pas un WH-1000XM6 : certaines fonctions peuvent ne pas répondre.", true)
        }
    }

    private fun applySettings(s: SettingsState?) {
        if (s == null) return
        settings = s.fillMissingFrom(settings)
        settingsLoaded = true
        emit(Part.SETTINGS)
    }

    private fun applyEq(e: EqState?) {
        if (e == null) {
            onMessage?.invoke("Le casque n'a pas répondu pour l'égaliseur.", true)
            emit(Part.EQ)
            return
        }
        eq = e
        eqLoaded = true
        emit(Part.EQ)
    }

    private fun applyDevices(info: PairedDevicesInfo) {
        devices = info
        devicesLoaded = true
        emit(Part.DEVICES)
    }

    // ---------------------------------------------------------------------------------------
    // Connexion et état
    // ---------------------------------------------------------------------------------------

    fun connect() {
        val d = device ?: return
        setConn(Conn.Connecting(deviceName))
        enqueue("Connexion à ${deviceName}…", { HeadphoneClient.connectAndReadBattery(it) }, { r ->
            applyReport(r)
            pendingMode?.let {
                pendingMode = null
                setMode(it)
            }
            // Les réglages servent sur plusieurs pages (raccourcis de l'accueil, Son, Réglages) : on les lit tout de suite.
            if (!settingsLoaded) readSettings(silent = true)
            FrameLog.note("connecté à ${d.address}")
        })
    }

    /** Relit batterie, bruit et volume ; [silent] = pas de bandeau ni de message en cas d'échec (relève périodique). */
    fun refreshStatus(silent: Boolean = false) {
        if (device == null) return
        if (!connected && !silent) {
            connect()
            return
        }
        enqueue(if (silent) null else "Actualisation…", { HeadphoneClient.connectAndReadBattery(it) }, ::applyReport,
            if (silent) { _ -> } else null)
    }

    /** Relit tout ce qui a déjà été chargé (bouton Actualiser). */
    fun refreshAll() {
        if (device == null) {
            discover()
            return
        }
        refreshStatus()
        if (settingsLoaded) readSettings()
        if (eqLoaded) readEq()
        if (devicesLoaded) readDevices()
    }

    private val live = object : MdrSession.StateListener {
        override fun onReport(report: BatteryReport) {
            main.post { if (device != null && queued == 0 && connected) applyReport(report) }
        }

        override fun onSettings(settings: SettingsState) {
            main.post { if (queued == 0 && settingsLoaded) applySettings(settings) }
        }

        override fun onDevices(info: PairedDevicesInfo) {
            main.post { if (queued == 0 && devicesLoaded) applyDevices(info) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (connected && queued == 0) refreshStatus(silent = true)
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        HeadphoneClient.addListener(live)
        runCatching { activity.registerReceiver(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID)) }
        main.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        HeadphoneClient.removeListener(live)
        runCatching { activity.unregisterReceiver(uuidReceiver) }
        main.removeCallbacks(poll)
        main.removeCallbacks(scanTimeout)
    }

    fun release() {
        worker.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Choix du casque
    // ---------------------------------------------------------------------------------------

    private val pendingScan = mutableListOf<BluetoothDevice>()
    private val uuids = HashMap<String, List<ParcelUuid>>()
    private val scanTimeout = Runnable { finishScan() }

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            @Suppress("DEPRECATION")
            val found = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<ParcelUuid>() ?: return
            uuids[d.address] = found
            if (pendingScan.isNotEmpty() && pendingScan.all { uuids.containsKey(it.address) }) finishScan()
        }
    }

    fun permissionMissing() = setConn(Conn.NoPermission)

    /**
     * Cherche le casque : le dernier utilisé s'il est toujours appairé (connexion directe, sans recherche), sinon les
     * appareils appairés qui exposent le service MDR. [choose] force la liste (« Choisir un autre casque »).
     */
    @Suppress("MissingPermission")
    fun discover(choose: Boolean = false) {
        val a = adapter
        if (a == null) {
            setConn(Conn.NoBluetooth)
            return
        }
        if (!a.isEnabled) {
            setConn(Conn.BluetoothOff)
            return
        }
        if (!choose) {
            LastDevice.load(activity, a)?.let {
                select(it)
                return
            }
        }
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty().filter { HeadphoneClient.isCandidate(it) }
        if (bonded.isEmpty()) {
            setConn(Conn.NoHeadset)
            return
        }
        setConn(Conn.Searching)
        pendingScan.clear()
        pendingScan.addAll(bonded)
        uuids.clear()
        var missing = false
        for (d in bonded) {
            val cached = runCatching { d.uuids?.toList() }.getOrNull()
            if (cached != null) {
                uuids[d.address] = cached
            } else {
                missing = true
                runCatching { d.fetchUuidsWithSdp() }
            }
        }
        // Corrigé : l'ancienne version attendait 4 s quand tout était déjà connu et n'attendait pas quand il fallait.
        if (missing) main.postDelayed(scanTimeout, UUID_SCAN_TIMEOUT_MS) else finishScan()
    }

    private fun finishScan() {
        main.removeCallbacks(scanTimeout)
        if (pendingScan.isEmpty()) return
        val sony = pendingScan.filter { d -> uuids[d.address]?.any { it.uuid == HeadphoneClient.SERVICE_UUID } == true }
        val list = HeadphoneClient.sortSonyFirst(sony.ifEmpty { pendingScan.toList() })
        pendingScan.clear()
        if (list.size == 1) select(list[0]) else setConn(Conn.Choose(list))
    }

    fun select(d: BluetoothDevice) {
        if (device?.address != d.address) {
            settings = null
            eq = null
            devices = null
            settingsLoaded = false
            eqLoaded = false
            devicesLoaded = false
            readingSettings = false
            readingEq = false
            readingDevices = false
            warnedModel = false
        }
        device = d
        connect()
    }

    // ---------------------------------------------------------------------------------------
    // Bruit, volume, lecture
    // ---------------------------------------------------------------------------------------

    private fun predicted(mode: Int, auto: Boolean): NcAsmState = NcAsmState(
        enabled = mode != MdrSession.MODE_OFF,
        mode = if (mode == MdrSession.MODE_AMBIENT) MdrConstants.NCASM_MODE_ASM else MdrConstants.NCASM_MODE_NC,
        ambientLevel = if (mode == MdrSession.MODE_AMBIENT) ambientLevel else 0,
        autoEnabled = auto,
    )

    private fun changeNc(label: String, optimistic: NcAsmState, work: (BluetoothDevice) -> BatteryReport) {
        val before = report
        before?.let {
            report = it.copy(ncAsm = optimistic)
            emit(Part.REPORT)
        }
        enqueue(label, work, ::applyReport) { e ->
            if (before != null) {
                report = before
                emit(Part.REPORT)
            }
            fail(e)
        }
    }

    fun setMode(mode: Int) {
        if (device == null) {
            pendingMode = mode
            return
        }
        val auto = mode == MdrSession.MODE_AMBIENT && (report?.ncAsm?.autoEnabled ?: HeadsetCache.load(activity).autoAmbient)
        val level = ambientLevel
        changeNc("${HeadsetCache.modeLabel(mode)}…", predicted(mode, auto)) {
            HeadphoneClient.connectAndSetNcAsm(it, mode, if (mode == MdrSession.MODE_AMBIENT) level else MdrConstants.AMBIENT_LEVEL_DEFAULT, auto)
        }
    }

    fun setAmbientLevel(level: Int) {
        ambientLevel = level
        changeNc("Niveau ambiant…", predicted(MdrSession.MODE_AMBIENT, false)) {
            HeadphoneClient.connectAndSetAmbientLevel(it, level, false)
        }
    }

    fun setAutoAmbient(on: Boolean) {
        val level = ambientLevel
        changeNc(if (on) "Ambiant automatique…" else "Ambiant manuel…", predicted(MdrSession.MODE_AMBIENT, on)) {
            HeadphoneClient.connectAndSetAutoAmbient(it, on, level)
        }
    }

    private val volumeTarget = AtomicInteger(-1)

    /** Volume du casque (0..30) ; pendant un glissement, seule la dernière valeur est envoyée. */
    fun setVolume(volume: Int) {
        report?.let {
            report = it.copy(musicVolume = volume)
            emit(Part.REPORT)
        }
        if (volumeTarget.getAndSet(volume) != -1) return
        enqueue(null, { d ->
            val v = volumeTarget.getAndSet(-1)
            HeadphoneClient.connectAndApply(d, 2) { it.setMusicVolume(v) }
        }, ::applyReport)
    }

    /** Affiche un volume connu autrement (volume du téléphone suivi par AVRCP), sans rien envoyer. */
    fun showVolume(volume: Int) {
        val r = report ?: return
        if (queued > 0 || r.musicVolume == volume) return
        report = r.copy(musicVolume = volume)
        emit(Part.REPORT)
    }

    fun playControl(control: Int) {
        enqueue(null, { d -> HeadphoneClient.connectAndApply(d, 2) { it.playControl(control) } }, ::applyReport)
    }

    fun powerOff() {
        enqueue("Extinction du casque…", { d -> HeadphoneClient.connectAndApply(d, 1) { it.powerOff() } }, {
            setConn(Conn.Failed("Casque éteint. Rallume-le avec son bouton puis réessaie."))
        }) { _ ->
            // La liaison se coupe au moment de l'extinction : c'est le signe que la commande est passée.
            HeadphoneClient.disconnect()
            setConn(Conn.Failed("Casque éteint. Rallume-le avec son bouton puis réessaie."))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Réglages
    // ---------------------------------------------------------------------------------------

    /** Vrai pendant qu'une lecture est déjà dans la file : deux pages qui la demandent en même temps n'en font qu'une. */
    private var readingSettings = false
    private var readingEq = false
    private var readingDevices = false

    fun readSettings(silent: Boolean = false) {
        if (readingSettings) return
        readingSettings = true
        enqueue(if (silent) null else "Lecture des réglages…", { d ->
            var s: SettingsState? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { s = it.readSettings() }
            r to s
        }, { (r, s) ->
            readingSettings = false
            applyReport(r)
            applySettings(s)
        }) { e ->
            readingSettings = false
            if (!silent) fail(e)
        }
    }

    /**
     * Change un réglage : [optimistic] donne l'état affiché tout de suite, [action] l'écrit sur le casque et renvoie
     * l'état relu. En cas d'échec l'écran revient à l'état d'avant.
     */
    fun changeSetting(label: String, optimistic: (SettingsState) -> SettingsState, action: (MdrSession) -> SettingsState) {
        val before = settings
        before?.let {
            settings = optimistic(it)
            emit(Part.SETTINGS)
        }
        enqueue(label, { d ->
            var s: SettingsState? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { s = action(it) }
            r to s
        }, { (r, s) ->
            applyReport(r)
            applySettings(s)
        }) { e ->
            settings = before
            emit(Part.SETTINGS)
            fail(e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Égaliseur
    // ---------------------------------------------------------------------------------------

    fun readEq() {
        if (readingEq) return
        readingEq = true
        enqueue("Lecture de l'égaliseur…", { d ->
            var e: EqState? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { e = it.readEq() }
            r to e
        }, { (r, e) ->
            readingEq = false
            applyReport(r)
            applyEq(e)
        }) { e ->
            readingEq = false
            fail(e)
        }
    }

    fun setEqPreset(code: Int) {
        val before = eq
        eq = (before ?: EqState(code, emptyList())).copy(preset = code)
        emit(Part.EQ)
        enqueue("Égaliseur…", { d ->
            var e: EqState? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { e = it.setEqPreset(code) }
            r to e
        }, { (r, e) ->
            applyReport(r)
            applyEq(e)
        }) { e ->
            eq = before
            emit(Part.EQ)
            fail(e)
        }
    }

    private val bandsTarget = AtomicReference<List<Int>?>(null)

    /** Courbe personnalisée ; si l'utilisateur rebouge une bande avant l'envoi, seule la dernière courbe part. */
    fun setEqBands(gains: List<Int>) {
        eq = EqState(MdrConstants.EQ_XM6_CUSTOM, gains)
        emit(Part.EQ)
        if (bandsTarget.getAndSet(gains) != null) return
        enqueue("Égaliseur…", { d ->
            val target = bandsTarget.getAndSet(null) ?: gains
            var e: EqState? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { e = it.setEqBands(target) }
            r to e
        }, { (r, e) ->
            applyReport(r)
            applyEq(e)
        })
    }

    // ---------------------------------------------------------------------------------------
    // Appareils (multipoint)
    // ---------------------------------------------------------------------------------------

    fun readDevices() {
        if (readingDevices) return
        deviceAction("Lecture des appareils…", null)
    }

    /**
     * Action multipoint facultative (renvoie un message à afficher) puis relecture de la liste. Une seule tentative :
     * une action comme « oublier » ne doit pas être rejouée.
     */
    fun deviceAction(label: String, action: ((MdrSession) -> String?)?) {
        if (action == null) readingDevices = true
        enqueue(label, { d ->
            var info: PairedDevicesInfo? = null
            var message: String? = null
            var listError: String? = null
            val r = HeadphoneClient.connectAndApply(d, 1) { s ->
                if (action != null) message = action(s)
                try {
                    info = s.readPairedDevices()
                } catch (e: Exception) {
                    listError = e.message ?: e.javaClass.simpleName
                }
            }
            Triple(r, info, message ?: listError?.let { "Liste des appareils indisponible : $it" })
        }, { (r, info, message) ->
            if (action == null) readingDevices = false
            applyReport(r)
            info?.let { applyDevices(it) }
            if (message != null) onMessage?.invoke(message, info == null)
        }) { e ->
            if (action == null) readingDevices = false
            fail(e)
        }
    }

    companion object {
        private const val UUID_SCAN_TIMEOUT_MS = 4_000L
        private const val POLL_MS = 60_000L
    }
}
