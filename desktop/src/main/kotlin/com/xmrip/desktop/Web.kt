package com.xmrip.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.EqState
import com.xmrip.app.protocol.HeadsetLook
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.PairedDevicesInfo
import com.xmrip.app.protocol.SettingsState
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Serveur local du banc de test pour l'app macOS (tools/mac-app) : le même `MdrSession` que la ligne de commande, derrière
 * une petite API HTTP en JSON. La session MDR est gardée ouverte entre deux requêtes (voir [Held]) ; les requêtes sont
 * traitées une par une (une seule session MDR à la fois). Écoute uniquement sur 127.0.0.1.
 *
 *   GET  /ping  -> « ok »
 *   POST /api   -> corps = commandes (« ambient 10 », « battery eq »…) ; réponse JSON {ok, error, output, state}
 *   POST /release -> rend la session gardée ouverte (libère le casque pour un autre client)
 *   POST /quit  -> arrête le serveur
 */

/** Commandes autorisées (pas `raw`, `guide`, `watch` ni `wait` : longues ou dangereuses). */
private val ALLOWED = setOf(
    "battery", "nc", "off", "ambient", "auto", "volume", "settings", "voice", "voice-volume", "priority",
    "chat-delay", "eq", "eq-preset", "eq-bands", "devices", "listen", "refresh",
    "chat", "dsee", "wear", "nc-button", "auto-off", "power-off", "pairing", "connect", "disconnect", "unpair", "switch",
)
/** Commandes après lesquelles les réglages, l'égaliseur ou la liste d'appareils sont relus pour la réponse. */
private val SETTING_COMMANDS = setOf(
    "settings", "voice", "voice-volume", "priority", "chat-delay", "listen", "chat", "dsee", "wear", "nc-button", "auto-off",
)
private val DEVICE_COMMANDS = setOf("devices", "pairing", "connect", "disconnect", "unpair", "switch")
private val TOKEN = Regex("[A-Za-z0-9:-]{1,20}")

private val lock = Any()

/** Durée sans requête au bout de laquelle la session est rendue (comme HeadphoneClient sur Android). */
private const val IDLE_CLOSE_MS = 60_000L

/**
 * Session gardée ouverte entre deux requêtes : connexion et handshake coûtent ~0,45 s chacun, et le casque n'accepte
 * qu'une session à la fois, donc un autre client (app du téléphone, `xm.sh`) attend qu'elle soit rendue.
 */
private class Held(val socket: Socket, val session: MdrSession) {
    var lastUse = System.currentTimeMillis()
    fun close() = runCatching { socket.close() }
}

private var held: Held? = null

/**
 * Modèle, firmware et couleur, gardés d'une session à l'autre comme dans HeadphoneClient (Android) : après une session
 * rendue, le handshake suivant ne les redemande pas (3 échanges, jusqu'à 1,5 s de plus si le casque tait sa couleur).
 */
private var knownModel: String? = null
private var knownFirmware: String? = null
private var knownColor: Int? = null

private fun dropHeld() {
    held?.close()
    held = null
}

/** Entretient la session pendant qu'elle est inactive : acquitte les notifications du casque, la rend après [IDLE_CLOSE_MS]. */
private fun startKeeper() {
    Thread {
        while (true) {
            Thread.sleep(300)
            synchronized(lock) {
                val h = held ?: return@synchronized
                if (System.currentTimeMillis() - h.lastUse > IDLE_CLOSE_MS) {
                    dropHeld()
                } else {
                    try { h.session.drain() } catch (_: Exception) { dropHeld() }
                }
            }
        }
    }.apply { isDaemon = true; name = "keeper" }.start()
}

private class Outcome(
    val ok: Boolean,
    val error: String?,
    val output: String,
    val state: BatteryReport?,
    val settings: SettingsState? = null,
    val eq: EqState? = null,
    val devices: PairedDevicesInfo? = null,
)

private fun fail(message: String) = Outcome(false, message, "", null)

