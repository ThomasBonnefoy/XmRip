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