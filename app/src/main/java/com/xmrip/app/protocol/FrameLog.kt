package com.xmrip.app.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des derniers échanges avec le casque (trames envoyées et reçues), gardé en mémoire pour l'écran
 * « Journal des échanges » : c'est ce qui permet de comprendre un réglage qui ne répond pas sans passer par logcat.
 * Sans dépendance Android : le banc PC (`desktop/`) le compile aussi.
 */
object FrameLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun add(direction: String, type: Int, payload: ByteArray) {
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        val line = "${clock.format(Date())}  $direction %02X  %s".format(type, hex)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun note(text: String) {
        synchronized(lines) {
            lines.addLast("${clock.format(Date())}  · $text")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
