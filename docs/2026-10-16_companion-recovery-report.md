# Préparer la réparation du Companion et la récupération des progressions

**État au 16 octobre 2026 à 20 h 44 : le correctif serveur des suppressions différées est installé et vérifié sur le NAS, après accord de l'utilisateur.** Cette seconde intervention a nécessité une seule relance du Companion, désormais au PID 119049. Les contrôles de persistance sont réussis ; aucune récupération historique, mise à jour d'APK ou intervention Supabase n'a été effectuée. [Dernier compte rendu de déploiement](../output/iptv-sync-20261016/deferred-delete-fix/deployment-final-summary.json). Les premières sections ci-dessous retracent l'installation précédente.

## Première installation et contrôles réalisés — historique

Le [compte rendu final](../output/iptv-sync-20261016/nas-install-final-summary.json) contient les empreintes, les propriétaires et permissions conservés, la sauvegarde et les preuves détaillées. Les sources installées correspondent exactement au diff approuvé et aux sources testées.

| Contrôle | Résultat |
|---|---|
| Processus Companion | Ancien PID 116295, nouveau PID 117385 ; actif, aucun redémarrage automatique supplémentaire. |
| Sauvegarde avant installation | Copie native SQLite incluant le WAL, JSON et sources originales, plus export de lecture stable, conservés dans `/home/freebox/jojotv-recovery/20261016T164300Z-fcba143c`. Intégrité de la copie validée ; données privées conservées sur le NAS. |
| Révisions | Avant installation : 602976, malgré un ancien maximum de journal à 614832. Les nouveaux événements commencent à 614833. Les 56 événements jusqu'à 614888 sont contigus. Au dernier contrôle stable, JSON, snapshot SQLite et maximum du journal sont tous à 614909. |
| SQLite en fonctionnement | `quick_check(10)` retourne `ok`. |
| Maintenance réseau | Règle temporaire sur le seul port du Companion retirée ; garde-fou terminé, aucune minuterie restante. Le garde-fou n'a ni remplacé le code ni relancé le service. |
| Mémoire des contrôles | Lecture en blocs plafonnée à 128 Mio d'espace d'adressage ; maximum mesuré d'environ 26 Mio de mémoire résidente pour les derniers contrôles. La limite n'est pas appliquée au service Node. |

Le premier contrôle HTTP est parti alors que systemd annonçait déjà le service actif, avant l'ouverture du port par Node. Il a échoué avec `URLError`. Le reçu initial est conservé : il ne s'agit pas d'une seconde tentative d'installation. Les contrôles suivants ont confirmé le même processus, les bonnes sources et la persistance ; aucune seconde relance n'a été nécessaire. L'outil local attend désormais la disponibilité HTTP, avec un test reproduisant ce délai de démarrage.

Les clients ont repris leurs écritures NAS avant le comparatif final. Une égalité globale de toutes les données après relance ne peut donc pas être affirmée. Pour Arnaud, la comparaison au 16 octobre à 16:52 UTC relève trois ajouts, une suppression et onze entrées communes modifiées : ces écarts sont retrouvés dans les clés des événements clients. La suppression correspond exactement à sa marque et à sa date dans le journal. Aucune date `lastWatched` n'a reculé. Les **216 marquages vus** et les domaines de lecture des autres profils sont identiques aux copies précédant l'installation. Les nouveaux états sont conservés séparément ; aucun ancien état n'a été réinjecté.

Les champs du snapshot dont les empreintes ont changé sont `events`, `nextRevision`, `profileBlobs`, `profileSecrets` et `watchProgress`. Les valeurs chiffrées de `profileSecrets` n'ont pas été déchiffrées ni comparées ; une nouvelle écriture des routes existantes peut produire un chiffrement différent. Les outils de contrôle n'ont appelé aucune route de modification de progression, de configuration ou de secret : seulement l'authentification NAS et les lectures de statut/profils/progression.

Les **86 tests précédents** des sources et du plan restent valides pour les sources installées. S'y ajoutent **15 tests locaux** de l'outil d'installation/vérification, et **3 vérifications Linux** de remplacement/retour arrière sur des fichiers fictifs isolés, sans toucher au vrai Companion lors de ces essais.

