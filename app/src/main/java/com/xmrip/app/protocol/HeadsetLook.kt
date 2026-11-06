package com.xmrip.app.protocol

/**
 * Teinte de la photo à afficher pour un casque, d'après la couleur qu'il annonce (« série et couleur », `ModelColor`
 * de libmdr). Le WH-1000XM6 n'existe qu'en six finitions (noir, argent platine, bleu nuit, rose sable, grès, gris
 * olive) : les codes proches sont regroupés, et un code inconnu ou absent donne le noir. Seul le code noir (1) est
 * vérifié sur un casque ; les autres viennent de la liste de libmdr, sans table officielle pour le XM6.
 */
enum class HeadsetLook {
    BLACK, SILVER, BLUE, PINK, OLIVE, SAND;

    companion object {
        fun of(color: Int?): HeadsetLook = when (color) {
            MdrConstants.MODEL_COLOR_SILVER, MdrConstants.MODEL_COLOR_WHITE -> SILVER
            MdrConstants.MODEL_COLOR_BLUE -> BLUE
            MdrConstants.MODEL_COLOR_PINK -> PINK
            MdrConstants.MODEL_COLOR_GREEN, MdrConstants.MODEL_COLOR_GRAY -> OLIVE
            MdrConstants.MODEL_COLOR_CREAM, MdrConstants.MODEL_COLOR_BROWN, MdrConstants.MODEL_COLOR_GOLD,
            MdrConstants.MODEL_COLOR_YELLOW, MdrConstants.MODEL_COLOR_ORANGE -> SAND
            else -> BLACK
        }
    }
}
