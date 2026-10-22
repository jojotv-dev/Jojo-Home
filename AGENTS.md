# Instructions du projet

## Objectif

Modifie le code de façon minimale, claire et cohérente avec l'architecture existante. Ne réécris pas des fichiers entiers lorsqu'une petite modification suffit.

## Avant de modifier

- Inspecte uniquement les fichiers pertinents.
- Cherche les fonctions, classes et tests concernés avant de lire des fichiers complets.
- Vérifie les conventions déjà utilisées dans le dossier concerné.
- Pour une tâche ambiguë ou importante, propose d'abord un plan court et attends une confirmation si une décision est nécessaire.

## Périmètre

- Ne modifie pas de fichier non nécessaire à la tâche.
- Ne change pas les dépendances, l'architecture ou les API publiques sans justification.
- Ne crée pas de nouveaux fichiers si un fichier existant convient.
- Ne lance pas d'installation, de migration ou de commande destructive sans confirmation.
- Ne touche jamais aux secrets, fichiers `.env`, clés privées ou données personnelles.
- Ne modifie pas les fichiers générés automatiquement.

## Sous-agents

- Utilise des sous-agents uniquement pour des tâches indépendantes et clairement séparables.
- Limite leur nombre au minimum nécessaire et ne les fais pas modifier les mêmes fichiers.
- Donne à chaque sous-agent un périmètre précis.
- Demande un résumé concis : fichiers examinés, constat, modifications, tests et problèmes restants.
- Utilise uniquement le mécanisme de coordination disponible dans l'environnement ; n'invente pas de commande de coordination absente.

## Sorties et contexte

- N'affiche pas de longues sorties de commandes si quelques lignes pertinentes suffisent.
- Utilise d'abord des commandes ciblées comme `rg`, `git diff` et `git status`.
- Résume les logs volumineux et ne fournis pas le contenu complet des fichiers modifiés sauf demande explicite.

## Implémentation

- Réutilise les utilitaires, types et abstractions existants.
- Respecte le style déjà présent dans le fichier.
- Préfère la solution la plus simple qui répond à la demande.
- N'ajoute pas de gestion d'erreur spéculative sans cas réel.
- Préserve la compatibilité avec le code existant lorsque cela est possible.
- Signale tout changement de comportement public.

## Contraste des états de focus

- Pour chaque ligne ou commande focalisable avec une icône, vérifie visuellement l’état normal et l’état sélectionné.
- Si le fond de focus utilise la couleur d’accent, l’icône et le texte doivent utiliser une couleur de premier plan contrastante, jamais cette même couleur d’accent. Réutilise `contrastingFolderThemeTextColor()` pour les composants qui utilisent la palette des dossiers.
- Les helpers partagés de menus et les composants spécialisés doivent appliquer la même règle, y compris quand le thème de couleur change.

## Validation

Après une modification :

1. Vérifie le diff avec `git diff`.
2. Exécute les tests ciblés lorsque cela est pertinent.
3. Exécute le lint ou le type-check uniquement si la modification le justifie.
4. Corrige les erreurs introduites et vérifie que seuls les fichiers nécessaires ont été modifiés.
5. Signale clairement les vérifications non exécutées.

## Graft

Utilise Graft pour les recherches multi-fichiers, l'analyse d'architecture, les refactorings importants ou lorsque le chemin d'appel est incertain. Pour une modification locale évidente, une recherche ciblée suffit.

- `graft map` pour l'orientation d'un dépôt inconnu.
- `graft ask "<question>" --source` pour comprendre et localiser un sujet complexe.
- `graft grep "<literal>"` pour rechercher exhaustivement toutes les occurrences.
- `graft skeleton <file>` pour parcourir l'API d'un fichier.
- `graft callers <symbol>` avant de modifier un symbole partagé.
- `graft build` après une modification importante.