La [proposition actualisée après installation](../output/iptv-sync-20261016/nas-recovery-proposal-after-install.json), calculée depuis une capture conservée à la révision 614888, contient **9 entrées, 5 marques de suppression, 1 opposition lecture/suppression et 9 conflits laissés inchangés**. Elle remplace le chiffrage initial ci-dessous. Elle n'est pas appliquée et doit être recalculée si l'état change : les clients continuent leur synchronisation habituelle. La récupération et les essais Tyson/programmations restent les étapes suivantes, avec les accords distincts prévus.

## Correctif préparé puis installé

Le [diff de production complet](../output/iptv-sync-20261016/nas-companion-reviewed.diff) compare les deux fichiers proposés aux sources originales sauvegardées sur le NAS. Le [diff complémentaire](../output/iptv-sync-20261016/nas-revision.diff) contient la nouvelle protection des révisions et les nouveaux tests, en complément du premier correctif de persistance.

Le [paquet préparé localement](../output/iptv-sync-20261016/jojotv-companion-persistence-reviewed-20261016.tar.gz) contient seulement les deux sources, leurs empreintes et un manifeste de revue. Son contenu a été revérifié après création. Il ne contient aucune donnée applicative ni aucun identifiant de connexion. L'archive est restée locale ; les deux sources ont été transférées séparément. Le [bilan de préparation](../output/iptv-sync-20261016/nas-recovery-preparation-summary.json) décrit l'état historique avant l'accord d'installation ; le compte rendu final en tête décrit l'état installé.

| Fichier et fonctions | Comportement proposé |
|---|---|
| `output/nas/server.js` : `initialiseStore` | Repart du maximum des compteurs JSON, de l'en-tête du snapshot SQLite et des révisions conservées dans leurs événements et le journal SQLite. Ne reconstitue aucun contenu perdu. |
| `output/nas/sync-sqlite-store.js` : `latestRevision`, `appendEvent`, `importEvents` | Lit le maximum du journal sans charger les payloads ; refuse un nouvel événement qui réutilise une révision ; l'import interne de démarrage conserve la ligne déjà présente. Les autres erreurs de contrainte restent des erreurs et annulent le lot. |
| `output/nas/server.js` : `persistStore`, branche `PUT watch-progress` sans changement | Premier correctif conservé : une erreur ne bloque plus définitivement les sauvegardes suivantes ; un renvoi identique n'est acquitté qu'après sauvegarde de sa révision. |
| `output/iptv-sync-20261016/nas_recovery_plan.py` | Produit uniquement une proposition depuis l'export antérieur vérifié et une capture actuelle stable. Aucun import, fusion, écriture de progression ou contrôle du service. |

Les réponses HTTP et le schéma de base restent identiques. Le refus d'un doublon de révision remplace l'ancien écrasement silencieux du journal. Le fallback JSON reste disponible lorsque `node:sqlite` est absent. Les deux fichiers de production doivent être installés ensemble, car `initialiseStore` utilise la nouvelle méthode interne `latestRevision`.

## Proposition initiale de récupération — historique

Ce chiffrage a précédé l'installation et la reprise des écritures clientes. Il est remplacé par la proposition actualisée liée plus haut.

La [proposition détaillée](../output/iptv-sync-20261016/nas-recovery-proposal.json) concerne uniquement le compte NAS vérifié et le profil technique **1**, correspondant au profil Arnaud retenu pour ce diagnostic. Les autres comptes et profils ne reçoivent aucune proposition de modification.

| Domaine | Propositions, sans application | Réserves |
|---|---|---|
| Progressions | 15 entrées : 10 absentes actuellement, 5 dont l'action antérieure porte une date plus récente | 3 de ces propositions remplaceraient une marque de suppression ; validation manuelle obligatoire. |
| Suppressions de progression | 7 marques : 5 absentes actuellement, 2 plus récentes que l'action actuelle | 1 remplacerait une entrée de progression ; l'autre actualiserait une ancienne marque de suppression. Validation manuelle obligatoire pour l'opposition lecture/suppression. La date originale `deletedAt` doit être conservée. |
| Conflits à date identique | 6 cas : 2 positions différentes et 4 différences de métadonnées | Aucune proposition d'écrasement pour ces cas. |
| Éléments vus | 216 marquages identiques | Aucune modification proposée. |

Les **4 propositions opposant lecture et suppression** restent à examiner séparément des **6 conflits**. Les 18 autres propositions restent elles aussi soumises à l'approbation d'une récupération concrète. Aucun programme d'application de la récupération n'est fourni à ce stade.

