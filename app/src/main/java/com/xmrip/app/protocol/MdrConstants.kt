package com.xmrip.app.protocol

/**
 * Constantes du protocole MDR v2 (issues de libmdr — mos9527/SonyHeadphonesClient, MIT).
 */
object MdrConstants {
    const val START_MARKER = 0x3E // ">"
    const val END_MARKER = 0x3C // "<"
    const val ESCAPE_BYTE = 0x3D // "="
    const val ESCAPED_3C = 0x2C
    const val ESCAPED_3D = 0x2D
    const val ESCAPED_3E = 0x2E

    const val SERVICE_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

    const val SOCKET_TIMEOUT_MS = 3000
    const val HANDSHAKE_TIMEOUT_MS = 12_000

    /** MDRDataType — types de trame. */
    const val TYPE_ACK = 0x01
    const val TYPE_DATA_MDR = 0x0C // Table 1
    const val TYPE_DATA_MDR_NO2 = 0x0E // Table 2

    /** Commands Table 1 (v2) — extraits de ProtocolV2T1.hpp. */
    const val CMD_CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CMD_CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CMD_CONNECT_GET_CAPABILITY_INFO = 0x02
    const val CMD_CONNECT_RET_CAPABILITY_INFO = 0x03
    const val CMD_CONNECT_GET_DEVICE_INFO = 0x04
    const val CMD_CONNECT_RET_DEVICE_INFO = 0x05
    const val CMD_CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CMD_CONNECT_RET_SUPPORT_FUNCTION = 0x07
    const val CMD_POWER_GET_STATUS = 0x22
    const val CMD_POWER_RET_STATUS = 0x23
    const val CMD_POWER_NTFY_STATUS = 0x25

    /** Journal d'événements du casque : LOG_SET_STATUS l'active, LOG_NTFY_PARAM porte les noms de gestes (texte). */
    const val CMD_LOG_SET_STATUS = 0xC4
    const val CMD_LOG_NTFY_PARAM = 0xC9
    const val CMD_NCASM_GET_PARAM = 0x66
    const val CMD_NCASM_RET_PARAM = 0x67
    const val CMD_NCASM_SET_PARAM = 0x68
    const val CMD_NCASM_NTFY_PARAM = 0x69

    /** POWER */
    const val CMD_POWER_GET_PARAM = 0x26
    const val CMD_POWER_RET_PARAM = 0x27
    const val CMD_POWER_SET_PARAM = 0x28
    const val CMD_POWER_NTFY_PARAM = 0x29

    /** EQEBB */
    const val CMD_EQEBB_GET_PARAM = 0x56
    const val CMD_EQEBB_RET_PARAM = 0x57
    const val CMD_EQEBB_SET_PARAM = 0x58
    const val CMD_EQEBB_NTFY_PARAM = 0x59

    /** PLAY */
    const val CMD_PLAY_SET_STATUS = 0xA4
    const val CMD_PLAY_RET_STATUS = 0xA3
    const val CMD_PLAY_NTFY_STATUS = 0xA5
    const val CMD_PLAY_GET_PARAM = 0xA6
    const val CMD_PLAY_RET_PARAM = 0xA7
    const val CMD_PLAY_SET_PARAM = 0xA8
    const val CMD_PLAY_NTFY_PARAM = 0xA9

    /** Requêtes de lecture et arrêt à distance, absentes de la liste d'origine. */
    const val CMD_POWER_SET_STATUS = 0x24
    const val CMD_AUDIO_GET_PARAM = 0xE6
    const val CMD_SYSTEM_GET_PARAM = 0xF6

    /** AUDIO */
    const val CMD_AUDIO_RET_PARAM = 0xE7
    const val CMD_AUDIO_SET_PARAM = 0xE8
    const val CMD_AUDIO_NTFY_PARAM = 0xE9

    /** SYSTEM */
    const val CMD_SYSTEM_RET_PARAM = 0xF7
    const val CMD_SYSTEM_SET_PARAM = 0xF8
    const val CMD_SYSTEM_NTFY_PARAM = 0xF9
    const val CMD_SYSTEM_RET_EXT_PARAM = 0xFB
    const val CMD_SYSTEM_SET_EXT_PARAM = 0xFC
    const val CMD_SYSTEM_NTFY_EXT_PARAM = 0xFD

    /**
     * OnOffSettingValue / EnableDisable (v2).
     * ATTENTION : inversé par rapport à v1 (NCASM) ! ON = 0x00, OFF = 0x01.
     */
    const val V2_VALUE_ON = 0x00
    const val V2_VALUE_OFF = 0x01
    const val V2_ENABLE = 0x00
    const val V2_DISABLE = 0x01

