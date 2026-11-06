package com.xmrip.desktop

import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrFrameReader
import com.xmrip.app.protocol.MdrFramer
import java.io.InputStream
import java.io.OutputStream

/**
 * Faux casque pour tester [com.xmrip.app.protocol.MdrSession] sans Bluetooth : on branche [input] et [output] sur la
 * session, et chaque commande reçue est acquittée puis reçoit la réponse de [replies] (comme les trames relevées sur un
 * WH-1000XM6 : l'acquittement porte la séquence inverse, la réponse garde celle de la commande). Une commande sans
 * réponse prévue reste muette, ce qui simule un casque qui ne la connaît pas.
 */
class FakeHeadset(private val replies: Map<List<Int>, ByteArray>) {
    /** Charges utiles reçues, dans l'ordre. */
    val requests = ArrayList<List<Int>>()

    private val reader = MdrFrameReader()
    private val toSession = java.util.ArrayDeque<Byte>()

    val input = object : InputStream() {
        override fun available(): Int = synchronized(toSession) { toSession.size }
        override fun read(): Int = synchronized(toSession) { if (toSession.isEmpty()) -1 else toSession.removeFirst().toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(toSession) {
            val n = minOf(len, toSession.size)
            for (i in 0 until n) b[off + i] = toSession.removeFirst()
            n
        }
    }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            reader.feed(b.copyOfRange(off, off + len))
            for (frame in reader.readAvailable()) {
                if (frame.type != MdrConstants.TYPE_DATA_MDR) continue
                val request = frame.payload.map { it.toInt() and 0xFF }
                requests.add(request)
                send(MdrFramer.pack(MdrConstants.TYPE_ACK, (1 - frame.seq) and 0xFF, ByteArray(0)))
                val answer = replies.entries.firstOrNull { request.take(it.key.size) == it.key }?.value ?: continue
                send(MdrFramer.pack(MdrConstants.TYPE_DATA_MDR, frame.seq, answer))
            }
        }
    }

    private fun send(bytes: ByteArray) = synchronized(toSession) { bytes.forEach { toSession.addLast(it) } }

    companion object {
        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** Réponses relevées sur un WH-1000XM6 (batterie 64 %, bruit désactivé, volume 4), avec la couleur [color] ou sans réponse si null. */
        fun xm6(color: Int?, series: Int = 0x30): FakeHeadset {
            val replies = mutableMapOf(
                listOf(0x04, 0x02) to bytes(0x05, 0x02, 0x05, '3'.code, '.'.code, '1'.code, '.'.code, '5'.code),
                listOf(0x04, 0x01) to bytes(0x05, 0x01, 0x0A) + "WH-1000XM6".toByteArray(),
                listOf(0x22, 0x00) to bytes(0x23, 0x00, 0x40, 0x00),
                listOf(0x66, 0x19) to bytes(0x67, 0x19, 0x01, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00),
                listOf(0xA6, 0x20) to bytes(0xA7, 0x20, 0x04),
            )
            if (color != null) replies[listOf(0x04, 0x03)] = bytes(0x05, 0x03, series, color)
            return FakeHeadset(replies)
        }
    }
}