La comparaison utilise `lastWatched`, `watchedAt` et `deletedAt`, jamais la plus grande position : un retour volontaire en arrière doit pouvoir être conservé. Une suppression gagne en cas d'égalité de date. Le champ serveur `updatedAt` ne départage pas les actions de lecture. Des différences de contenu à date identique restent des conflits ; les erreurs d'horloge des appareils restent une limite.

Le rapport ne contient ni titre, URL, chemin média, clé de contenu brute, mot de passe ni jeton. Ses indices d'entrées se rapportent aux images comparées. La capture actuelle est traitée en mémoire, sans être sauvegardée par ce comparateur : **la proposition expire dès que les données changent**. Une récupération nécessitera une nouvelle capture conservée et vérifiée, puis une nouvelle proposition avant application. Les endpoints `DELETE` ordinaires ne conviennent pas pour rétablir une ancienne suppression car ils créeraient une date de suppression actuelle.

## Constats et preuves

| Preuve | Constat | Suite proposée |
|---|---|---|
| E1 : [contrôle avant intervention](../output/iptv-sync-20261016/nas-predeploy-backup-summary.json), export mémoire 614831 et journal 614832 | F1 : le redémarrage a repris un compteur plus ancien que le journal et un état de progression différent. | Correctif de démarrage et récupération sélective séparée. |
| E2 : [tests de reproduction](../output/iptv-sync-20261016/nas-revision-red.tap), puis [tests finaux](../output/iptv-sync-20261016/nas-revision-final.tap) | F2 : les régressions de compteur et l'écrasement de révisions sont reproduits localement ; les contrôles finaux passent. | Installer les deux sources ensemble uniquement après accord. |
| E3 : [proposition actuelle](../output/iptv-sync-20261016/nas-recovery-proposal.json) | F3 : 22 propositions, dont 4 oppositions lecture/suppression, et 6 conflits distincts ; les 216 marquages vus sont identiques. | Garder les conflits inchangés et préparer un lot de récupération explicitement approuvé. |

Les dates UTC, empreintes et révisions exactes de la capture sont dans E3. Le snapshot SQLite et la mémoire avaient la même révision lors de cette consultation ; le journal conserve une révision supérieure. Cela ne prouve pas que les anciennes modifications absentes sont présentes dans le snapshot.

Lors de la première phase de sauvegarde, un contrôle trop gourmand a très probablement provoqué le manque de mémoire qui a fait arrêter le Companion puis déclenché sa relance automatique. Les deux jeux de sauvegardes sont conservés. Les nouveaux contrôles limitent le processus Python distant à **128 Mio**, les réponses et le temps d'exécution ; ils ne chargent ni le gros `store.json` ni le payload du snapshot SQLite. Le PID et le compteur de relances sont restés identiques pendant les consultations de cette phase. Ce constat ne constitue pas une autorisation de redémarrer le service.

## Tests exécutés

**63 tests Node réussis**, dont 18 contrôles de persistance existants, 9 contrôles de démarrage/SQLite et 36 tests DNS/lecture existants. Les nouvelles vérifications utilisent de vraies bases SQLite fictives, un WAL non checkpointé, la conservation des lignes existantes, le rejet des doublons et l'annulation d'un import invalide. Le serveur HTTP n'est jamais démarré par ces tests.

**23 tests Python réussis** pour le plan de récupération : isolation du compte/profil, captures stables, reprises et retours arrière, suppression contre ancienne lecture, égalités, métadonnées, doublons et refus de connexion sans option explicite. [Résultats Python](../output/iptv-sync-20261016/nas-recovery-tests.txt).

Depuis la racine du dépôt, commandes locales sans réseau :

```powershell
node --test --test-reporter=tap output/nas/server-persistence.test.js output/nas/server-dns.test.js output/nas/server-playback.test.js
& 'C:\Python314\python.exe' -X utf8 -m unittest discover -s output/iptv-sync-20261016 -p test_nas_recovery_plan.py -v
node --check output/nas/server.js
node --check output/nas/sync-sqlite-store.js
```

Le diff ciblé a été contrôlé avec Git ; les fins de ligne existantes du serveur sont conservées. Graft n'est pas disponible dans cette session. Aucun test Android complet, compilation APK, essai sur TV, test Supabase ou test RLS n'a été effectué pendant cette phase. Les contrôles distants sont des consultations SSH et API NAS ; ces outils n'envoient rien à Supabase.

