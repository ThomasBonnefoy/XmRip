package com.xmrip.app.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.BatteryReport
import java.util.UUID

/**
 * Connexion RFCOMM au service "Serial HPC" du casque et exécution de la session MDR.
 */
object HeadphoneClient {

    val SERVICE_UUID: UUID = UUID.fromString(MdrConstants.SERVICE_UUID)

    /** Candidat idoine : appareil classique/dual appairé avec un nom. */
    fun isCandidate(device: BluetoothDevice): Boolean {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return false
        return !device.name.isNullOrBlank()
    }

    /** Trie : les casques/enceintes Sony d'abord. */
    fun sortSonyFirst(devices: List<BluetoothDevice>): List<BluetoothDevice> {
        return devices.sortedByDescending { score(deviceNameSony(it)) }
    }

    private fun score(name: String): Int {
        return when {
            "WH" in name && "XM" in name -> 8
            "XM" in name -> 6
            "WH" in name || "WF" in name -> 4
            "SONY" in name || "Sony" in name || "LINKBUDS" in name.uppercase() -> 2
            else -> 0
        }
    }

    private fun deviceNameSony(device: BluetoothDevice): String = device.name ?: ""

    @Suppress("MissingPermission")
    fun connectAndReadBattery(device: BluetoothDevice): BatteryReport {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            val session = MdrSession(socket.inputStream, socket.outputStream)
            return session.handshakeAndBattery()
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}