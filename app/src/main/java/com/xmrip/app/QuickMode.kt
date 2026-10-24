package com.xmrip.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.HeadsetState
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.MdrSession

/**
 * Changement de mode de bruit sans ouvrir l'app (raccourcis, tuile des réglages rapides, widget) : connexion sans
 * handshake, puis le mode demandé avec le dernier niveau ambiant connu (avant, le raccourci « ambiant » imposait le
 * niveau 20 et coupait l'ambiant automatique). Bloquant : à appeler hors du thread d'interface.
 */
object QuickMode {

    data class Result(val ok: Boolean, val message: String)

    /** Ordre du bouton de bruit du casque : réduction, ambiant, désactivé. */
    fun next(mode: Int?): Int = when (mode) {
        MdrSession.MODE_NC -> MdrSession.MODE_AMBIENT
        MdrSession.MODE_AMBIENT -> MdrSession.MODE_OFF
        else -> MdrSession.MODE_NC
    }

    const val NOT_CONNECTED = "Casque non connecté au téléphone : mode inchangé."

    /** Le dernier casque utilisé est-il connecté en audio à ce téléphone ? Faux sans autorisation Bluetooth. */
    fun headsetConnected(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        return runCatching { adapter.isEnabled && HeadsetState.connectedToPhone(context, adapter) }.getOrDefault(false)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun apply(context: Context, mode: Int): Result {
        if (!hasPermission(context)) return Result(false, "Ouvre XmRip une fois pour autoriser le Bluetooth.")
        val app = context.applicationContext
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return Result(false, "Bluetooth indisponible.")
        if (!adapter.isEnabled) return Result(false, "Le Bluetooth est désactivé.")
        // Casque absent ou connecté ailleurs : on ne tente rien (avant, la tentative le reconnectait au téléphone ou
        // échouait au bout de plusieurs secondes).
        if (!headsetConnected(app)) return Result(false, NOT_CONNECTED)
        val cache = HeadsetCache.load(app)
        val auto = mode == MdrSession.MODE_AMBIENT && cache.autoAmbient
        var lastError: Exception? = null
        val candidates: List<BluetoothDevice> = try {
            HeadphoneClient.findCandidates(app, adapter)
        } catch (e: Exception) {
            return Result(false, "Échec : ${e.message ?: e.javaClass.simpleName}")
        }
        for (device in candidates) {
            try {
                val state = HeadphoneClient.connectAndSetNcAsmFast(device, mode, cache.ambientLevel, auto)
                LastDevice.save(app, device)
                HeadsetCache.saveMode(app, state)
                return Result(true, "${HeadsetCache.modeLabel(HeadsetCache.modeOf(state))} · ${device.name ?: "casque"}")
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result(false, "Échec : ${lastError?.message ?: "aucun casque trouvé"}")
    }
}