    /** PlayInquiredType. */
    const val PLAY_TYPE_PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT = 0x01
    const val PLAY_TYPE_MUSIC_VOLUME = 0x20
    const val PLAY_TYPE_MUSIC_VOLUME_WITH_MUTE = 0x30

    /** PlaybackControl. */
    const val PLAY_CTRL_KEY_OFF = 0x00
    const val PLAY_CTRL_PAUSE = 0x01
    const val PLAY_CTRL_TRACK_UP = 0x02
    const val PLAY_CTRL_TRACK_DOWN = 0x03
    const val PLAY_CTRL_GROUP_UP = 0x04
    const val PLAY_CTRL_GROUP_DOWN = 0x05
    const val PLAY_CTRL_STOP = 0x06
    const val PLAY_CTRL_PLAY = 0x07
    const val PLAY_CTRL_FAST_FORWARD = 0x08
    const val PLAY_CTRL_FAST_REWIND = 0x09
    const val PLAY_VOLUME_MAX = 30

    /** AutoPowerOffElements + PowerInquiredType. */
    const val POWER_TYPE_AUTO_POWER_OFF = 0x04
    const val AUTO_POWER_OFF_5_MIN = 0x00
    const val AUTO_POWER_OFF_30_MIN = 0x01
    const val AUTO_POWER_OFF_60_MIN = 0x02
    const val AUTO_POWER_OFF_180_MIN = 0x03
    const val AUTO_POWER_OFF_15_MIN = 0x04
    const val AUTO_POWER_OFF_DISABLE = 0x11

    /** Le XM6 règle l'extinction auto par le type 0x05 (avec détection du port) ; 0x10 = au retrait du casque. */
    const val POWER_TYPE_AUTO_POWER_OFF_WEARING = 0x05
    const val AUTO_POWER_OFF_WHEN_REMOVED = 0x10
    const val POWER_TYPE_POWER_OFF = 0x03
    const val POWER_OFF_USER = 0x01

    /** Pause à l'ôtage du casque (famille SYSTEM, valeur inversée : 0 = activé). */
    const val SYSTEM_TYPE_PLAYBACK_BY_WEARING = 0x01

    /** Modes d'écoute (famille AUDIO, valeurs inversées : 0 = activé) ; le BGM porte la taille de la pièce. */
    const val AUDIO_TYPE_BGM_MODE = 0x09
    const val AUDIO_TYPE_UPMIX_CINEMA = 0x04
    const val ROOM_SMALL = 0x00
    const val ROOM_MIDDLE = 0x01
    const val ROOM_LARGE = 0x02

    /** EqEbb InquiredType/Preset. */
    const val EQEBB_TYPE_PRESET_EQ = 0x00

    /**
     * Type d'inquiry que le XM6 est seul à comprendre (PRESET_EQ_AND_ERRORCODE) : la requête
     * classique 0x00 reste sans réponse. Format : [cmd, type, préréglage, nbBandes, bandes+6...].
     */
    const val EQEBB_TYPE_PRESET_EQ_AND_ERRORCODE = 0x04

    /** Préréglages acceptés par le XM6 (firmware 3.0.0+), à la place de la table classique ci-dessous. */
    const val EQ_XM6_HEAVY = 0x30
    const val EQ_XM6_CLEAR = 0x31
    const val EQ_XM6_HARD = 0x32
    const val EQ_XM6_SOFT = 0x33
    const val EQ_XM6_CUSTOM = 0xA0

    /** Égaliseur 10 bandes du XM6 : gain de -6 à +6 dB, envoyé avec un décalage de +6. */
    const val EQ_XM6_BAND_COUNT = 10
    const val EQ_BAND_GAIN_MIN = -6
    const val EQ_BAND_GAIN_MAX = 6
    const val EQ_BAND_OFFSET = 6

    /** Attente de la notification après une écriture EQEBB, avant de relire l'état (le XM6 ne la renvoie pas toujours). */
    const val EQ_ECHO_WAIT_MS = 1500L

    /** Attente de la notification après l'écriture d'un réglage (onglet Réglages) ; elle ne vient pas toujours. */
    const val SETTING_REPLY_WAIT_MS = 1500L

    /** Attente maximale des notifications qui confirment un réglage écrit, avant de le relire explicitement. */
    const val SETTING_CONFIRM_WAIT_MS = 1200L

    /**
     * Attente de la réponse à une lecture. Après un changement de mode d'écoute, le casque envoie une rafale
     * de notifications spontanées qui retarde ses réponses de plus d'une seconde.
     */
    const val SETTING_READ_WAIT_MS = 4000L

