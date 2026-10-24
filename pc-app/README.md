# XmRip PC

Interface Windows pour piloter le WH-1000XM6 depuis le PC, dans un seul `.exe` (WinForms, .NET 10, autonome :
rien à installer, ni Python ni Java).

Onglets : **Son** (réduction de bruit, ambiant et son niveau, ambiant automatique, volume, lecture),
**Égaliseur** (préréglages Heavy, Clear, Hard, Soft, Perso et dix curseurs), **Réglages** (DSEE, Speak-to-Chat,
pause au retrait, mode d'écoute, extinction), **Appareils** (multipoint : connecter, déconnecter, lire ici).
L'onglet **Gestes** teste ce que le casque envoie quand on le touche : bouton du geste (1, 2, 3 taps, maintien long, glissés), puis le geste sur le casque dans les 8 s ; l'appli liste les messages reçus. Fermer l'app du téléphone avant : le casque n'a qu'une session de contrôle.
La case « Afficher les trames » montre les échanges TX/RX dans le journal du bas.

## Utiliser

1. Casque **appairé à Windows** (Paramètres, Bluetooth et appareils), allumé.
2. Fermer l'app XmRip du téléphone : le casque n'accepte probablement qu'une session MDR à la fois.
3. Lancer `dist\XmRipPc.exe` (fichier non versionné, voir ci-dessous pour le reconstruire).

La connexion reste **ouverte** : elle se paie une fois (environ 2 s à froid, 5 s la toute première fois), puis chaque
action coûte un échange (0,4 à 0,8 s, c'est le temps de réponse du casque). L'adresse du casque, son canal RFCOMM,
son modèle et son firmware sont mémorisés dans `%LOCALAPPDATA%\XmRipPc` : la connexion passe alors directement par
Winsock (0,5 à 1 s au lieu de 2 à 2,5 s par WinRT, qui reste le repli). Les onglets Égaliseur, Réglages et Appareils
ne sont lus qu'à leur première ouverture ; « Actualiser » relit tout, firmware compris. L'appli acquitte les trames du casque en tâche de fond, donc batterie, bruit et volume changés
depuis le casque ou le téléphone se mettent à jour tout seuls. Le voyant « Connecté » est en haut. Après 2 minutes
sans action la connexion se ferme pour laisser le casque libre ; la suivante se rouvre toute seule. Tant qu'elle
est ouverte, le casque est tenu par l'appli : le téléphone et `tools\xm.ps1` ne peuvent pas s'y connecter.
Les actions s'enchaînent dans l'ordre ; les curseurs n'envoient qu'une fois la valeur stable.

## Reconstruire l'exe

```powershell
cd pc-app
dotnet publish -c Release -r win-x64 -o ..\dist
```

Il faut le SDK .NET 10. Le résultat (`dist\XmRipPc.exe`, environ 53 Mo) est ignoré par git.

## Code

- `Mdr/` : le protocole en C# (trames, constantes, session), jumeau de `app/.../protocol/`. Les octets se vérifient
  contre `reference/libmdr/`. Une correction d'octet se reporte des deux côtés. Pour rejouer le code Kotlin lui-même
  sans téléphone, voir `tools/pc-bridge/README.md`.
- `Bluetooth/` : connexion RFCOMM, Winsock direct sur le canal mémorisé, WinRT en repli.
- `MainForm.cs` : l'interface, construite en code.

## Façon de travailler

On code et on teste **d'abord ici**, sur la version Windows. Une fois que c'est testé sur le casque et validé, on
le porte sur Android (`MdrSession.kt`, `HeadphoneClient.kt`, `MainActivity.kt`), puis APK debug par adb. Une fonction
non validée côté Windows n'est pas portée. Un seul client MDR à la fois : fermer l'exe avant le téléphone ou le banc.
