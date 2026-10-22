# Navigation D-pad IPTV : référence TiviMate et corrections Jojo Home

## Périmètre et état de livraison

Demande : reproduire les interactions D-pad de TiviMate dans le module IPTV,
notamment le focus, OK, les groupes et les vignettes. Appareil autorisé :
`192.168.1.163:5555`, Fire TV AFTMM. Aucun autre appareil n'est utilisé.
Les manipulations concernent la navigation des applications installées, leurs
captures et une copie en lecture seule de l'APK TiviMate. Aucun fournisseur,
compte, profil, favori ou réglage de synchronisation n'est modifié.

Les corrections sont dans le code. Leur comportement Android reste à valider
avec une nouvelle APK. L'utilisateur demande ensuite « ne compile pas » : la
commande Gradle déjà démarrée est interrompue, ses processus ne restent pas
actifs et aucune installation n'a lieu. Aucune compilation supplémentaire
n'est exécutée après cette consigne.

Les preuves et les scripts sont dans
[`output/iptv-dpad-20261021`](../output/iptv-dpad-20261021/).

## Identité et limites de REA

| Élément | Observation |
| --- | --- |
| TiviMate installé | `ar.tvplayer.tv`, 5.4.0 Beta 4, code 1000005404 |
| APK copiée | `tivimate-tv163.apk`, 22 918 616 octets |
| SHA-256 | `94f1e8f0fb2b48c571fa633b70282b58db4bc49324f74857d362025ae64b294d` |
| Jojo Home installé | `com.jojotv.home`, 0.2.48, code 163 ; installation du 21 octobre à 21:11 |
| REA | JADX headless 0.7.1 ; inventaire de 6 466 classes |

REA inspecte l'APK sans l'exécuter. La recherche exhaustive de noms contenant
`ar.tvplayer` ne donne que cinq classes Application ou classes imbriquées.
Elle ne permet pas de récupérer les contrôleurs de navigation déclarés dans
le manifeste. Leur implémentation interne reste inconnue.

Le serveur REA disponible n'expose pas de capture Android en exécution.
Les comportements ci-dessous sont donc observés par **ADB, screenshots et
UIAutomator**, et non par un outil d'exécution Android REA. Aucune équivalence
du code interne ou du rendu complet de TiviMate n'est revendiquée.

## Preuves

Les captures ont un PNG, un XML et un JSON de même nom. Le JSON conserve
l'appareil, les touches, les heures de début/fin et le focus. Une capture
prend plusieurs secondes : elle prouve un état après la touche, pas un délai
de réaction de quelques millisecondes.

| ID | Source et commande de reproduction | Observation et empreinte |
| --- | --- | --- |
| E01 | REA `inspect_android_package` sur l'APK copiée ; [`rea-evidence.json`](../output/iptv-dpad-20261021/rea-evidence.json) | Identité ci-dessus ; Evidence `ev_fdbcfc26826f641c4c52a68eef0987e8b19b934745c548f178e6572ca5d7820a` ; hash de l'APK ci-dessus |
| E02 | REA `search_android_classes`, query `ar.tvplayer` | Cinq correspondances ; Evidence `ev_0039a793fb0d57486c8d98c234ecc07ee9408a35f1bc38f521493c27989b2e16` ; même APK |
| E03 | `Capture-TvState.ps1 -Label t03-group-down -Keys KEYCODE_DPAD_DOWN`, depuis t02 | Le groupe devient LIGUE 1+, son contenu change et le focus reste dans les groupes ; PNG SHA-256 `6B7FE49E036F47F0AC6C38F4A18EA3D676E3979500257D18103FFD2C8CD98B16` |
| E04 | `Capture-TvState.ps1 -Label t04-group-enter -Keys KEYCODE_DPAD_CENTER` ; puis Gauche en t05 | OK entre dans la première chaîne ; Gauche retrouve LIGUE 1+ ; t04 PNG SHA-256 `28A24B8AB30F5ECDF1BE9DA84C80591D7019396BC142CF06BE131C7FE4A7300F` |
| E05 | t06 menu principal, puis Bas en t07 et OK en t08 | Survoler Films dans le menu principal conserve l'ancien contenu TV ; OK ouvre Films. Ce fonctionnement existant de Jojo Home est conservé ; empreinte n/a, série de captures |
| E06 | t10 fournisseur, OK en t11, Bas en t12, OK en t13 | Le fournisseur charge son contenu au focus ; OK déplie sans quitter son en-tête ; OK sur Tous les films entre dans les affiches ; empreinte n/a, série de captures |
| E07 | Droite en t14, OK en t15, Retour en t16 | OK sur Coup de foudre en Amazonie ouvre sa fiche avec Regarder focalisé ; Retour retrouve la même affiche ; t15 PNG SHA-256 `5CAC974F9A0DB08566DF89BC0E6897A9643EC56D95C600A8A39E4357D432A19C`, t16 `09BC8F1438DEA33E74273D4917FC482874BDA08EE5E13BA331267F8B553FDD44` |
| E08 | Gauche en t17 puis Gauche en t18 | Première affiche, puis retour au groupe exact Tous les films ; empreinte n/a, série de captures |
| E09 | Source avant correction, [`IptvActivity.kt.before`](../output/iptv-dpad-20261021/IptvActivity.kt.before) | `clearFocus()` au recyclage du guide, suppression du rail VOD, restauration conditionnée par un mode mutable, validation via le focus au relâchement ; SHA-256 `3720A6613AFCE7AEF701D568CBFE3327C068D43000F4C09EB67F659BE6A43E5F` |
| E10 | `node output/iptv-dpad-20261021/run-focus-tests.cjs` ; même commande avec `--baseline` | 27 succès sur le code corrigé ; 11 échecs sur 27 avec la source initiale ; [`focus-tests.log`](../output/iptv-dpad-20261021/focus-tests.log), [`focus-tests-baseline.log`](../output/iptv-dpad-20261021/focus-tests-baseline.log) ; source corrigée normalisée LF SHA-256 `8b0fcc3d54fb83b8a7915f04bf005836e1a4f58ed3e617a1e42ea098dfb13c87` |
| E11 | Jojo Home installé : Accueil → Apps → IPTV → compte existant → TV → Droite en j14, puis Droite en j15 | Le module installé est accessible sans modifier le lanceur ; captures j13 à j16. Elles portent sur la version déjà installée et ne valident pas le correctif ; empreinte n/a, série de captures |

