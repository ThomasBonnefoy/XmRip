# Banc de test PC

Teste le protocole MDR (le code de `app/.../protocol/`) sur le PC avec le vrai casque, sans téléphone ni APK.

```
casque ⇄ (Bluetooth RFCOMM) ⇄ rfcomm_bridge.py ⇄ (TCP 127.0.0.1:9876) ⇄ module :desktop (MdrSession)
```

- `tools/pc-bridge/rfcomm_bridge.py` : pont Windows (WinRT via `winsdk`). Une connexion RFCOMM par client TCP.
- `tools/pc-bridge/Bridge.swift` + `main.swift` : le même pont pour macOS (IOBluetooth), sans dépendance Python.
- `desktop/` : module JVM qui compile `protocol/` tel quel (pas de copie) avec un `android.util.Log` de remplacement.
- `tools/xm.ps1` (Windows) / `tools/xm.sh` (macOS) : raccourci qui lance une session de commandes.

## Prérequis

- Le casque est **appairé à Windows** (Paramètres → Bluetooth) ou **au Mac** (Réglages Système → Bluetooth). Il n'a pas besoin d'être connecté en audio.
- Python 3 et le JDK d'Android Studio. Le venv du pont est créé tout seul dans `%LOCALAPPDATA%\xmrip-bridge\venv`
  (hors du dossier synchronisé par Seafile).
- Le casque n'accepte qu'**une session MDR à la fois** : ferme l'app sur le téléphone, et `XmRipPc.exe` (qui garde sa
  connexion ouverte), pendant les tests. Sinon le handshake expire.

## App macOS « XmRip Banc »

```sh
tools/mac-app/build.sh --open       # compile dist/XmRip Banc.app puis la lance (ensuite : double-clic)
```

Quatre onglets, comme l'app mobile : **Accueil** (bruit / ambiant et son niveau, ambiant automatique, volume et muet,
Speak-to-Chat, DSEE, pause au retrait), **Son** (préréglages et 10 bandes de l'égaliseur, mode d'écoute et pièce, DSEE,
priorité qualité / connexion), **Appareils** (mode appairage, liste multipoint : connecter, déconnecter, lire ici, oublier),
**Réglages** (Speak-to-Chat et délai de reprise, guidage vocal et son volume, bouton de bruit, extinction automatique,
éteindre le casque, journal). L'état affiché est celui d'après chaque action.

L'accueil et l'en-tête montrent la photo du casque à sa couleur, comme l'app Android : le moteur lit la couleur annoncée
par le casque et l'API la renvoie (`color`, `look`), avec la même table (`HeadsetLook.kt`) ; les six photos de
`app/src/main/res/drawable-nodpi/` sont copiées dans l'app par `build.sh`. La dernière couleur connue est gardée d'un
lancement à l'autre. L'anneau autour de la photo donne la batterie (violet, orange sous 30 %, rouge sous 15 %, vert en charge).

Ne sont pas repris, car propres au téléphone : lecture des notifications, coupure du son quand le PC est connecté, actions
des gestes, tuile, widget, raccourcis, thème, titre en cours de lecture.

- Ce n'est pas un portage du protocole : l'app embarque le pont RFCOMM (`Bridge.swift`, dans son propre processus, donc la
  permission Bluetooth est celle de l'app) et lance en arrière-plan le moteur Kotlin `:desktop:runWeb` (`Web.kt`, l'API
  HTTP locale sur 127.0.0.1:8765) : c'est bien `MdrSession`, le code d'Android, qui parle au casque.
- Le moteur garde la session MDR ouverte entre deux clics (comme l'app Android) : une action prend 0,05 à 0,1 s au lieu
  de 0,5 à 2 s. Elle est rendue après 60 s d'inactivité. Tant qu'elle est ouverte, le casque est tenu : l'app du téléphone
  ou `xm.sh` ne peuvent pas s'y connecter. Pour la libérer tout de suite : `curl -X POST localhost:8765/release`.
- Au premier lancement : macOS demande l'autorisation Bluetooth ; le moteur met jusqu'à une minute à démarrer (Gradle).
- Il faut Xcode ou les Command Line Tools pour compiler, et un JDK 17+ (cherché par `tools/env.sh`).
- Fermer la fenêtre (ou ⌘Q) arrête l'app et le moteur. Un arrêt brutal (`kill`) laisse le moteur tourner : le prochain
  lancement le réutilise, ou `curl -X POST localhost:8765/quit` l'arrête.
- Les commandes longues ou dangereuses (`raw`, `guide`, `watch`) ne sont pas exposées.

## Utilisation (macOS)

```sh
tools/pc-bridge/run.sh --list                  # appareils appairés
tools/pc-bridge/run.sh                         # démarre le pont (casque Sony détecté tout seul)
tools/pc-bridge/run.sh --address AA:BB:CC:DD:EE:FF --trace
```

Dans un autre terminal : `tools/xm.sh battery`, `tools/xm.sh nc ambient 10 off`, `tools/xm.sh -v eq settings devices`…

- Le pont est compilé au premier lancement (Xcode ou les Command Line Tools suffisent) dans `~/Library/Caches/xmrip-bridge`,
  et empaqueté en mini-app lancée via `open` : macOS attribue le Bluetooth à l'application responsable du processus, et un
  exécutable lancé depuis un terminal ou depuis Claude serait tué sans description d'usage. Au premier lancement, macOS
  demande d'autoriser le Bluetooth pour « rfcomm_bridge » : accepter.
- `xm.sh` cherche un JDK 17+ (`JAVA_HOME`, `/usr/libexec/java_home`, Homebrew `openjdk`, Android Studio). Gradle récupère
  tout seul le JDK du démon. Le module `:app` n'est pas configuré (`configureondemand`), donc le SDK Android est inutile.
- Si le canal MDR ne s'ouvre pas (le casque garde parfois une session « occupée » après un arrêt brutal du pont ou d'un
  client), le pont coupe puis rétablit la liaison avec le casque et réessaie une fois ; l'audio coupe quelques secondes.
  `run.sh --reconnect` force cette réinitialisation au démarrage.
- Le résultat de `guide` est écrit dans `~/Library/Caches/xmrip-bridge/guide.txt`.
- `gradlew` doit avoir des fins de ligne LF (un `gradlew` en CRLF, par exemple après une synchro, échoue avec `bad interpreter`).

## Sans casque : le faux casque

Le faux casque des tests (`desktop/src/test/…/FakeHeadset.kt`) peut remplacer le pont, par exemple casque éteint ou pour
voir une autre couleur que celle du sien :

```sh
./gradlew -Dorg.gradle.configureondemand=true :desktop:runFakeHeadset --args="--port 9877 --color 5"   # 5 = bleu
tools/xm.sh --port 9877 battery nc ambient 12
./gradlew -Dorg.gradle.configureondemand=true :desktop:runWeb --args="--port 8765 --bridge-port 9877"  # puis ouvrir l'app
```

Il rejoue les réponses relevées sur un WH-1000XM6 (firmware 3.1.5, batterie 64 %, volume 4) pour le handshake, et confirme
toute écriture (bruit, ambiant, volume…) en la renvoyant en notification. Les lectures de réglages, d'égaliseur et
d'appareils n'ont pas de réponse et expirent, donc « Actualiser » échoue dans l'app ; changer de mode fonctionne. L'app
lancée pendant que ce moteur tourne le réutilise au lieu de lancer le sien.

## Utilisation (Windows)

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
