package com.xmrip.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.xmrip.app.protocol.MdrSession

/**
 * Tuile des réglages rapides : affiche le mode de bruit et la batterie connus, et passe au mode suivant à chaque
 * appui (réduction → ambiant → désactivé, comme le bouton du casque).
 */
class NcTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var sending = false

    override fun onStartListening() {
        super.onStartListening()
        render(HeadsetCache.load(this).mode, null)
    }

    override fun onClick() {
        super.onClick()
        if (!QuickMode.hasPermission(this)) {
            openApp()
            return
        }
        if (sending) return
        val target = QuickMode.next(HeadsetCache.load(this).mode)
        sending = true
        render(target, "Envoi…")
        Thread {
            val result = QuickMode.apply(applicationContext, target)
            main.post {
                sending = false
                render(HeadsetCache.load(this).mode, if (result.ok) null else "Échec")
                if (!result.ok) Toast.makeText(applicationContext, result.message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun render(mode: Int?, note: String?) {
        val tile = qsTile ?: return
        val snap = HeadsetCache.load(this)
        // Casque non connecté au téléphone : tuile grisée, rien à envoyer.
        val connected = !QuickMode.hasPermission(this) || QuickMode.headsetConnected(this)
        tile.label = if (connected) HeadsetCache.modeLabel(mode) else "Casque"
        val subtitle = when {
            !connected -> "Non connecté"
            note != null -> note
            else -> snap.battery?.let { "Batterie $it %" + if (snap.charging) " · charge" else "" } ?: "XmRip"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.state = when {
            !connected -> Tile.STATE_UNAVAILABLE
            mode == MdrSession.MODE_NC || mode == MdrSession.MODE_AMBIENT -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val icon = when (mode) {
            MdrSession.MODE_AMBIENT -> R.drawable.ic_ui_ambient
            MdrSession.MODE_OFF -> R.drawable.ic_ui_off
            else -> R.drawable.ic_ui_nc
        }
        tile.icon = Icon.createWithResource(this, icon)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