/** Session prête à l'emploi : celle gardée ouverte si elle répond encore, sinon une nouvelle (+ handshake). */
private fun obtainSession(host: String, bridgePort: Int): Pair<Held, BatteryReport?> {
    held?.let {
        try {
            it.session.drain()
            return it to null
        } catch (_: Exception) {
            dropHeld()
        }
    }
    val socket = Socket().apply { connect(InetSocketAddress(host, bridgePort), 3_000) }
    val session = MdrSession(socket.getInputStream(), socket.getOutputStream())
    session.seedDeviceInfo(knownModel, knownFirmware, knownColor)
    val first = try {
        session.handshakeAndBattery()
    } catch (e: Exception) {
        runCatching { socket.close() }
        throw e
    }
    knownModel = session.modelName ?: knownModel
    knownFirmware = session.firmwareVersion ?: knownFirmware
    knownColor = session.modelColor ?: knownColor
    return Held(socket, session).also { held = it } to first
}

/** Exécute une ligne de commandes et renvoie ce qu'elles ont affiché, avec l'état du casque APRÈS les commandes. */
private fun execute(host: String, bridgePort: Int, line: String): Outcome {
    val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.any { !TOKEN.matches(it) }) return fail("Commande invalide")
    if (tokens.first() !in ALLOWED) return fail("Commande non autorisée : ${tokens.first()}")
    synchronized(lock) {
        val buffer = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        try {
            var attempt = 0
            while (true) {
                attempt++
                buffer.reset()
                try {
                    val (h, first) = obtainSession(host, bridgePort)
                    val session = h.session
                    val rest = ArrayDeque(tokens)
                    var readSettings = false
                    var wantSettings = false
                    var wantEq = false
                    var wantDevices = false
                    while (rest.isNotEmpty()) {
                        val command = rest.first()
                        if (command in SETTING_COMMANDS) wantSettings = true
                        if (command.startsWith("eq")) wantEq = true
                        if (command in DEVICE_COMMANDS) wantDevices = true
                        when (command) {
                            // Lectures : relues une fois à la fin, structurées, pour la réponse JSON.
                            "settings" -> { rest.removeFirst(); readSettings = true }
                            "eq", "devices" -> rest.removeFirst()
                            // Session gardée : les 3 échanges du rafraîchissement remplacent ceux du handshake.
                            "battery" -> { rest.removeFirst(); if (first == null) session.refreshStatus() }
                            else -> run(session, rest)
                        }
                    }
                    // L'extinction coupe la liaison : rien à relire, la session est rendue.
                    val gone = "power-off" in tokens
                    // Après une écriture, le cache de la session est déjà confirmé par le casque : pas de relecture.
                    val settings = when {
                        gone || !wantSettings -> null
                        readSettings -> session.readSettings()
                        else -> session.settings
                    }
                    val eq = if (wantEq && !gone) session.readEq() else null
                    val devices = if (wantDevices && !gone) session.readPairedDevices() else null
                    val outcome = Outcome(true, null, buffer.toString("UTF-8").trim(), session.report ?: first, settings, eq, devices)
                    if (gone) dropHeld() else h.lastUse = System.currentTimeMillis()
                    return outcome
                } catch (e: ConnectException) {
                    dropHeld()
                    return fail("Pont injoignable sur $host:$bridgePort")
                } catch (e: Exception) {
                    // Session périmée (le casque a coupé) ou échec : on repart d'une connexion neuve une fois.
                    dropHeld()
                    if (attempt >= 2) return fail("${e::class.simpleName}: ${e.message}")
                    Thread.sleep(500)
                }
            }
        } finally {
            System.setOut(realOut)
        }
    }
}

private fun jsonString(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder("\"")
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}

private fun jb(v: Boolean?) = v?.toString() ?: "null"
private fun ji(v: Int?) = v?.toString() ?: "null"

