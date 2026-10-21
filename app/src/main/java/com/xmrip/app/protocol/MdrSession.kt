package com.xmrip.app.protocol

import android.util.Log
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
    val ncAsm: NcAsmState? = null,
    val musicVolume: Int? = null,
) {
    val chargingLabel: String
        get() = when (chargingStatus) {
            MdrConstants.CHARGING_NOT_CHARGING -> "pas en charge"
            MdrConstants.CHARGING_CHARGING -> "en charge"
            MdrConstants.CHARGING_CHARGED -> "chargée"
            else -> "inconnu"
        }
}

data class NcAsmState(
    val enabled: Boolean,
    val mode: Int,
    val ambientLevel: Int,
    val autoEnabled: Boolean = false,
    val noiseAdaptiveSensitivity: Int = MdrConstants.NCASM_NOISE_ADAPTIVE_STANDARD,
) {
    val label: String
        get() = when {
            !enabled -> "Désactivé"
            mode == MdrConstants.NCASM_MODE_NC -> "Réduction de bruit"
            else -> if (autoEnabled) "Son ambiant automatique" else "Son ambiant (niveau $ambientLevel)"
        }
}

/** État de l'égaliseur : code de préréglage brut et gains par bande en dB (vide si non rapportés). */
data class EqState(
    val preset: Int,
    val bands: List<Int>,
) {
    val label: String
        get() = when (preset) {
            MdrConstants.EQ_OFF -> "Désactivé"
            MdrConstants.EQ_XM6_HEAVY -> "Heavy"
            MdrConstants.EQ_XM6_CLEAR -> "Clear"
            MdrConstants.EQ_XM6_HARD -> "Hard"
            MdrConstants.EQ_XM6_SOFT -> "Soft"
            MdrConstants.EQ_XM6_CUSTOM -> "Personnalisé"
            else -> "Préréglage 0x%02X".format(preset)
        }
}

/** Appareil appairé au casque (multipoint). [linkStatus] vaut 0 si l'appareil n'est pas connecté. */
data class PairedDevice(
    val address: String,
    val name: String,
    val linkStatus: Int,
    val isPlaybackDevice: Boolean,
) {
    val connected: Boolean get() = linkStatus != 0
}

