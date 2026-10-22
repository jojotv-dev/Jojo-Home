# ✅ CHECKLIST VÉRIFICATION VISUELLE UI

**Après déploiement sur Android TV, vérifier les points suivants :**

---

## 🎯 SECTION 1: Popups de menu

### Menus fichiers (clic droit local/SMB)
- [ ] **Fond par défaut** : blanc léger légèrement visible (pas transparent)
- [ ] **Bordure** : subtile bordure grise (pas juste un contour noir)
- [ ] **État focus** : fond bleu clair visible au D-pad
- [ ] **Texte lisible** : aucune troncature, taille cohérente
- [ ] **Icônes** : bien alignés avec le texte
- [ ] **Spacing** : cohérent entre les éléments

### Menu raccourcis d'accueil (long-press)
- [ ] **Dimensions** : PopupWindow pas trop grande, pas trop petite
- [ ] **Deux options** : "Ouvrir" et "Supprimer" visibles
- [ ] **Focus initial** : "Ouvrir" highlight au D-pad
- [ ] **Interaction** : Clic bas D-pad accède à "Supprimer"
- [ ] **Fonctionnalité Ouvrir** : raccourci s'ouvre correctement
- [ ] **Fonctionnalité Supprimer** : raccourci disparaît + toast "Raccourci supprimé"

---

## 🎨 SECTION 2: Paramètres (layout responsive)

### Écran principal de paramètres
- [ ] **Titre** : centré, lisible
- [ ] **Lignes** : pleine largeur, pas tronquées
- [ ] **Hauteur cohérente** : toutes les lignes même hauteur `dp(72)`
- [ ] **Spacing** : cohérent entre les lignes
- [ ] **Texte** : aucune coupure, ellipsis (…) si trop long

### Grille 2-colonnes (arrangeSettingsOptions)
- [ ] **Chaque colonne** : largeur égale (-1 adapt écran)
- [ ] **Pas de troncature** : les deux colonnes visibles sans scroll horizontal
- [ ] **Alignement** : centré ou bien justifié
- [ ] **Espacement** : écart régulier entre les colonnes

### Grille 3-colonnes (arrangeSettingsHubOptions)
- [ ] **Chaque colonne** : largeur égale adaptée
- [ ] **Pas de débordement** : les trois colonnes tiennent à l'écran
- [ ] **Focus** : D-pad gauche/droite navigue entre colonnes
- [ ] **Hauteur** : pas d'étirement indésirable

### Couleur de fond
- [ ] **Couleur cohérente** : match le thème sélectionné (pas hardcoded noir)
- [ ] **Contraste** : texte lisible sur le fond
- [ ] **Pas de clignotement** : changement couleur fluide

---

## 🏠 SECTION 3: Raccourcis d'accueil (home shortcuts)

### Section "Raccourcis fichiers"
- [ ] **Titre** : "Raccourcis fichiers" visible
- [ ] **Scroll horizontal** : HorizontalScrollView fonctionne
- [ ] **Tuiles** : icônes et noms de fichiers visibles
- [ ] **Spacing** : écart régulier entre les tuiles (dp(18))
- [ ] **Clipping** : tuiles ne débordent pas du conteneur

### Interaction court-appui (normal)
- [ ] **Clic normal** : ouvre le fichier/dossier
- [ ] **Pas d'erreur** : pas de crash au clic
- [ ] **Navigation** : retour rapide après ouverture

### Interaction long-appui
- [ ] **Long-press détecté** : menu popup s'affiche
- [ ] **Menu positionné** : sous/à côté de la tuile
- [ ] **Options visibles** : "Ouvrir" et "Supprimer" lisibles
- [ ] **Ouvrir fonctionne** : réouvre le fichier via menu
- [ ] **Supprimer fonctionne** : raccourci disparaît immédiatement
- [ ] **Toast** : "Raccourci supprimé" s'affiche

### Gestion des raccourcis
- [ ] **Ajout** : clic sur file → "Raccourci ajouté à l'écran d'accueil"
- [ ] **Pas de doublon** : ajouter plusieurs fois = pas de dupliquer
- [ ] **Suppression** : long-press → supprimer → refresh immédiat
- [ ] **Persistance** : raccourcis survivent aux restarts

---

## 👤 SECTION 4: Cartes profil (Profile Cards)