private fun settingsJson(s: SettingsState?): String {
    if (s == null) return "null"
    return "{\"pauseWhenRemoved\":${jb(s.pauseWhenRemoved)},\"speakToChat\":${jb(s.speakToChat)},\"dsee\":${jb(s.dsee)}," +
        "\"bgm\":${jb(s.bgm)},\"bgmRoom\":${ji(s.bgmRoom)},\"cinema\":${jb(s.cinema)},\"autoPowerOff\":${ji(s.autoPowerOff)}," +
        "\"ncButton\":${ji(s.ncButton)},\"chatSensitivity\":${ji(s.chatSensitivity)},\"chatTimeout\":${ji(s.chatTimeout)}," +
        "\"connectionPriority\":${ji(s.connectionPriority)},\"voiceGuidance\":${jb(s.voiceGuidance)}," +
        "\"voiceGuidanceVolume\":${ji(s.voiceGuidanceVolume)}}"
}

private fun eqJson(e: EqState?): String =
    if (e == null) "null" else "{\"label\":${jsonString(e.label)},\"preset\":${e.preset},\"bands\":[${e.bands.joinToString(",")}]}"

private fun devicesJson(d: PairedDevicesInfo?): String {
    if (d == null) return "null"
    val list = d.devices.joinToString(",") {
        "{\"address\":${jsonString(it.address)},\"name\":${jsonString(it.name)},\"connected\":${it.connected}," +
            "\"linkStatus\":${it.linkStatus},\"playback\":${it.isPlaybackDevice}}"
    }
    return "{\"pairingMode\":${jb(d.pairingMode)},\"list\":[$list]}"
}

private fun stateJson(r: BatteryReport?): String {
    if (r == null) return "null"
    val nc = r.ncAsm
    val kind = when {
        nc == null -> null
        !nc.enabled -> "off"
        nc.mode == MdrConstants.NCASM_MODE_NC -> "nc"
        else -> "ambient"
    }
    return "{\"model\":${jsonString(r.modelName)},\"firmware\":${jsonString(r.firmwareVersion)}," +
        "\"battery\":${r.levelPercent},\"charging\":${jsonString(r.chargingLabel)}," +
        "\"ncKind\":${jsonString(kind)},\"ncLabel\":${jsonString(nc?.label)},\"ambient\":${nc?.ambientLevel ?: "null"}," +
        "\"autoAmbient\":${jb(nc?.autoEnabled)}," +
        "\"volume\":${r.musicVolume ?: "null"}," +
        // Photo à afficher (même table que l'app Android) ; null tant que le casque n'a pas annoncé sa couleur.
        "\"color\":${ji(r.modelColor)},\"look\":${jsonString(r.modelColor?.let { HeadsetLook.of(it).name.lowercase() })}}"
}

private fun toJson(o: Outcome) =
    "{\"ok\":${o.ok},\"error\":${jsonString(o.error)},\"output\":${jsonString(o.output)},\"state\":${stateJson(o.state)}," +
        "\"settings\":${settingsJson(o.settings)},\"eq\":${eqJson(o.eq)},\"devices\":${devicesJson(o.devices)}}"

private fun reply(ex: HttpExchange, status: Int, type: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
    ex.sendResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

fun main(args: Array<String>) {
    var port = 8765
    var bridgeHost = "127.0.0.1"
    var bridgePort = 9876
    val it = args.iterator()
    while (it.hasNext()) {
        when (it.next()) {
            "--port" -> port = it.next().toInt()
            "--bridge-host" -> bridgeHost = it.next()
            "--bridge-port" -> bridgePort = it.next().toInt()
        }
    }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext("/ping") { ex -> reply(ex, 200, "text/plain", "ok") }
    server.createContext("/api") { ex ->
        if (ex.requestMethod != "POST") return@createContext reply(ex, 405, "text/plain", "POST seulement")
        val line = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        reply(ex, 200, "application/json", toJson(execute(bridgeHost, bridgePort, line)))
    }
    server.createContext("/release") { ex ->
        synchronized(lock) { dropHeld() }
        reply(ex, 200, "text/plain", "released")
    }
    server.createContext("/quit") { ex ->
        reply(ex, 200, "text/plain", "bye")
        exitProcess(0)
    }
    startKeeper()
    server.start()
    println("Serveur du banc sur http://127.0.0.1:$port")
}
