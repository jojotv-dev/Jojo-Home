# Icônes météo : soleil et couverture nuageuse

## Objectif

Éviter qu'un nuage soit affiché lorsque les données météo indiquent un ciel réellement dégagé ou principalement dégagé. La température ne participe jamais au choix de l'icône.

## Données

Jojo Home conservera Open-Meteo et ajoutera `cloud_cover` au jeu de données `current`. Les prévisions quotidiennes utilisent déjà les codes WMO de la journée ; elles conserveront leur comportement actuel, sauf pour la distinction soleil / nuage léger lorsque la donnée est disponible.

## Règles d'affichage de la météo actuelle

- Code WMO `0` : soleil le jour, lune la nuit.
- Codes `1` ou `2` avec une couverture nuageuse inférieure à 20 % : soleil le jour, lune la nuit.
- Codes `1` ou `2` avec une couverture de 20 % à 59 % : icône soleil derrière un nuage le jour ; lune derrière un nuage la nuit.
- Code `3` ou couverture de 60 % et plus : nuage.
- Pluie, neige et orage : les icônes dédiées existantes restent prioritaires.
- Si `cloud_cover` est absent, le comportement de repli est prudent : code `1` affiche soleil, code `2` affiche partiellement nuageux, code `3` affiche nuage.

## Expérience et robustesse

- Aucun changement de fournisseur ni de permissions.
- L'échec de la nouvelle donnée ne rend pas le widget indisponible : la règle de repli s'applique.
- Les icônes actuelles sont réutilisées si l'asset « partiellement nuageux » existe ; sinon un asset dédié est ajouté.

## Vérification

1. Réponse Open-Meteo avec `weather_code=1`, `cloud_cover=5` : soleil.
2. Réponse avec `weather_code=2`, `cloud_cover=40` : soleil avec nuage.
3. Réponse avec `weather_code=3` : nuage.
4. Réponse pluvieuse : pluie, quelles que soient température et couverture.
