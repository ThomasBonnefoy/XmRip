package com.xmrip.app.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.MdrProtocolException
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.NcAsmState
import java.util.UUID

/**
 * Connexion RFCOMM au service "Serial HPC" du casque et exécution de la session MDR.
 */
object HeadphoneClient {

    val SERVICE_UUID: UUID = UUID.fromString(MdrConstants.SERVICE_UUID)

    private const val UUID_WAIT_TIMEOUT_MS = 4_000L

    /** Candidat idoine : appareil classique/dual appairé avec un nom. */
    fun isCandidate(device: BluetoothDevice): Boolean {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return false
        return !device.name.isNullOrBlank()
    }

    /**
     * Liste des candidats dans l'ordre de tentative :
     * dernier casque connecté (mémoire), sinon scan SDP filtré sur le service MDR,
     * sinon tous les appareils appairés triés « Sony d'abord ».
     */
    @Suppress("MissingPermission")
    fun findCandidates(context: Context, btAdapter: BluetoothAdapter?): List<BluetoothDevice> {
        if (btAdapter == null) return emptyList()
        LastDevice.load(context, btAdapter)?.let { return listOf(it) }
        val bonded = btAdapter.bondedDevices
        if (bonded == null || bonded.isEmpty()) return emptyList()
        val pre = bonded.filter { isCandidate(it) }
        if (pre.isEmpty()) return emptyList()

        val uuids = HashMap<String, List<ParcelUuid>>()
        for (device in pre) {
            runCatching { device.uuids?.toList() }?.getOrNull()?.let { uuids[device.address] = it }
        }
        if (pre.any { !uuids.containsKey(it.address) }) {
            runCatching { pre.forEach { it.fetchUuidsWithSdp() } }
            val deadline = System.currentTimeMillis() + UUID_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                pre.forEach { device ->
                    if (!uuids.containsKey(device.address)) {
                        runCatching { device.uuids?.toList() }?.getOrNull()
                            ?.let { uuids[device.address] = it }
                    }
                }
                Thread.sleep(200)
            }
        }
        val sony = pre.filter {
            uuids[it.address]?.any { u -> u.uuid == SERVICE_UUID } == true
        }
        return if (sony.isNotEmpty()) sortSonyFirst(sony) else sortSonyFirst(pre)
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
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attempt(device)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    /**
     * Se connecte, se synchronise (batterie + état NC/ASM), applique le mode demandé
     * puis renvoie le rapport avec l'état mis à jour.
     * @param mode MdrSession.MODE_NC, MODE_AMBIENT ou MODE_OFF.
     */
    @Suppress("MissingPermission")
    fun connectAndSetNcAsm(
        device: BluetoothDevice,
        mode: Int,
        ambientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT,
        autoEnabled: Boolean = false,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setNcAsm(mode, ambientLevel, autoEnabled) }
    }

    /** Règle le niveau de son ambiant (1..20) puis renvoie le rapport avec l'état confirmé. */
    @Suppress("MissingPermission")
    fun connectAndSetAmbientLevel(
        device: BluetoothDevice,
        level: Int,
        autoEnabled: Boolean,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setAmbientLevel(level, autoEnabled) }
    }

    /** Active/désactive le son ambiant automatique puis renvoie le rapport avec l'état confirmé. */
    @Suppress("MissingPermission")
    fun connectAndSetAutoAmbient(
        device: BluetoothDevice,
        enabled: Boolean,
        level: Int,
    ): BatteryReport {
        return retrySetNcAsm(device) { it.setAutoAmbient(enabled, level) }
    }

    /**
     * Version rapide pour les raccourcis : handshake minimal (pas de battery/device info)
     * puis application du mode. Ne renvoie que l'état NC/ASM confirmé.
     */
    @Suppress("MissingPermission")
    fun connectAndSetNcAsmFast(device: BluetoothDevice, mode: Int): NcAsmState {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attemptSetNcAsmFast(device, mode)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    private fun attempt(device: BluetoothDevice): BatteryReport {
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

    private fun attemptSetNcAsm(device: BluetoothDevice, apply: (MdrSession) -> NcAsmState): BatteryReport {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            val session = MdrSession(socket.inputStream, socket.outputStream)
            val report = session.handshakeAndBattery()
            val updated = apply(session)
            return report.copy(ncAsm = updated)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun retrySetNcAsm(device: BluetoothDevice, apply: (MdrSession) -> NcAsmState): BatteryReport {
        var lastError: Exception? = null
        repeat(2) {
            try {
                return attemptSetNcAsm(device, apply)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1_000)
            }
        }
        throw lastError ?: MdrProtocolException("Connexion impossible")
    }

    private fun attemptSetNcAsmFast(device: BluetoothDevice, mode: Int): NcAsmState {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            val session = MdrSession(socket.inputStream, socket.outputStream)
            return session.handshakeAndSetNcAsm(mode)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}