    // Préréglages de la table classique (autres modèles) ; le XM6 les ignore.
    const val EQ_OFF = 0x00
    const val EQ_ROCK = 0x01
    const val EQ_POP = 0x02
    const val EQ_JAZZ = 0x03
    const val EQ_DANCE = 0x04
    const val EQ_EDM = 0x05
    const val EQ_R_AND_B_HIP_HOP = 0x06
    const val EQ_ACOUSTIC = 0x07
    const val EQ_BRIGHT = 0x10
    const val EQ_EXCITED = 0x11
    const val EQ_MELLOW = 0x12
    const val EQ_RELAXED = 0x13
    const val EQ_VOCAL = 0x14
    const val EQ_TREBLE = 0x15
    const val EQ_BASS = 0x16
    const val EQ_SPEECH = 0x17

    /** AudioInquiredType + UpscalingTypeAutoOff (DSEE). */
    const val AUDIO_TYPE_UPSCALING = 0x01
    const val UPSCALING_OFF = 0x00
    const val UPSCALING_AUTO = 0x01

    /** SystemInquiredType. */
    const val SYSTEM_TYPE_SMART_TALKING_MODE_TYPE2 = 0x0C

    /** DetectSensitivity (Speak-to-Chat). */
    const val STC_DETECT_AUTO = 0x00
    const val STC_DETECT_HIGH = 0x01
    const val STC_DETECT_LOW = 0x02

    /** ModeOutTime (Speak-to-Chat). */
    const val STC_OFF_TIME_FAST = 0x00
    const val STC_OFF_TIME_MID = 0x01
    const val STC_OFF_TIME_SLOW = 0x02
    const val STC_OFF_TIME_NONE = 0x03

    /**
     * Réglages de Speak-to-Chat (sensibilité, délai) : `FA 0C` → `FB 0C <s> <t>`, écriture `FC 0C <s> <t>` →
     * `FD 0C <s> <t>`. Essayé sur le XM6 (3.1.5) le 2026-10-24 : le délai change ; la sensibilité n'a changé qu'une
     * fois (auto → haute) puis a ignoré les écritures suivantes, elle n'est donc que lue.
     */
    const val CMD_SYSTEM_GET_EXT_PARAM = 0xFA

    /**
     * Priorité qualité / connexion (AudioInquiredType CONNECTION_MODE) : `E6 00` → `E7 00 <v>`, écriture `E8 00 <v>`.
     * Après l'écriture le casque notifie `E9 05 …` (pas `E9 00`), il faut donc relire. Essayé sur le XM6 le 2026-10-24.
     */
    const val AUDIO_TYPE_CONNECTION_MODE = 0x00
    const val PRIORITY_SOUND_QUALITY = 0x00
    const val PRIORITY_CONNECTION = 0x01

    /**
     * Guidage vocal (table 2) : `46 01` → `47 01 <v> <langue>` (v : 0 = activé), écriture `48 01 <v>` → `49 01 <v>` ;
     * volume `46 20` → `47 20 <v>` (signé, -2 à +2), écriture `48 20 <v> <bip>` → `49 20 <v>` (bip 1 = sans bip de
     * confirmation). Lecture, écriture, relecture et retour à la valeur d'origine essayés sur le XM6 le 2026-10-24.
     */
    const val CMD_VOICE_GUIDANCE_GET_PARAM = 0x46
    const val CMD_VOICE_GUIDANCE_RET_PARAM = 0x47
    const val CMD_VOICE_GUIDANCE_SET_PARAM = 0x48
    const val CMD_VOICE_GUIDANCE_NTFY_PARAM = 0x49
    const val VOICE_GUIDANCE_TYPE_ON_OFF = 0x01
    const val VOICE_GUIDANCE_TYPE_VOLUME = 0x20
    const val VOICE_GUIDANCE_VOLUME_MIN = -2
    const val VOICE_GUIDANCE_VOLUME_MAX = 2

    /** NcAsmInquiredType. */
    const val NCASM_TYPE_DUAL_NC_ASM = 0x17
    const val NCASM_TYPE_DUAL_NC_ASM_NA = 0x19 // XM6 : gestion auto du son ambiant
    const val NCASM_TYPE_ASM_SEAMLESS = 0x22
    const val NCASM_TYPE_BUTTON = 0x30 // NC_AMB_TOGGLE : modes que le bouton de bruit fait défiler

