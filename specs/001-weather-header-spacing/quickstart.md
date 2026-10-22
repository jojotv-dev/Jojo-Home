# Quickstart de validation: module météo de la barre supérieure

## Prérequis

- Dépôt Jojo Home ouvert dans `C:\Users\arnau\Desktop\Jojo Home`.
- JDK et SDK Android configurés pour le projet.
- TV Android ou émulateur TV disponible pour l’observation du rendu et la navigation D-pad.
- Données météo disponibles pour une ville avec trois prévisions.

## Compilation

Depuis la racine du dépôt :

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Résultat attendu : compilation réussie et APK dans `app/build/outputs/apk/debug/app-debug.apk`.

## Scénario nominal

1. Installer et lancer la version debug sur la TV ou l’émulateur.
2. Afficher l’accueil avec le widget météo activé.
3. Vérifier que la ville et la température courante sont centrées au-dessus de la ligne de prévisions.
4. Vérifier que les trois groupes jour-icône-température sont distincts, espacés et lisibles à environ 2 mètres.
5. Vérifier que Recherche à gauche et Local/NAS à droite restent entièrement visibles.

## Scénario avec indicateurs optionnels

1. Répéter le scénario avec VPN visible.
2. Vérifier que les trois prévisions restent visibles ou adoptent une réduction de secours sans être coupées.
3. Vérifier la même chose avec VPN masqué et, si possible, avec des indicateurs Réseau/NAS actifs.

## Scénario de robustesse

1. Tester une ville au nom long et des températures négatives ou à plusieurs chiffres.
2. Tester zéro, une et deux prévisions disponibles si un jeu de données contrôlé est disponible.
3. Vérifier l’absence de chevauchement, d’artefact ou de valeur fictive.

## Scénario de navigation et actualisation

1. Parcourir la barre avec Gauche/Droite et vérifier que le focus passe par le widget météo sans saut.
2. Sélectionner le widget météo et vérifier que son action existante fonctionne.
3. Attendre ou déclencher une actualisation météo et vérifier que la mise en page ne saute pas et que les valeurs restent associées aux bons jours.

## Contrôles complémentaires

```powershell
git diff --check
```

Le résultat attendu est vide. La réussite de la compilation et de `git diff --check` ne remplace pas la validation visuelle TV décrite ci-dessus.
