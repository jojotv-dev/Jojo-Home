# Data Model: Aérer et redimensionner le module météo

Cette feature ne crée ni table, ni préférence, ni endpoint. Elle réorganise uniquement les données déjà affichées par le widget météo.

## Bloc météo courant

Représente la ville et la température courante affichées sur la première ligne.

| Champ | Rôle | Règle d’affichage |
|---|---|---|
| Ville | Localisation météo courante | Une ligne, contenue dans la zone météo |
| Température courante | Température principale | Associée visuellement à la ville |
| Icône courante | État météo actuel | Placée avant le couple ville-température |

## Prévision journalière

Représente un jour de prévision et ses informations associées.

| Champ | Rôle | Règle d’affichage |
|---|---|---|
| Jour | Nom court du jour | Une ligne, associé au bon groupe |
| Icône | Condition météo prévue | Visible sans chevaucher le texte |
| Température | Température prévue | Visible entièrement, y compris signe négatif et plusieurs chiffres |

## Barre supérieure

Conteneur visuel qui distribue les éléments Recherche, météo, Local/NAS, Réseau, VPN, Sources, Paramètres, Compte et date/heure.

### Relations et contraintes

- Un bloc météo contient zéro à trois prévisions journalières.
- La barre supérieure contient au plus un bloc météo visible.
- La largeur du bloc météo dépend de la largeur réellement disponible après réservation des éléments visibles.
- Aucun élément ne change de source de données ou de cycle de vie dans cette feature.
