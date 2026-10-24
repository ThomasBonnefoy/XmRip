package com.xmrip.desktop

import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

private const val USAGE = """Banc de test PC XmRip : MdrSession (code de l'app) via le pont RFCOMM.

Lancer d'abord le pont :  tools\pc-bridge\run.ps1
Puis :  gradlew :desktop:run --args="[options] commande [commande ...]"

Options : --host H (127.0.0.1)   --port P (9876)   -v (affiche les trames TX/RX)

Commandes (enchaînées dans une seule session) :
  battery            batterie, modèle, firmware, NC/ambiant, volume (fait au handshake)
  nc | off           réduction de bruit / désactivé
  ambient [1..20]    son ambiant (niveau, 20 par défaut)
  auto on|off        son ambiant automatique
  volume [0..30]     lit ou règle le volume
  settings           lit les réglages
  voice on|off       guidage vocal du casque
  voice-volume N     volume du guidage vocal, -2 à 2
  priority quality|connection   priorité qualité du son / stabilité de la connexion
  chat-delay N       délai de reprise de Speak-to-Chat (0 court, 1 standard, 2 long, 3 jamais)
  eq                 lit l'égaliseur
  eq-preset NOM      heavy | clear | hard | soft | off
  eq-bands G1..G10   courbe personnalisée, 10 gains de -6 à 6 dB (graves en premier)
  devices            appareils appairés (multipoint)
  guide [n ...]      test guidé des gestes (zone tactile, bouton de bruit) avec compte à rebours ; résultat dans guide.txt
  watch [secondes]   écoute passive : affiche tout ce que le casque envoie (20 s par défaut)
  raw HEX [t2]       commande brute en hexadécimal sans espaces (ex. raw F603), attend 2 s, affiche les réponses ;
                     t2 = trame de la table 2. Peut modifier le casque si la commande est une écriture.
"""

fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))
    var host = "127.0.0.1"
    var port = 9876
    val rest = ArrayDeque<String>()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--host" -> host = it.next()
            "--port" -> port = it.next().toInt()
            "-v" -> System.setProperty("xmrip.verbose", "true")
            "-h", "--help" -> { print(USAGE); return }
            else -> rest.addLast(a)
        }
    }
    if (rest.isEmpty()) rest.addLast("battery")
    val quick = rest.first() == "quick"
    if (quick) rest.removeFirst()

    // Comme l'app (HeadphoneClient) : 2 tentatives de connexion + handshake, 1 s d'écart.
    var attempt = 0
    while (true) {
        attempt++
        val socket = try {
            Socket().apply { connect(InetSocketAddress(host, port), 3_000) }
        } catch (e: Exception) {
            System.err.println("Pont injoignable sur $host:$port (${e.message}). Lance tools\\pc-bridge\\run.ps1")
            exitProcess(2)
        }
        try {
            socket.use {
                val session = MdrSession(it.getInputStream(), it.getOutputStream())
                val t0 = System.nanoTime()
                fun ms() = (System.nanoTime() - t0) / 1_000_000
                if (quick) {
                    // Comme les raccourcis de l'app : aucun handshake, la commande part directement.
                    session.quickHandshake(0)
                    println("[sans handshake]")
                } else {
                    println(session.handshakeAndBattery().let { r ->
                        "${r.modelName} fw ${r.firmwareVersion} | batterie ${r.levelPercent}% (${r.chargingLabel}) | " +
                            "NC/ASM: ${r.ncAsm?.label} | volume ${r.musicVolume}"
                    })
                    println("[handshake complet : ${ms()} ms]")
                }
                while (rest.isNotEmpty()) {
                    val t1 = System.nanoTime()
                    run(session, rest)
                    println("[commande : ${(System.nanoTime() - t1) / 1_000_000} ms]")
                }
            }
            return
        } catch (e: Exception) {
            System.err.println("Erreur (tentative $attempt) : ${e::class.simpleName}: ${e.message}")
            if (attempt >= 2) exitProcess(1)
            Thread.sleep(1_000)
        }
    }
}

private data class GuideStep(val name: String, val instruction: String, val seconds: Int)