## Autorisations et ordre de la suite

**Les étapes 1 et 2 ci-dessous sont effectuées. Les étapes 3 et 4 restent en attente.**

1. **Accord sur l'installation du correctif uniquement**, avec une courte interruption et une relance du Companion. Avant toute intervention, vérifier les deux empreintes déployées contre les originaux et conserver les sauvegardes. Préparer une période sans écritures des clients et vérifier que l'état courant de tous les domaines synchronisés est bien durable ; si ce point échoue, arrêter la procédure avant la relance. Le journal doit être conservé avec les données SQLite, sans suppression de WAL ou SHM.
2. Sous cet accord, remplacer ensemble les deux fichiers de code, en conservant leurs droits et propriétaires, puis contrôler le démarrage, la révision et les écritures. Une simple réponse `/health` ne suffit pas à valider la persistance. Le retour arrière porte sur les fichiers de code sauvegardés ; aucune ancienne base ou ancien JSON ne doit être réinjecté automatiquement. Une erreur de démarrage impose de vérifier les effets de l'initialisation avant toute relance supplémentaire.
3. **Accord distinct sur un lot de récupération** : après une capture actuelle conservée et une comparaison refaite, présenter les entrées, suppressions, conflits et empreintes du lot. Toute différence depuis cette comparaison impose de la recalculer. L'application devra conserver les dates originales, les nouveaux éléments, les autres domaines, comptes et profils, et produire une nouvelle révision de synchronisation. Une restauration globale est exclue. Aucun lot n'est encore approuvé.
4. **Reprendre les essais réels** sur `.119` et `.150` après accord et disponibilité : E1 coché, E2 en cours et reprise Tyson ; publication de la programmation depuis l'appareil créateur, puis modification/annulation à distance. Vérifier aussi la persistance après une relance contrôlée. `.163` reste en attente.

La mise à niveau du code ne récupère pas à elle seule les données manquantes et ne valide pas l'affichage IPTV. Les autres catégories de modifications perdues avant l'incident restent à évaluer. Le journal, le snapshot et le JSON restent des écritures séparées ; cette modification ne constitue pas une transaction globale. Les branches sans changement des autres domaines ne sont pas renforcées par le correctif de `watch-progress`.

## Diagnostic complémentaire du hero, le 16 octobre à 19 h 51

**Un défaut de synchronisation des suppressions reste présent après la réparation de la persistance.** Cette consultation n'a installé aucun code, restauré aucune progression ni commandé de lecture sur les appareils. La `.150` utilise toujours `0.2.31 / 146` ; le lecteur était au premier plan pendant la consultation et n'a pas été interrompu. La `.163` n'a pas été pilotée.

Pour la vidéo signalée par l'utilisateur, l'ancien enregistrement `file_video_resumes` du profil 1 conserve une position de **1 505 311 ms** sur **4 087 808 ms**, datée de **19 h 28 min 49 s**. L'identité du contenu comporte une empreinte de fichier `#fb:` ; le chemin obtenu avec la règle de production correspond exactement à la source de cette reprise. Aucun rapprochement entre vidéos n'a été effectué par titre.

L'entrée correspondante est absente de `watch-progress`. Sa marque de suppression porte **19 h 33 min 48,637 s**, révision **615082**. Le journal du NAS enregistre neuf suppressions dans cette opération. Sur la `.150`, le journal indique « Replayed 9 pending watch-progress deletions for profile 1 » à **19 h 33 min 52,366 s**, après l'envoi de 80 progressions. Cet envoi de 80 entrées est également retrouvé dans le journal NAS, avec la même identité technique que l'opération de suppression. Les horodatages correspondent respectivement au traitement serveur et au retour de la requête côté client. [Preuve NAS avec identifiants réduits à des empreintes](../output/iptv-sync-20261016/hero-90-enquetes-diagnostic.json).

L'utilisateur situe l'arrêt de la lecture sur la `.163` à 19 h 33. Cette précision ne constitue pas une suppression volontaire et ne permet pas d'attribuer l'émission des neuf suppressions à l'arrêt du lecteur. Leur reprise par la `.150` est confirmée ; l'origine et la date initiales de chaque intention en attente, ainsi que le checkpoint final d'arrêt de la `.163`, ne sont pas établies par cette consultation. Le diagnostic de la `.150` s'appuie sur ses journaux : ses préférences privées n'ont pas été extraites.

