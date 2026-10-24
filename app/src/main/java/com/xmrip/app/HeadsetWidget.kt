package com.xmrip.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.xmrip.app.protocol.MdrSession

/**
 * Widget d'écran d'accueil : batterie et mode de bruit connus (voir [HeadsetCache]), et trois boutons pour changer
 * de mode sans ouvrir l'app. Un appui ailleurs ouvre XmRip.
 */
class HeadsetWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_SET_MODE = "com.xmrip.app.widget.SET_MODE"
        private const val EXTRA_MODE = "mode"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HeadsetWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context, null)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context, pendingMode: Int?): RemoteViews {
            val snap = HeadsetCache.load(context)
            val mode = pendingMode ?: snap.mode
            val views = RemoteViews(context.packageName, R.layout.widget_headset)
            views.setTextViewText(R.id.widget_name, snap.name ?: "XmRip")
            views.setTextViewText(
                R.id.widget_battery,
                snap.battery?.let { "$it %" + if (snap.charging) " ⚡" else "" } ?: "— %",
            )
            views.setTextViewText(R.id.widget_mode, if (pendingMode != null) "Envoi…" else HeadsetCache.modeLabel(mode))
            val buttons = listOf(
                R.id.widget_nc to MdrSession.MODE_NC,
                R.id.widget_amb to MdrSession.MODE_AMBIENT,
                R.id.widget_off to MdrSession.MODE_OFF,
            )
            for ((id, m) in buttons) {
                views.setInt(id, "setBackgroundResource", if (m == mode) R.drawable.widget_btn_on else R.drawable.widget_btn)
                val intent = Intent(context, HeadsetWidget::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, m)
                views.setOnClickPendingIntent(
                    id,
                    PendingIntent.getBroadcast(context, 100 + m, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        private fun showPending(context: Context, mode: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HeadsetWidget::class.java))
            val views = build(context, mode)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = build(context, null)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_MODE) {
            super.onReceive(context, intent)
            return
        }
        val mode = intent.getIntExtra(EXTRA_MODE, -1).takeIf { it >= 0 } ?: return
        val app = context.applicationContext
        showPending(app, mode)
        val pending = goAsync()
        Thread {
            try {
                val result = QuickMode.apply(app, mode)
                if (!result.ok) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(app, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                refreshAll(app)
                pending.finish()
            }
        }.start()
    }
}