    /** Function du bouton de bruit (NC_AMB_TOGGLE) ; 0x01 et 0x02 vérifiés sur un WH-1000XM6, 0x03 et 0x04 non essayés. */
    const val NC_BUTTON_ALL = 0x01 // bruit, ambiant, désactivé
    const val NC_BUTTON_NC_ASM = 0x02 // bruit et ambiant
    const val NC_BUTTON_NC_OFF = 0x03 // bruit et désactivé
    const val NC_BUTTON_ASM_OFF = 0x04 // ambiant et désactivé

    /** NcAsmOnOffValue. */
    const val NCASM_VALUE_OFF = 0
    const val NCASM_VALUE_ON = 1

    /** NcAsmMode. */
    const val NCASM_MODE_NC = 0
    const val NCASM_MODE_ASM = 1

    /** AmbientSoundMode. */
    const val NCASM_AMBIENT_NORMAL = 0
    const val NCASM_AMBIENT_VOICE = 1

    /** NoiseAdaptiveSensitivity (XM6 auto ambient). */
    const val NCASM_NOISE_ADAPTIVE_STANDARD = 0
    const val NCASM_NOISE_ADAPTIVE_HIGH = 1
    const val NCASM_NOISE_ADAPTIVE_LOW = 2

    /** ValueChangeStatus. */
    const val NCASM_STATUS_CHANGED = 1

    const val AMBIENT_LEVEL_MAX = 20
    const val AMBIENT_LEVEL_DEFAULT = 20

    /** DeviceInfoType. */
    const val DEVICE_INFO_MODEL_NAME = 0x01
    const val DEVICE_INFO_FW_VERSION = 0x02
    const val DEVICE_INFO_SERIES_AND_COLOR = 0x03

    /** PowerInquiredType. */
    const val POWER_BATTERY = 0x00
    const val POWER_LEFT_RIGHT_BATTERY = 0x01
    const val POWER_CRADLE_BATTERY = 0x02

    /** BatteryChargingStatus. */
    const val CHARGING_NOT_CHARGING = 0
    const val CHARGING_CHARGING = 1
    const val CHARGING_UNKNOWN = 2
    const val CHARGING_CHARGED = 3

    /**
     * Commands Table 2 (v2) — extraits de ProtocolV2T2.hpp. Envoyées dans des trames
     * TYPE_DATA_MDR_NO2. Gestion des appareils appairés / multipoint (PERI_*).
     */
    const val CMD_PERI_GET_STATUS = 0x32
    const val CMD_PERI_RET_STATUS = 0x33
    const val CMD_PERI_SET_STATUS = 0x34
    const val CMD_PERI_NTFY_STATUS = 0x35
    const val CMD_PERI_GET_PARAM = 0x36
    const val CMD_PERI_RET_PARAM = 0x37
    const val CMD_PERI_NTFY_PARAM = 0x39
    const val CMD_PERI_SET_EXT_PARAM = 0x3C
    const val CMD_PERI_NTFY_EXT_PARAM = 0x3D

    /** MessageMdrV2FunctionType_Table2 (support de la gestion des appareils). */
    const val T2_FUNC_PAIRING_CLASSIC_BT = 0x30
    const val T2_FUNC_PAIRING_WITH_COD_CLASSIC_BT = 0x32
    const val T2_FUNC_PAIRING_WITH_COD_CLASSIC_LE = 0x33

    /** PeripheralInquiredType. */
    const val PERI_TYPE_PAIRING_CLASSIC_BT = 0x00
    const val PERI_TYPE_SOURCE_SWITCH = 0x01
    const val PERI_TYPE_PAIRING_WITH_COD = 0x02

    /** PeripheralBluetoothMode. */
    const val BT_MODE_NORMAL = 0x00
    const val BT_MODE_INQUIRY_SCAN = 0x01

    /** ConnectivityActionType. */
    const val CONN_ACTION_DISCONNECT = 0x00
    const val CONN_ACTION_CONNECT = 0x01
    const val CONN_ACTION_UNPAIR = 0x02

    const val MAC_ADDRESS_LENGTH = 17
    const val PERIPHERAL_ACTION_TIMEOUT_MS = 25_000

    /** PeripheralResult : le quartet bas vaut 2 tant que l'opération est en cours. */
    const val PERIPHERAL_IN_PROGRESS = 0x02
    const val PERIPHERAL_BUSY = 0x03

    /** SourceSwitchControlResult. */
    const val SOURCE_SWITCH_SUCCESS = 0x00
    const val SOURCE_SWITCH_FAIL = 0x01
    const val SOURCE_SWITCH_FAIL_CALLING = 0x02
    const val SOURCE_SWITCH_FAIL_A2DP_NOT_CONNECT = 0x03
    const val SOURCE_SWITCH_FAIL_VOICE_ASSISTANT = 0x04
}