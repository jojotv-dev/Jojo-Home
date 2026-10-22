# Research: Aérer et redimensionner le module météo

## Decision 1: Conserver la construction actuelle du widget

**Rationale**: Le widget, ses vues météo, son actualisation et ses interactions sont regroupés dans `weatherWidget()` et `refreshWeather(...)`. La demande porte sur la mise en page, pas sur la récupération des données.

**Alternatives considered**: Créer un composant ou un layout séparé. Écarté pour limiter le risque de régression et ne pas déplacer la logique météo existante.

## Decision 2: Donner la largeur disponible au module météo

**Rationale**: `layoutTopBarItems(...)` mesure déjà la largeur réelle de la barre, réserve les autres éléments et calcule les marges. La solution doit renforcer cette répartition plutôt que dépendre d’une largeur fixe unique.

**Alternatives considered**: Agrandir seulement la largeur fixe du widget. Écarté, car une largeur fixe peut faire déborder les éléments sur les pages internes ou lorsque VPN est visible.

## Decision 3: Espacer les groupes de prévision sans compression nominale

**Rationale**: Le problème vient de la chaîne dense `icône + jour + température + séparateur`. Chaque prévision doit être un groupe lisible, séparé régulièrement ; la réduction d’échelle ne doit être qu’un dernier recours en espace réellement insuffisant.

**Alternatives considered**: Continuer à réduire toute la ligne par `scaleX`. Écarté en configuration nominale, car cela diminue directement la lisibilité à distance.

## Decision 4: Garder le contenu et les interactions inchangés

**Rationale**: La spécification exclut toute modification de source, de fréquence de mise à jour, de permission et de navigation. Le widget doit rester focalisable, cliquable et actualisable.

**Alternatives considered**: Reconcevoir le fournisseur météo ou déplacer le réglage dans un nouvel écran. Hors périmètre.

## Decision 5: Valider sur rendu TV réel ou représentatif

**Rationale**: Les contrôles statiques ne peuvent pas prouver la lisibilité, le clipping ou le focus D-pad. La validation doit couvrir plusieurs largeurs, VPN visible/masqué et actualisation.

**Alternatives considered**: Se limiter à la compilation et à l’inspection du code. Insuffisant pour cette feature visuelle.
