# Audit des profils et de la stabilité IPTV sur .119

Le 20 octobre 2026, l’audit confirme un crash par épuisement de mémoire dans le traitement local de la synchronisation IPTV/Supabase. Il ne démontre pas une perte du compte Auth. Les sélections de profils effectuées pendant l’essai ont réussi, mais la charge mémoire reste anormalement élevée au repos.

Les constats ci-dessous décrivent l’état avant correction. Le correctif local autorisé ensuite est présenté à la fin du rapport ; il reste à compiler et à installer pour vérifier son effet sur la TV.

## Périmètre et résultat

Audit de la seule TV `.119`, Fire OS / Android SDK 25, application `com.jojotv.home`, version **0.2.43 / 158**, installée à **14:48:14**. Toutes les heures ci-dessous sont en Europe/Paris.

- Consultation des traces existantes avant toute relance, puis navigation dans Jojo Home et le module IPTV.
- Sélection Arnaud, observation de six échantillons sur environ 70 secondes, passage à Stéphanie puis retour à Arnaud, ouverture de la rubrique Films et retour à l’accueil.
- Aucun lancement de vidéo, installation, compilation d’APK, effacement, changement de mode de synchronisation, déconnexion Auth, modification SQL ou accès à `.150` / `.163`.
- Le code applicatif n’a pas été modifié par cet audit. Des modifications locales préexistantes sont postérieures au build installé ; elles ne prouvent pas le comportement de cette APK.
- État final observé : accueil Jojo Home, compte Arnaud. Le même processus applicatif a survécu aux manipulations de l’audit.

## Constats classés

### F-001 — P1 : crash confirmé dans le cache de progression IPTV

**Statut : confirmé sur la version installée. Confiance : élevée. Preuves : E-001 et E-002.**

À **15:16:02.970**, après l’installation de l’APK actuelle, le processus 9043 a levé une `OutOfMemoryError` sur `DefaultDispatcher-worker-14` : tentative d’allocation de **9 437 192 octets**, avec seulement **6 Mo jusqu’à la limite du tas**. La TV annonce une limite de tas applicatif `192m`.

L’identifiant R8 du crash correspond exactement au fichier de mapping local : `ae9e301fb44372c96ee78691f3a1e445b31569e7bdca8d2f25880a1e23b06a19`. Le décodage relie l’erreur à :

1. `SupabaseAccountContentRuntime.sync` → `restoreActivity` ;
2. `IptvStore.registerCloudActivityContents` → `registerCloudCatalogue` ;
3. `SupabaseProgressSyncStore.registerContents` ;
4. `gson.toJson(cachedTemplates)` à la ligne 359, puis agrandissement du tampon `StringWriter`.

Il s’agit d’une allocation locale qui échoue pendant la préparation du cache. Ce crash ne constitue pas une erreur d’identifiants, de HMAC ou une preuve de panne serveur Supabase. Le processus est commun à l’accueil et au module IPTV : sa mort peut fermer ces écrans et provoquer une nouvelle présentation du choix de profil lors d’une relance. L’écran exact utilisé à 15:16 n’a pas été reconstitué.

**Correction prioritaire :** éviter de reconstruire et sérialiser tout le catalogue pour une synchronisation sans changement ; limiter le cache des modèles de progression ; ne pas faire grossir ce cache indéfiniment avec toutes les pages consultées. Conserver les identifiants, positions et données de progression existants.

### F-002 — P1 : surcharge persistante sans lecture, et traitement intégral répétitif

**Statut : charge confirmée ; attribution de chaque rafraîchissement visuel non établie. Confiance : élevée pour la charge, moyenne pour son effet exact sur les profils. Preuves : E-002 et E-003.**

Entre **16:56 et 16:59**, les captures bornées contiennent au moins **488 passages du ramasse-miettes** dans le même processus et **16 fins de travaux `AccountContentWorker`**. Aucune lecture n’a été lancée pendant cette fenêtre. Le PSS total des six échantillons varie d’environ **223 à 245 Mio**.

Le cumul des durées totales des collectes atteint environ **73,65 secondes**, mais inclut du travail concurrent : ce nombre ne doit pas être présenté comme 73 secondes d’interface gelée. Les pauses explicitement indiquées par ces lignes totalisent environ **1,30 seconde**, auxquelles peuvent s’ajouter attentes, contention CPU et autres traitements non mesurés ici.

