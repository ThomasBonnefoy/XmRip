package com.xmrip.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

/**
 * Les trois raccourcis de l'icône (appui long) et des routines : réduction de bruit, son ambiant, désactivé.
 *
 * Ils étaient déclarés dans le manifeste (`xml/shortcuts.xml`) ; Android lance alors leur activité avec l'option
 * « tâche au-dessus de l'accueil » (`FLAG_ACTIVITY_TASK_ON_HOME`, vu dans le journal du téléphone le 2026-10-25) : une
 * routine Samsung qui les déclenchait renvoyait sur l'écran d'accueil au lieu de laisser l'appli en cours. Publiés ici
 * avec seulement `NEW_TASK` et `NO_ANIMATION`, ils se referment sur l'appli qui était affichée. Mêmes identifiants
 * qu'avant (`mode_nc`, `mode_ambient`, `mode_off`) pour que les routines existantes les retrouvent.
 */
object Shortcuts {
    private const val TAG = "XmRip-Shortcuts"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val specs = listOf(
            Spec("mode_nc", "noise_cancelling", R.string.shortcut_mc_short, R.string.shortcut_mc_long, R.drawable.ic_shortcut_nc),
            Spec("mode_ambient", "ambient", R.string.shortcut_ambient_short, R.string.shortcut_ambient_long, R.drawable.ic_shortcut_ambient),
            Spec("mode_off", "off", R.string.shortcut_off_short, R.string.shortcut_off_long, R.drawable.ic_shortcut_off),
        )
        val shortcuts = specs.mapIndexed { rank, s ->
            val intent = Intent(NcAsmService.ACTION_SET_NC_ASM)
                .setClassName(context.packageName, NcAsmActionActivity::class.java.name)
                .putExtra(NcAsmService.EXTRA_NC_ASM_MODE, s.value)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ShortcutInfo.Builder(context, s.id)
                .setShortLabel(context.getString(s.shortLabel))
                .setLongLabel(context.getString(s.longLabel))
                .setIcon(Icon.createWithResource(context, s.icon))
                .setRank(rank)
                .setIntent(intent)
                .build()
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
            .onFailure { Log.w(TAG, "Publication des raccourcis impossible : ${it.message}") }
    }

    private class Spec(val id: String, val value: String, val shortLabel: Int, val longLabel: Int, val icon: Int)
}
