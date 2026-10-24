# Banc de test PC

Teste le protocole MDR (le code de `app/.../protocol/`) sur le PC avec le vrai casque, sans téléphone ni APK.

```
casque ⇄ (Bluetooth RFCOMM) ⇄ rfcomm_bridge.py ⇄ (TCP 127.0.0.1:9876) ⇄ module :desktop (MdrSession)
```

- `tools/pc-bridge/rfcomm_bridge.py` : pont Windows (WinRT via `winsdk`). Une connexion RFCOMM par client TCP.
- `desktop/` : module JVM qui compile `protocol/` tel quel (pas de copie) avec un `android.util.Log` de remplacement.
- `tools/xm.ps1` : raccourci qui lance une session de commandes.

## Prérequis

- Le casque est **appairé à Windows** (Paramètres → Bluetooth). Il n'a pas besoin d'être connecté en audio.
- Python 3 et le JDK d'Android Studio. Le venv du pont est créé tout seul dans `%LOCALAPPDATA%\xmrip-bridge\venv`
  (hors du dossier synchronisé par Seafile).
- Le casque n'accepte qu'**une session MDR à la fois** : ferme l'app sur le téléphone, et `XmRipPc.exe` (qui garde sa
  connexion ouverte), pendant les tests. Sinon le handshake expire.

## Utilisation

```powershell
tools\pc-bridge\run.ps1 --list                 # appareils appairés
tools\pc-bridge\run.ps1                        # démarre le pont (casque Sony détecté tout seul)
tools\pc-bridge\run.ps1 --address AA:BB:CC:DD:EE:FF --trace   # adresse explicite, octets échangés
```

Dans un autre terminal :

```powershell
tools\xm.ps1 battery
tools\xm.ps1 nc ambient 10 off
tools\xm.ps1 -v eq settings devices            # -v : trames TX/RX du protocole
tools\xm.ps1 --help
```

Les commandes s'enchaînent dans une seule session (voir `--help`). Comme l'app, le banc réessaie une fois si le handshake
échoue ; le premier essai après une période d'inactivité échoue parfois.

Attention : les commandes d'écriture (`nc`, `eq-preset`, `eq-bands`, `volume N`…) modifient vraiment le casque.
Relève l'état (`eq`, `settings`) avant de tester pour pouvoir le remettre.
