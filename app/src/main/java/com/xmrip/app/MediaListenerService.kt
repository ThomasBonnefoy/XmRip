package com.xmrip.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Service d'accès aux notifications. Android n'autorise la lecture des sessions média des autres applis (titre,
 * pochette, état de lecture) qu'à une appli à qui l'utilisateur a donné « l'accès aux notifications » : ce service
 * en est le point d'accès (`MediaSessionManager.getActiveSessions`).
 *
 * Il reçoit aussi les notifications des autres applis, mais n'en lit que si Thomas a activé « Lecture des
 * notifications » et choisi l'application ([NotificationReader]) : aucune autre n'est examinée, mémorisée ni envoyée.
 */
class MediaListenerService : NotificationListenerService() {

    private var speaker: NotificationReader.Speaker? = null

    override fun onListenerConnected() {
        // Le système garde ce service en vie et le relance après un redémarrage : la surveillance de l'ordinateur
        // s'y accroche, sans service de premier plan ni notification permanente.
        PcWatcher.start(applicationContext)
        GestureWatcher.start(applicationContext)
        // Relancé aussi après une mise à jour : les raccourcis sont republiés sans qu'il faille ouvrir l'app.
        Shortcuts.publish(applicationContext)
        if (NotificationReader.isEnabled(this)) {
            (speaker ?: NotificationReader.Speaker(applicationContext).also { speaker = it }).warmUp()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val text = NotificationReader.textFor(this, sbn) ?: return
        val voice = speaker ?: NotificationReader.Speaker(applicationContext).also { speaker = it }
        voice.say(text)
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
