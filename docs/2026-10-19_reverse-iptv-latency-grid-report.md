# IPTV : lenteurs, navigation et grille

## Perimetre

Demande : analyser `C:/Users/arnau/Desktop/IPTV.mkv`, comparer TiviMate sur
`192.168.1.119:5555`, corriger les chargements, les favoris, les accroches et
la presentation des catalogues Films/Series. Le lecteur JoJoHome reste en place.
Aucune compilation Android, installation, mise a jour de TiviMate ou modification
des fournisseurs n'est autorisee dans cette intervention.

## Preuves recueillies avant modification

| Preuve | Source et reproduction | Constat | Limite |
|---|---|---|---|
| E1 | `ffprobe ... IPTV.mkv` ; planche `output/iptv-latency-grid-20261019/video-six-second-contact.png` | Video 101,83 s ; chargement persistant sur plusieurs images espacees de 6 s ; favoris retardes ; retour a l'accueil en fin de sequence | La video ne fournit ni stacktrace ni horodatage des touches |
| E2 | `adb -s 192.168.1.119:5555 shell dumpsys dropbox --print data_app_crash` | JoJoHome 0.2.33 a subi un `OutOfMemoryError` le 18/10/2026 a 11:05:44 | Crash anterieur : correlation avec la video du 19 non prouvee |
| E3 | APK installee extraite en lecture seule ; REA `inspect_android_package` | `ar.tvplayer.tv`, 5.3.3, 6545 classes ; preuve `ev_d54ebe8db3d60cf079dcd47f59f49a02390b8aaead24f0dae0b4cc6843df5e19` | Inspection statique ; signatures et execution non verifiees |
| E4 | Capture `tivimate-update.png`, puis confirmation de l'utilisateur | Dialogue proposant 5.4.0 Beta 2 ferme avec la telecommande ; l'arbre redevient accessible | Aucune mise a jour lancee |
| E5 | `IptvActivity.loadItems`, `addVodCards`, `bindVodPresentation`, `loadImage` | Publication de listes cumulees ; carrousel horizontal ; rechargements d'illustrations identiques ; requetes d'images anciennes non annulees par selection | Analyse du code courant, pas de l'APK de la video |
| E6 | `IptvStore.cache`, `registerCloudCatalogue`, `addChannelDates`, `commit` | Enregistrement cloud bloque la reception de chaque page ; ecritures completes de bibliotheque pour les dates LIVE ; selections et favoris partagent le verrou de bibliotheque | Cout exact sur la nouvelle APK non mesure |
| E7 | `tivimate-films-layout-menu.xml`, `tivimate-recent-films-grid.png/xml`, `tivimate-grid-next-row.png/xml` | Dans TiviMate 5.3.3, "Tous les films" peut etre regroupe par categories ; un groupe de 1648 films est une grille. Bas passe de 1/1648 a 8/1648, donc 7 colonnes dans cette configuration | Les affiches restent des placeholders sur ce fournisseur ; les temps de favoris et les fiches completes ne sont pas mesures |

Les interactions Android ont ete observees par ADB (captures et arbre
UIAutomator), pas par un outil d'execution Android REA. REA a inspecte
l'APK reellement installee. SHA-256 de cette APK :
`beaebb25bf818450bf496d4cefb16885b190f156815dea280c31eaae0909ee6c`.

## Constats et chemins

- F1, confirme dans le code : le catalogue VOD est un `RecyclerView.HORIZONTAL`
  de 186 dp de haut. Chemin P1 : `displayItems -> addCards -> addVodCards`.
- F2, confirme dans le code : les illustrations et details du hero partagent
  les workers d'images et les demandes obsoletees persistent jusqu'a leur fin.
  Chemin P2 : focus affiche -> `updateInfo -> bindVodHero -> loadImage`.