Le mécanisme en cause est confirmé dans les sources : `WatchProgressPreferences.pendingRemoteDeleteKeys` conserve des clés sans la date de l'intention ; `WatchProgressSyncService.flushPendingRemoteDeletesLocked` les rejoue avant la lecture du snapshot ; `server.js` date chaque suppression reçue avec `Date.now()` et retire l'entrée sans comparer cette intention à une lecture plus récente. Deux essais avec le véritable code de la route et un stockage fictif reproduisent la suppression d'un checkpoint puis le refus de son renvoi, pourtant acquitté HTTP 200. **Ces essais confirment le défaut actuel ; ils ne valident pas une correction.** Ils ne démarrent aucun serveur et n'ouvrent aucune connexion réseau.

```powershell
node --test-name-pattern=HERO_DIAGNOSTIC output/iptv-sync-20261016/hero-stale-delete-reproduction.cjs
```

[Reproduction](../output/iptv-sync-20261016/hero-stale-delete-reproduction.cjs), [résultat : deux reproductions confirmées](../output/iptv-sync-20261016/hero-stale-delete-reproduction.tap).

Le prochain correctif devra conserver la date et la version des intentions de suppression, vérifier atomiquement côté NAS qu'elles ne détruisent pas une lecture plus récente et empêcher qu'un acquittement ancien efface une nouvelle intention. Les anciennes intentions déjà enregistrées sans date exigent un traitement conservateur explicite. Une simple comparaison côté client avant `DELETE` laisserait une course avec les écritures des autres appareils. Cela concerne les préférences de progression, le service et le transport NAS de l'application, ainsi que la route Companion et leurs tests ; Supabase n'intervient pas dans ce chemin.

Ce nouveau correctif reste à préparer et à tester ; son installation sur le NAS et les appareils demandera un accord distinct. La récupération de l'entrée signalée demeure séparée : sa copie encore disponible ne doit pas être réinjectée automatiquement contre la suppression plus récente. Aucune progression ni intention en attente n'a été effacée ou réécrite pendant le diagnostic, et aucune vidéo n'a été lancée.

## Correctif local des suppressions différées

**À l'issue de la correction locale : sources corrigées et validées, sans déploiement.** Le déploiement du Companion autorisé ensuite est décrit dans la section suivante. Les éléments ci-dessus décrivent le diagnostic antérieur. Le [diff de cette correction uniquement](../output/iptv-sync-20261016/deferred-delete-fix/implementation.diff) compare les sources à leur état au début de cette intervention et préserve les autres modifications du dépôt.

| Fichier | Changement |
|---|---|
| `WatchProgressPreferences.kt` | Chaque nouvelle intention garde sa date originale et une version UUID dans le même DataStore que la file existante. L'objet Gson est protégé par `@Keep` pour conserver ses champs dans les APK optimisées. Un acquittement n'enlève que les versions envoyées. Les anciennes clés sans date restent conservées et non envoyées. |
| `WatchProgressSyncService.kt` | Résolution des anciens alias avec conservation de la date ; utilisation exclusive de la nouvelle suppression conditionnelle ; acquittement des versions capturées après succès. Les demandes déjà acquittées sont ignorées sans nouvel envoi ni erreur. Les demandes sans date n'empêchent pas la consultation des snapshots. |
| `playercompat/NasIptvFavoritesApi.kt` | Requête authentifiée `DELETE /api/sync/profiles/{profileId}/watch-progress/conditional`, avec `deletions: [{key, deletedAt}]`. Aucun repli vers l'ancien endpoint. |
| `playercompat/WatchProgressRepositoryImpl.kt` | Conservation de l'heure de l'action avant la suppression locale. Le nettoyage des anciennes identités Freebox garde la date du checkpoint. Les acquittements appartiennent au service de synchronisation. |
| `output/nas/server.js` | Vérification et suppression sans suspension intermédiaire : une entrée dont `lastWatched > deletedAt` est conservée. La marque garde la date originale, sans régression ni rajeunissement lors d'un renvoi. Un succès attend la persistance, même pour une requête répétée sans changement. |
| `WatchProgressSyncServiceTest.kt`, `server-persistence.test.js` | Cas de lecture sur un autre appareil, retour en arrière, écriture pendant l'envoi, reprise après erreur, redémarrage du DataStore, acquittement obsolète, profils distincts, ancien serveur et anciennes files sans date. |

