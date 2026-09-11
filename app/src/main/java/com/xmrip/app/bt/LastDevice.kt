package com.xmrip.app.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Mémorise l'adresse du dernier casque connecté avec succès pour sauter le scan SDP
 * aux lancements suivants (raccourcis notamment).
 */
object LastDevice {

    private const val PREFS = "last_device"
    private const val KEY_ADDRESS = "address"

    fun save(context: Context, device: BluetoothDevice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, device.address)
            .apply()
    }

    @Suppress("MissingPermission")
    fun load(context: Context, adapter: BluetoothAdapter?): BluetoothDevice? {
        if (adapter == null) return null
        val address = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null) ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        val bonded = bufferCopy(adapter)
        return if (bonded.contains(device)) device else null
    }

    private fun bufferCopy(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        runCatching { adapter.bondedDevices?.toSet() }.getOrDefault(emptySet()).orEmpty()
}