private val GUIDE_STEPS = listOf(
    GuideStep("Témoin", "NE TOUCHE À RIEN", 4),
    GuideStep("Zone tactile : glisser vers la droite", "GLISSE vers la DROITE sur la zone tactile (vers l'avant)", 5),
    GuideStep("Zone tactile : glisser vers la gauche", "GLISSE vers la GAUCHE sur la zone tactile (vers l'arrière)", 5),
    GuideStep("Zone tactile : glisser vers le haut", "GLISSE vers le HAUT sur la zone tactile", 5),
    GuideStep("Zone tactile : glisser vers le bas", "GLISSE vers le BAS sur la zone tactile", 5),
    GuideStep("Zone tactile : un appui", "APPUIE UNE FOIS sur la zone tactile", 6),
    GuideStep("Zone tactile : double appui", "APPUIE DEUX FOIS de suite sur la zone tactile", 5),
    GuideStep("Zone tactile : main posée", "POSE la main à plat sur toute la zone et GARDE-la posée", 4),
    GuideStep("Zone tactile : main relâchée", "RELÂCHE la main", 4),
    GuideStep("Bouton de bruit : double appui", "APPUIE DEUX FOIS de suite sur le bouton de bruit", 5),
    GuideStep("Bouton de bruit : triple appui", "APPUIE TROIS FOIS de suite sur le bouton de bruit", 5),
    GuideStep("Fin", "NE TOUCHE À RIEN", 3),
)

/** Test guidé des gestes : annonce chaque geste avec un compte à rebours, note ce que le casque envoie, résume à la fin. */
private fun runGuide(session: MdrSession, steps: List<GuideStep>) {
    val report = StringBuilder()
    fun say(text: String = "") {
        println(text)
        report.appendLine(text)
    }
    fun beep() {
        print("\u0007")
        System.out.flush()
    }
    // Active le journal d'événements du casque (LOG_SET_STATUS), comme le fait libmdr à l'initialisation.
    session.rawExchange(byteArrayOf(0xC4.toByte(), 0x01, 0x00), 500)

    say("=== TEST GUIDÉ DES GESTES DU CASQUE ===")
    say("Mets le casque sur la tête. Lance une musique si tu veux voir le volume et les pistes changer.")
    say("Chaque geste est annoncé 3 secondes à l'avance : attends « MAINTENANT » pour le faire.")
    say()
    for (s in 10 downTo 1) {
        println("Début dans $s s…")
        session.rawExchange(ByteArray(0), 1000)
    }
    val summary = mutableListOf<Pair<String, List<String>>>()
    steps.forEachIndexed { i, step ->
        say()
        say("──── Étape ${i + 1}/${steps.size} : ${step.name} ────")
        say("Prépare-toi : ${step.instruction}")
        for (s in 3 downTo 1) {
            println("   $s…")
            session.rawExchange(ByteArray(0), 1000)
        }
        beep()
        say(">>> MAINTENANT : ${step.instruction}   (${step.seconds} s)")
        val events = mutableListOf<String>()
        val start = System.currentTimeMillis()
        val end = start + step.seconds * 1000L
        while (System.currentTimeMillis() < end) {
            session.rawExchange(ByteArray(0), 400).forEach { (t, p) ->
                if (t == 0x01) return@forEach
                val ascii = if (p.size > 3 && (p[0].toInt() and 0xFF) == 0xC9) " « " + String(p, 3, p.size - 3).filter { it.code in 32..126 } + " »" else ""
                val line = "+%.1f s  %s%s".format((System.currentTimeMillis() - start) / 1000.0, p.joinToString(" ") { "%02X".format(it) }, ascii)
                events += line
                say("   reçu : $line")
            }
        }
        if (events.isEmpty()) say("   (rien reçu du casque)")
        summary += step.name to events
    }
    say()
    say("=== RÉSUMÉ ===")
    summary.forEach { (name, events) ->
        say("$name : ${if (events.isEmpty()) "rien reçu" else "${events.size} message(s)"}")
        events.forEach { say("     $it") }
    }
    beep()
    say()
    say("Terminé. Tu peux enlever le casque. Résultat enregistré dans %LOCALAPPDATA%\\xmrip-bridge\\guide.txt")
    runCatching {
        val dir = java.io.File(System.getenv("LOCALAPPDATA") ?: ".", "xmrip-bridge")
        dir.mkdirs()
        java.io.File(dir, "guide.txt").writeText(report.toString(), Charsets.UTF_8)
    }
}

