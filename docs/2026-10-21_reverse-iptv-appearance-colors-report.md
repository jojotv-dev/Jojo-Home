# Couleurs TiviMate dans Apparence IPTV

Analyse du 21 octobre 2026. Les valeurs de référence proviennent de TiviMate 5.3.3 installé sur la TV .119. Le code de Jojo Home a été modifié sans compilation ni installation ; son rendu sur TV reste à valider.

## Périmètre

Autorisation : demande de l’utilisateur de corriger les couleurs TiviMate proposées dans Apparence du module IPTV, et autorisation antérieure d’observer TiviMate sur la .119. Analyse locale et lecture des ressources APK ; pas de modification de TiviMate ni de lecture de ses données de compte. Modification limitée aux couleurs et à leur application dans `app/src/main/java/com/jojotv/home/iptv/IptvActivity.kt`. La palette JojoHome conserve son chemin existant.

## Preuves

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E1 | APK de la .119, paquet `ar.tvplayer.tv`, version 5.3.3, code 1000005332 | `adb -s 192.168.1.119:5555 shell pm path ar.tvplayer.tv`, puis `adb pull` du chemin retourné | SHA256 : beaebb25bf818450bf496d4cefb16885b190f156815dea280c31eaae0909ee6c |
| E2 | Table `resources.arsc`, styles `0x7f1404a3` à `0x7f1404b4` ; nuances de fond `0x7f040133`, `0x7f040134`, `0x7f040136` | `aapt2 dump resources <APK>` et lecture des références numériques de la table compilée | Même APK que E1 |
| E3 | Styles de surbrillance `0x7f140493` à `0x7f1404a2`, attribut `0x7f040132` | Même relevé de ressources que E2 | Même APK que E1 |
| E4 | Source locale avant modification : `themePalette()`, `applyTheme()`, `surface()` | Diff avec la copie préalable `IptvActivity.kt.before` | Voir le répertoire temporaire ci-dessous |

Les ressources drawable `res/2e5e71us.xml` et `res/2teiqvfB.xml` référencent respectivement les attributs de fond principal et de fond relevé. `res/1diilgB.xml` référence le fond sombre. Les libellés français et anglais proviennent des chaînes du même APK.

Dossier de preuves de cette session : `C:\Users\arnau\AppData\Local\Temp\jojo-tivimate-theme-colors-zp__2kfj`. Il contient l’APK, son relevé AAPT2, les références numériques, la copie avant modification et un aperçu des couleurs normal/focus. Ce dossier temporaire n’est pas une archive permanente du projet.

## Constats et correction

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F1 | n/a_re | E2, E3, E4 | Confirmé par les ressources | `themePalette()` et `applyTheme()` | Corrigé dans le code : fond principal, sombre et relevé sont distincts de la surbrillance. |
| F2 | n/a_re | E2, E4 | Confirmé par les ressources | Présélection `tivimate:darkGrey` | Corrigé : « Sombre » et « Gris foncé » ont des entrées distinctes. |
| F3 | n/a_re | E4 | Confirmé par le code | Déduplication par couleur | Retirée pour que deux libellés partageant un RGB gardent leurs identifiants persistés. |

P1, `path_type=callflow` : ressources E2/E3 → `themePalette()` → choix conservé par sa clé → `applyTheme()` → `surface()` → panneaux et titres. La pastille du menu utilise la couleur principale du fond lorsqu’on choisit un arrière-plan ; les autres champs conservent la couleur de surbrillance. Les fonds principal et relevé sont les ancrages utilisés par les panneaux et leurs titres ; les autres surfaces de Jojo Home restent interpolées entre ces nuances. Cette adaptation conserve les composants de Jojo Home et ne constitue pas une copie intégrale du moteur de rendu TiviMate.

Les clés des 18 choix existants sont conservées, y compris `tivimate:white`. Ce dernier reste un choix de compatibilité blanc, sans triplet de fond extrait. « Sombre » est ajouté. La sélection instantanée et l’enregistrement asynchrone précédemment ajoutés sont conservés. Les règles de contraste au focus restent actives ; aucune couleur de texte personnalisée n’est imposée si elle rend le texte illisible.

## Palette des fonds extraite

Valeurs RGB hexadécimales opaques, sans préfixe alpha `FF`.

| Libellé | Principal | Sombre | Relevé |
|---|---|---|---|
| Ambre | 58491D | 3A3113 | 756226 |
| Noir | 131313 | 060606 | 202020 |
| Bleu | 1F415C | 142B3D | 29577B |
| Bleu-gris | 2C333C | 1C232C | 3C434C |
| Marron | 362923 | 201913 | 4C3933 |
| Cyan | 174247 | 0F2C2F | 1F585F |
| Sombre | 232629 | 131619 | 333639 |
| Gris foncé | 262626 | 161616 | 363636 |
| Vert | 194C1B | 103312 | 216624 |
| Gris | 363636 | 262626 | 464646 |
| Indigo | 1F295F | 151B3F | 2A377F |
| Citron | 575C1F | 3A3D14 | 747B29 |
| Orange | 553E1C | 392912 | 725325 |
| Rose | 642138 | 431625 | 862D4A |
| Violet | 4E1D57 | 34133A | 692674 |
| Rouge | 6B2523 | 471817 | 8F312F |
| Sarcelle | 164440 | 0E2D2A | 1D5B55 |
| Jaune | 696223 | 464117 | 8D822F |

## Vérifications

- Comparaison automatique du code avec les références numériques de l’APK : les 54 nuances de fond et 16 couleurs de surbrillance contrôlées concordent exactement.
- Les 18 clés TiviMate existantes restent reconnues.
- Analyse syntaxique isolée des blocs modifiés : aucun diagnostic ; les six limites de grammaire déjà présentes dans l’analyse du fichier Kotlin entier restent inchangées, aux décalages de lignes près.
- Diff et espaces vérifiés. Les autres modifications simultanées du fichier, notamment le lancement d’un épisode depuis une vignette, sont préservées.
- Aperçu des lignes normal/focus inspecté visuellement ; contrôle de 36 combinaisons de couleurs avec contraste minimal de 4,80:1 pour le texte et l’icône. Cet aperçu reproduit les couleurs et la règle de contraste, pas le rendu Android de la nouvelle APK.
- Aucun test Gradle, aucune compilation, aucune installation.

## Limites d’observation

L’application TiviMate de la .119 a été ouverte, mais la proposition de mise à jour est restée affichée malgré les commandes ADB tentées. L’écran Apparence de TiviMate n’a pas pu être parcouru pendant ce relevé. L’utilisateur a été invité à fermer cette proposition et ouvrir les couleurs. Aucune mise à jour TiviMate n’a été lancée.

Les outils REA n’étaient pas chargés dans la session et le diagnostic sans installation a confirmé l’absence du paquet CLI demandé. L’inspection a donc utilisé AAPT2 35.0.0 et la table de ressources du fichier APK. Les observations de ressources sont établies ; l’équivalence visuelle complète entre les deux applications reste non validée. La transparence et le contenu situé derrière un panneau influencent aussi le rendu à l’écran.

## Chronologie

1. Relevé des palettes existantes et copie de l’APK installé sur la .119.
2. Identification des trois nuances de chaque fond et de la palette de surbrillance.
3. Correction des couleurs et de leur application, maintien des clés existantes.
4. Comparaison automatique avec l’APK, contrôle syntaxique, diff et aperçu normal/focus.
5. Validation Android et TV laissée en attente d’une APK compilée et installée par l’utilisateur.
