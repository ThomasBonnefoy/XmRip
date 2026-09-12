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

    /** EqEbb InquiredType/Preset. */
    const val EQEBB_TYPE_PRESET_EQ = 0x00
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
    const val SYSTEM_TYPE_ASSIGNABLE_SETTINGS = 0x03
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

    /** AssignableSettings Preset. */
    const val ASSIGN_AMBIENT_SOUND_CONTROL = 0x00
    const val ASSIGN_VOLUME_CONTROL = 0x10
    const val ASSIGN_PLAYBACK_CONTROL = 0x20
    const val ASSIGN_TRACK_CONTROL = 0x21
    const val ASSIGN_QUICK_ACCESS = 0x36

    /** NcAsmInquiredType. */
    const val NCASM_TYPE_DUAL_NC_ASM = 0x17
    const val NCASM_TYPE_DUAL_NC_ASM_NA = 0x19 // XM6 : gestion auto du son ambiant
    const val NCASM_TYPE_ASM_SEAMLESS = 0x22

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
}