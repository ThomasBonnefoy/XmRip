package com.xmrip.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.xmrip.app.protocol.MdrSession

/**
 * Applique un mode NC/Ambiant/Désactivé en arrière-plan (déclenché par les raccourcis), en service de premier plan.
 * Une demande qui arrive pendant une autre est gardée et appliquée ensuite ; le service ne s'arrête qu'une fois la
 * dernière demande traitée (avant, un second raccourci pouvait laisser la notification affichée).
 */
class NcAsmService : Service() {

    companion object {
        const val ACTION_SET_NC_ASM = "com.xmrip.app.action.SET_NC_ASM"
        const val EXTRA_NC_ASM_MODE = "com.xmrip.app.extra.NC_ASM_MODE"

        private const val CHANNEL_ID = "nc_asm_action"
        private const val NOTIF_ID = 1

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
    private var running = false
    private var queuedMode: Int? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val mode = parseModeFromIntent(intent)
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(mode?.let { "${HeadsetCache.modeLabel(it)}…" } ?: "XmRip"))
        if (mode == null) {
            if (!running) finish()
            return START_NOT_STICKY
        }
        if (running) {
            queuedMode = mode
        } else {
            run(mode)
        }
        return START_NOT_STICKY
    }

    private fun run(mode: Int) {
        running = true
        Thread {
            val result = QuickMode.apply(applicationContext, mode)
            uiHandler.post {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(result.message))
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                val next = queuedMode
                queuedMode = null
                if (next != null) {
                    run(next)
                } else {
                    running = false
                    uiHandler.postDelayed({ if (!running) finish() }, 1_200)
                }
            }
        }.start()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(this)
        }
        return builder
            .setContentTitle("XmRip")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_ui_nc)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Actions rapides", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