Le chemin du crash est également un chemin normal récurrent : `restoreActivity` appelle `registerCloudActivityContents` après le traitement des lignes, même sans changement de métadonnées. `registerContents` relit et copie les cartes existantes, sérialise progression, modèles et index, puis applique la projection. Il n’y a dans cette méthode ni limite du nombre de modèles, ni détection préalable d’un lot inchangé. L’enregistrement des modèles est aussi appelé lors de la mise en cache des pages et de consultations de la progression.

Dans le code local actuel :

- [SupabaseProgressSyncStore.kt](<C:/Users/arnau/Desktop/Jojo Home/app/src/main/java/com/jojotv/home/progresspilot/SupabaseProgressSyncStore.kt:338>) : inscription et sérialisation intégrale des modèles ;
- [SupabaseAccountContentRuntime.kt](<C:/Users/arnau/Desktop/Jojo Home/app/src/main/java/com/jojotv/home/progresspilot/SupabaseAccountContentRuntime.kt:320>) : restauration de l’activité et inscription finale des modèles ;
- [IptvStore.kt](<C:/Users/arnau/Desktop/Jojo Home/app/src/main/java/com/jojotv/home/iptv/IptvStore.kt:1081>) : inscription du catalogue, utilisée aussi par les lecteurs de progression.

**Correction prioritaire :** rendre les lectures de progression passives, dédupliquer les inscriptions de métadonnées et ne notifier l’interface que lorsqu’un contenu visible change. Espacer un minuteur seul ne corrigerait pas l’allocation qui a déjà fait tomber le processus. L’audit ne quantifie pas la part des autres caches/images dans la mémoire retenue.

### F-003 — P2 : verrou de sélection susceptible de rester actif

**Statut : risque identifié dans le code local ; scénario non reproduit sur la TV. Confiance : moyenne. Preuve : E-005.**

Dans [IptvActivity.kt](<C:/Users/arnau/Desktop/Jojo Home/app/src/main/java/com/jojotv/home/iptv/IptvActivity.kt:7058>), `enterAccount` positionne `accountSwitching = true`, puis utilise le helper `async` avec la génération courante de page. Le verrou n’est libéré que par le résultat de sélection ou son callback d’échec.

Or [async](<C:/Users/arnau/Desktop/Jojo Home/app/src/main/java/com/jojotv/home/iptv/IptvActivity.kt:10032>) abandonne directement ces callbacks lorsque `requestVersion` a changé. Si cette invalidation survient pendant l’opération et que l’activité reste utilisable, le profil peut avoir été sélectionné dans le store sans que l’écran soit fermé, et les nouveaux clics sont ignorés par le verrou resté actif.

**Correction proposée :** gérer l’achèvement de la sélection indépendamment de la génération des résultats de catalogue, et garantir la libération du verrou lorsque le résultat est écarté. Vérifier par un test ciblé de changement de génération pendant une sélection. Ce défaut potentiel ne suffit pas à attribuer tous les retours signalés par l’utilisateur à cette cause.

## Ce qui a été reproduit ou non

| Vérification | Résultat | Limite |
|---|---|---|
| Crash par manque de mémoire | Confirmé dans le journal de l’APK 0.2.43 / 158 | Crash antérieur aux manipulations de l’audit |
| Charge mémoire et GC sans lecture | Confirmés pendant l’observation | Captures bornées, pas un profilage exhaustif |
| Choix Arnaud dans IPTV | Réussi | Pas de crash immédiat pendant cet essai |
| Stabilité sur six échantillons | Arnaud conservé, aucun retour au sélecteur | Ne valide pas une longue session avec lecture |
| Arnaud → Stéphanie → Arnaud | Réussi | Ne valide pas toutes les courses asynchrones |
| Ouverture de Films | Réussie, liste affichée | Aucun film lancé |
| Déconnexion/reconnexion Auth Supabase | Non démontrée | Pas d’inspection de jeton ni de secret |
| Nouveau crash / ANR entre 16:32 et la fin des manipulations | Aucun relevé | Les journaux bornés ne remplacent pas une capture système complète |