Le script de capture est borné à la .163. Il dépend de l'état courant : les
commandes ci-dessus ne sont pas un scénario autonome depuis un écran inconnu.

## Constats avant correction et réponses du code

| ID | Preuves | Constat et statut | Emplacement et correction |
| --- | --- | --- | --- |
| F01 | E03, E04, E09 | Confirmé dans la source, confiance élevée : le recyclage du guide retire explicitement le focus. La sélection de repli exacte d'Android n'est pas démontrée par le code. | `IptvActivity.renderGuide` : conserver la cellule du même programme ; actualiser sa description sans réémettre un faux événement de focus ; restaurer seulement si aucune nouvelle touche n'est intervenue. |
| F02 | E06, E08, E09 | Confirmé dans la source, confiance élevée : cache puis fournisseur reconstruisent les affiches, leurs demandes d'images et leur focus. | `displayItems -> updateVodCards` : réconcilier les éléments du rail existant par identité ; garder les lignes du cache pendant les pages partielles, puis appliquer la liste fournisseur finale. |
| F03 | E09, E10 | Reproduit en JVM, confiance élevée dans la limite des substituts : un ancien rappel VOD peut sélectionner une ancienne position après une nouvelle navigation ou un réordonnancement. | `focusVodCard -> doOnNextLayout` : numéro de demande, version des entrées et recherche de l'identité au moment du rappel. |
| F04 | E09, E10 | Reproduit en JVM : OK peut valider une nouvelle vue ou une vue réaffectée à un autre programme entre DOWN et UP. | `dispatchKeyEvent -> IptvRemotePress` : capturer vue, identité et heure ; annuler si la cible change. Le timer d'appui long vérifie aussi l'identité. |
| F05 | E03, E06, E09 | Confirmé dans la source : délai artificiel de 150 ms et messages de chargement qui effacent un contenu déjà disponible. Le temps exact de TiviMate n'est pas mesuré. | `buildGroups`, `loadCategories`, `loadItems`, `selectProviderCategory` : traiter le focus à la prochaine boucle UI et afficher le cache sans écran de chargement intermédiaire. |
| F06 | E09 | Confirmé dans la source : le payload de progression rebinde toute l'affiche et repasse dans le chargeur d'images. | `addVodCards` : mettre à jour uniquement le badge pour le payload progress. La clé de réutilisation inclut les couleurs et la taille du texte afin d'appliquer un changement de thème. |
| F07 | E07, E09 | Différence de comportement confirmée : OK sur une affiche de film lance directement la lecture dans Jojo Home, alors que le parcours TiviMate observé ouvre sa fiche. | `addVodCards` : OK ouvre la fiche existante ; Regarder utilise ensuite le lecteur Jojo Home existant. |

Les changements applicatifs de ce lot restent dans `IptvActivity.kt`. Aucun
modèle, stockage, lecteur, API publique ou dépendance n'est changé. Les
épisodes déjà présents dans l'historique gardent leur lecture directe ;
l'ouverture de fiche concerne les films et les séries.