private fun run(session: MdrSession, rest: ArrayDeque<String>) {
    fun optInt(): Int? = rest.firstOrNull()?.toIntOrNull()?.also { rest.removeFirst() }
    when (val cmd = rest.removeFirst()) {
        "battery" -> Unit
        "wait" -> {
            // Entretient la liaison comme le fait HeadphoneClient entre deux actions (acquitte les trames du casque).
            val end = System.currentTimeMillis() + (optInt() ?: 10) * 1000L
            while (System.currentTimeMillis() < end) {
                session.drain()
                Thread.sleep(300)
            }
            println("liaison entretenue ; état connu : ${session.report}")
        }
        "refresh" -> println(session.refreshStatus())
        "listen" -> {
            val mode = when (val name = rest.removeFirst().lowercase()) {
                "standard" -> com.xmrip.app.protocol.ListeningMode.STANDARD
                "bgm" -> com.xmrip.app.protocol.ListeningMode.BGM
                "cinema" -> com.xmrip.app.protocol.ListeningMode.CINEMA
                else -> error("Mode inconnu : $name")
            }
            println(session.setListeningMode(mode, optInt() ?: MdrConstants.ROOM_LARGE))
        }
        "nc" -> println(session.setNcAsm(MdrSession.MODE_NC).label)
        "off" -> println(session.setNcAsm(MdrSession.MODE_OFF).label)
        "ambient" -> println(session.setNcAsm(MdrSession.MODE_AMBIENT, optInt() ?: MdrConstants.AMBIENT_LEVEL_DEFAULT).label)
        "auto" -> println(session.setAutoAmbient(rest.removeFirst() == "on").label)
        "volume" -> println(optInt()?.let { session.setMusicVolume(it) } ?: session.readMusicVolume())
        "settings" -> println(session.readSettings())
        "voice" -> println(session.setVoiceGuidance(rest.removeFirst() == "on").let { "guidage ${it.voiceGuidance} volume ${it.voiceGuidanceVolume}" })
        "voice-volume" -> println(session.setVoiceGuidanceVolume(rest.removeFirst().toInt()).let { "guidage ${it.voiceGuidance} volume ${it.voiceGuidanceVolume}" })
        "priority" -> {
            val value = if (rest.removeFirst() == "connection") MdrConstants.PRIORITY_CONNECTION else MdrConstants.PRIORITY_SOUND_QUALITY
            println("priorité ${session.setConnectionPriority(value).connectionPriority}")
        }
        "chat-delay" -> println(session.setChatTimeout(rest.removeFirst().toInt()).let { "Speak-to-Chat sensibilité ${it.chatSensitivity} délai ${it.chatTimeout}" })
        "eq" -> println(session.readEq()?.let { "${it.label} ${it.bands}" })
        "eq-preset" -> {
            val id = when (val name = rest.removeFirst().lowercase()) {
                "heavy" -> MdrConstants.EQ_XM6_HEAVY
                "clear" -> MdrConstants.EQ_XM6_CLEAR
                "hard" -> MdrConstants.EQ_XM6_HARD
                "soft" -> MdrConstants.EQ_XM6_SOFT
                "off" -> MdrConstants.EQ_OFF
                else -> error("Préréglage inconnu : $name")
            }
            println(session.setEqPreset(id)?.let { "${it.label} ${it.bands}" })
        }
        "eq-bands" -> {
            val gains = List(MdrConstants.EQ_XM6_BAND_COUNT) { rest.removeFirst().toInt() }
            println(session.setEqBands(gains)?.let { "${it.label} ${it.bands}" })
        }
        "raw" -> {
            val hex = rest.removeFirst()
            val t2 = rest.firstOrNull() == "t2"
            if (t2) rest.removeFirst()
            val payload = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val type = if (t2) MdrConstants.TYPE_DATA_MDR_NO2 else MdrConstants.TYPE_DATA_MDR
            val replies = session.rawExchange(payload, 2_000, type)
            if (replies.isEmpty()) println("pas de réponse")
            replies.forEach { (t, p) -> println("  type 0x%02X : %s".format(t, p.joinToString(" ") { "%02X".format(it) })) }
        }
        "guide" -> {
            // Sans numéro : toutes les étapes ; sinon seulement celles indiquées, dans l'ordre (ex. guide 6 6 6).
            val picked = generateSequence { optInt() }.toList()
            runGuide(session, if (picked.isEmpty()) GUIDE_STEPS else picked.map { GUIDE_STEPS[it - 1] })
        }
        "watch" -> {
            // Écoute passive : affiche tout ce que le casque envoie de lui-même pendant N secondes (texte ASCII à droite).
            val end = System.currentTimeMillis() + (optInt() ?: 20) * 1000L
            val t0 = System.currentTimeMillis()
            println("écoute…")
            while (System.currentTimeMillis() < end) {
                session.rawExchange(ByteArray(0), 500).forEach { (t, p) ->
                    if (t == 0x01) return@forEach
                    val ascii = String(p.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
                    println("  +%.1f s  type 0x%02X : %s   %s".format((System.currentTimeMillis() - t0) / 1000.0, t, p.joinToString(" ") { "%02X".format(it) }, ascii))
                }
            }
        }
        "devices" -> {
            val info = session.readPairedDevices()
            println("Mode d'appairage : ${info.pairingMode}")
            info.devices.forEach { d ->
                println("  ${d.address}  ${d.name}  ${if (d.connected) "connecté" else "-"}${if (d.isPlaybackDevice) "  (lecture)" else ""}")
            }
        }
        else -> { System.err.println("Commande inconnue : $cmd\n"); print(USAGE); exitProcess(64) }
    }
}