Les suppressions provenant d'anciens masques non datés restent locales. En particulier, un masque ne reçoit ni la date actuelle ni celle d'une progression nouvellement reçue : ces deux choix pourraient le transformer en suppression injustifiée d'une nouvelle lecture. Les intentions correspondantes sont gardées pour vérification ; aucune ancienne file réelle n'a été purgée par ces outils.

La nouvelle route reprend l'authentification et l'isolation par compte/profil du NAS. Elle refuse les autres méthodes et les dates absentes, non entières, non positives ou dépassant la précision sûre des entiers JSON. Une suppression gagne en cas d'égalité avec `lastWatched`. La comparaison reste fondée sur les horloges des appareils, comme les progressions NAS existantes ; elle ne règle pas les erreurs d'horloge. La politique de conservation des anciennes marques reste inchangée.

**Compatibilité et ordre d'installation :** le Companion doit fournir la nouvelle route avant la mise à jour des applications. Un ancien Companion ne comprend pas cette route : la demande échoue, reste dans la file et n'est jamais convertie en suppression non protégée. L'ancien endpoint reste compatible avec les anciennes APK ; **celles-ci ne bénéficient pas de la nouvelle protection** et peuvent encore transmettre des suppressions non datées. Tous les appareils qui écrivent dans ce compte devront être mis à jour pour éliminer ce risque ; l'intervention sur la `.163` reste soumise à sa disponibilité et à l'accord de l'utilisateur.

Les **70 tests Node passent**, dont sept nouveaux cas de suppression conditionnelle. La compilation Java/Kotlin et les **149 tests Android ciblés passent** : synchronisation NAS 57, règles média/hero 78 et capture du pilote 14. Cela représente **219 tests locaux**, dont 21 nouveaux cas. [Résultats détaillés](../output/iptv-sync-20261016/deferred-delete-fix/test-results.json), [journal Node](../output/iptv-sync-20261016/deferred-delete-fix/node-tests.tap), [journal Android final](../output/iptv-sync-20261016/deferred-delete-fix/android-tests-final.log).

Aucun serveur HTTP n'est démarré par les tests Node : le code réel de la route travaille sur un stockage fictif. Les tests Android utilisent de vrais DataStore temporaires et un transport NAS fictif, y compris pour les erreurs et les écritures concurrentes. L'annotation `Keep` est présente dans la classe compilée ; aucune APK minifiée n'a toutefois été assemblée ou essayée. Les essais sur appareils, le lint complet et la suite entière du dépôt ne sont pas exécutés. Le diff ciblé est contrôlé avec Git ; Graft est indisponible dans cette session.

