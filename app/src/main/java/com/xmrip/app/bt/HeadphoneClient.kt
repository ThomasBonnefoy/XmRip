package com.xmrip.app.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.MdrProtocolException
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.NcAsmState
import com.xmrip.app.protocol.PairedDevicesInfo
import com.xmrip.app.protocol.SettingsState
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Connexion RFCOMM au service "Serial HPC" du casque et exécution de la session MDR.
 */
object HeadphoneClient {

    val SERVICE_UUID: UUID = UUID.fromString(MdrConstants.SERVICE_UUID)

    private const val UUID_WAIT_TIMEOUT_MS = 4_000L

    /** Candidat idoine : appareil classique/dual appairé avec un nom. */
    fun isCandidate(device: BluetoothDevice): Boolean {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return false
        return !device.name.isNullOrBlank()
    }

    /**
     * Liste des candidats dans l'ordre de tentative :
     * dernier casque connecté (mémoire), sinon scan SDP filtré sur le service MDR,
     * sinon tous les appareils appairés triés « Sony d'abord ».
     */
    @Suppress("MissingPermission")
    fun findCandidates(context: Context, btAdapter: BluetoothAdapter?): List<BluetoothDevice> {
        if (btAdapter == null) return emptyList()
        LastDevice.load(context, btAdapter)?.let { return listOf(it) }
        val bonded = btAdapter.bondedDevices
        if (bonded == null || bonded.isEmpty()) return emptyList()
        val pre = bonded.filter { isCandidate(it) }
        if (pre.isEmpty()) return emptyList()

        val uuids = HashMap<String, List<ParcelUuid>>()
        for (device in pre) {
            runCatching { device.uuids?.toList() }?.getOrNull()?.let { uuids[device.address] = it }
        }
        if (pre.any { !uuids.containsKey(it.address) }) {
            runCatching { pre.forEach { it.fetchUuidsWithSdp() } }
            val deadline = System.currentTimeMillis() + UUID_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                pre.forEach { device ->
                    if (!uuids.containsKey(device.address)) {
                        runCatching { device.uuids?.toList() }?.getOrNull()
                            ?.let { uuids[device.address] = it }
                    }
                }
                Thread.sleep(200)
            }
        }
        val sony = pre.filter {
            uuids[it.address]?.any { u -> u.uuid == SERVICE_UUID } == true
        }
        return if (sony.isNotEmpty()) sortSonyFirst(sony) else sortSonyFirst(pre)
    }

    /** Trie : les casques/enceintes Sony d'abord. */
    fun sortSonyFirst(devices: List<BluetoothDevice>): List<BluetoothDevice> {
        return devices.sortedByDescending { score(deviceNameSony(it)) }
    }

    private fun score(name: String): Int {
        return when {
            "WH" in name && "XM" in name -> 8
            "XM" in name -> 6
            "WH" in name || "WF" in name -> 4
            "SONY" in name || "Sony" in name || "LINKBUDS" in name.uppercase() -> 2
            else -> 0
        }
    }

    private fun deviceNameSony(device: BluetoothDevice): String = device.name ?: ""

    @Suppress("MissingPermission")
    fun connectAndReadBattery(device: BluetoothDevice): BatteryReport {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attempt(device)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    /**
     * Se connecte, se synchronise (batterie + état NC/ASM), applique le mode demandé
     * puis renvoie le rapport avec l'état mis à jour.
     * @param mode MdrSession.MODE_NC, MODE_AMBIENT ou MODE_OFF.
     */
    @Suppress("MissingPermission")
    fun connectAndSetNcAsm(
        device: BluetoothDevice,
        mode: Int,
        ambientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT,
        autoEnabled: Boolean = false,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setNcAsm(mode, ambientLevel, autoEnabled) }
    }

    /** Règle le niveau de son ambiant (1..20) puis renvoie le rapport avec l'état confirmé. */
    @Suppress("MissingPermission")
    fun connectAndSetAmbientLevel(
        device: BluetoothDevice,
        level: Int,
        autoEnabled: Boolean,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setAmbientLevel(level, autoEnabled) }
    }

    /** Active/désactive le son ambiant automatique puis renvoie le rapport avec l'état confirmé. */
    @Suppress("MissingPermission")
    fun connectAndSetAutoAmbient(
        device: BluetoothDevice,
        enabled: Boolean,
        level: Int,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setAutoAmbient(enabled, level) }
    }

    /**
     * Version rapide pour les raccourcis : handshake minimal (pas de battery/device info)
     * puis application du mode. Ne renvoie que l'état NC/ASM confirmé.
     */
    @Suppress("MissingPermission")
    fun connectAndSetNcAsmFast(
        device: BluetoothDevice,
        mode: Int,
        ambientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT,
        autoEnabled: Boolean = false,
    ): NcAsmState {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attemptSetNcAsmFast(device, mode, ambientLevel, autoEnabled)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    /**
     * Connexion complète (handshake + batterie/état NC/ASM) puis exécution d'une action
     * quelconque sur la session. Renvoie le rapport pour rafraîchir l'écran.
     */
    @Suppress("MissingPermission")
    fun connectAndApply(device: BluetoothDevice, block: (MdrSession) -> Unit): BatteryReport {
        return connectAndApply(device, 2, block)
    }

    /** Comme [connectAndApply] avec un nombre de tentatives choisi (1 pour une action non rejouable, ex. désappairer). */
    @Suppress("MissingPermission")
    fun connectAndApply(device: BluetoothDevice, attempts: Int, block: (MdrSession) -> Unit): BatteryReport {
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                return attemptApply(device, attempts > 1, block)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    // ---------------------------------------------------------------------------------------
    // Session gardée ouverte
    //
    // Ouvrir la socket RFCOMM puis refaire le handshake à chaque action coûtait plusieurs secondes : la liaison
    // du XM6 met 0,4 à 0,9 s à répondre à chaque commande. La connexion reste donc ouverte entre deux actions ;
    // une action ne coûte plus qu'un échange. Un thread l'entretient (acquitte les notifications du casque,
    // sans quoi il renvoie ses trames) et la ferme après un moment d'inactivité pour laisser le casque libre.
    // ---------------------------------------------------------------------------------------

    private class Live(val device: BluetoothDevice, val socket: BluetoothSocket, val session: MdrSession)

    private const val POLL_TIMEOUT_MS = 4_000L
    private const val IDLE_CLOSE_MS = 90_000L
    private const val KEEPALIVE_PERIOD_MS = 300L

    /**
     * Prévenu des changements que le casque annonce (touche du casque, autre appareil qui se connecte…), depuis le
     * thread qui lit la liaison : l'écouteur doit basculer sur le thread d'interface lui-même.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<MdrSession.StateListener>()

    fun addListener(listener: MdrSession.StateListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: MdrSession.StateListener) {
        listeners.remove(listener)
    }

    private val forwarder = object : MdrSession.StateListener {
        override fun onReport(report: BatteryReport) {
            listeners.forEach { it.onReport(report) }
        }

        override fun onSettings(settings: SettingsState) {
            listeners.forEach { it.onSettings(settings) }
        }

        override fun onDevices(info: PairedDevicesInfo) {
            listeners.forEach { it.onDevices(info) }
        }

        override fun onGesture(gesture: String) {
            listeners.forEach { it.onGesture(gesture) }
        }
    }

    /**
     * Vrai tant que des actions de gestes sont choisies ([com.xmrip.app.GestureWatcher]) : la session gardée ouverte
     * n'est alors plus fermée pour inactivité, sans quoi le casque ne pourrait plus annoncer ses gestes.
     */
    @Volatile
    var keepOpen = false

    /** Vrai si une session est déjà ouverte avec ce casque. */
    fun isLive(device: BluetoothDevice): Boolean = synchronized(lock) { live?.device?.address == device.address }

    /**
     * Ouvre (ou réutilise) la session gardée ouverte et active le journal d'événements du casque, pour que ses
     * gestes arrivent aux écouteurs. Renvoie faux si la connexion échoue (casque absent, autre client en tête).
     */
    @Suppress("MissingPermission")
    fun openForGestures(device: BluetoothDevice): Boolean = try {
        withSession(device, full = false, retryStale = false) { it.enableGestureLog() }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Lit la liste des appareils connectés au casque pour une surveillance en arrière-plan. Sur la session gardée
     * ouverte si l'app en tient une ; sinon par une connexion à part, courte et fermée aussitôt, pour ne pas
     * occuper la session de contrôle (l'appli Windows en a besoin) ni le verrou de l'app. Renvoie null si le casque
     * ne répond pas : un autre client tient probablement la session, l'état précédent reste alors valable.
     */
    @Suppress("MissingPermission")
    fun pollDevices(device: BluetoothDevice): PairedDevicesInfo? {
        synchronized(lock) {
            val l = live
            if (l != null && l.device.address == device.address) {
                return try {
                    l.session.readPairedDevices()
                } catch (e: Exception) {
                    null
                }
            }
        }
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            val session = MdrSession(socket.inputStream, socket.outputStream)
            session.exchangeTimeoutMs = POLL_TIMEOUT_MS
            session.quickHandshake(0)
            session.readPairedDevices()
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private val lock = Any()
    private var live: Live? = null
    private var lastUse = 0L
    private var keepAlive: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xmrip-keepalive").apply { isDaemon = true }
    }

    @Suppress("MissingPermission")
    private fun openLive(device: BluetoothDevice): Live {
        val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        try {
            socket.connect()
            val session = MdrSession(socket.inputStream, socket.outputStream)
            session.stateListener = forwarder
            val l = Live(device, socket, session)
            live = l
            lastUse = System.currentTimeMillis()
            keepAlive = scheduler.scheduleWithFixedDelay(::keepAliveTick, KEEPALIVE_PERIOD_MS, KEEPALIVE_PERIOD_MS, TimeUnit.MILLISECONDS)
            return l
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** À appeler sous [lock]. */
    private fun closeLive() {
        keepAlive?.cancel(false)
        keepAlive = null
        val l = live ?: return
        live = null
        try {
            l.socket.close()
        } catch (_: Exception) {
        }
    }

    /** Ferme la connexion gardée ouverte (sortie de l'appli, ou casque à rendre à un autre appareil). */
    fun disconnect() = synchronized(lock) { closeLive() }

    private fun keepAliveTick() {
        synchronized(lock) {
            val l = live ?: return
            if (!keepOpen && System.currentTimeMillis() - lastUse > IDLE_CLOSE_MS) {
                closeLive()
                return
            }
            try {
                l.session.drain()
            } catch (_: Exception) {
                closeLive() // liaison coupée : la prochaine action se reconnecte
            }
        }
    }

    /** Modèle et firmware du casque, gardés d'une connexion à l'autre : ils ne se redemandent pas (2 échanges). */
    private var knownModel: String? = null
    private var knownFirmware: String? = null

    private fun ensureHandshake(session: MdrSession, full: Boolean) {
        if (full) {
            if (session.report == null) {
                session.seedDeviceInfo(knownModel, knownFirmware)
                session.handshakeAndBattery()
                knownModel = session.modelName ?: knownModel
                knownFirmware = session.firmwareVersion ?: knownFirmware
            }
        } else if (!session.handshaken) {
            session.quickHandshake(System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS)
        }
    }

    /**
     * Exécute [block] sur la session ouverte (connexion et handshake faits une seule fois). Si la session gardée
     * est périmée (casque éteint ou éloigné entre-temps) et que [retryStale] est vrai, l'action est rejouée une
     * fois sur une connexion neuve ; sinon (action non rejouable) l'erreur remonte.
     */
    private fun <T> withSession(device: BluetoothDevice, full: Boolean, retryStale: Boolean = true, block: (MdrSession) -> T): T =
        synchronized(lock) {
            var l = live
            if (l != null && l.device.address != device.address) {
                closeLive()
                l = null
            }
            val reused = l != null
            try {
                val current = l ?: openLive(device)
                ensureHandshake(current.session, full)
                val result = block(current.session)
                lastUse = System.currentTimeMillis()
                result
            } catch (e: Exception) {
                closeLive()
                if (!reused || !retryStale) throw e
                val fresh = openLive(device)
                try {
                    ensureHandshake(fresh.session, full)
                    val result = block(fresh.session)
                    lastUse = System.currentTimeMillis()
                    result
                } catch (e2: Exception) {
                    closeLive()
                    throw e2
                }
            }
        }

    private fun attempt(device: BluetoothDevice): BatteryReport =
        withSession(device, full = true) {
            // Connexion neuve : le handshake vient de tout lire.
            if (System.currentTimeMillis() - it.statusAt < 2_000L) it.report!! else it.refreshStatus()
        }

    private fun attemptSetNcAsm(device: BluetoothDevice, apply: (MdrSession) -> NcAsmState): BatteryReport =
        withSession(device, full = true) { session ->
            val updated = apply(session)
            session.report!!.copy(ncAsm = updated)
        }

    private fun retrySetNcAsm(device: BluetoothDevice, apply: (MdrSession) -> NcAsmState): BatteryReport {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attemptSetNcAsm(device, apply)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    private fun attemptApply(device: BluetoothDevice, retryStale: Boolean, block: (MdrSession) -> Unit): BatteryReport =
        withSession(device, full = true, retryStale = retryStale) { session ->
            block(session)
            session.report!!
        }

    private fun attemptSetNcAsmFast(device: BluetoothDevice, mode: Int, ambientLevel: Int, autoEnabled: Boolean): NcAsmState =
        withSession(device, full = false) { it.setNcAsm(mode, ambientLevel, autoEnabled) }
}