- F3, confirme dans le code : les pages fournisseur attendent les travaux de
  cache et d'enregistrement cloud. Chemin P3 : `loadItems -> loadCatalogue ->
  onPage -> store.cache -> registerCloudCatalogue`.
- F4, confirme historiquement : manque de memoire sur .119. Le lien entre F2/F3
  et le crash filme reste une hypothese, pas une cause demontree.
- F5, confirme dans le code : les lectures de progression et les commandes de
  favoris utilisent la meme file `libraryWork`. Une lecture lente retarde donc
  la commande de favoris. Son enregistrement local reste durable ; le NAS
  et le serveur cloud ne sont pas attendus pour confirmer un ajout.

Le mapping R8 disponible ne correspond pas au crash historique :
identifiant `f1bd5999...` contre `b764f671...` dans la trace. Les noms
obfusques ne sont donc pas attribues a des fonctions du code actuel.

## Corrections par lot

### Retour TV et correction du 20/10/2026

L'utilisateur signale une affiche unique et agrandie pendant plus d'une minute,
puis des affiches trop grandes, ainsi qu'un hero reduit sans demande. Les tests
du 19/10 n'avaient pas valide le rendu Android et n'ont pas detecte ces regressions.

- Le nombre de colonnes est maintenant calcule dans `RecyclerView.onMeasure`,
  avant `super.onMeasure`, et non apres la premiere disposition. La premiere
  affiche ne depend plus d'une nouvelle page fournisseur ou d'un rafraichissement
  des badges pour prendre sa place dans la grille.
- Les affiches et leurs demandes d'images restent fixes a 120 x 180 dp ; elles
  ne s'etirent plus pour occuper la largeur restante d'une colonne.
- `layoutVodHero` est restaure a l'identique de la sauvegarde anterieure a la
  grille, avec les dimensions precedentes du logo, des credits et du synopsis.
  La reduction decrite dans le lot 1 ci-dessous est donc annulee.
- Verification : 7 tests JVM isoles, 8 controles statiques cibles et l'analyse
  syntaxique de quatre fichiers Kotlin passent. Les tests de premiere mesure
  couvrent une largeur initiale nulle, les changements de largeur et quatre
  densites ; leurs vues sont des substituts JVM, pas un rendu Android.
- La selection VOD/navigation donne 55 succes et les quatre echecs deja listes.
  Aucune compilation Android, installation ni nouvelle action sur TV. La duree
  d'attente constatee sur appareil reste a recontroler apres installation.

### Lot 1 : grille et illustrations

- `IptvActivity.addVodCards` utilise `GridLayoutManager`, une hauteur limitee
  au viewport et des affiches 2:3 recyclees. Le hero du catalogue est compacte
  pour laisser apparaitre les rangees ; les fiches Films/Series restent intactes.
- Les quatre directions de la telecommande suivent la grille. Gauche depuis
  la premiere colonne rend le focus au groupe ; le retour de lecture conserve
  la recherche de l'identifiant exact, y compris apres recreation asynchrone.
- La premiere mesure utilise une largeur de secours de 120 dp, jamais une
  affiche de 1 px qui provoquerait la creation massive de cellules.
- Chaque image a une portee HTTP annulable : remplacement, recyclage,
  changement de saison, changement d'ecran et destruction annulent ses appels.
  Un echec libere sa demande pour permettre une nouvelle tentative au rebind.
- Les details fournisseur du hero ne passent plus dans la file des affiches.
  Les logos sont toujours charges pour le hero selectionne, sans precharger
  tous les logos des affiches voisines. La limite memoire des bitmaps reste 8 Mo ;
  les demandes de fond du hero sont bornees a 1920 px.

Verification apres ce lot : 2 tests JVM de navigation passent ; quatre fichiers
Kotlin sont analyses sans erreur de syntaxe. Les contrats statiques de la grille
et de sa geometrie passent. Les quatre echecs VOD deja presents sont listes
ci-dessous, sans etre transformes en succes.

### Lot 2 : cache, progression et favoris

- La lecture du cache n'attend plus la sauvegarde des selections. Le filtre
  choisi est applique immediatement a l'affichage et aux pages suivantes,
  avec une persistance distincte en arriere-plan.
- Les lectures de progression ont une file `progressWork` distincte des
  mutations de favoris. Les lectures obsoletees sont annulables.
- La reception des pages n'attend plus leur enregistrement cloud. Une seule
  tache traite un instantane en attente par catalogue ; les pages recentes
  remplacent cet instantane au lieu d'empiler une tache par page.
- Cette tache est annulee au changement de groupe ; le compte et le profil
  captures sont verifies avant l'enregistrement. Les autres appelants de
  `IptvStore.cache` conservent leur comportement par defaut.
- Le format chiffre, l'ecriture atomique, les donnees des fournisseurs et
  l'enregistrement durable des favoris ne sont pas modifies. Les dates LIVE
  restent sauvegardees comme auparavant : leur cout n'est pas traite ici.

Verification apres ce lot : 4 tests JVM passent, dont deux exercant le bloc
de regroupement extrait du code de production. Pendant un traitement bloque,
200 publications sont reduites aux instantanes de tailles 1 et 200 et une
commande dans la file des favoris termine sans attendre. Un changement de
generation annule l'ancien traitement sans enregistrer les pages en attente.

## Validation et suites

Commandes reproductibles, sans Gradle ni generation d'APK :

```powershell
node output/iptv-latency-grid-20261019/run-isolated-tests.cjs
node --test output/iptv-latency-grid-20261019/performance-static.test.cjs
```

Resultat final : 4 tests JVM, 6 controles statiques cibles et l'analyse
syntaxique de `IptvActivity.kt`, `IptvStore.kt`, `IptvModels.kt` et
`IptvParsingTest.kt` passent. Aucun diagnostic d'espace blanc dans les diffs
isoles des quatre fichiers source/tests modifies.

Les controles VOD existants donnent 27 succes sur 31 ; les quatre echecs
suivants existent egalement sur la sauvegarde des sources anterieure a ce lot :

- `Hero: native bitmap, fade and real logo preparation remain shared`
- `Title logo: failure releases only the current logo so a later bind can retry`
- `Editorial: exact title/year matching, French retrieval and missing fields are retained`
- `Badges: native dimensions, check mark, background and contrasting text are reused`

Une selection supplementaire de controles partages donne 94 succes sur 95.
L'echec `Displayed-photo notifications retain current-slot tokens while the
queue retargets pending loads` concerne le module Photos, non modifie ici.
Il n'est pas compte comme un succes ni corrige hors perimetre.

Graft : CLI et outils MCP absents ; 2 tentatives, aucun jeton economise.
Aucun type-check Android complet, lint Android, build, installation ou test
de la nouvelle interface sur appareil n'a ete execute.
La validation physique de JoJoHome apres correction reste necessaire apres
compilation et installation par l'utilisateur. Le succes de tests isoles ne
prouve ni le temps fournisseur ni l'absence de crash sur TV.

Parcours de recette : ouvrir Films puis Series ; descendre plusieurs rangees,
revenir aux groupes par Gauche, enchainer trois groupes rapidement, ajouter
un favori et attendre sa confirmation, lire un titre puis revenir a son focus.
Verifier les affiches apres defilement, le hero sans chevauchement et la
memoire pendant dix minutes. Si le retour a l'accueil se reproduit, relever
immediatement la trace et l'heure avec le mapping de cette APK precise.

## Chronologie

1. Lecture de la video et extraction des images de controle.
2. Lecture des traces disponibles avant ouverture de TiviMate.
3. Identification de l'APK installee avec REA, sans execution par REA.
4. Observation du dialogue de mise a jour sur .119 ; aucune installation.
5. Analyse separee de l'interface et du stockage avant correction.
6. Apres fermeture du dialogue par l'utilisateur : observation des groupes,
   du menu de tri et de la grille TiviMate sur .119.
7. Corrections par lots avec controles apres chaque lot ; aucun deploiement.
