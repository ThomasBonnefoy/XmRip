# XmRip

App Android open-source qui remplace **Sony Sound Connect** pour contrôler le casque **WH-1000XM6**, en parlant directement le protocole **MDR v2** par socket RFCOMM Bluetooth. Aucune dépendance externe, UI 100% programmatique.

## Fonctionnement

- Connexion RFCOMM au service "Serial HPC" du casque (`createInsecureRfcommSocketToServiceRecord`), sur des appareils déjà appairés.
- Le protocole **MDR v2** repose sur des trames `0x3E / 0x3C`, avec échappement `0x3D`, checksum et acquittement (`ACK`) de chaque trame de données.
- Une session complète (handshake : protocol info → capability info → device info → support function → power status) est obligatoire avant toute commande. Chaque action ouvre sa propre socket, exécute, puis ferme la socket.

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
adb install -r app-debug.apk
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
| Extinction auto | à câbler dans l'UI |
| Speak-to-Chat | à câbler dans l'UI |
| Égaliseur XM6 : préréglages (Heavy, Clear, Hard, Soft, Perso) + courbe à 10 bandes sur « Perso » (type `0x04`) | ✔ (vérifié sur un WH-1000XM6, firmware 3.1.5) |
| Carte lecture : titre, artiste, pochette, bouton lecture / pause unique, curseur de progression | ✔ (titre, pochette et curseur demandent « Accès aux notifications ») |
| DSEE (upscaling) | à câbler dans l'UI |
| Fonctions panneaux tactiles (assignables) | à câbler dans l'UI |

La vérification se fait sur le casque réel — aucune suite de tests automatisés pour l'instant.

## Licence

Le code de XmRip est sous licence MIT (voir [`LICENSE.md`](LICENSE.md)) ; la partie protocole provient de `libmdr` ([mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient)), aussi sous licence MIT (voir `reference/LICENSE`).