Pendant l'intervention, d'autres modifications de thème apparaissent dans
ce même fichier (`themeSurface`, `themeRaisedSurface`, palette et `surface`).
Elles sont conservées et ne sont pas attribuées au lot navigation. Le diff
global depuis la copie initiale contient donc aussi ce travail concurrent.
La vérification finale compare les blocs focus/validation exécutés par les
tests JVM à la source actuelle : ils sont identiques. Après les retouches
de l'action des affiches et les changements de thème, le hash LF du fichier
observé est `1f99bf5ad1ed44f017e303956d4a2b5298c0c8b22052371fbac405ca7d558c89`.

## Chemins vérifiés

- P01, navigation observée : guide TiviMate → Gauche → groupe → Bas → contenu
  du nouveau groupe → OK → première chaîne → Gauche → groupe initial.
  Preuves E03/E04 ; constats F01/F05.
- P02, validation observée : Tous les films → OK → affiche → Droite → OK →
  fiche Regarder → Retour → même affiche → Gauche → première affiche →
  Gauche → Tous les films. Preuves E06/E07/E08 ; constat F07.
- P03, chemin de code : page fournisseur → publication → `displayItems` →
  rail existant → changements ciblés → restauration par identité, invalidée
  par une nouvelle touche. Preuves E09/E10 ; constats F02/F03/F06.
- P04, chemin de code : DOWN → cible capturée → éventuel recyclage/focus →
  UP → validation ou annulation. Preuve E10 ; constat F04.

## Vérifications et limites

| Vérification | Résultat |
| --- | --- |
| Régressions focus/validation JVM | 27/27 ; la même source avant correction échoue sur 11 scénarios |
| Navigation source | 32/32 avant les changements de thème concurrents ; sélection finale centrée sur sidebar, arbre, entrée et guide : 21/21 |
| Fiches et retour VOD source | Sélection finale : 10/10, dont OK sur film, progression tardive et retour de lecture |
| Performance source | 12/14 ; les deux mêmes échecs géométrie grille/hero sont reproduits avec la source initiale et restent hors de cette correction |
| Ancien contrat source des badges | Échec de l'assertion sur le stockage des profils, également reproduit avec la source initiale ; les tests de rafraîchissement tardif et de retour restent verts |
| Suite IPTV élargie exécutée avant la consigne | 310/314 ; quatre échecs de persistance/progression listés ci-dessous ; aucun fichier de stockage ou de modèle n'est modifié |
| Contrôle Kotlin intermédiaire | Réussi avant les derniers ajustements ; aucun contrôle de types final après « ne compile pas » |
| Diff isolé | Aucun défaut d'espacement |
| Build APK | Démarré, puis arrêté à la demande de l'utilisateur pendant la configuration ; aucune APK corrigée produite |
| Installation | Aucune |
| Validation de la correction sur Android | En attente d'une APK compilée et installée par l'utilisateur |

Les quatre échecs de la suite élargie sont
`nextEpisodePreferencesStayPrivateToEachAccountAndSurviveNasSync`,
`subtitlePreferencesStayPrivateToEachAccountAndSurviveNasSync`,
`privatePlaybackIdsKeepProviderIdentityAndSeparateEpisodeProgress` et
`audioLanguageChoicesStayPrivateToEachAccountAndSurviveNasSync`.
Le détail reste dans [`iptv-junit.log`](../output/iptv-dpad-20261021/iptv-junit.log).

Les 27 tests utilisent des fonctions et blocs extraits du code réel, avec des
substituts JVM pour les vues et le rail. Ils ne prouvent pas le rendu Android,
le comportement de RecyclerView sur une vraie fenêtre, la latence réseau,
la longue pression physique ou le contraste des thèmes sur la nouvelle APK.
Les assertions de source ne remplacent pas une recette D-pad.

Checklist restante après installation : TV puis Films/Séries ; changement
rapide de trois groupes ; navigation sur plusieurs rangées ; OK et Retour
d'une fiche ; retour de lecture ; arrivée tardive des pages et de l'EPG ;
changement de thème ; états normaux/focalisés des icônes et textes ; appui
long physique. Les rubriques non observées ne sont pas déclarées identiques
à TiviMate. Le premier téléchargement d'une image absente du cache dépend
toujours du réseau.

## Chronologie

1. Identification de la .163 et des versions réellement installées.
2. Copie de l'APK TiviMate, analyse REA et export des deux Evidence.
3. Captures TiviMate TV/groupes puis Films/fiche/retour avant modification.
4. Audit du code, sauvegarde initiale et corrections limitées au module IPTV.
5. Revue indépendante : conservation de la description EPG, clé de thème et
   suppression d'un message de chargement redondant.
6. Tests JVM : 27/27, contre 11 échecs avant correction ; contrôles source.
7. Démarrage d'une compilation de contrôle, puis arrêt immédiat à la consigne
   « ne compile pas » ; pas de nouvelle compilation ni installation.
8. Observations de l'ancienne application Jojo Home et rédaction du rapport.

Graft CLI et outils MCP indisponibles : deux tentatives au total, aucun jeton
économisé. Repli sur recherches et lectures ciblées, sans installation d'outil.
