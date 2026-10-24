package com.xmrip.app

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.xmrip.app.bt.HeadsetState

/**
 * « Son des notifications coupé quand l'ordinateur est connecté au casque ».
 *
 * Quand le PC et le téléphone sont tous deux connectés au casque (multipoint), le son d'une notification du téléphone
 * passe dans le casque, par-dessus ce qu'on écoute sur le PC, et coupe la musique ou la vidéo du téléphone le temps
 * du son. Aucun réglage Android ne dépend de l'état du PC : c'est le casque qui sait quels appareils lui sont
 * connectés (liste multipoint, lue par [PcWatcher]). Si le téléphone lit lui-même du son dans le casque, le volume
 * reste en place : les notifications passent.
 *
 * Le son est coupé en mettant le volume des notifications à 0 : Android ne joue alors pas le son d'une notification
 * (ni sa demande de focus audio), mais les vibrations et les pop-ups restent. Un mode « Ne pas déranger » coupait aussi
 * les vibrations et les pop-ups (essayé le 2026-10-21, Thomas n'en voulait pas). Le volume d'avant est mémorisé et
 * rétabli quand l'ordinateur se déconnecte.
 */
object PcQuiet {

    private const val TAG = "XmRip-PcQuiet"
    private const val PREFS = "pc_quiet"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_RULE = "rule_id"
    private const val KEY_NOTE = "note"
    private const val KEY_SILENCED = "silenced"
    private const val KEY_SAVED_VOLUME = "saved_volume"
    private const val KEY_LAST_AUDIBLE = "last_audible_volume"
    private const val KEY_ACCESS_NEEDED = "access_needed"
    private const val KEY_PC_CONNECTED = "pc_connected"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun watchedAddress(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun watchedName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun setWatched(context: Context, address: String, name: String) {
        prefs(context).edit().putString(KEY_ADDRESS, address.uppercase()).putString(KEY_NAME, name).apply()
    }

    /** La surveillance de l'ordinateur sert à deux fonctions : couper le son des notifications, et savoir quand les lire. */
    fun watchNeeded(context: Context): Boolean =
        (isEnabled(context) || NotificationReader.isEnabled(context)) && watchedAddress(context) != null

    /** Dernier état connu de l'ordinateur (vrai = connecté au casque), tenu à jour par [PcWatchService]. */
    fun isPcConnected(context: Context): Boolean = prefs(context).getBoolean(KEY_PC_CONNECTED, false)

    fun setPcConnected(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_PC_CONNECTED, connected).apply()
    }

    /**
     * Le téléphone lit-il du son (musique, vidéo, podcast) dans le casque ? Dans ce cas Thomas écoute le téléphone, et
     * les notifications peuvent passer même si l'ordinateur est connecté au casque.
     */
    fun phonePlaying(context: Context): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (!HeadsetState.connectedToPhone(context, adapter)) return false
        return context.getSystemService(AudioManager::class.java).isMusicActive
    }

    /** Dernier état décrit par le service, affiché dans l'app. */
    fun note(context: Context): String? = prefs(context).getString(KEY_NOTE, null)

    fun setNote(context: Context, text: String) {
        prefs(context).edit().putString(KEY_NOTE, text).apply()
    }

    /** Vrai si Android a refusé de changer le volume des notifications faute d'accès « Ne pas déranger ». */
    fun accessNeeded(context: Context): Boolean = prefs(context).getBoolean(KEY_ACCESS_NEEDED, false)

    /**
     * Coupe ou rétablit le son des notifications. Sans effet si déjà dans l'état demandé. Renvoie faux si Android
     * refuse (certains appareils exigent l'accès « Ne pas déranger » pour toucher à ce volume) : l'appelant réessaiera.
     */
    fun setSilent(context: Context, silent: Boolean): Boolean {
        removeLegacyRule(context)
        val p = prefs(context)
        return try {
            val audio = context.getSystemService(AudioManager::class.java)
            if (silent) {
                if (!p.getBoolean(KEY_SILENCED, false)) {
                    val current = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                    val edit = p.edit().putBoolean(KEY_SILENCED, true).putInt(KEY_SAVED_VOLUME, current)
                    // Un volume déjà à 0 ne vaut pas la peine d'être rétabli : on garde le dernier volume audible.
                    if (current > 0) edit.putInt(KEY_LAST_AUDIBLE, current)
                    edit.apply()
                }
                // Répété à chaque passage : le volume est mémorisé par sortie audio, et celle-ci change quand le
                // casque se connecte ou se déconnecte.
                audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                Log.d(TAG, "Son des notifications coupé")
            } else if (p.getBoolean(KEY_SILENCED, false)) {
                // Volume d'avant, sinon le dernier audible, sinon la moitié du maximum.
                val saved = p.getInt(KEY_SAVED_VOLUME, 0).takeIf { it > 0 }
                    ?: p.getInt(KEY_LAST_AUDIBLE, 0).takeIf { it > 0 }
                    ?: (audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) / 2).coerceAtLeast(1)
                // Si Thomas a remonté le volume lui-même entre-temps, on n'y touche pas.
                if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
                    audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, saved, 0)
                }
                p.edit().putBoolean(KEY_SILENCED, false).apply()
                Log.d(TAG, "Son des notifications rétabli")
            }
            p.edit().putBoolean(KEY_ACCESS_NEEDED, false).apply()
            true
        } catch (e: SecurityException) {
            p.edit().putBoolean(KEY_ACCESS_NEEDED, true).apply()
            Log.w(TAG, "Volume des notifications refusé : ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Volume des notifications impossible : ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /**
     * Supprime la règle « Ne pas déranger » créée par une version précédente (elle coupait aussi vibrations et
     * pop-ups) : sa suppression éteint le mode qu'elle avait activé.
     */
    fun removeLegacyRule(context: Context) {
        val p = prefs(context)
        val id = p.getString(KEY_RULE, null) ?: return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.removeAutomaticZenRule(id)
        }
        p.edit().remove(KEY_RULE).apply()
    }
}