### État inactif (Ajouter, Supprimer)
- [ ] **Fond** : bleu foncé `rgb(28, 61, 139)` visible
- [ ] **Badge** : vert vif ovale avec bordure
- [ ] **Texte titre** : blanc sur fond bleu = contraste bon ✓
- [ ] **Texte détail** : bleu clair lisible

### État actif (Profil local)
- [ ] **Fond** : bleu très clair `rgb(210, 228, 255)` distinct
- [ ] **Badge** : vert identique aux autres (cohérence)
- [ ] **Texte titre** : bleu foncé sur fond clair = contraste bon ✓
- [ ] **Texte détail** : "Actuellement utilisé" en bleu moyen

### Navigation focus
- [ ] **D-pad Bas** : passe d'une carte à la suivante
- [ ] **Animation** : agrandissement subtile 1.025x (presque imperceptible)
- [ ] **Couleur focus** : fond force au bleu clair au focus
- [ ] **OK/Entrée** : action appropriée (settings/add/settings)

### Interaction au clic/OK
- [ ] **Profil local** : ouvre Account Settings
- [ ] **Ajouter** : ouvre Add Account
- [ ] **Supprimer** : ouvre Account Settings (pour supprimer)
- [ ] **Pas d'erreur** : pas de crash au clic

---

## 📐 SECTION 5: Éléments de fichier

### Icônes
- [ ] **.exe files** : icône exécutable visible (nouvelles)
- [ ] **.pdf files** : icône PDF visible
- [ ] **Dossiers** : icône folder cohérente
- [ ] **Autres types** : icônes appropriées selon type

### Couleurs de dossier (folder button theme)
- [ ] **Fond par défaut** : couleur thème à alpha 26 (très léger)
- [ ] **Fond focus** : couleur thème à alpha 200 (brillant)
- [ ] **Bordure** : visible mais subtile
- [ ] **Transition** : changement couleur fluide au focus

---

## 🎮 SECTION 6: Navigation D-pad globale

### Home screen complet
- [ ] **En haut** : focus barre d'en-tête (Search, Settings, etc.)
- [ ] **Gauche/Droite** : navigation entre commandes header
- [ ] **Bas** : descend vers applications
- [ ] **Haut** : remonte vers header

### Paramètres
- [ ] **Navigation linéaire** : D-pad bas/haut entre lignes
- [ ] **Gauche/Droite** : navigue entre colonnes (si grille)
- [ ] **Pas de focus perdu** : focus reste visible à tout moment

### Profil
- [ ] **Entre les trois cartes** : D-pad bas/haut fonctionne
- [ ] **Pas de saut** : navigation fluide et prévisible

---

## ⚠️ SECTION 7: Régression (vérifier que rien n'a cassé)

### Fonctionnalités existantes
- [ ] **Menu fichier local** : long-press toujours fonctionne
- [ ] **Menu fichier SMB** : long-press toujours fonctionne
- [ ] **Navigation D-pad** : complète et sans dead-spots
- [ ] **Applications** : affichage et navigation intacts
- [ ] **Recherche** : toujours accessible et fonctionnelle
- [ ] **Multi-sélection** : badge ✓ toujours visible
- [ ] **Clipboard** : copy/paste/move toujours opérationnel

### Performance
- [ ] **Pas de lag** : navigation fluide
- [ ] **Pas de crash** : aucune exception au runtime
- [ ] **Mémoire** : pas de fuite apparente (test long-run)

---

## 📊 RÉSUMÉ DE CONFORMITÉ

**Avant de valider la build APK, confirmer:**

- [ ] Tous les items de la Section 1 sont ✓
- [ ] Tous les items de la Section 2 sont ✓
- [ ] Tous les items de la Section 3 sont ✓
- [ ] Tous les items de la Section 4 sont ✓
- [ ] Tous les items de la Section 5 sont ✓
- [ ] Tous les items de la Section 6 sont ✓
- [ ] Tous les items de la Section 7 sont ✓

**Signature:**
- Testeur: ________________
- Date: ________________
- Appareil: ________________
- Résolution: ________________
- Notes: ________________

---

## 🚀 SI TOUS LES POINTS SONT VALIDÉS

✅ **L'application est prête pour production**

→ Générer APK de release
→ Valider sur plusieurs appareils TV
→ Préparer déploiement
