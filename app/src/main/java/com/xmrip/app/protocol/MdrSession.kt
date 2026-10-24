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

enum class ListeningMode { STANDARD, BGM, CINEMA }

/** Réglages de l'onglet Réglages ; un champ vaut null si le casque n'a pas répondu à sa lecture. */
data class SettingsState(
    val pauseWhenRemoved: Boolean?,
    val speakToChat: Boolean?,
    val dsee: Boolean?,
    val bgm: Boolean?,
    val bgmRoom: Int?,
    val cinema: Boolean?,
    /** Code brut de l'extinction auto (AUTO_POWER_OFF_*), null si non lu. */
    val autoPowerOff: Int?,
    /** Fonction du bouton de bruit (NC_BUTTON_*), null si non lue. */
    val ncButton: Int? = null,
    /** Sensibilité (STC_DETECT_*) et délai de reprise (STC_OFF_TIME_*) de Speak-to-Chat. */
    val chatSensitivity: Int? = null,
    val chatTimeout: Int? = null,
    /** Priorité de la liaison (PRIORITY_SOUND_QUALITY ou PRIORITY_CONNECTION). */
    val connectionPriority: Int? = null,
    /** Guidage vocal du casque (annonces « Bluetooth connecté »…) et son volume, de -2 à +2. */
    val voiceGuidance: Boolean? = null,
    val voiceGuidanceVolume: Int? = null,
) {
    /** Complète les champs non lus avec les valeurs précédentes : une lecture manquée ne doit pas griser le réglage. */
    fun fillMissingFrom(old: SettingsState?) = if (old == null) this else SettingsState(
        pauseWhenRemoved = pauseWhenRemoved ?: old.pauseWhenRemoved,
        speakToChat = speakToChat ?: old.speakToChat,
        dsee = dsee ?: old.dsee,
        bgm = bgm ?: old.bgm,
        bgmRoom = bgmRoom ?: old.bgmRoom,
        cinema = cinema ?: old.cinema,
        autoPowerOff = autoPowerOff ?: old.autoPowerOff,
        ncButton = ncButton ?: old.ncButton,
        chatSensitivity = chatSensitivity ?: old.chatSensitivity,
        chatTimeout = chatTimeout ?: old.chatTimeout,
        connectionPriority = connectionPriority ?: old.connectionPriority,
        voiceGuidance = voiceGuidance ?: old.voiceGuidance,
        voiceGuidanceVolume = voiceGuidanceVolume ?: old.voiceGuidanceVolume,
    )

    val listeningMode: ListeningMode?
        get() = when {
            bgm == true -> ListeningMode.BGM
            cinema == true -> ListeningMode.CINEMA
            bgm == false || cinema == false -> ListeningMode.STANDARD
            else -> null
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
    /** Séquence des commandes que NOUS envoyons : bit du dernier ACK reçu (voir [takeFresh]). */
    private var seqByte: Int = 0
    private val readBuffer = ByteArray(1024)

    /** Le casque annonce la Table 2 (gestion multipoint) dans la réponse protocole. */
    private var hasTable2 = false

    /** Type PeripheralInquiredType à utiliser ; null = pas encore déterminé, -1 = non pris en charge. */
    private var pairingType: Int? = null

    /**
     * Dernière trame de données reçue. Le casque renvoie une trame quand il croit que son acquittement s'est perdu
     * (liaison lente) : même type, même séquence, même contenu. La traiter comme une nouvelle réponse désynchronise
     * la session (commande suivante envoyée avec la séquence d'une commande non acquittée, que le casque ignore).
     */
    private var lastData: MdrFrame? = null

    /** Dernières valeurs connues, tenues à jour par le handshake et par les notifications du casque. */
    var modelName: String? = null
        private set
    var firmwareVersion: String? = null
        private set
    private var batteryLevel: Int? = null
    private var batteryCharging: Int = MdrConstants.CHARGING_UNKNOWN
    private var knownNcAsm: NcAsmState? = null
    private var knownVolume: Int? = null

    /** Heure (ms) de la dernière lecture complète de batterie, bruit et volume (handshake ou [refreshStatus]). */
    var statusAt: Long = 0L
        private set

    /** Vrai une fois le handshake complet fait : une session gardée ouverte ne le refait pas. */
    var handshaken: Boolean = false
        private set

    /** Rapport construit depuis les dernières valeurs connues, sans aucun échange ; null avant la batterie. */
    val report: BatteryReport?
        get() = batteryLevel?.let { BatteryReport(modelName, firmwareVersion, it, batteryCharging, knownNcAsm, knownVolume) }

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

        // Capacités, fonctions prises en charge et « série et couleur » ne sont plus demandés : leurs réponses ne sont
        // jamais lues, le XM6 répond sans eux (essayé sur le casque), et chaque échange coûte 0,4 à 0,9 s. L'info de
        // protocole (qui dit si la table 2 multipoint existe) attend qu'on en ait besoin ([ensureProtocolInfo]).
        // Firmware et modèle ne sont lus que s'ils ne sont pas déjà connus ([seedDeviceInfo]).
        if (firmwareVersion == null) {
            firmwareVersion = readDeviceInfoString(requestDeviceInfo(MdrConstants.DEVICE_INFO_FW_VERSION, deadline, "fw version"))
        }
        if (modelName == null) {
            modelName = readDeviceInfoString(requestDeviceInfo(MdrConstants.DEVICE_INFO_MODEL_NAME, deadline, "model name"))
        }

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
        batteryLevel = ret[2].toInt() and 0xFF
        batteryCharging = ret[3].toInt() and 0xFF

        try {
            readNcAsm(deadline)
        } catch (_: Exception) {
            // Le casque peut ne pas répondre sur ce type NCASM ; on garde la batterie.
        }

        try {
            readMusicVolume()
        } catch (_: Exception) {
            // Lecture du volume facultative.
        }

        handshaken = true
        statusAt = System.currentTimeMillis()
        return report!!
    }

    /** Retient un modèle et un firmware déjà connus (d'une session précédente), pour ne pas les redemander au casque. */
    fun seedDeviceInfo(model: String?, firmware: String?) {
        if (modelName == null) modelName = model
        if (firmwareVersion == null) firmwareVersion = firmware
    }

    /**
     * Délai d'attente des échanges de la table 2 (info de protocole, fonctions, liste des appareils). Une surveillance
     * en arrière-plan le réduit : quand un autre client tient la session de contrôle, le casque ne répond pas et
     * l'attente par défaut (12 s) bloquerait tout le reste.
     */
    var exchangeTimeoutMs: Long = MdrConstants.HANDSHAKE_TIMEOUT_MS.toLong()
    private var protocolKnown = false

    /** Demande l'info de protocole (table 2 multipoint présente ou non) la première fois qu'elle sert. */
    private fun ensureProtocolInfo() {
        if (protocolKnown) return
        val deadline = System.currentTimeMillis() + exchangeTimeoutMs
        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00))
        noteProtocolInfo(awaitCommand(MdrConstants.CMD_CONNECT_RET_PROTOCOL_INFO, deadline, "info de protocole"))
        protocolKnown = true
    }

    /**
     * Relit batterie, bruit et volume sur une session déjà ouverte (3 échanges). Pour un rafraîchissement demandé
     * explicitement : après une action, [report] suffit, il est tenu à jour par les réponses et notifications.
     */
    fun refreshStatus(): BatteryReport {
        val deadline = System.currentTimeMillis() + MdrConstants.HANDSHAKE_TIMEOUT_MS
        sendCommand(byteArrayOf(MdrConstants.CMD_POWER_GET_STATUS.toByte(), MdrConstants.POWER_BATTERY.toByte()))
        awaitCommand(MdrConstants.CMD_POWER_RET_STATUS, deadline, "battery")
        try {
            readNcAsm(deadline)
        } catch (_: Exception) {
        }
        try {
            readMusicVolume()
        } catch (_: Exception) {
        }
        statusAt = System.currentTimeMillis()
        return report ?: throw MdrProtocolException("Réponse batterie inattendue")
    }

    /** Envoie une demande d'info appareil et attend la réponse de CE type (pas une réponse retardée d'un autre). */
    private fun requestDeviceInfo(infoType: Int, deadline: Long, what: String): ByteArray {
        sendCommand(byteArrayOf(MdrConstants.CMD_CONNECT_GET_DEVICE_INFO.toByte(), infoType.toByte()))
        while (System.currentTimeMillis() < deadline) {
            var match: ByteArray? = null
            for (frame in readFrames(deadline, what)) {
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (match == null && frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.size >= 2 &&
                    (frame.payload[0].toInt() and 0xFF) == MdrConstants.CMD_CONNECT_RET_DEVICE_INFO &&
                    (frame.payload[1].toInt() and 0xFF) == infoType
                ) {
                    match = frame.payload
                }
            }
            if (match != null) return match
        }
        throw MdrProtocolException("Timeout en attendant: $what")
    }

    /**
     * Lit et acquitte ce qui est déjà arrivé, sans attendre. À appeler régulièrement sur une session gardée ouverte :
     * le casque envoie des notifications spontanées (batterie, bruit, volume) et renvoie ce qu'on n'acquitte pas.
     * Lève une exception si la liaison est coupée.
     */
    fun drain() {
        val n = readNow()
        if (n < 0) throw EOFException("Flux fermé par le casque")
        if (n > 0) reader.feed(readBuffer.copyOf(n))
        for (frame in takeFresh(reader.readAvailable())) {
            if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                sendAck(frame.seq)
            }
        }
    }

    /**
     * Envoie une commande brute (outil de diagnostic du banc PC) et renvoie tout ce que le casque envoie pendant
     * [waitMs] : chaque élément est (type de trame, charge utile). Les trames de données sont acquittées.
     */
    fun rawExchange(payload: ByteArray, waitMs: Long = 2000, type: Int = MdrConstants.TYPE_DATA_MDR): List<Pair<Int, ByteArray>> {
        val deadline = System.currentTimeMillis() + waitMs
        if (payload.isNotEmpty()) sendCommand(payload, type)
        val out = mutableListOf<Pair<Int, ByteArray>>()
        while (System.currentTimeMillis() < deadline) {
            val frames = try {
                readFrames(deadline, "réponse brute")
            } catch (e: MdrProtocolException) {
                break
            }
            for (frame in frames) {
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                out += frame.type to frame.payload
            }
        }
        return out
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
     * Handshake minimal pour une action rapide (raccourcis) : aucun échange. Le XM6 répond aux commandes sans
     * handshake (essayé sur le casque) ; l'info de protocole n'est demandée que si le multipoint sert.
     */
    @Suppress("UNUSED_PARAMETER")
    fun quickHandshake(deadline: Long) {
        handshaken = true
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

    // ---------------------------------------------------------------------------------------
    // Réglages du casque : lecture groupée et écritures une à une (onglet Réglages)
    // ---------------------------------------------------------------------------------------

    /**
     * Lit tous les réglages de l'onglet Réglages. Une lecture sans réponse (type non géré par le casque) donne
     * null pour ce champ ; elle coûte [MdrConstants.SETTING_REPLY_WAIT_MS] d'attente.
     */
    fun readSettings(): SettingsState {
        readSettingKinds(SettingKind.values().toSet())
        return settingsCache
    }

    private enum class SettingKind { WEAR, CHAT, CHAT_EXT, DSEE, BGM, CINEMA, POWER, NC_BUTTON, CONNECTION, VOICE, VOICE_VOLUME }

    /** Dernier état connu des réglages : lectures, réponses et notifications du casque (voir [observe]). */
    private var settingsCache = SettingsState(null, null, null, null, null, null, null)

    /** Réglages tels que le casque les a annoncés en dernier, sans aucun échange. */
    val settings: SettingsState get() = settingsCache

    /**
     * Après une écriture, attend que les notifications du casque confirment le nouvel état (le cache de réglages se
     * met à jour à chaque trame reçue), au plus [MdrConstants.SETTING_CONFIRM_WAIT_MS] ; sinon relit explicitement
     * [kinds]. Relire tout de suite était lent : après un changement de mode d'écoute le casque envoie une rafale de
     * notifications qui retarde ses réponses de plus d'une seconde, alors que ces mêmes notifications portent déjà l'état.
     */
    private fun confirmSettings(kinds: Set<SettingKind>, expected: (SettingsState) -> Boolean) {
        val deadline = System.currentTimeMillis() + MdrConstants.SETTING_CONFIRM_WAIT_MS
        while (!expected(settingsCache)) {
            try {
                readFrames(deadline, "confirmation du réglage").forEach { frame ->
                    if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                        sendAck(frame.seq)
                    }
                }
            } catch (e: MdrProtocolException) {
                readSettingKinds(kinds)
                return
            }
        }
    }

    /** Lit seulement les réglages demandés ; chaque réponse met le cache à jour. */
    private fun readSettingKinds(kinds: Set<SettingKind>) {
        val ret = { cmd: Int -> intArrayOf(cmd, cmd + 2) }
        for (kind in kinds) {
            when (kind) {
                SettingKind.WEAR -> readParam(MdrConstants.CMD_SYSTEM_GET_PARAM, ret(MdrConstants.CMD_SYSTEM_RET_PARAM), MdrConstants.SYSTEM_TYPE_PLAYBACK_BY_WEARING)
                SettingKind.CHAT -> readParam(MdrConstants.CMD_SYSTEM_GET_PARAM, ret(MdrConstants.CMD_SYSTEM_RET_PARAM), MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2)
                SettingKind.DSEE -> readParam(MdrConstants.CMD_AUDIO_GET_PARAM, ret(MdrConstants.CMD_AUDIO_RET_PARAM), MdrConstants.AUDIO_TYPE_UPSCALING)
                SettingKind.BGM -> readParam(MdrConstants.CMD_AUDIO_GET_PARAM, ret(MdrConstants.CMD_AUDIO_RET_PARAM), MdrConstants.AUDIO_TYPE_BGM_MODE)
                SettingKind.CINEMA -> readParam(MdrConstants.CMD_AUDIO_GET_PARAM, ret(MdrConstants.CMD_AUDIO_RET_PARAM), MdrConstants.AUDIO_TYPE_UPMIX_CINEMA)
                SettingKind.POWER -> readParam(MdrConstants.CMD_POWER_GET_PARAM, ret(MdrConstants.CMD_POWER_RET_PARAM), MdrConstants.POWER_TYPE_AUTO_POWER_OFF_WEARING)
                SettingKind.NC_BUTTON -> readParam(MdrConstants.CMD_NCASM_GET_PARAM, ret(MdrConstants.CMD_NCASM_RET_PARAM), MdrConstants.NCASM_TYPE_BUTTON)
                SettingKind.CHAT_EXT -> readParam(MdrConstants.CMD_SYSTEM_GET_EXT_PARAM, ret(MdrConstants.CMD_SYSTEM_RET_EXT_PARAM), MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2)
                SettingKind.CONNECTION -> readParam(MdrConstants.CMD_AUDIO_GET_PARAM, ret(MdrConstants.CMD_AUDIO_RET_PARAM), MdrConstants.AUDIO_TYPE_CONNECTION_MODE)
                SettingKind.VOICE -> readParamT2(MdrConstants.CMD_VOICE_GUIDANCE_GET_PARAM, MdrConstants.VOICE_GUIDANCE_TYPE_ON_OFF)
                SettingKind.VOICE_VOLUME -> readParamT2(MdrConstants.CMD_VOICE_GUIDANCE_GET_PARAM, MdrConstants.VOICE_GUIDANCE_TYPE_VOLUME)
            }
        }
    }

    /** Lecture d'un réglage de la table 2 (guidage vocal) ; la réponse met le cache à jour via [observe]. */
    private fun readParamT2(getCmd: Int, type: Int): ByteArray? {
        sendCommandT2(byteArrayOf(getCmd.toByte(), type.toByte()))
        return try {
            awaitT2(System.currentTimeMillis() + MdrConstants.SETTING_READ_WAIT_MS, "lecture réglage t2 0x%02X".format(type)) {
                (it[0].toInt() and 0xFF) == getCmd + 1 && (it[1].toInt() and 0xFF) == type
            }
        } catch (e: MdrProtocolException) {
            null
        }
    }

    /** Délai avant que la musique reprenne après Speak-to-Chat (STC_OFF_TIME_*) ; la sensibilité lue est renvoyée telle quelle. */
    fun setChatTimeout(timeout: Int): SettingsState {
        if (settingsCache.chatSensitivity == null) readSettingKinds(setOf(SettingKind.CHAT_EXT))
        val sensitivity = settingsCache.chatSensitivity ?: MdrConstants.STC_DETECT_AUTO
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_EXT_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2.toByte(),
                sensitivity.toByte(),
                timeout.toByte(),
            ),
        )
        confirmSettings(setOf(SettingKind.CHAT_EXT)) { it.chatTimeout == timeout }
        return settingsCache
    }

    /** Priorité qualité du son ou stabilité de la connexion. Le casque ne notifie pas ce type : on relit. */
    fun setConnectionPriority(priority: Int): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_AUDIO_SET_PARAM.toByte(),
                MdrConstants.AUDIO_TYPE_CONNECTION_MODE.toByte(),
                priority.toByte(),
            ),
        )
        readSettingKinds(setOf(SettingKind.CONNECTION))
        return settingsCache
    }

    /** Active/désactive les annonces vocales du casque. Renvoie les réglages relus. */
    fun setVoiceGuidance(on: Boolean): SettingsState {
        sendCommandT2(
            byteArrayOf(
                MdrConstants.CMD_VOICE_GUIDANCE_SET_PARAM.toByte(),
                MdrConstants.VOICE_GUIDANCE_TYPE_ON_OFF.toByte(),
                (if (on) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
            ),
        )
        awaitAck(System.currentTimeMillis() + MdrConstants.SETTING_REPLY_WAIT_MS)
        confirmSettings(setOf(SettingKind.VOICE)) { it.voiceGuidance == on }
        return settingsCache
    }

    /** Volume des annonces vocales, de -2 à +2, sans bip de confirmation. Renvoie les réglages relus. */
    fun setVoiceGuidanceVolume(volume: Int): SettingsState {
        val v = volume.coerceIn(MdrConstants.VOICE_GUIDANCE_VOLUME_MIN, MdrConstants.VOICE_GUIDANCE_VOLUME_MAX)
        sendCommandT2(
            byteArrayOf(
                MdrConstants.CMD_VOICE_GUIDANCE_SET_PARAM.toByte(),
                MdrConstants.VOICE_GUIDANCE_TYPE_VOLUME.toByte(),
                v.toByte(),
                MdrConstants.V2_VALUE_OFF.toByte(),
            ),
        )
        awaitAck(System.currentTimeMillis() + MdrConstants.SETTING_REPLY_WAIT_MS)
        confirmSettings(setOf(SettingKind.VOICE_VOLUME)) { it.voiceGuidanceVolume == v }
        return settingsCache
    }

    /**
     * Modes que le bouton de bruit (oreillette gauche) fait défiler : [function] = NC_BUTTON_*. Écriture `68 30 <f>`,
     * notification `69 30 <f>`. Renvoie les réglages relus.
     */
    fun setNcButton(function: Int): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_NCASM_SET_PARAM.toByte(),
                MdrConstants.NCASM_TYPE_BUTTON.toByte(),
                function.toByte(),
            ),
        )
        confirmSettings(setOf(SettingKind.NC_BUTTON)) { it.ncButton == function }
        return settingsCache
    }

    /** Pause automatique quand on ôte le casque. Renvoie les réglages relus. */
    fun setPauseWhenRemoved(on: Boolean): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_PLAYBACK_BY_WEARING.toByte(),
                (if (on) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
            ),
        )
        confirmSettings(setOf(SettingKind.WEAR)) { it.pauseWhenRemoved == on }
        return settingsCache
    }

    /** Active/désactive Speak-to-Chat sans toucher à sa sensibilité ni à son délai. Renvoie les réglages relus. */
    fun setSpeakToChat(on: Boolean): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_SYSTEM_SET_PARAM.toByte(),
                MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2.toByte(),
                (if (on) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
                MdrConstants.V2_VALUE_OFF.toByte(), // aperçu désactivé
            ),
        )
        confirmSettings(setOf(SettingKind.CHAT)) { it.speakToChat == on }
        return settingsCache
    }

    /**
     * Mode d'écoute : les deux réglages BGM et cinéma s'excluent, on éteint donc l'autre avant d'allumer le choisi.
     * Un réglage déjà éteint d'après le dernier état connu n'est pas réécrit (chaque échange coûte du temps).
     * Renvoie les réglages relus.
     */
    fun setListeningMode(mode: ListeningMode, room: Int = MdrConstants.ROOM_SMALL): SettingsState {
        val known = settingsCache
        when (mode) {
            ListeningMode.STANDARD -> {
                if (known.bgm != false) writeBgm(false, room)
                if (known.cinema != false) writeCinema(false)
            }
            ListeningMode.BGM -> {
                if (known.cinema != false) writeCinema(false)
                writeBgm(true, room)
            }
            ListeningMode.CINEMA -> {
                if (known.bgm != false) writeBgm(false, room)
                writeCinema(true)
            }
        }
        confirmSettings(setOf(SettingKind.BGM, SettingKind.CINEMA)) {
            when (mode) {
                ListeningMode.STANDARD -> it.bgm == false && it.cinema == false
                ListeningMode.BGM -> it.bgm == true && it.cinema == false
                ListeningMode.CINEMA -> it.cinema == true && it.bgm == false
            }
        }
        return settingsCache
    }

    private fun writeBgm(on: Boolean, room: Int) = writeParam(
        byteArrayOf(
            MdrConstants.CMD_AUDIO_SET_PARAM.toByte(),
            MdrConstants.AUDIO_TYPE_BGM_MODE.toByte(),
            (if (on) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
            room.toByte(),
        ),
    )

    private fun writeCinema(on: Boolean) = writeParam(
        byteArrayOf(
            MdrConstants.CMD_AUDIO_SET_PARAM.toByte(),
            MdrConstants.AUDIO_TYPE_UPMIX_CINEMA.toByte(),
            (if (on) MdrConstants.V2_VALUE_ON else MdrConstants.V2_VALUE_OFF).toByte(),
        ),
    )

    /**
     * Extinction automatique (type XM6 0x05) : [element] = AUTO_POWER_OFF_WHEN_REMOVED ou AUTO_POWER_OFF_DISABLE.
     * Renvoie les réglages relus.
     */
    fun setAutoPowerOff(element: Int): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_POWER_SET_PARAM.toByte(),
                MdrConstants.POWER_TYPE_AUTO_POWER_OFF_WEARING.toByte(),
                element.toByte(),
                0x00,
            ),
        )
        confirmSettings(setOf(SettingKind.POWER)) { it.autoPowerOff == element }
        return settingsCache
    }

    /** Éteint le casque ; il ne répond pas, la liaison se coupe. */
    fun powerOff() {
        sendCommand(
            byteArrayOf(
                MdrConstants.CMD_POWER_SET_STATUS.toByte(),
                MdrConstants.POWER_TYPE_POWER_OFF.toByte(),
                MdrConstants.POWER_OFF_USER.toByte(),
            ),
        )
    }

    /** Envoie une lecture et renvoie la réponse du type demandé, ou null si le casque ne répond pas à temps. */
    private fun readParam(getCmd: Int, retCmds: IntArray, type: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + MdrConstants.SETTING_READ_WAIT_MS
        sendCommand(byteArrayOf(getCmd.toByte(), type.toByte()))
        return try {
            awaitParam(deadline, "lecture réglage 0x%02X".format(type), retCmds, type)
        } catch (e: MdrProtocolException) {
            null
        }
    }

    /**
     * Envoie une écriture et attend seulement l'ACQUITTEMENT du casque (une cinquantaine de millisecondes), pas sa
     * notification : elle ne vient pas toujours, et attendre jusqu'à [MdrConstants.SETTING_REPLY_WAIT_MS] à chaque
     * écriture rendait le changement de mode d'écoute lent. L'état réel se relit ensuite ou arrive par notification.
     */
    private fun writeParam(payload: ByteArray) {
        sendCommand(payload)
        awaitAck(System.currentTimeMillis() + MdrConstants.SETTING_REPLY_WAIT_MS)
    }

    /** Attend l'acquittement de la dernière commande ; sans lui avant [deadline], on continue (la liaison est lente). */
    private fun awaitAck(deadline: Long) {
        while (true) {
            val frames = try {
                readFrames(deadline, "acquittement")
            } catch (e: MdrProtocolException) {
                return
            }
            var acked = false
            for (frame in frames) {
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_ACK) acked = true
            }
            if (acked) return
        }
    }

    private fun awaitParam(deadline: Long, what: String, retCmds: IntArray, type: Int): ByteArray {
        while (System.currentTimeMillis() < deadline) {
            var match: ByteArray? = null
            for (frame in readFrames(deadline, what)) {
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (match == null && frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.size >= 3 &&
                    retCmds.contains(frame.payload[0].toInt() and 0xFF) &&
                    (frame.payload[1].toInt() and 0xFF) == type
                ) {
                    match = frame.payload
                }
            }
            if (match != null) return match
        }
        throw MdrProtocolException("Timeout en attendant: $what")
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
        // Notification ou acquittement, le premier des deux : sans notification on relit tout de suite au lieu
        // d'attendre la fin du délai (l'acquittement arrive en quelques dizaines de millisecondes).
        val deadline = System.currentTimeMillis() + MdrConstants.EQ_ECHO_WAIT_MS
        var acked = false
        var echo: ByteArray? = null
        while (!acked && echo == null) {
            val frames = try {
                readFrames(deadline, "réponse égaliseur")
            } catch (e: MdrProtocolException) {
                break
            }
            for (frame in frames) {
                if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                    sendAck(frame.seq)
                }
                if (frame.type == MdrConstants.TYPE_ACK) acked = true
                if (echo == null && frame.type == MdrConstants.TYPE_DATA_MDR && frame.payload.size >= 4) {
                    val cmd = frame.payload[0].toInt() and 0xFF
                    val type = frame.payload[1].toInt() and 0xFF
                    if ((cmd == MdrConstants.CMD_EQEBB_RET_PARAM || cmd == MdrConstants.CMD_EQEBB_NTFY_PARAM) &&
                        type == MdrConstants.EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE
                    ) {
                        echo = frame.payload
                    }
                }
            }
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

    /** Active/désactive le DSEE (upscaling automatique). Renvoie les réglages relus. */
    fun setDsee(enabled: Boolean): SettingsState {
        writeParam(
            byteArrayOf(
                MdrConstants.CMD_AUDIO_SET_PARAM.toByte(),
                MdrConstants.AUDIO_TYPE_UPSCALING.toByte(),
                (if (enabled) MdrConstants.UPSCALING_AUTO else MdrConstants.UPSCALING_OFF).toByte(),
            ),
        )
        confirmSettings(setOf(SettingKind.DSEE)) { it.dsee == enabled }
        return settingsCache
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
        ensureProtocolInfo()
        if (!hasTable2) {
            pairingType = -1
            throw MdrProtocolException(UNSUPPORTED_MULTIPOINT)
        }
        val deadline = System.currentTimeMillis() + exchangeTimeoutMs
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
        val deadline = System.currentTimeMillis() + exchangeTimeoutMs
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
            .also { pairedDevices = it }
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
            val available = takeFresh(reader.readAvailable())
            if (available.isNotEmpty()) return available
            if (System.currentTimeMillis() >= deadline) {
                throw MdrProtocolException("Timeout en attendant: $what")
            }
            val n = readFromSocket()
            if (n < 0) throw EOFException("Flux fermé par le casque")
            if (n > 0) reader.feed(readBuffer.copyOf(n))
        }
    }

    /**
     * Écarte les trames de données renvoyées en double (voir [lastData]) après les avoir acquittées de nouveau, et
     * note au passage batterie, bruit et volume annoncés par le casque. Ne renvoie que les trames nouvelles.
     */
    private fun takeFresh(frames: List<MdrFrame>): List<MdrFrame> {
        if (frames.isEmpty()) return frames
        val fresh = ArrayList<MdrFrame>(frames.size)
        for (frame in frames) {
            Log.d(LOG_TAG, "RX type=0x%02X seq=%d %s".format(frame.type, frame.seq, frame.payload.toHex()))
            // Notre prochain numéro de séquence est celui du dernier ACQUITTEMENT reçu, pas celui de n'importe quelle
            // trame : les trames de données du casque (réponses, mais aussi notifications spontanées) ont leur propre
            // compteur. Pendant un échange les deux coïncident ; après une notification (titre, volume, appareils)
            // ils divergent, et une commande envoyée avec la séquence de la précédente est ignorée sans même un
            // acquittement (l'égaliseur ne répondait plus).
            if (frame.type == MdrConstants.TYPE_ACK) seqByte = frame.seq
            if (frame.type == MdrConstants.TYPE_DATA_MDR || frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
                if (frame == lastData) {
                    Log.d(LOG_TAG, "RX doublon ignoré seq=%d".format(frame.seq))
                    sendAck(frame.seq)
                    continue
                }
                lastData = frame
            }
            observe(frame)
            fresh.add(frame)
        }
        return fresh
    }

    /** Retient l'état annoncé dans une réponse ou une notification, qu'une commande l'ait demandé ou non. */
    /** Prévenu quand le casque annonce un changement (touche du casque, autre appareil connecté, notification). */
    interface StateListener {
        fun onReport(report: BatteryReport)
        fun onSettings(settings: SettingsState)
        fun onDevices(info: PairedDevicesInfo)

        /** Geste physique annoncé par le casque (« keyCustomBtnTwo »…), une fois [MdrSession.enableGestureLog] fait. */
        fun onGesture(gesture: String) {}
    }

    /** Vrai une fois le journal d'événements du casque activé sur cette session. */
    var gestureLogOn: Boolean = false
        private set

    /**
     * Active le journal d'événements du casque (comme libmdr à l'initialisation) : sans cela il n'annonce aucun geste.
     * Sans effet s'il est déjà actif sur cette session.
     */
    fun enableGestureLog() {
        if (gestureLogOn) return
        writeParam(byteArrayOf(MdrConstants.CMD_LOG_SET_STATUS.toByte(), 0x01, 0x00))
        gestureLogOn = true
    }

    private var lastGesture: String? = null
    private var lastGestureAt = 0L

    /** Un même geste reçu deux fois à moins de 1,8 s est compté une fois (le casque l'a déjà renvoyé ainsi). */
    private fun observeLog(p: ByteArray) {
        val text = String(p, 2, p.size - 2, Charsets.ISO_8859_1).filter { it.code in 32..126 }
        if (!text.startsWith("key")) return
        val now = System.currentTimeMillis()
        if (text == lastGesture && now - lastGestureAt < 1_800L) return
        lastGesture = text
        lastGestureAt = now
        stateListener?.onGesture(text)
    }

    @Volatile
    var stateListener: StateListener? = null

    /** Dernière liste d'appareils appairés vue passer (réponse ou notification). */
    var pairedDevices: PairedDevicesInfo? = null
        private set

    private fun observe(frame: MdrFrame) {
        val p = frame.payload
        if (frame.type == MdrConstants.TYPE_DATA_MDR_NO2) {
            val before = settingsCache
            observeVoiceGuidance(p)
            if (settingsCache != before && handshaken) stateListener?.onSettings(settingsCache)
            observeDevices(p)
            return
        }
        if (frame.type != MdrConstants.TYPE_DATA_MDR || p.size < 3) return
        if ((p[0].toInt() and 0xFF) == MdrConstants.CMD_LOG_NTFY_PARAM) {
            observeLog(p)
            return
        }
        val reportBefore = report
        val settingsBefore = settingsCache
        observeState(p)
        val listener = stateListener ?: return
        if (!handshaken) return
        report?.takeIf { it != reportBefore }?.let { listener.onReport(it) }
        if (settingsCache != settingsBefore) listener.onSettings(settingsCache)
    }

    /** Guidage vocal (réponse ou notification de la table 2) : marche/arrêt et volume signé. */
    private fun observeVoiceGuidance(p: ByteArray) {
        if (p.size < 3) return
        val cmd = p[0].toInt() and 0xFF
        if (cmd != MdrConstants.CMD_VOICE_GUIDANCE_RET_PARAM && cmd != MdrConstants.CMD_VOICE_GUIDANCE_NTFY_PARAM) return
        when (p[1].toInt() and 0xFF) {
            MdrConstants.VOICE_GUIDANCE_TYPE_ON_OFF ->
                settingsCache = settingsCache.copy(voiceGuidance = (p[2].toInt() and 0xFF) == MdrConstants.V2_VALUE_ON)
            MdrConstants.VOICE_GUIDANCE_TYPE_VOLUME ->
                settingsCache = settingsCache.copy(voiceGuidanceVolume = p[2].toInt())
        }
    }

    /** Liste des appareils du multipoint (réponse ou notification de la table 2). */
    private fun observeDevices(p: ByteArray) {
        if (p.size < 3) return
        val cmd = p[0].toInt() and 0xFF
        val type = p[1].toInt() and 0xFF
        if (cmd != MdrConstants.CMD_PERI_RET_PARAM && cmd != MdrConstants.CMD_PERI_NTFY_PARAM) return
        if (type != MdrConstants.PERI_TYPE_PAIRING_WITH_COD && type != MdrConstants.PERI_TYPE_PAIRING_CLASSIC_BT) return
        val devices = try {
            parsePairedDevices(p, withClassOfDevice = type == MdrConstants.PERI_TYPE_PAIRING_WITH_COD)
        } catch (_: MdrProtocolException) {
            return // notification tronquée : on garde la liste précédente
        }
        val info = PairedDevicesInfo(devices, pairedDevices?.pairingMode, p.joinToString(" ") { "%02X".format(it) })
        pairedDevices = info
        stateListener?.onDevices(info)
    }

    /** Retient batterie, bruit, volume et réglages annoncés dans une trame de la table 1. */
    private fun observeState(p: ByteArray) {
        val cmd = p[0].toInt() and 0xFF
        val type = p[1].toInt() and 0xFF
        val on = (p[2].toInt() and 0xFF) == MdrConstants.V2_ENABLE
        when {
            (cmd == MdrConstants.CMD_SYSTEM_RET_PARAM || cmd == MdrConstants.CMD_SYSTEM_NTFY_PARAM) &&
                type == MdrConstants.SYSTEM_TYPE_PLAYBACK_BY_WEARING ->
                settingsCache = settingsCache.copy(pauseWhenRemoved = on)
            (cmd == MdrConstants.CMD_SYSTEM_RET_PARAM || cmd == MdrConstants.CMD_SYSTEM_NTFY_PARAM) &&
                type == MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2 ->
                settingsCache = settingsCache.copy(speakToChat = on)
            (cmd == MdrConstants.CMD_AUDIO_RET_PARAM || cmd == MdrConstants.CMD_AUDIO_NTFY_PARAM) &&
                type == MdrConstants.AUDIO_TYPE_UPSCALING ->
                settingsCache = settingsCache.copy(dsee = (p[2].toInt() and 0xFF) == MdrConstants.UPSCALING_AUTO)
            (cmd == MdrConstants.CMD_AUDIO_RET_PARAM || cmd == MdrConstants.CMD_AUDIO_NTFY_PARAM) &&
                type == MdrConstants.AUDIO_TYPE_BGM_MODE ->
                settingsCache = settingsCache.copy(
                    bgm = on,
                    bgmRoom = if (p.size > 3) p[3].toInt() and 0xFF else settingsCache.bgmRoom,
                )
            (cmd == MdrConstants.CMD_AUDIO_RET_PARAM || cmd == MdrConstants.CMD_AUDIO_NTFY_PARAM) &&
                type == MdrConstants.AUDIO_TYPE_UPMIX_CINEMA ->
                settingsCache = settingsCache.copy(cinema = on)
            (cmd == MdrConstants.CMD_POWER_RET_PARAM || cmd == MdrConstants.CMD_POWER_NTFY_PARAM) &&
                type == MdrConstants.POWER_TYPE_AUTO_POWER_OFF_WEARING ->
                settingsCache = settingsCache.copy(autoPowerOff = p[2].toInt() and 0xFF)
            (cmd == MdrConstants.CMD_NCASM_RET_PARAM || cmd == MdrConstants.CMD_NCASM_NTFY_PARAM) &&
                type == MdrConstants.NCASM_TYPE_BUTTON ->
                settingsCache = settingsCache.copy(ncButton = p[2].toInt() and 0xFF)
            (cmd == MdrConstants.CMD_SYSTEM_RET_EXT_PARAM || cmd == MdrConstants.CMD_SYSTEM_NTFY_EXT_PARAM) &&
                type == MdrConstants.SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2 && p.size >= 4 ->
                settingsCache = settingsCache.copy(chatSensitivity = p[2].toInt() and 0xFF, chatTimeout = p[3].toInt() and 0xFF)
            (cmd == MdrConstants.CMD_AUDIO_RET_PARAM || cmd == MdrConstants.CMD_AUDIO_NTFY_PARAM) &&
                type == MdrConstants.AUDIO_TYPE_CONNECTION_MODE ->
                settingsCache = settingsCache.copy(connectionPriority = p[2].toInt() and 0xFF)
        }
        observeStatus(p, cmd, type)
    }

    private fun observeStatus(p: ByteArray, cmd: Int, type: Int) {
        when {
            (cmd == MdrConstants.CMD_POWER_RET_STATUS || cmd == MdrConstants.CMD_POWER_NTFY_STATUS) &&
                type == MdrConstants.POWER_BATTERY && p.size >= 4 -> {
                batteryLevel = p[2].toInt() and 0xFF
                batteryCharging = p[3].toInt() and 0xFF
            }
            (cmd == MdrConstants.CMD_NCASM_RET_PARAM || cmd == MdrConstants.CMD_NCASM_NTFY_PARAM) &&
                type == MdrConstants.NCASM_TYPE_DUAL_NC_ASM_NA && p.size >= 7 ->
                knownNcAsm = runCatching { parseNcAsm(p) }.getOrNull() ?: knownNcAsm
            (cmd == MdrConstants.CMD_PLAY_RET_PARAM || cmd == MdrConstants.CMD_PLAY_NTFY_PARAM) &&
                type == MdrConstants.PLAY_TYPE_MUSIC_VOLUME -> knownVolume = p[2].toInt() and 0xFF
        }
    }

    /** Lit un bloc d'octets si disponible ; renvoie 0 si rien (borné par le deadline via readFrames). */
    private fun readFromSocket(): Int {
        val n = readNow()
        if (n == 0) Thread.sleep(20)
        return n
    }

    /** Comme [readFromSocket] sans pause quand il n'y a rien. */
    private fun readNow(): Int {
        return try {
            val avail = input.available()
            if (avail <= 0) 0 else input.read(readBuffer, 0, avail.coerceAtMost(readBuffer.size))
        } catch (e: SocketTimeoutException) {
            0
        }
    }
}