Le premier constat d’absence de focus venait aussi d’interactions tactiles ADB : il n’est pas retenu comme preuve d’un défaut autonome de télécommande.

Deux ANR IPTV sont présents à **14:47**, avec délais de distribution des touches. Ils précèdent l’installation de 14:48 et ne sont donc pas attribués à la nouvelle APK. Leurs traces détaillées sont protégées (`system:system`, mode `600`) ; aucun contournement d’accès n’a été tenté.

## Preuves et chemin d’appel

Le dossier [audit-tv119-profiles-20261020](<C:/Users/arnau/Desktop/Jojo Home/output/audit-tv119-profiles-20261020>) contient les éléments ci-dessous. Les empreintes SHA-256 des pièces principales sont dans [evidence-manifest.json](<C:/Users/arnau/Desktop/Jojo Home/output/audit-tv119-profiles-20261020/evidence-manifest.json>).

| Preuve | Source et observation | Constat lié |
|---|---|---|
| E-001 | `initial.json`, `initial.log`, `crash-current.txt` : version, installation, crash de 15:16 | F-001 |
| E-002 | `crash-mapping.json`, mapping R8 correspondant et `source-provenance.jsonl` | F-001, F-002 |
| E-003 | `load-summary.json`, `observation.log`, captures `observe-0` à `observe-5` et journaux complémentaires | F-002 |
| E-004 | `actions.log`, `arnaud-tap.ui.json`, `stephanie-selected.ui.json`, `arnaud-restored.ui.json`, `final-home.ui.json` | Sélections réussies et limites de reproduction |
| E-005 | `IptvActivity.enterAccount`, `displaySelectedAccount`, `async`, et empreinte du fichier dans `source-provenance.jsonl` | F-003, code local courant |

**P-001 — chemin confirmé par le crash :** synchronisation de l’activité → restauration locale → inscription des modèles du catalogue → sérialisation JSON du cache de modèles → échec d’agrandissement du tampon → mort du processus. Ce chemin relie E-001/E-002 à F-001.

**P-002 — scénario à tester pour le verrou :** début d’une sélection → changement de génération de page → fin de l’opération → callback rejeté → verrou non libéré. Ce chemin vient de E-005 et reste à reproduire dans l’application.

Pour refaire les captures ciblées depuis la racine du dépôt, avec la `.119` disponible et ADB autorisé :

```powershell
python output/audit-tv119-profiles-20261020/capture.py status controle
python output/audit-tv119-profiles-20261020/capture.py ui controle
python output/audit-tv119-profiles-20261020/capture.py logs controle
```

Ces commandes lisent l’état de l’application et les journaux. Elles ne recréent pas à elles seules la charge historique exacte du crash. Les URLs et principaux champs de secrets sont masqués avant écriture ; aucun fichier d’identifiants n’a été consulté.

## Suite utile

1. Corriger le cache et les réinscriptions de modèles responsables du crash, sans réinitialiser comptes ni progressions.
2. Sécuriser la fin des changements de profil et réduire les reconstructions d’interface sans changement visible.
3. Après une nouvelle APK, refaire sur `.119` le parcours télécommande : sélection des deux profils, retour à l’accueil, ouverture IPTV, navigation Films/Séries, puis lecture et reprise. Vérifier la stabilité mémoire sur plusieurs cycles, l’absence de nouvelles exceptions et la conservation du profil.

La suite n’exige à ce stade ni nouveau SQL, ni nouvelle association d’appareil, ni investigation supplémentaire du Companion. Les tests réels de la prochaine APK restent à effectuer ; aucun correctif n’est déclaré livré par cet audit.

## Correctif local après autorisation — 20 octobre 2026

Le code a été corrigé dans trois fichiers, avec six tests supplémentaires dans `SupabaseProgressSyncStoreTest` :

