Référence du protocole MDR — XmRip
===

Ce dossier est une **copie statique et condensée** de ce qui est utile à XmRip
dans le dépôt [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient)
(lui-même successeur du projet originel de Plutoberth). Il est la **source de
vérité** du port Kotlin de XmRip : quand un doute porte sur un octet ou une
séquence, c'est ici qu'il faut lire avant d'écrire du code.

Contenu :

- `libmdr/` — headers C++ du protocole (`include/mdr/` : tables de commandes
  T1/T2, constantes, framing), lecture seule.
- `docs/` — documentation technique (`WH-1000XM6.md` spécifiquement).
- `LICENSE` — licence MIT (libmdr).

Règles :

- **Lecture seule** : ne pas modifier ce dossier dans XmRip.
- Les modifications utiles se font côté clone upstream
  (`SonyHeadphonesClient/`, hors repo git) puis sont recondensées ici si besoin.
- Ne pas committer `SonyHeadphonesClient/` lui-même : c'est un clone git de
  travail, ignoré par le `.gitignore` du dépôt.