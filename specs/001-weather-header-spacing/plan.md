# Implementation Plan: Aérer et redimensionner le module météo

**Branch**: `001-weather-header-spacing` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

## Summary

Le widget météo doit exploiter davantage l’espace disponible dans la barre supérieure et présenter les trois prévisions comme des groupes distincts, lisibles à distance. Le code possède déjà une distribution adaptative de la barre et une logique spécifique lorsque le VPN est visible ; le plan conserve cette logique, augmente la priorité donnée au widget météo et remplace la compression visuelle par un espacement régulier entre groupes jour-icône-température. La source, l’actualisation et l’interaction du widget restent inchangées.

## Technical Context

**Language/Version**: Java, Android SDK compileSdk 36, minSdk 24

**Primary Dependencies**: Android framework views (`LinearLayout`, `TextView`, `ImageView`) et composants internes de `MainActivity`

**Storage**: N/A pour cette amélioration ; les préférences météo existantes restent inchangées

**Testing**: Compilation Gradle `:app:assembleDebug`, tests unitaires existants si concernés, inspection statique ciblée, puis validation visuelle sur TV réelle ou émulateur TV représentatif

**Target Platform**: Android TV, notamment la largeur d’écran de référence et au moins deux largeurs représentatives supplémentaires

**Project Type**: Application Android TV mono-module

**Performance Goals**: La barre supérieure doit rester fluide à l’affichage et lors de l’actualisation météo ; aucun saut perceptible ni blocage utilisateur.

**Constraints**: Ne pas modifier la source ni la fréquence d’actualisation météo ; ne pas chevaucher Recherche, Local, NAS, Réseau, VPN, Sources, Paramètres, Compte ou date/heure ; préserver le focus horizontal et l’action d’ouverture du widget.

**Scale/Scope**: Un widget météo et sa distribution dans la barre supérieure ; trois prévisions au maximum ; aucun changement de stockage, de réseau ou de navigation hors météo.

## Constitution Check

La constitution du dépôt est encore un gabarit non renseigné (`.specify/memory/constitution.md`) et ne définit aucune règle de gouvernance applicable à cette feature.

**GATE**: PASS — aucune violation identifiable ; le périmètre reste limité à la présentation de l’en-tête et à sa validation TV.

## Project Structure

### Documentation (this feature)

```text
specs/001-weather-header-spacing/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
└── tasks.md                 # créé par $speckit-tasks
```

### Source Code (repository root)

```text
app/
├── src/main/java/com/jojotv/home/MainActivity.java
└── src/test/java/com/jojotv/home/       # tests unitaires existants, si extension nécessaire
```

**Structure Decision**: La feature reste dans `MainActivity.java`, où sont construits `weatherWidget()`, `headerWeatherParams()` et `layoutTopBarItems()`. Aucun nouveau module, service, contrat réseau ou modèle persistant n’est nécessaire.

## Implementation Design

1. Ajuster la largeur préférentielle et le plafond adaptatif du conteneur météo afin d’utiliser l’espace libre entre Recherche et Local/NAS, tout en conservant une largeur maximale sûre lorsque les éléments optionnels sont visibles.
2. Restructurer la ligne de prévisions pour que chaque jour soit un groupe cohérent et que les groupes soient séparés par un espacement constant ; éviter toute mise à l’échelle horizontale en régime nominal.
3. Conserver l’alignement centré du bloc ville-température au-dessus de la largeur complète de la ligne de prévisions.
4. Prévoir un comportement propre pour zéro à trois prévisions, les libellés longs et les températures négatives ou à plusieurs chiffres.
5. Vérifier le focus, le clic, l’actualisation automatique et la répartition avec VPN visible et masqué.

## Validation Gates

- Compilation debug réussie.
- `git diff --check` sans erreur sur les fichiers modifiés.
- Les trois groupes de prévisions sont séparés et lisibles à au moins 2 m sur la TV de référence.
- Aucun élément voisin n’est tronqué ou recouvert en configurations VPN/non-VPN.
- Le widget reste activable, actualisable et navigable à la télécommande.

## Complexity Tracking

Aucune violation de constitution à justifier.