- `SupabaseProgressSyncStore` borne à **512 fiches sans progression** le cache du catalogue. Les positions enregistrées et leurs identifiants restent conservés. Un ancien cache trop volumineux est parcouru en flux puis réduit au premier échange, sans charger tous ses objets à la fois.
- Une inscription de métadonnées inchangées s’arrête sans reprojection. Une réponse serveur inchangée ne réécrit plus le journal ni la progression ; la projection évite aussi de sérialiser une progression identique.
- `IptvStore` ne réinscrit plus le catalogue lors d’une simple lecture des badges, de la fiche ou des favoris de l’accueil. L’inscription reste assurée par l’arrivée des métadonnées et la préparation d’une lecture.
- `IptvActivity.enterAccount` ne dépend plus de la génération des résultats de catalogue : un rafraîchissement de page ne peut plus écarter son callback et laisser `accountSwitching` verrouillé.

**Validation :** ressources Android validées, contrôle Kotlin de 31 fichiers réussi, **179 tests réussis** avec un tas JVM limité à **192 Mio**. Les six nouveaux tests couvrent les échanges sans changement, l’éviction du cache avec conservation d’une position, la réduction d’un ancien cache avec un envoi interrompu, la résolution des lignes avant éviction, la reprise à la demande d’un contenu évincé et la modification des métadonnées pendant un checkpoint en attente.

L’exécution sur PC utilise les doublures Android déjà présentes pour le cache d’avatars et les logs. Elle valide les contrats de données ; elle ne mesure pas les performances du Fire Stick. Les premières erreurs du contrôle isolé provenaient des ressources/classes de build anciennes ; les ressources ont été régénérées et le modèle IPTV actuel a été inclus, sans modification de ces changements préexistants.

Les preuves, empreintes des entrées contrôlées et le diff limité à ce correctif sont dans [profile-cache-fix-20261020](<C:/Users/arnau/Desktop/Jojo Home/output/supabase-progress-pilot/profile-cache-fix-20261020>). Aucun APK n’a été produit ni installé, aucune TV n’a été commandée et aucun SQL n’a été exécuté pendant cette correction. La validation KSP/R8 et les essais télécommande avec l’APK finale restent à faire.

## Complément — suppression de l’historique IPTV

L’utilisateur signale une attente de 12 secondes, puis le retour systématique de la vignette après une suppression individuelle ou collective. Ce délai exact n’a pas été mesuré sur la TV pendant cette correction.

Deux courses ont été identifiées dans `SupabaseAccountContentRuntime.restoreActivity` :

1. La référence utilisée pour comparer l’état local était relue après le début de la restauration. Une suppression intercalée entre ces deux lectures pouvait donc être écrasée par l’ancienne entrée serveur.
2. Après une restauration, l’état observé était relu à nouveau. Une suppression locale survenue juste après pouvait être considérée à tort comme déjà synchronisée, au lieu de produire une demande de suppression.

Le correctif utilise un instantané cohérent pour la comparaison et retourne l’état réellement appliqué sous le verrou de la bibliothèque. Les lignes successives de favoris et d’historique utilisent leurs résultats appliqués, sans adopter les modifications locales concurrentes comme état serveur. Une suppression locale reste ainsi détectable et à envoyer.

Le chemin de suppression Supabase évite aussi les deux projections complètes des données destinées au NAS. La sauvegarde locale chiffrée, sa vérification et son remplacement atomique sont conservés. Les favoris, la progression de lecture et les autres profils sont préservés. Le délai réel après recompilation reste à vérifier ; le correctif ne promet pas une durée précise.

**Validation : 215 tests ciblés réussis, dont neuf nouveaux**, avec un tas JVM de 192 Mio, et contrôle Kotlin réussi des fichiers corrigés. Les cas couvrent les suppressions intercalées avec une restauration, l’effacement collectif d’un compte, les autres profils, les états successifs favoris/historique et la conservation d’une suppression après redémarrage ou accusé de réception tardif.

Les fichiers de preuve sont dans [history-delete-fix-20261020](<C:/Users/arnau/Desktop/Jojo Home/output/supabase-progress-pilot/history-delete-fix-20261020>). Le contrôle réutilise les modèles et dépendances de la validation précédente ; des changements préexistants dans l’activité IPTV et `HomeMediaRules.java` ne sont pas validés par ce contrôle ciblé. Aucun APK ni SQL n’a été produit ou appliqué et aucune donnée réelle d’historique n’a été supprimée pendant les tests. La prochaine vérification utile est la suppression d’une entrée de test puis l’effacement d’un historique de test sur `.119`, après installation de l’APK recompilée.
