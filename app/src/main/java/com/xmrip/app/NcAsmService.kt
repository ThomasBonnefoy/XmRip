package com.xmrip.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.MdrSession

/**
 * Applique un mode NC/Ambiant/Désactivé en arrière-plan (déclenché par les raccourcis).
 * Tourne en service de premier plan : l'écran et l'utilisateur ne sont jamais bloqués.
 */
class NcAsmService : Service() {

    companion object {
        const val ACTION_SET_NC_ASM = "com.xmrip.app.action.SET_NC_ASM"
        const val EXTRA_NC_ASM_MODE = "com.xmrip.app.extra.NC_ASM_MODE"

        private const val CHANNEL_ID = "nc_asm_action"
        private const val NOTIF_ID = 1
        private const val MSG_BUSY = "Action en cours…"

        private val modeLabel = mapOf(
            MdrSession.MODE_NC to "réduction de bruit",
            MdrSession.MODE_AMBIENT to "son ambiant",
            MdrSession.MODE_OFF to "désactivation",
        )

        private val modeDoneLabel = mapOf(
            MdrSession.MODE_NC to "Réduction de bruit activée",
            MdrSession.MODE_AMBIENT to "Son ambiant activé",
            MdrSession.MODE_OFF to "Réduction de bruit désactivée",
        )

        fun start(context: Context, modeValue: String?) {
            val intent = Intent(context, NcAsmService::class.java).apply {
                action = ACTION_SET_NC_ASM
                putExtra(EXTRA_NC_ASM_MODE, modeValue)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Lit le mode depuis un intent ; null si invalide. */
        fun parseModeFromIntent(intent: Intent?): Int? {
            if (intent?.action != ACTION_SET_NC_ASM) return null
            return when (intent.getStringExtra(EXTRA_NC_ASM_MODE)) {
                "noise_cancelling" -> MdrSession.MODE_NC
                "ambient" -> MdrSession.MODE_AMBIENT
                "off" -> MdrSession.MODE_OFF
                else -> null
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = parseModeFromIntent(intent)
        if (mode == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Application de la ${modeLabel[mode] ?: "commande"}…"))

        if (busy) {
            toast(MSG_BUSY)
            return START_NOT_STICKY
        }
        busy = true
        applyMode(mode, startId)
        return START_NOT_STICKY
    }

    private fun applyMode(mode: Int, startId: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        Thread {
            try {
                var applied: BluetoothDevice? = null
                var lastError: Throwable? = null
                val adapterRef: BluetoothAdapter? =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                runCatching { HeadphoneClient.findCandidates(this, adapterRef) }
                    .getOrElse { lastError = it; emptyList() }
                    .forEach { device ->
                        if (applied == null) {
                            try {
                                HeadphoneClient.connectAndSetNcAsmFast(device, mode)
                                applied = device
                            } catch (e: Exception) {
                                lastError = e
                            }
                        }
                    }
                val device = applied
                val message = if (device == null) {
                    "Échec : ${lastError?.message ?: "aucun casque trouvé"}"
                } else {
                    LastDevice.save(this, device)
                    "${modeDoneLabel[mode] ?: "Mode appliqué"} sur ${device.name ?: "le casque"}"
                }
                uiHandler.post {
                    notificationManager.notify(NOTIF_ID, buildNotification(message))
                    toast(message)
                    uiHandler.postDelayed({
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }, 1_500)
                }
            } finally {
                busy = false
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(this)
        }
        return builder
            .setContentTitle("XmRip")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Actions rapides",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun toast(message: String) {
        uiHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}