Depuis la racine du dépôt, les vérifications Android se reproduisent avec le JBR d'Android Studio, en conservant les sorties isolées :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --offline --no-daemon --max-workers=2 --console=plain --project-cache-dir output/iptv-sync-20261016/validation-gradle-cache -I output/iptv-sync-20261016/validation.init.gradle '-Dorg.gradle.jvmargs=-Xmx3072m' '-Pkotlin.compiler.execution.strategy=in-process' '-PjojoHomeAbi=armeabi-v7a' '-PjojoProgressPilotCompiled=true' :app:testDebugUnitTest --tests com.jojotv.home.WatchProgressSyncServiceTest --tests com.jojotv.home.HomeMediaRulesTest --tests com.jojotv.home.progresspilot.SupabaseProgressCaptureTest
node --test --test-reporter=tap output/nas/server-persistence.test.js output/nas/server-dns.test.js output/nas/server-playback.test.js
```

Cette intervention ne change ni les SQL, ni l'association Supabase, ni le mode `NAS_ONLY`, ni les réglages du pilote. Aucun appel au NAS, à Supabase ou aux appareils, aucun redémarrage de service et aucune lecture vidéo ne sont effectués pendant la correction locale. Aucune APK n'est assemblée ou installée. La récupération de la progression déjà supprimée reste une opération distincte à approuver ; ce correctif ne restaure aucune donnée historique.

## Mise à jour du Companion pour les suppressions différées — effectuée

L'accord « on va mettre à jour companion » couvre cette installation et sa relance contrôlée. Le [bilan vérifié](../output/iptv-sync-20261016/deferred-delete-fix/deployment-final-summary.json) conserve les références des reçus initiaux et des contrôles complémentaires. Le manifeste de correction locale `summary.json` reste une preuve historique de la phase antérieure, sans déploiement à cette date.

| Élément | Résultat constaté |
|---|---|
| Serveur | `/opt/jojotv-companion/server.js` : SHA-256 `07d99e256ac8c37e61353514a81223ae0fcbd64c9e0bf662f85f0a2ade4ea274`, identique à la source validée. |
| Adaptateur SQLite | Réinstallé par la procédure de remplacement de la paire, avec un contenu strictement identique : SHA-256 `5941a93de888f45af206ccda895e1bd601530fb634bab7e4a95e3ee9416b3d47`. Aucun changement de schéma. |
| Permissions | `server.js` conserve root:root et 0644 ; l'adaptateur conserve 1000:1000 et 0644. |
| Sauvegarde avant relance | `/home/freebox/jojotv-recovery/20261016T183934Z-bc0498a1` : sources originales, JSON, copie native SQLite incluant le WAL et export stable des progressions. Intégrité SQLite vérifiée, révision 615452. Les données privées restent sur le NAS. |
| Service | Une relance demandée : PID 117385 vers 119049, actif/en fonctionnement, `NRestarts=0`, HTTP `/health` 200. |
| Durabilité finale | JSON, snapshot SQLite et maximum du journal tous à **615468**, sur un prélèvement stable avec le même inode JSON. `quick_check(10)` réussit. |
| Nouvelle route | Sans authentification : HTTP 401 ; GET et PUT authentifiés sur `/watch-progress/conditional` : HTTP 405. Aucune requête DELETE envoyée au vrai service. |
| Maintenance | Règle temporaire du port 8095 retirée ; minuterie et service du garde-fou inactifs. Le garde-fou n'a pas exécuté de retour arrière. |

Le reçu initial s'arrête à `restart_requested` avec `TimeoutError` : la commande administrative de relance a dépassé sa limite de dix secondes. Le service est néanmoins devenu actif avec le nouveau code. Les contrôles complémentaires ont confirmé HTTP 200, les empreintes et le même PID 119049 ; **aucune deuxième relance n'a été demandée**. Ce délai demeure une limite de l'outil d'installation, sans imposer de nouvelle relance.

Les appareils ont repris leurs écritures ordinaires avant la comparaison. À la révision 615457, cinq événements clients `watch_progress:upsert` expliquent la seule entrée de progression modifiée du profil 1. Les 115 progressions et les 817 marques de suppression de ce profil restent présentes ; aucune marque de suppression ne diffère. Ses 216 marquages vus sont identiques, comme les données de lecture des profils 2 et 3. Seuls les champs `events`, `nextRevision` et `watchProgress` du snapshot ont changé. Il serait donc incorrect d'affirmer une égalité globale après relance ; **aucun écart de lecture inexpliqué n'a été détecté dans ce comparatif**. [Comparaison et contrôles de route](../output/iptv-sync-20261016/deferred-delete-fix/deployment-verification.json), [dernier contrôle de durabilité](../output/iptv-sync-20261016/deferred-delete-fix/deployment-durability.json).

Les **15 tests locaux de l'outil de déploiement** et la vérification syntaxique du serveur ont été exécutés à nouveau et réussissent. La source déployée est exactement celle des 70 tests Node et 149 tests Android précédemment réussis ; ces deux suites n'ont pas été relancées pour ce déploiement sans changement de source. Les trois contrôles HTTP ci-dessus vérifient le routage et l'authentification, sans supprimer ni écrire une progression réelle. Les contrôles Python distants restent plafonnés à 128 Mio ; le pic résident du comparatif est de 28 316 Kio.

**Limite restante : les anciennes APK utilisent encore l'ancienne suppression non protégée.** Il faut compiler puis installer les clients corrigés, sous l'accord correspondant, avant de valider les scénarios réels de suppression et de reprise entre appareils. Cette installation du Companion ne restaure pas la progression historique manquante. Aucune APK, lecture vidéo, modification SQL, action Supabase ou restauration de données n'a été effectuée pendant cette mise à jour.
