package com.xmrip.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Déclencheur invisible (aucune fenêtre, grâce au thème windowNoDisplay) des raccourcis
 * NC/Ambiant/Désactivé : lance NcAsmService et se referme immédiatement.
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

        if (!hasConnectPermission()) {
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

        NcAsmService.start(this, modeValue)
        finishAndRemoveTask()
    }

    @Suppress("MissingPermission")
    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
}