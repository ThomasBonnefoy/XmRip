package com.xmrip.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Actions du téléphone déclenchées par les gestes du bouton de bruit (double et triple appui), annoncés par le casque
 * dans son journal d'événements. Réglages et dernier résultat gardés sur le téléphone seulement.
 */
object GestureActions {

    private const val TAG = "XmRip-Gesture"
    private const val PREFS = "gesture_actions"
    private const val KEY_NOTE = "note"

    const val DOUBLE = "keyCustomBtnTwo"
    const val TRIPLE = "keyCustomBtnThree"

    const val NONE = "none"
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val LAUNCH = "launch"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun action(context: Context, gesture: String): String = prefs(context).getString("$gesture.action", NONE) ?: NONE

    fun target(context: Context, gesture: String): String = prefs(context).getString("$gesture.target", "") ?: ""

    fun set(context: Context, gesture: String, action: String, target: String = "") {
        prefs(context).edit().putString("$gesture.action", action).putString("$gesture.target", target).apply()
    }

    /** Vrai dès qu'un geste a une action : la session avec le casque doit alors rester ouverte ([GestureWatcher]). */
    fun anyEnabled(context: Context): Boolean = listOf(DOUBLE, TRIPLE).any { action(context, it) != NONE }

    /** Dernier événement (geste reçu, action lancée ou erreur), pour la carte de réglage. */
    fun note(context: Context): String = prefs(context).getString(KEY_NOTE, "") ?: ""

    fun setNote(context: Context, text: String) {
        prefs(context).edit().putString(KEY_NOTE, text).apply()
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())

    /** Texte du choix courant d'un geste, pour le bouton de la carte. */
    fun label(context: Context, gesture: String): String = when (action(context, gesture)) {
        PLAY_PAUSE -> "Lecture / pause"
        NEXT -> "Piste suivante"
        PREVIOUS -> "Piste précédente"
        LAUNCH -> "Ouvrir : " + appLabel(context, target(context, gesture))
        else -> "Aucune action"
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrDefault(packageName.ifEmpty { "application non choisie" })

    private fun describe(gesture: String) = when (gesture) {
        DOUBLE -> "double appui du bouton de bruit"
        TRIPLE -> "triple appui du bouton de bruit"
        else -> gesture
    }

    /** Exécute l'action d'un geste annoncé par le casque (hors fil d'interface). */
    fun onGesture(context: Context, gesture: String) {
        val action = action(context, gesture)
        if (action == NONE) return
        val error = try {
            when (action) {
                PLAY_PAUSE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                NEXT -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                PREVIOUS -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                LAUNCH -> launch(context, target(context, gesture))
                else -> null
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        val text = "${now()} : ${describe(gesture)}, " + (error ?: "action lancée (${label(context, gesture)})")
        Log.d(TAG, text)
        setNote(context, text)
    }

    private fun mediaKey(context: Context, code: Int): String? {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    /**
     * Ouvre une application. Depuis l'arrière-plan, Android 10 et plus bloque l'ouverture d'une activité sauf si
     * l'appli a l'autorisation « Afficher par-dessus les autres applis ».
     */
    private fun launch(context: Context, packageName: String): String? {
        if (packageName.isEmpty()) return "aucune application choisie"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "application introuvable ($packageName)"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) {
            return "ouverture demandée, mais Android la bloque sans l'autorisation « Afficher par-dessus les autres applis »"
        }
        return null
    }
}
