package com.xmrip.app.bt

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** État du casque côté téléphone (audio Bluetooth), sans aucun échange avec lui. */
object HeadsetState {

    /**
     * Le dernier casque connu est-il connecté en audio à ce téléphone ? Avant Android 9, l'adresse des sorties audio
     * n'est pas fournie : on suppose que oui.
     */
    @Suppress("MissingPermission")
    fun connectedToPhone(context: Context, adapter: BluetoothAdapter?): Boolean {
        val headset = LastDevice.load(context, adapter) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(headset.address, ignoreCase = true)
        }
    }
}
