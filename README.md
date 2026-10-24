# XmRip

App Android open-source qui remplace **Sony Sound Connect** pour contrôler le casque **WH-1000XM6**, en parlant directement le protocole **MDR v2** par socket RFCOMM Bluetooth. Aucune dépendance externe, UI 100% programmatique.

## Fonctionnement

- Connexion RFCOMM au service "Serial HPC" du casque (`createInsecureRfcommSocketToServiceRecord`), sur des appareils déjà appairés.
- Le protocole **MDR v2** repose sur des trames `0x3E / 0x3C`, avec échappement `0x3D`, checksum et acquittement (`ACK`) de chaque trame de données.
- Le handshake se limite à firmware, modèle et état (batterie, bruit, volume) ; la liaison reste ouverte entre deux actions (fermée après 90 s d'inactivité, ou gardée tant qu'une action de geste est choisie). L'état suit les notifications du casque.

## Source de vérité du protocole

Le code Kotlin de `app/` est un **port** du protocole MDR, pas une traduction du client desktop.

La référence autorisée pour toute question sur un octet ou une séquence est **[`reference/libmdr/`](reference/libmdr/)**, copie statique et condensée du dépôt [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) (lui-même successeur du projet de [Plutoberth](https://github.com/Plutoberth/SonyHeadphonesClient)), licence MIT, en lecture seule.

Autres ressources utiles :

| Ressource | Description |
|---|---|
| `reference/libmdr/include/mdr/` | Headers C++ du protocole (tables de commandes T1/T2, constantes) |
| `reference/docs/WH-1000XM6.md` | Documentation du protocole spécifique au WH-1000XM6 |
| `reference/LICENSE` | Licence MIT de libmdr |

## Cible matérielle

- **WH-1000XM6 uniquement.** L'UI affiche un avertissement si un autre modèle est détecté.
- Un seul contrôleur à la fois : si Sony Sound Connect contrôle le casque, la connexion échoue.

## Build (APK debug)

```
.\gradlew.bat assembleDebug
```

APK de sortie : `app/build/outputs/apk/debug/app-debug.apk`

Installation sur appareil connecté (avec le débogage USB activé) :

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `compileSdk`/`targetSdk` : 37, `minSdk` : 24, jvmTarget 17.
- Aucune dépendance externe (`android.useAndroidX=true`).

## État des features

| Fonction | État |
|---|---|
| Connexion + batterie / niveau de charge / modèle / firmware | ✔ |
| Mode NC / Ambiant / Désactivé (+ raccourcis) | ✔ |
| Niveau son ambiant (1..20) | ✔ |
| Son ambiant automatique | ✔ |
| Volume lecture (0..30) + muet | ✔ |
| Contrôle transport (play / pause / piste) | ✔ |
| Multipoint : liste des appareils, connecter / déconnecter / oublier, bascule de la lecture, mode appairage (Table 2, trames `0x0E`) | ✔ (compilé, à valider sur le casque) |
| Extinction auto (au retrait du casque / jamais, type `0x05`) | ✔ (vérifié sur un WH-1000XM6, firmware 3.1.5) |
| Speak-to-Chat (activer / désactiver) + pause quand le casque est retiré | ✔ (vérifié sur un WH-1000XM6) |
| Égaliseur XM6 : préréglages (Heavy, Clear, Hard, Soft, Perso) + courbe à 10 bandes sur « Perso » (type `0x04`) | ✔ (vérifié sur un WH-1000XM6, firmware 3.1.5) |
| Carte lecture : titre, artiste, pochette, bouton lecture / pause unique, curseur de progression | ✔ (titre, pochette et curseur demandent « Accès aux notifications ») |
| DSEE Extreme | ✔ (vérifié sur un WH-1000XM6) |
| Mode d'écoute : Standard / Musique de fond (3 ambiances) / Cinéma | ✔ (vérifié sur un WH-1000XM6 ; l'ambiance de la musique de fond n'a pas été essayée) |
| Éteindre le casque à distance | ✔ (non essayé) |
| Son des notifications coupé quand l'ordinateur est connecté au casque (volume des notifications à 0, vibrations et pop-ups conservés ; lit la liste multipoint toutes les 30 s) | installé, son coupé vérifié par adb, à valider sur le téléphone |
| Lecture à voix haute, dans le casque, des notifications d'applications choisies quand l'ordinateur n'est pas connecté (demande « l'accès aux notifications » ; musique baissée avec voix amplifiée, ou mise en pause, au choix ; option écran éteint seulement) | installé, voix vérifiée par adb, à valider sur le téléphone |
| Bouton de bruit : modes que le bouton de l'oreillette gauche fait défiler (les trois / bruit et ambiant ; bruit et normal et ambiant et normal non essayés) | ✔ (vérifié sur un WH-1000XM6, firmware 3.1.5) |
| Gestes du casque : le casque annonce glissés, double appui, main posée, double et triple appui du bouton de bruit (journal d'événements, `C4 01 00`) | ✔ (vérifié sur un WH-1000XM6) ; l'appui simple de la zone tactile n'envoie rien |
| Actions du téléphone sur le double et le triple appui du bouton de bruit (touches média, ouvrir une application ; garde la session ouverte) | ✔ (vérifié sur un WH-1000XM6) |
| Réglages de la zone tactile de droite (glissés, double tap, main posée) | aucune commande trouvée |

La vérification se fait sur le casque réel — aucune suite de tests automatisés pour l'instant.

## Outils de test sur PC

- **Banc de test** (`tools/pc-bridge/`, module `desktop/`, `tools/xm.ps1`) : rejoue le code du protocole de l'app sur le vrai casque depuis Windows, sans téléphone ni APK (pont RFCOMM vers `127.0.0.1:9876`). Mode d'emploi : [`tools/pc-bridge/README.md`](tools/pc-bridge/README.md).
- Le casque n'accepte qu'une session de contrôle à la fois : fermer l'app du téléphone (ou couper ses actions de gestes) pendant les essais.

## Licence

Le code de XmRip est sous licence MIT (voir [`LICENSE.md`](LICENSE.md)) ; la partie protocole provient de `libmdr` ([mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient)), aussi sous licence MIT (voir `reference/LICENSE`).