package com.xmrip.app

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.protocol.NcAsmState

/**
 * Dernier état connu du casque (batterie, mode de bruit, niveau ambiant), gardé sur le téléphone : l'app l'affiche
 * dès son ouverture, avant la connexion, et la tuile des réglages rapides et le widget s'en servent sans se connecter.
 * Chaque changement réel met à jour la tuile et le widget.
 */
object HeadsetCache {
    private const val PREFS = "headset_cache"

    data class Snapshot(
        val name: String?,
        val firmware: String?,
        val battery: Int?,
        val charging: Boolean,
        /** MdrSession.MODE_* ou null si jamais lu. */
        val mode: Int?,
        val ambientLevel: Int,
        val autoAmbient: Boolean,
        val at: Long,
    )

    fun load(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            name = p.getString("name", null),
            firmware = p.getString("firmware", null),
            battery = p.getInt("battery", -1).takeIf { it >= 0 },
            charging = p.getBoolean("charging", false),
            mode = p.getInt("mode", -1).takeIf { it >= 0 },
            ambientLevel = p.getInt("ambient", MdrConstants.AMBIENT_LEVEL_DEFAULT),
            autoAmbient = p.getBoolean("auto", false),
            at = p.getLong("at", 0L),
        )
    }

    fun modeOf(nc: NcAsmState?): Int? = when {
        nc == null -> null
        !nc.enabled -> MdrSession.MODE_OFF
        nc.mode == MdrConstants.NCASM_MODE_ASM -> MdrSession.MODE_AMBIENT
        else -> MdrSession.MODE_NC
    }

    /** Retient [report] ; ne réveille la tuile et le widget que si batterie, charge ou mode ont changé. */
    fun save(context: Context, name: String?, report: BatteryReport) {
        val before = load(context)
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (name != null) e.putString("name", name)
        report.firmwareVersion?.let { e.putString("firmware", it) }
        e.putInt("battery", report.levelPercent)
        e.putBoolean("charging", report.chargingStatus == MdrConstants.CHARGING_CHARGING)
        e.putLong("at", System.currentTimeMillis())
        report.ncAsm?.let { putMode(e, it) }
        e.apply()
        val after = load(context)
        if (after.copy(at = 0, firmware = null) != before.copy(at = 0, firmware = null)) notifySurfaces(context)
    }

    fun saveMode(context: Context, nc: NcAsmState) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        putMode(e, nc)
        e.putLong("at", System.currentTimeMillis())
        e.apply()
        notifySurfaces(context)
    }

    private fun putMode(e: android.content.SharedPreferences.Editor, nc: NcAsmState) {
        modeOf(nc)?.let { e.putInt("mode", it) }
        // Le niveau manuel n'est retenu qu'en ambiant manuel : en automatique le casque annonce un niveau qu'il choisit.
        if (nc.enabled && nc.mode == MdrConstants.NCASM_MODE_ASM) {
            if (!nc.autoEnabled && nc.ambientLevel in 1..MdrConstants.AMBIENT_LEVEL_MAX) e.putInt("ambient", nc.ambientLevel)
            e.putBoolean("auto", nc.autoEnabled)
        }
    }

    private fun notifySurfaces(context: Context) {
        runCatching { HeadsetWidget.refreshAll(context) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, NcTileService::class.java)) }
    }

    fun modeLabel(mode: Int?): String = when (mode) {
        MdrSession.MODE_NC -> "Réduction de bruit"
        MdrSession.MODE_AMBIENT -> "Son ambiant"
        MdrSession.MODE_OFF -> "Désactivé"
        else -> "Mode inconnu"
    }
}
