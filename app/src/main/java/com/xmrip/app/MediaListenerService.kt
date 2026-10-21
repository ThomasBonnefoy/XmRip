package com.xmrip.app

import android.service.notification.NotificationListenerService

/**
 * Service vide : Android n'autorise la lecture des sessions média des autres applis (titre, pochette,
 * état de lecture) qu'à une appli à qui l'utilisateur a donné « l'accès aux notifications ». Le service ne lit
 * aucune notification ; il sert seulement de point d'accès à `MediaSessionManager.getActiveSessions`.
 */
class MediaListenerService : NotificationListenerService()
