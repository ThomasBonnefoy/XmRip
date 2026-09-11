package com.xmrip.app.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

data class BatteryReport(
    val modelName: String?,
    val firmwareVersion: String?,
    val levelPercent: Int,
    val chargingStatus: Int,
) {
    val chargingLabel: String
        get() = when (chargingStatus) {
            MdrConstants.CHARGING_NOT_CHARGING -> "pas en charge"
            MdrConstants.CHARGING_CHARGING -> "en charge"
            MdrConstants.CHARGING_CHARGED -> "chargée"
            else -> "inconnu"
        }
}

class MdrProtocolException(message: String) : IOException(message)

/**
 * Session MDR v2 : handshake puis échange de commandes.
 *
 * Réplique la logique de libmdr (Headphones.cpp / HeadphonesV2.cpp) :
 *  - chaque trame AREçuE fixe le numéro de séquence partagé ;
 *  - chaque trame DATA doit être acquittée avec la séquence inverse ;
 *  - les commandes sont envoyées avec la séquence courante.
 */
class MdrSession(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val reader = MdrFrameReader()
    private var seqByte: Int = 0
    private val readBuffer = ByteArray(1024)

    /**
     * Exécute le handshake complet puis récupère le niveau de batterie.
     * Bloquant — à appeler hors du thread UI.
     */
    fun handshakeAndBattery(): BatteryReport {
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS

        var modelName: String? = null
        var firmwareVersion: String? = null
        var battery: BatteryReport? = null

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_PROTOCOL_INFO, deadline, "handshake")

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_CAPABILITY_INFO.toByte(), 0x00))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_CAPABILITY_INFO, deadline, "handshake")

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_DEVICE_INFO.toByte(), MdrConstants.DEVICE_INFO_FW_VERSION.toByte()))
        firmwareVersion = readDeviceInfoString(awaitCommand(MdrConstants.CMD_CONNECT_RET_DEVICE_INFO, deadline, "fw version"))

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_DEVICE_INFO.toByte(), MdrConstants.DEVICE_INFO_MODEL_NAME.toByte()))
        modelName = readDeviceInfoString(awaitCommand(MdrConstants.CMD_CONNECT_RET_DEVICE_INFO, deadline, "model name"))

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_DEVICE_INFO.toByte(), MdrConstants.DEVICE_INFO_SERIES_AND_COLOR.toByte()))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_DEVICE_INFO, deadline, "series & color")

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_SUPPORT_FUNCTION.toByte(), 0x00))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_SUPPORT_FUNCTION, deadline, "support function")

        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_POWER_GET_STATUS.toByte(),
                MdrConstants.POWER_BATTERY.toByte(),
            ),
        )
        val ret = awaitCommand(MdrConstants.CMD_POWER_RET_STATUS, deadline, "battery")
        if (ret.size < 4 || (ret[1].toInt() and 0xFF) != MdrConstants.POWER_BATTERY) {
            throw MdrProtocolException("Réponse batterie inattendue")
        }
        val level = ret[2].toInt() and 0xFF
        val charging = ret[3].toInt() and 0xFF
        battery = BatteryReport(modelName, firmwareVersion, level, charging)
        return battery
    }

    private fun readDeviceInfoString(ret: ByteArray): String? {
        if (ret.size < 3) return null
        val len = ret[2].toInt() and 0xFF
        if (ret.size < 3 + len) return null
        return String(ret, 3, len, Charsets.UTF_8)
    }

    private fun sendCommand(payload: ByteArray) {
        val frame = MdrFramer.pack(MdrConstants.TYPE_DATA_MDR, seqByte and 0xFF, payload)
        output.write(frame)
        output.flush()
    }

    private fun sendAck(seq: Int) {
        val frame = MdrFramer.pack(MdrConstants.TYPE_ACK, (1 - seq) and 0xFF, ByteArray(0))
        output.write(frame)
        output.flush()
    }

    private fun awaitCommand(command: Int, deadline: Long, what: String): ByteArray {
        while (System.currentTimeMillis() < deadline) {
            val frames = readFrames(deadline, what)
            for (frame in frames) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.isNotEmpty()) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    if (cmd == command) return frame.payload
                }
            }
        }
        throw MdrProtocolException("Timeout en attendant: $what")
    }

    /**
     * Lit les octets entrants jusqu'à produire au moins une trame complète.
     * Vérifie la deadline globale à chaque itération (les lectures sont bornées par le timeout socket).
     */
    private fun readFrames(deadline: Long, what: String): List<MdrFrame> {
        while (true) {
            val available = reader.readAvailable()
            if (available.isNotEmpty()) return available
            if (System.currentTimeMillis() >= deadline) {
                throw MdrProtocolException("Timeout en attendant: $what")
            }
            val n = readFromSocket()
            if (n < 0) throw EOFException("Flux fermé par le casque")
            if (n > 0) reader.feed(readBuffer.copyOf(n))
        }
    }

    /** Lit un bloc d'octets si disponible ; renvoie 0 si rien (borné par le deadline via readFrames). */
    private fun readFromSocket(): Int {
        return try {
            val avail = input.available()
            if (avail <= 0) {
                Thread.sleep(50)
                0
            } else {
                input.read(readBuffer, 0, avail.coerceAtMost(readBuffer.size))
            }
        } catch (e: SocketTimeoutException) {
            0
        }
    }
}