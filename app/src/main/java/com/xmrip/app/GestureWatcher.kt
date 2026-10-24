package com.xmrip.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.HeadsetState
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.PairedDevicesInfo
import com.xmrip.app.protocol.SettingsState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Garde la session de contrôle du casque ouverte tant qu'une action de geste est choisie ([GestureActions]), avec le
 * journal d'événements du casque activé, pour recevoir ses gestes et lancer l'action du téléphone.
 *
 * Comme [PcWatcher] il n'y a aucun service de premier plan : la surveillance vit dans le processus de l'app, que le
 * système garde en vie tant que « l'accès aux notifications » est accordé ([MediaListenerService]). Le casque n'a
 * qu'une session de contrôle : tant que celle-ci est tenue, l'appli Windows ne peut pas s'y connecter ; si un autre
 * client la prend, elle est reprise plus tard, de moins en moins souvent.
 */
object GestureWatcher {

    private const val TAG = "XmRip-GestureWatch"
    private const val PERIOD_MS = 15_000L
    private const val MAX_BACKOFF_MS = 120_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xmrip-gesturewatch").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var appContext: Context? = null
    private var failures = 0
    private var nextAttemptAt = 0L

    private val listener = object : MdrSession.StateListener {
        override fun onReport(report: BatteryReport) {}

        override fun onSettings(settings: SettingsState) {}

        override fun onDevices(info: PairedDevicesInfo) {}

        override fun onGesture(gesture: String) {
            val context = appContext ?: return
            scheduler.execute { GestureActions.onGesture(context, gesture) }
        }
    }

    /** Lance la surveillance si une action est choisie (et l'arrête sinon). Sans effet si elle tourne déjà. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!GestureActions.anyEnabled(app)) {
            stop(app)
            return
        }
        appContext = app
        HeadphoneClient.keepOpen = true
        HeadphoneClient.addListener(listener)
        if (task != null) return
        failures = 0
        nextAttemptAt = 0L
        task = scheduler.scheduleWithFixedDelay({ tick(app) }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    /** Arrête la surveillance si plus aucune action n'est choisie ; la session retombe sur sa fermeture normale. */
    @Synchronized
    fun stop(context: Context) {
        if (GestureActions.anyEnabled(context.applicationContext)) return
        task?.cancel(false)
        task = null
        HeadphoneClient.keepOpen = false
        HeadphoneClient.removeListener(listener)
    }

    /** Reprend tout de suite la connexion (après un changement de réglage), sans attendre le prochain passage. */
    fun retryNow() {
        nextAttemptAt = 0L
        failures = 0
        val app = appContext ?: return
        scheduler.execute { tick(app) }
    }

    @Suppress("MissingPermission")
    private fun tick(context: Context) {
        try {
            if (!GestureActions.anyEnabled(context)) {
                stop(context)
                return
            }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val headset = LastDevice.load(context, adapter)
            if (headset == null) {
                GestureActions.setNote(context, "Casque inconnu : ouvre XmRip une fois")
                return
            }
            if (!HeadsetState.connectedToPhone(context, adapter)) return
            if (HeadphoneClient.isLive(headset)) {
                failures = 0
                return
            }
            if (System.currentTimeMillis() < nextAttemptAt) return
            if (HeadphoneClient.openForGestures(headset)) {
                failures = 0
                Log.d(TAG, "Session ouverte, journal du casque actif")
            } else {
                failures++
                val delay = (PERIOD_MS shl failures.coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
                nextAttemptAt = System.currentTimeMillis() + delay
                Log.d(TAG, "Casque injoignable (un autre client tient la session ?), nouvel essai dans ${delay / 1000} s")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Surveillance : ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