data class PairedDevicesInfo(
    val devices: List<PairedDevice>,
    /** null si le casque n'a pas répondu à la lecture du mode d'appairage. */
    val pairingMode: Boolean?,
    /** Réponse brute du casque (hex), pour le diagnostic dans logcat. */
    val rawHex: String = "",
)

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

    /** Le casque annonce la Table 2 (gestion multipoint) dans la réponse protocole. */
    private var hasTable2 = false

    /** Type PeripheralInquiredType à utiliser ; null = pas encore déterminé, -1 = non pris en charge. */
    private var pairingType: Int? = null

    companion object {
        const val MODE_NC = 0
        const val MODE_AMBIENT = 1
        const val MODE_OFF = 2
        private const val LOG_TAG = "XmRip-MDR"
    }

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
        noteProtocolInfo(awaitCommand(MdrConstants.CMD_CONNECT_RET_PROTOCOL_INFO, deadline, "handshake"))

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

        var ncAsm: NcAsmState? = null
        try {
            ncAsm = readNcAsm(deadline)
        } catch (_: Exception) {
            // Le casque peut ne pas répondre sur ce type NCASM ; on garde la batterie.
        }

        var musicVolume: Int? = null
        try {
            musicVolume = readMusicVolume()
        } catch (_: Exception) {
            // Lecture du volume facultative.
        }

        battery = BatteryReport(modelName, firmwareVersion, level, charging, ncAsm, musicVolume)
        return battery
    }

    /**
     * Change le mode NC/Ambiant/Désactivé puis relit l'état confirmé par le casque.
     * @param mode MODE_NC, MODE_AMBIENT ou MODE_OFF.
     * @param ambientLevel Niveau de son ambiant (1..20) utilisé si le mode Ambiant est sélectionné.
     * @param autoEnabled Son ambiant automatique (adaptatif) dans le mode Ambiant.
     */
    fun setNcAsm(
        mode: Int,
        ambientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT,
        autoEnabled: Boolean = false,
        sensitivity: Int = MdrConstants.NCASM_NOISE_ADAPTIVE_STANDARD,
    ): NcAsmState {
        val enabled = mode != MODE_OFF
        val asmMode = if (mode == MODE_AMBIENT) MdrConstants.NCASM_MODE_ASM else MdrConstants.NCASM_MODE_NC
        val level = if (mode == MODE_AMBIENT) ambientLevel else 0x00
        return sendNcAsmSet(enabled, asmMode, level, autoEnabled, sensitivity)
    }

    /** Règle le niveau de son ambiant (1..20), mode Ambiant actif. */
    fun setAmbientLevel(
        level: Int,
        autoEnabled: Boolean = false,
        sensitivity: Int = MdrConstants.NCASM_NOISE_ADAPTIVE_STANDARD,
    ): NcAsmState {
        return sendNcAsmSet(true, MdrConstants.NCASM_MODE_ASM, level, autoEnabled, sensitivity)
    }

    /** Active/désactive le son ambiant automatique (mode Ambiant, niveau manuel conservé). */
    fun setAutoAmbient(
        enabled: Boolean,
        level: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT,
        sensitivity: Int = MdrConstants.NCASM_NOISE_ADAPTIVE_STANDARD,
    ): NcAsmState {
        return sendNcAsmSet(true, MdrConstants.NCASM_MODE_ASM, level, enabled, sensitivity)
    }

    /** Envoie un SET NC/ASM complet (type 0x19 XM6) et attend la confirmation. */
    private fun sendNcAsmSet(
        enabled: Boolean,
        mode: Int,
        level: Int,
        autoEnabled: Boolean,
        sensitivity: Int,
    ): NcAsmState {
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_NCASM_SET_PARAM.toByte(),
                MdrConstants.NCASM_TYPE_DUAL_NC_ASM_NA.toByte(),
                MdrConstants.NCASM_STATUS_CHANGED.toByte(),
                (if (enabled) MdrConstants.NCASM_VALUE_ON else MdrConstants.NCASM_VALUE_OFF).toByte(),
                mode.toByte(),
                MdrConstants.NCASM_AMBIENT_NORMAL.toByte(),
                level.toByte(),
                (if (autoEnabled) MdrConstants.NCASM_VALUE_ON else MdrConstants.NCASM_VALUE_OFF).toByte(),
                sensitivity.toByte(),
            ),
        )
        val reply = awaitNcAsmReply(deadline, "ncasm set")
        return parseNcAsm(reply)
    }

    /**
     * Handshake minimal pour une action rapide (pas de metadata device/batterie) :
     * protocol, capability puis support function.
     */
    fun quickHandshake(deadline: Long) {
        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00))
        noteProtocolInfo(awaitCommand(MdrConstants.CMD_CONNECT_RET_PROTOCOL_INFO, deadline, "handshake"))

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_CAPABILITY_INFO.toByte(), 0x00))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_CAPABILITY_INFO, deadline, "handshake")

        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_SUPPORT_FUNCTION.toByte(), 0x00))
        awaitCommand(MdrConstants.CMD_CONNECT_RET_SUPPORT_FUNCTION, deadline, "handshake")
    }

    /** Handshake minimal puis application d'un mode NC/Ambiant/Désactivé. */
    fun handshakeAndSetNcAsm(mode: Int): NcAsmState {
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        quickHandshake(deadline)
        return setNcAsm(mode)
    }

    /** Règle le volume de lecture (0..30). Renvoie la valeur effectivement appliquée. */
    fun setMusicVolume(volume: Int): Int {
        val v = volume.coerceIn(0, MdrConstants.PLAY_VOLUME_MAX)
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_PLAY_SET_PARAM.toByte(),
                MdrConstants.PLAY_TYPE_MUSIC_VOLUME.toByte(),
                v.toByte(),
            ),
            intArrayOf(MdrConstants.CMD_PLAY_RET_PARAM, MdrConstants.CMD_PLAY_NTFY_PARAM),
            "réponse volume",
        )
        return v
    }

    /**
     * Coupe/rétablit le son.
     * Il n'existe pas de commande « mute » dédiée en v2 (MUSIC_VOLUME_WITH_MUTE
     * n'existe qu'en Ret/Ntfy) : le mute passe par un volume à 0.
     */
    fun setMusicMute(muted: Boolean, volume: Int): Int {
        return setMusicVolume(if (muted) 0 else volume)
    }

    /** Contrôle transport lecture/pause/piste (sans attendre de retour). */
    fun playControl(control: Int) {
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_PLAY_SET_STATUS.toByte(),
                MdrConstants.PLAY_TYPE_PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT.toByte(),
                MdrConstants.V2_ENABLE.toByte(),
                control.toByte(),
            ),
        )
    }

    /** Règle le délai d'extinction automatique. */
    fun setAutoPowerOff(element: Int) {
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_POWER_SET_PARAM.toByte(),
                MdrConstants.POWER_TYPE_AUTO_POWER_OFF.toByte(),
                element.toByte(),
                element.toByte(),
            ),
            intArrayOf(MdrConstants.CMD_POWER_RET_PARAM, MdrConstants.CMD_POWER_NTFY_PARAM),
            "réponse extinction auto",
        )
    }

    /** Active/désactive Speak-to-Chat (détection AUTO, réactivation rapide). */
    fun setSpeakToChat(enabled: Boolean) {
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_EXT_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2.toByte(),
                MdrConstants.STC_DETECT_AUTO.toByte(),
                MdrConstants.STC_OFF_TIME_FAST.toByte(),
            ),
            intArrayOf(MdrConstants.CMD_SYSTEM_RET_EXT_PARAM, MdrConstants.CMD_SYSTEM_NTFY_EXT_PARAM),
            "réponse speak-to-chat ext",
        )
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2.toByte(),
                (if (enabled) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
                MdrConstants.V2_VALUE_OFF.toByte(), // preview mode
            ),
            intArrayOf(MdrConstants.CMD_SYSTEM_RET_PARAM, MdrConstants.CMD_SYSTEM_NTFY_PARAM),
            "réponse speak-to-chat",
        )
    }

    /** Lit l'égaliseur du XM6 (préréglage et bandes), ou null si le casque ne répond pas. */
    fun readEq(): EqState? {
        val deadline = System.currentTimeMillis() + MdrConstants.SOCKET_TIMEOUT_MS
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_EQEBB_GET_PARAM.toByte(),
                MdrConstants.EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE.toByte(),
            ),
        )
        return try {
            parseEq(awaitEqReply(deadline, "lecture égaliseur"))
        } catch (e: MdrProtocolException) {
            null
        }
    }

    /**
     * Applique un préréglage XM6 (EQ_OFF, EQ_XM6_HEAVY..SOFT ou EQ_XM6_CUSTOM) et renvoie l'état
     * relu dans la notification du casque.
     */
    fun setEqPreset(presetId: Int): EqState? {
        return writeEq(
            byteArrayOf(
                MdrConstants.CMD_EQEBB_SET_PARAM.toByte(),
                MdrConstants.EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE.toByte(),
                presetId.toByte(),
                0x00, // MDRPodArray<UInt8> bandSteps : count = 0
            ),
        )
    }

    /**
     * Envoie une écriture EQEBB. Le XM6 acquitte toujours la trame mais ne renvoie la notification
     * d'état que pour un changement de préréglage : si elle n'arrive pas, on relit l'état.
     */
    private fun writeEq(payload: ByteArray): EqState? {
        sendCommand(payload)
        val echo = try {
            awaitEqReply(System.currentTimeMillis() + MdrConstants.EQ_ECHO_WAIT_MS, "réponse égaliseur")
        } catch (e: MdrProtocolException) {
            null
        }
        return if (echo != null) parseEq(echo) else readEq()
    }

    /**
     * Écrit une courbe personnalisée de 10 bandes (-6..+6 dB, graves en premier). Le casque
     * bascule seul sur le préréglage personnalisé et renvoie la courbe appliquée.
     */
    fun setEqBands(gains: List<Int>): EqState? {
        require(gains.size == MdrConstants.EQ_XM6_BAND_COUNT) { "L'égaliseur XM6 a 10 bandes" }
        val payload = ByteArray(4 + gains.size)
        payload[0] = MdrConstants.CMD_EQEBB_SET_PARAM.toByte()
        payload[1] = MdrConstants.EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE.toByte()
        payload[2] = MdrConstants.EQ_XM6_CUSTOM.toByte()
        payload[3] = gains.size.toByte()
        for ((i, g) in gains.withIndex()) {
            val clamped = g.coerceIn(MdrConstants.EQ_BAND_GAIN_MIN, MdrConstants.EQ_BAND_GAIN_MAX)
            payload[4 + i] = (clamped + MdrConstants.EQ_BAND_OFFSET).toByte()
        }
        return writeEq(payload)
    }

    /** Attend un RET/NTFY EQEBB du type XM6 (0x04) ; les autres trames sont acquittées et ignorées. */
    private fun awaitEqReply(deadline: Long, what: String): ByteArray {
        while (System.currentTimeMillis() < deadline) {
            var match: ByteArray? = null
            for (frame in readFrames(deadline, what)) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (match == null && frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.size >= 4) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    val type = frame.payload[1].toInt() and 0xFF
                    if ((cmd == MdrConstants.CMD_EQEBB_RET_PARAM || cmd == MdrConstants.CMD_EQEBB_NTFY_PARAM) &&
                        type == MdrConstants.EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE
                    ) {
                        match = frame.payload
                    }
                }
            }
            if (match != null) return match
        }
        throw MdrProtocolException("Timeout en attendant: $what")
    }

    private fun parseEq(payload: ByteArray): EqState {
        val preset = payload[2].toInt() and 0xFF
        val count = payload[3].toInt() and 0xFF
        val bands = if (count > 0 && payload.size >= 4 + count) {
            // Les réponses à 6 bandes (anciens modèles) utilisent un décalage de 10, celles à 10 bandes de 6.
            val offset = if (count == 6) 10 else MdrConstants.EQ_BAND_OFFSET
            List(count) { (payload[4 + it].toInt() and 0xFF) - offset }
        } else {
            emptyList()
        }
        return EqState(preset, bands)
    }

    /** Active/désactive le DSEE (upscaling automatique). */
    fun setDsee(enabled: Boolean) {
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_AUDIO_SET_PARAM.toByte(),
                MdrConstants.AUDIO_TYPE_UPSCALING.toByte(),
                (if (enabled) MdrConstants.UPSCALING_AUTO else MdrConstants.UPSCALING_OFF).toByte(),
            ),
            intArrayOf(MdrConstants.CMD_AUDIO_RET_PARAM, MdrConstants.CMD_AUDIO_NTFY_PARAM),
            "réponse DSEE",
        )
    }

    /** Affecte les fonctions des panneaux tactiles gauche/droit. */
    fun setAssignable(leftPreset: Int, rightPreset: Int) {
        sendAndAwait(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_ASSIGNABLE_SETTINGS.toByte(),
                0x02, // MDRPodArray<Preset> : count = 2
                leftPreset.toByte(),
                rightPreset.toByte(),
            ),
            intArrayOf(MdrConstants.CMD_SYSTEM_RET_PARAM, MdrConstants.CMD_SYSTEM_NTFY_PARAM),
            "réponse boutons",
        )
    }

    /**
     * Envoie une commande SET puis attend une réponse RET/NTFY de la même famille.
     * Les trames sans rapport sont acquittées et ignorées.
     */
    private fun sendAndAwait(payload: ByteArray, expected: IntArray, what: String) {
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        sendCommand(payload)
        while (System.currentTimeMillis() < deadline) {
            val frames = readFrames(deadline, what)
            for (frame in frames) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.isNotEmpty()) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    if (expected.contains(cmd)) return
                }
            }
        }
        throw MdrProtocolException("Timeout en attendant: $what")
    }

    /** Lit le volume de lecture actuel du casque (0..30), ou null si non renseigné. */
    fun readMusicVolume(): Int? {
        val deadline = System.currentTimeMillis() + MdrConstants.SOCKET_TIMEOUT_MS
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_PLAY_GET_PARAM.toByte(),
                MdrConstants.PLAY_TYPE_MUSIC_VOLUME.toByte(),
            ),
        )
        while (System.currentTimeMillis() < deadline) {
            val frames = readFrames(deadline, "lecture volume")
            for (frame in frames) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.size >= 3) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    val type = frame.payload[1].toInt() and 0xFF
                    if ((cmd == MdrConstants.CMD_PLAY_RET_PARAM || cmd == MdrConstants.CMD_PLAY_NTFY_PARAM) &&
                        type == MdrConstants.PLAY_TYPE_MUSIC_VOLUME
                    ) {
                        return frame.payload[2].toInt() and 0xFF
                    }
                }
            }
        }
        return null
    }

    private fun readNcAsm(deadline: Long): NcAsmState {
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_NCASM_GET_PARAM.toByte(),
                MdrConstants.NCASM_TYPE_DUAL_NC_ASM_NA.toByte(),
            ),
        )
        val reply = awaitNcAsmReply(deadline, "ncasm")
        return parseNcAsm(reply)
    }

    private fun parseNcAsm(payload: ByteArray): NcAsmState {
        if (payload.size < 7) throw MdrProtocolException("Réponse NC/ASM inattendue")
        val enabled = (payload[3].toInt() and 0xFF) == MdrConstants.NCASM_VALUE_ON
        val mode = payload[4].toInt() and 0xFF
        val level = payload[6].toInt() and 0xFF
        val auto = payload.size >= 9 && (payload[7].toInt() and 0xFF) == MdrConstants.NCASM_VALUE_ON
        val sensitivity = if (payload.size >= 9) payload[8].toInt() and 0xFF else MdrConstants.NCASM_NOISE_ADAPTIVE_STANDARD
        return NcAsmState(enabled, mode, level, auto, sensitivity)
    }

    private fun readDeviceInfoString(ret: ByteArray): String? {
        if (ret.size < 3) return null
        val len = ret[2].toInt() and 0xFF
        if (ret.size < 3 + len) return null
        return String(ret, 3, len, Charsets.UTF_8)
    }

    private fun sendCommand(payload: ByteArray, type: Int = MdrConstants.TYPE_DATA_MDR) {
        val frame = MdrFramer.pack(type, seqByte and 0xFF, payload)
        Log.d(LOG_TAG, "TX type=0x%02X seq=%d %s".format(type, seqByte and 0xFF, payload.toHex()))
        output.write(frame)
        output.flush()
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    // ---------------------------------------------------------------------------------------
    // Multipoint / appareils appairés (Table 2, trames TYPE_DATA_MDR_NO2)
    // ---------------------------------------------------------------------------------------

    private fun noteProtocolInfo(ret: ByteArray) {
        // [cmd, type, version(4), table1, table2] ; ENABLE = 0x00 en v2.
        hasTable2 = ret.size > 7 && (ret[7].toInt() and 0xFF) == MdrConstants.V2_ENABLE
    }

    private fun sendCommandT2(payload: ByteArray) = sendCommand(payload, MdrConstants.TYPE_DATA_MDR_NO2)

    /**
     * Attend une trame Table 2 acceptée par [accept]. Toutes les trames reçues sont acquittées ;
     * les trames Table 1 (batterie, NC/ASM…) sont ignorées.
     */
    private fun awaitT2(deadline: Long, what: String, accept: (ByteArray) -> Boolean): ByteArray {
        while (System.currentTimeMillis() < deadline) {
            var match: ByteArray? = null
            for (frame in readFrames(deadline, what)) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (match == null && frame.type == MdrConstants.TYPE_DATA_MDR_NO2 &&
                    frame.payload.size >= 2 && accept(frame.payload)
                ) {
                    match = frame.payload
                }
            }
            if (match != null) return match
        }
        throw MdrProtocolException("Timeout en attendant: $what")
    }

    /** Détermine le type d'inquiry (avec ou sans classe d'appareil) selon les fonctions Table 2 du casque. */
    private fun ensurePairingType(): Int {
        val cached = pairingType
        if (cached != null) {
            if (cached < 0) throw MdrProtocolException(UNSUPPORTED_MULTIPOINT)
            return cached
        }
        if (!hasTable2) {
            pairingType = -1
            throw MdrProtocolException(UNSUPPORTED_MULTIPOINT)
        }
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        sendCommandT2(byteArrayOf(MdrConstants.CMD_CONNECT_GET_SUPPORT_FUNCTION.toByte(), 0x00))
        val ret = awaitT2(deadline, "fonctions table 2") {
            (it[0].toInt() and 0xFF) == MdrConstants.CMD_CONNECT_RET_SUPPORT_FUNCTION
        }
        // [cmd, type, count, (fonction, priorité) * count]
        val count = if (ret.size > 2) ret[2].toInt() and 0xFF else 0
        val functions = HashSet<Int>()
        for (i in 0 until count) {
            val at = 3 + i * 2
            if (at >= ret.size) break
            functions.add(ret[at].toInt() and 0xFF)
        }
        val type = when {
            MdrConstants.T2_FUNC_PAIRING_WITH_COD_CLASSIC_BT in functions ||
                MdrConstants.T2_FUNC_PAIRING_WITH_COD_CLASSIC_LE in functions ->
                MdrConstants.PERI_TYPE_PAIRING_WITH_COD
            MdrConstants.T2_FUNC_PAIRING_CLASSIC_BT in functions -> MdrConstants.PERI_TYPE_PAIRING_CLASSIC_BT
            else -> -1
        }
        pairingType = type
        if (type < 0) throw MdrProtocolException(UNSUPPORTED_MULTIPOINT)
        return type
    }

    /** Liste les appareils appairés au casque (et lesquels sont connectés / en lecture) + mode d'appairage. */
    fun readPairedDevices(): PairedDevicesInfo {
        val type = ensurePairingType()
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        sendCommandT2(byteArrayOf(MdrConstants.CMD_PERI_GET_PARAM.toByte(), type.toByte()))
        val ret = awaitT2(deadline, "appareils appairés") {
            val cmd = it[0].toInt() and 0xFF
            (cmd == MdrConstants.CMD_PERI_RET_PARAM || cmd == MdrConstants.CMD_PERI_NTFY_PARAM) &&
                (it[1].toInt() and 0xFF) == type
        }
        val devices = parsePairedDevices(ret, withClassOfDevice = type == MdrConstants.PERI_TYPE_PAIRING_WITH_COD)

        var pairingMode: Boolean? = null
        try {
            sendCommandT2(byteArrayOf(MdrConstants.CMD_PERI_GET_STATUS.toByte(), type.toByte()))
            val status = awaitT2(System.currentTimeMillis() + MdrConstants.SOCKET_TIMEOUT_MS, "mode d'appairage") {
                val cmd = it[0].toInt() and 0xFF
                (cmd == MdrConstants.CMD_PERI_RET_STATUS || cmd == MdrConstants.CMD_PERI_NTFY_STATUS) &&
                    (it[1].toInt() and 0xFF) == type
            }
            pairingMode = parsePairingMode(status)
        } catch (_: MdrProtocolException) {
            // Mode d'appairage facultatif.
        }
        return PairedDevicesInfo(devices, pairingMode, ret.joinToString(" ") { "%02X".format(it) })
    }

    /** [cmd, type, count, (mac[17], état, [classe d'appareil[3]], nom(len+octets)) * count, appareil en lecture] */
    private fun parsePairedDevices(ret: ByteArray, withClassOfDevice: Boolean): List<PairedDevice> {
        fun need(at: Int, n: Int) {
            if (at + n > ret.size) throw MdrProtocolException("Liste d'appareils tronquée")
        }
        need(2, 1)
        val count = ret[2].toInt() and 0xFF
        var at = 3
        data class Raw(val mac: String, val status: Int, val name: String)
        val raws = ArrayList<Raw>(count)
        for (i in 0 until count) {
            need(at, MdrConstants.MAC_ADDRESS_LENGTH + 1)
            val mac = String(ret, at, MdrConstants.MAC_ADDRESS_LENGTH, Charsets.US_ASCII)
            at += MdrConstants.MAC_ADDRESS_LENGTH
            val status = ret[at++].toInt() and 0xFF
            if (withClassOfDevice) {
                need(at, 3)
                at += 3
            }
            need(at, 1)
            val len = ret[at++].toInt() and 0xFF
            need(at, len)
            raws.add(Raw(mac, status, String(ret, at, len, Charsets.UTF_8)))
            at += len
        }
        need(at, 1)
        val playback = ret[at].toInt() and 0xFF
        return raws.map {
            PairedDevice(it.mac.uppercase(), it.name, it.status, it.status != 0 && it.status == playback)
        }
    }

    /** [cmd, type, mode bluetooth, enable] — actif si INQUIRY_SCAN et ENABLE. */
    private fun parsePairingMode(status: ByteArray): Boolean? {
        if (status.size < 4) return null
        val mode = status[2].toInt() and 0xFF
        val enable = status[3].toInt() and 0xFF
        return enable == MdrConstants.V2_ENABLE && mode == MdrConstants.BT_MODE_INQUIRY_SCAN
    }

    /** Active/désactive le mode d'appairage (le casque devient visible pour un nouvel appareil). */
    fun setPairingMode(enabled: Boolean) {
        val type = ensurePairingType()
        sendCommandT2(
            byteArrayOf(
                MdrConstants.CMD_PERI_SET_STATUS.toByte(),
                type.toByte(),
                (if (enabled) MdrConstants.BT_MODE_INQUIRY_SCAN else MdrConstants.BT_MODE_NORMAL).toByte(),
                MdrConstants.V2_ENABLE.toByte(),
            ),
        )
        try {
            awaitT2(System.currentTimeMillis() + MdrConstants.SOCKET_TIMEOUT_MS, "mode d'appairage") {
                val cmd = it[0].toInt() and 0xFF
                (cmd == MdrConstants.CMD_PERI_RET_STATUS || cmd == MdrConstants.CMD_PERI_NTFY_STATUS) &&
                    (it[1].toInt() and 0xFF) == type
            }
        } catch (_: MdrProtocolException) {
            // Certains casques n'acquittent que la trame ; la relecture de la liste confirmera l'état.
        }
    }

    /** Connecte un appareil déjà appairé. Renvoie le code PeripheralResult. */
    fun connectPairedDevice(address: String): Int = peripheralAction(MdrConstants.CONN_ACTION_CONNECT, address)

    /** Déconnecte un appareil appairé (l'appairage est conservé). Renvoie le code PeripheralResult. */
    fun disconnectPairedDevice(address: String): Int = peripheralAction(MdrConstants.CONN_ACTION_DISCONNECT, address)

    /** Supprime l'appairage d'un appareil. Renvoie le code PeripheralResult. */
    fun unpairDevice(address: String): Int = peripheralAction(MdrConstants.CONN_ACTION_UNPAIR, address)

    private fun peripheralAction(action: Int, address: String): Int {
        val type = ensurePairingType()
        val mac = macBytes(address)
        val deadline = System.currentTimeMillis() + MdrConstants.PERIPHERAL_ACTION_TIMEOUT_MS
        sendCommandT2(byteArrayOf(MdrConstants.CMD_PERI_SET_EXT_PARAM.toByte(), type.toByte(), action.toByte()) + mac)
        // [cmd, type, action, résultat, mac[17]] ; on attend la fin de l'opération (pas « en cours »).
        val ret = awaitT2(deadline, "résultat de l'opération") {
            (it[0].toInt() and 0xFF) == MdrConstants.CMD_PERI_NTFY_EXT_PARAM &&
                it.size >= 4 &&
                (it[1].toInt() and 0xFF) == type &&
                (it[2].toInt() and 0xFF) == action &&
                (it[3].toInt() and 0x0F) != MdrConstants.PERIPHERAL_IN_PROGRESS
        }
        return ret[3].toInt() and 0xFF
    }

    /** Bascule la lecture audio vers l'appareil indiqué (déjà connecté). Renvoie SOURCE_SWITCH_*. */
    fun switchSource(address: String): Int {
        ensurePairingType() // vérifie que la Table 2 est disponible
        val mac = macBytes(address)
        val deadline = System.currentTimeMillis() + MdrConstants.PERIPHERAL_ACTION_TIMEOUT_MS
        sendCommandT2(
            byteArrayOf(MdrConstants.CMD_PERI_SET_EXT_PARAM.toByte(), MdrConstants.PERI_TYPE_SOURCE_SWITCH.toByte()) + mac,
        )
        // [cmd, type, résultat, mac[17]]
        val ret = awaitT2(deadline, "changement de source") {
            (it[0].toInt() and 0xFF) == MdrConstants.CMD_PERI_NTFY_EXT_PARAM &&
                it.size >= 3 &&
                (it[1].toInt() and 0xFF) == MdrConstants.PERI_TYPE_SOURCE_SWITCH
        }
        return ret[2].toInt() and 0xFF
    }

    private fun macBytes(address: String): ByteArray {
        val bytes = address.uppercase().toByteArray(Charsets.US_ASCII)
        if (bytes.size != MdrConstants.MAC_ADDRESS_LENGTH) {
            throw MdrProtocolException("Adresse Bluetooth invalide : $address")
        }
        return bytes
    }

    private val UNSUPPORTED_MULTIPOINT = "Ce casque n'expose pas la gestion multipoint (Table 2)."

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

    /** Attend une confirmation NC/ASM (RET_PARAM 0x67 ou NTFY 0x69) et renvoie son payload. */
    private fun awaitNcAsmReply(deadline: Long, what: String): ByteArray {
        while (System.currentTimeMillis() < deadline) {
            val frames = readFrames(deadline, what)
            for (frame in frames) {
                seqByte = frame.seq
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.isNotEmpty()) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    if (cmd == MdrConstants.CMD_NCASM_RET_PARAM ||
                        cmd == MdrConstants.CMD_NCASM_NTFY_PARAM
                    ) {
                        return frame.payload
                    }
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
            if (available.isNotEmpty()) {
                available.forEach { Log.d(LOG_TAG, "RX type=0x%02X seq=%d %s".format(it.type, it.seq, it.payload.toHex())) }
                return available
            }
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
                Thread.sleep(20)
                0
            } else {
                input.read(readBuffer, 0, avail.coerceAtMost(readBuffer.size))
            }
        } catch (e: SocketTimeoutException) {
            0
        }
    }
}