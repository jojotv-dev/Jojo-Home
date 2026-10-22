# Feature Specification: Aérer et redimensionner le module météo

**Feature Branch**: `001-weather-header-spacing`

**Created**: 2026-08-13

**Status**: Draft

**Input**: User description: "Aérer et redimensionner le module météo sur la barre supérieure de Jojo Home afin de rendre les prévisions sur trois jours lisibles à distance sur un écran TV."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lire les prévisions depuis le canapé (Priority: P1)

En tant qu’utilisateur de Jojo Home sur un écran TV, je veux lire séparément les prévisions des trois prochains jours afin de comprendre rapidement l’évolution de la météo sans devoir m’approcher de l’écran.

**Why this priority**: La lisibilité des prévisions est le problème principal signalé et concerne l’usage quotidien de la barre supérieure.

**Independent Test**: Afficher l’accueil avec des données météo pour trois jours et vérifier, depuis une distance d’usage TV, que chaque jour, son icône et sa température sont identifiables sans chevauchement ni texte tassé.

**Acceptance Scenarios**:

1. **Given** la météo de la ville courante contient trois prévisions, **When** l’accueil est affiché, **Then** les trois prévisions apparaissent comme trois éléments visuellement distincts, avec un espacement régulier entre eux.
2. **Given** chaque prévision possède un nom de jour, une icône et une température, **When** l’utilisateur regarde la barre supérieure, **Then** ces informations restent lisibles et associées au bon jour.
3. **Given** le nom de la ville et la température courante sont affichés au-dessus, **When** les prévisions sont visibles, **Then** la ligne inférieure reste alignée harmonieusement avec le bloc principal.

### User Story 2 - Préserver l’équilibre de la barre supérieure (Priority: P2)

En tant qu’utilisateur de Jojo Home, je veux que le module météo utilise l’espace disponible entre la recherche et les informations système afin que la barre reste équilibrée et que les éléments voisins restent utilisables.

**Why this priority**: L’agrandissement du module météo doit résoudre le problème sans dégrader les contrôles et indicateurs adjacents.

**Independent Test**: Vérifier l’accueil avec et sans les indicateurs optionnels de la barre et confirmer que la météo gagne de l’espace sans recouvrir, couper ou rendre inaccessibles Recherche, Local, NAS, Réseau, VPN, Sources, Paramètres et Compte.

**Acceptance Scenarios**:

1. **Given** la barre supérieure dispose d’un espace libre entre Recherche et Local, **When** l’accueil est affiché, **Then** le module météo occupe une largeur suffisante pour afficher ses trois prévisions sans compression excessive.
2. **Given** un ou plusieurs indicateurs système sont visibles, **When** la largeur disponible varie, **Then** le module météo et les éléments voisins restent entièrement visibles, sans chevauchement ni déplacement incohérent.

### User Story 3 - Conserver un affichage robuste (Priority: P3)

En tant qu’utilisateur, je veux que le module météo reste stable lorsque les données sont incomplètes ou que l’écran change d’état afin de ne pas perturber l’accueil.

**Why this priority**: Les données météo peuvent être absentes ou plus longues que l’exemple fourni ; le composant doit rester prévisible.

**Independent Test**: Tester successivement des données complètes, une prévision manquante et une ville au nom long, puis vérifier que la barre reste lisible et fonctionnelle.

**Acceptance Scenarios**:

1. **Given** moins de trois prévisions sont disponibles, **When** l’accueil est affiché, **Then** seules les prévisions disponibles sont montrées sans espace vide trompeur ni artefact visuel.
2. **Given** la ville ou une température nécessite davantage de caractères, **When** la météo est affichée, **Then** le bloc reste contenu dans sa zone et ne masque aucun élément voisin.

### Edge Cases

- Une seule ou deux prévisions sont disponibles.
- Le nom de la ville est long ou contient des caractères accentués.
- Une température est absente, négative ou comporte plusieurs chiffres.
- Les indicateurs VPN, Réseau ou NAS apparaissent simultanément et réduisent l’espace disponible.
- Les données météo ne sont pas encore chargées ou ne peuvent pas être récupérées.
- La barre est affichée sur une largeur d’écran TV différente de la largeur de référence.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le module météo DOIT disposer d’une largeur visuelle supérieure à la largeur actuellement contrainte lorsque l’espace entre Recherche et Local le permet.
- **FR-002**: Les prévisions des trois jours DOIVENT être présentées comme des éléments distincts et régulièrement espacés, et non comme une chaîne de texte visuellement compressée.
- **FR-003**: Chaque élément de prévision DOIT regrouper clairement le nom du jour, l’icône météo et la température correspondante.
- **FR-004**: Le bloc des prévisions DOIT rester aligné avec le bloc ville-température principal situé au-dessus.
- **FR-005**: Le module météo NE DOIT PAS chevaucher, couper ou rendre inaccessible un élément voisin de la barre supérieure.
- **FR-006**: La mise en page DOIT s’adapter à l’espace réellement disponible lorsque des indicateurs optionnels de la barre sont visibles ou masqués.
- **FR-007**: L’absence partielle ou totale de données météo DOIT produire un affichage propre, sans valeurs fictives ni artefacts de mise en page.
- **FR-008**: Les changements de mise en page DOIVENT conserver le contenu météo existant, son actualisation et les interactions déjà disponibles.

### Key Entities

- **Bloc météo courant** : ville affichée et température actuelle servant de repère principal.
- **Prévision journalière** : jour, icône descriptive et température maximale affichés ensemble.
- **Barre supérieure** : zone qui distribue Recherche, météo, informations Local/NAS et indicateurs système.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Sur l’écran TV de référence, les trois prévisions sont lisibles sans chevauchement et chaque groupe jour-icône-température est identifiable à une distance d’au moins 2 mètres.
- **SC-002**: Dans la configuration nominale, chaque prévision dispose d’un espacement visuel d’au moins la largeur d’un caractère par rapport à l’élément voisin de la même ligne.
- **SC-003**: Avec les indicateurs optionnels visibles, aucun élément de la barre supérieure ne présente de texte ou d’icône tronqué à cause du module météo.
- **SC-004**: Sur au moins trois largeurs d’écran TV représentatives, la barre reste stable, sans chevauchement ni saut perceptible lors de l’affichage ou de l’actualisation météo.
- **SC-005**: Lors d’un test de lecture à distance, au moins 90 % des utilisateurs identifient correctement les trois jours et leurs températures au premier regard.

## Assumptions

- La cible principale est l’affichage Android TV de Jojo Home, observé avec une télécommande et à distance.
- La demande concerne la présentation et la répartition de l’espace ; le format, la source et la fréquence de mise à jour des données météo restent inchangés.
- La largeur disponible entre Recherche et Local/NAS est utilisée en priorité avant de réduire les autres éléments de la barre.
- La référence de lisibilité est l’exemple fourni avec trois jours, une icône et une température par jour.
- La validation finale doit inclure un rendu sur TV réelle ou émulateur TV représentatif ; la vérification statique seule ne suffit pas.
