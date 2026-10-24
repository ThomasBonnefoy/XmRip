package com.xmrip.app

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * Surveille la liste des appareils connectés au casque et coupe le son des notifications du téléphone tant que
 * l'ordinateur choisi en fait partie (voir [PcQuiet]), sauf si le téléphone lui-même lit du son dans le casque.
 *
 * Aucun service de premier plan, donc aucune notification permanente : la surveillance tourne dans le processus de
 * l'app, que le système garde en vie tant que « l'accès aux notifications » est accordé ([MediaListenerService], qui la
 * relance à chaque connexion). Sans cet accès elle ne tourne que pendant que l'app est ouverte.
 *
 * Toutes les [PERIOD_MS], si le casque est connecté au téléphone, une connexion courte lit la liste, puis se ferme
 * (la session de contrôle du casque est à un seul client : l'appli Windows en a besoin). Si le casque ne répond pas,
 * un autre client la tient probablement, et l'état précédent reste valable. Quand l'app a une session ouverte, le
 * casque annonce lui-même chaque changement de la liste : la réaction est alors immédiate. Le début et la fin d'une
 * lecture sur le téléphone sont signalés par Android (Android 8 et plus).
 */
object PcWatcher {

    private const val TAG = "XmRip-PcWatch"
    private const val PERIOD_MS = 30_000L
    private const val PLAYBACK_SETTLE_MS = 400L

    /** Ancienne version : service de premier plan et sa notification permanente. */
    private const val LEGACY_CHANNEL_ID = "pc_watch"
    private const val LEGACY_NOTIF_ID = 2

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xmrip-pcwatch").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var appContext: Context? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** Dernier état appliqué (vrai = notifications coupées) ; null tant que rien n'a été appliqué. */
    @Volatile
    private var silent: Boolean? = null

    private val listener = object : MdrSession.StateListener {
        override fun onReport(report: BatteryReport) {}

        override fun onSettings(settings: SettingsState) {}

        override fun onDevices(info: PairedDevicesInfo) {
            val context = appContext ?: return
            scheduler.execute { decide(context, pcConnected(context, info)) }
        }
    }

    /** Lance la surveillance si elle est nécessaire (et l'arrête sinon). Sans effet si elle tourne déjà. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!PcQuiet.watchNeeded(app)) {
            stop(app)
            return
        }
        appContext = app
        removeLegacyNotification(app)
        PcQuiet.removeLegacyRule(app) // ancienne version : mode « Ne pas déranger », éteint par sa suppression
        if (task != null) return
        HeadphoneClient.addListener(listener)
        registerPlaybackCallback(app)
        task = scheduler.scheduleWithFixedDelay({ tick(app) }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    /** Arrête la surveillance si plus aucune fonction n'en a besoin, et rétablit le volume. */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        if (PcQuiet.watchNeeded(app)) return
        task?.cancel(false)
        task = null
        HeadphoneClient.removeListener(listener)
        unregisterPlaybackCallback(app)
        silent = null
        PcQuiet.setSilent(app, false)
        PcQuiet.setPcConnected(app, false)
        PcQuiet.setNote(app, "")
    }

    private fun removeLegacyNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching {
            nm.cancel(LEGACY_NOTIF_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    // --------------------------------------------------------------------------------------------

    private fun registerPlaybackCallback(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || playbackCallback != null) return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                // Android signale la lecture un instant avant que l'état de la sortie soit à jour.
                scheduler.schedule({ reapply(context) }, PLAYBACK_SETTLE_MS, TimeUnit.MILLISECONDS)
            }
        }
        playbackCallback = callback
        context.getSystemService(AudioManager::class.java)
            .registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun unregisterPlaybackCallback(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val callback = playbackCallback ?: return
        context.getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(callback)
        playbackCallback = null
    }

    @Suppress("MissingPermission")
    private fun tick(context: Context) {
        try {
            if (!PcQuiet.watchNeeded(context)) {
                stop(context)
                return
            }
            if (PcQuiet.watchedAddress(context) == null) {
                note(context, "Choisis l'ordinateur à surveiller")
                return
            }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val headset = LastDevice.load(context, adapter)
            if (headset == null) {
                note(context, "Casque inconnu : ouvre XmRip une fois")
                return
            }
            if (!HeadsetState.connectedToPhone(context, adapter)) {
                // Le casque n'est pas sur le téléphone : ses notifications ne passent de toute façon pas dedans.
                decide(context, false, reason = "Casque non connecté au téléphone")
                return
            }
            val info = HeadphoneClient.pollDevices(headset)
            if (info == null) {
                Log.d(TAG, "Casque muet : un autre client tient probablement la session, état conservé")
                return
            }
            decide(context, pcConnected(context, info))
        } catch (e: Exception) {
            Log.w(TAG, "Surveillance : ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun pcConnected(context: Context, info: PairedDevicesInfo): Boolean {
        val watched = PcQuiet.watchedAddress(context) ?: return false
        return info.devices.any { it.address.equals(watched, ignoreCase = true) && it.connected }
    }

    /** Début ou fin d'une lecture sur le téléphone : réapplique l'état connu de l'ordinateur sans interroger le casque. */
    private fun reapply(context: Context) {
        try {
            if (!PcQuiet.watchNeeded(context) || task == null) return
            decide(context, PcQuiet.isPcConnected(context))
        } catch (e: Exception) {
            Log.w(TAG, "Lecture : ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * Retient l'état de l'ordinateur (la lecture des notifications s'en sert) puis, si « couper le son » est activé,
     * applique le volume voulu : coupé tant que l'ordinateur est connecté et que le téléphone ne lit rien dans le
     * casque. Rappelé à chaque passage : le volume dépend de la sortie audio, qui change quand le casque se (dé)connecte.
     */
    private fun decide(context: Context, pcOn: Boolean, reason: String? = null) {
        PcQuiet.setPcConnected(context, pcOn)
        val name = PcQuiet.watchedName(context) ?: "l'ordinateur"
        val state = reason ?: if (pcOn) "$name connecté" else "$name non connecté"
        if (!PcQuiet.isEnabled(context)) {
            if (silent == true) PcQuiet.setSilent(context, false) // option coupée entre-temps : volume rétabli
            silent = false
            note(context, state)
            return
        }
        val playing = pcOn && PcQuiet.phonePlaying(context)
        val wantSilent = pcOn && !playing
        val message = when {
            wantSilent && reason == null -> "$state : son des notifications coupé"
            playing -> "$state, mais le téléphone lit dans le casque : notifications audibles"
            else -> state
        }
        if (silent == wantSilent && !wantSilent) {
            note(context, message)
            return
        }
        if (PcQuiet.setSilent(context, wantSilent)) {
            silent = wantSilent
            note(context, message)
        } else {
            note(context, "Android refuse de couper le volume : accès « Ne pas déranger » requis")
        }
    }

    private fun note(context: Context, text: String) {
        PcQuiet.setNote(context, text)
    }
}
