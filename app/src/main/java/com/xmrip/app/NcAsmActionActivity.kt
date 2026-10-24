package com.xmrip.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Déclencheur invisible (aucune fenêtre, grâce au thème windowNoDisplay) des raccourcis NC/Ambiant/Désactivé : lance
 * NcAsmService et se referme immédiatement. Ne fait rien (court message) si le casque n'est pas connecté au téléphone.
 * Les raccourcis sont publiés par [Shortcuts] sans l'option « au-dessus de l'accueil » des raccourcis du manifeste :
 * lancé par une routine, il se referme sur l'appli en cours au lieu de ramener à l'écran d'accueil.
 */
class NcAsmActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modeValue = intent.getStringExtra(NcAsmService.EXTRA_NC_ASM_MODE)

        if (NcAsmService.parseModeFromIntent(intent) == null) {
            Toast.makeText(this, "Commande de mode invalide", Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }

        if (!QuickMode.hasPermission(this)) {
            // Délègue à la UI qui demandera la permission puis appliquera le mode.
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    setAction(NcAsmService.ACTION_SET_NC_ASM)
                    putExtra(NcAsmService.EXTRA_NC_ASM_MODE, modeValue)
                },
            )
            finishAndRemoveTask()
            return
        }

        if (!QuickMode.headsetConnected(this)) {
            Toast.makeText(this, QuickMode.NOT_CONNECTED, Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        NcAsmService.start(this, modeValue)
        finishAndRemoveTask()
    }
}
