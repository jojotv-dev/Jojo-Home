# Temps restant Netflix dans le hero Jojo Home

Le calcul initial de 52 min 03 s correspondait à l'écran, mais le contre-test GIGN ci-dessous invalide l'association automatique entre une session Netflix sans métadonnées et la seule notification de reprise disponible. Cette notification peut rester liée à une ancienne vidéo. L'accès aux notifications de Jojo Home a été activé sur la .119 avec l'autorisation explicite de l'utilisateur ; l'obtention fiable du titre et du temps restant de GIGN demeure non résolue. Une protection contre le mélange des vidéos est ajoutée dans le source, sans compilation ni installation.

## Périmètre

L'utilisateur demande le même affichage du temps restant pour Netflix que pour Freebox, IPTV et TizenTube et autorise l'observation sur la .119. L'analyse couvre les interfaces Android publiées par Netflix et les consommateurs existants dans Jojo Home. Elle exclut les données privées de Netflix, les secrets, les changements de dépendances, les installations et toute modification de l'APK Netflix.

Le [périmètre détaillé](C:/Users/arnau/AppData/Local/Temp/jojo-netflix119-timing-10b64ujg/scope.md) et la [chronologie](C:/Users/arnau/AppData/Local/Temp/jojo-netflix119-timing-10b64ujg/timeline.md) accompagnent les observations locales. Ces éléments sont conservés dans un dossier temporaire.

## Observations et preuves

| E-id | Source et reproduction | Résultat | content_hash |
|---|---|---|---|
| E-01 | `adb -s 192.168.1.119:5555 shell dumpsys media_session`, épisode en pause | Netflix publie `position=634607`, `state=2`, `metadata:size=0`. Aucun titre ni durée dans la session. | n/a |
| E-02 | `adb -s 192.168.1.119:5555 shell dumpsys notification`, uniquement les champs Netflix | Une notification du groupe `continueWatching` publie `android.progressMax=3758`, `android.progress=0` et un identifiant de contenu. Quatorze autres notifications appartiennent à `trendingNow`. | n/a |
| E-03 | REA `inspect_android_package` puis `inspect_android_method`, APK installé copié en lecture seule | Netflix `com.netflix.ninja` 13.2.1, code 27207. `PreAppRecoBuilder.getRecoBuilder` transmet `mRuntime` et `mBookmark` au constructeur de notification ; celui-ci appelle `setProgress`. La valeur de repli sans runtime est 100. | SHA-256 APK : `5a7c59d362cca2934caa077355f2953c863968f9b1a23b2bef29f421882b299d` |
| E-04 | Calcul `3758 * 1000 - 634607` et comparaison humaine à l'écran | 3123393 ms, soit 52 min 03 s restantes ; l'utilisateur confirme environ 52 minutes. | n/a |
| E-05 | REA `PreAppRecoBuilder.PreAppRecoContentProvider.query` | L'implémentation renvoie `null` ; ce provider ne constitue pas une source supplémentaire de progression. | même APK que E-03 |

Références REA : E-03 `ev_5255fd0e109d78a9c42d81679c1db0fec0d2a48aac347c18e43ac6f3eccc4def`, `ev_45583328a2488fa55c186dc8f17eaa4915fe5f701b2d77f30234d512bcb3e2b8`, `ev_d59479992bea8211d672d8fe82d836e2653fe6b17be82e1f439d9105605b84da` ; E-05 `ev_75b8c290efeea7d9bf6c93b060ec8eef08a7f1fbb4cf88ff9df18b2203721a8f`.

Les extraits pertinents sont conservés dans [le relevé REA](C:/Users/arnau/AppData/Local/Temp/jojo-netflix119-timing-10b64ujg/rea-netflix-timing-evidence.json), [la session en pause](C:/Users/arnau/AppData/Local/Temp/jojo-netflix119-timing-10b64ujg/netflix-paused-timing.txt) et [le relevé des notifications de reprise](C:/Users/arnau/AppData/Local/Temp/jojo-netflix119-timing-10b64ujg/netflix-notification-resumes.json). L'analyse statique d'un APK ne prouve pas son exécution ; la comparaison E-04 confirme séparément l'unité des valeurs observées sur cette TV.

## Constats

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-01 : les valeurs initiales donnent le calcul demandé, sans prouver l'identité commune des deux sources | n/a_re | E-01, E-02, E-03, E-04, E-06 | élevée pour le calcul ; rapprochement initial invalidé | Session média et notification Netflix `continueWatching` | contre-test GIGN déterminant |
| F-02 : Jojo Home ne décodait pas la durée Netflix en secondes et pouvait choisir une recommandation sans rapport comme titre de secours | n/a_re | E-01, E-02, E-03 ; baseline du listener | élevée | `NotificationResumeListenerService` et `NotificationResumeRules` | corrigé dans le source, validation sur appareil en attente |
| F-03 : le provider PreApp ne résout pas le problème | n/a_re | E-05 | élevée pour l'implémentation inspectée | `PreAppRecoContentProvider.query` | établi statiquement |

## P-01 — parcours des données

`path_type=callflow` : notification Netflix `continueWatching` → durée convertie des secondes en millisecondes et identifiant Netflix → association avec la session uniquement si elle publie indépendamment le même identifiant de contenu → position de cette session → snapshot de reprise existant → fusion par identité Netflix → affichage existant du temps restant dans le hero. Une session sans identité ne peut plus fournir sa position à cette notification.

La sélection exclut `trendingNow`, refuse une association ambiguë entre plusieurs notifications de reprise et ignore le runtime de repli 100. Un bookmark nul conserve une position positive uniquement si la session précédente avait publié le même identifiant indépendamment de la recommandation. Les positions héritées de l'ancien rapprochement non vérifié ne bénéficient plus de cette conservation. Un changement d'identifiant Netflix efface la progression héritée de l'autre épisode. Le lien de reprise conserve l'identifiant de contenu publié par Netflix.

## Modifications et validation

- `app/src/main/java/com/jojotv/home/NotificationResumeRules.java` : filtre des notifications de reprise Netflix et décodage de leur runtime.
- `app/src/main/java/com/jojotv/home/NotificationResumeListenerService.java` : rapprochement des sources, vrai titre de contenu, conservation de l'identité et de la progression.
- `app/src/test/java/com/jojotv/home/NotificationResumeRulesTest.java` : trois tests sur la mesure observée, les recommandations et les valeurs de repli.

Syntaxe Java et diff sans erreurs sur ces trois fichiers. Les tests JUnit ne sont pas exécutés, aucune compilation ni installation n'a été lancée. Les données d'entrée sont validées sur la .119 ; le nouveau code n'est pas encore validé sur appareil. L'exposition des mêmes notifications sur les deux autres TV reste à confirmer. Aucun changement du rendu du hero, du lecteur Freebox/IPTV ou de l'application Netflix.

Le contrôle initial de `enabled_notification_listeners` ne trouvait pas Jojo Home sur la .119. Ce point est remplacé par E-07 : l'accès est maintenant activé sur cette TV. L'activation n'installe pas les modifications de source.

## Contre-test GIGN avec le profil Arnaud

Le 21 octobre 2026, l'utilisateur signale l'absence des reprises du profil Arnaud et choisit un épisode de GIGN. Il annonce une pause à **41 min 37 s restantes**. La TV .119 est un Fire Stick AFTMM ; Jojo Home installé annonce la version 0.2.46, code 161. Le réglage Fire OS « Notifications de l'application » contrôle l'émission de notifications et ne donne pas l'accès au service d'écoute. L'action Android d'accès spécial n'a aucune activité sur cet appareil.

| E-id | Source et reproduction | Résultat | content_hash |
|---|---|---|---|
| E-06 | Lecture GIGN effectuée par l'utilisateur ; `adb -s 192.168.1.119:5555 shell dumpsys media_session` et `shell dumpsys notification --noredact`, extraits Netflix uniquement | Session en pause à 811939 ms, métadonnées vides. La notification reste « Reprendre avec le profil de ARNAUD », contenu « The Gentlemen, La Reconquête du royaume », ID 81437051, maximum 3758, progression 0. Le calcul avec cette notification donnerait 49 min 06 s, différent des 41 min 37 s observées sur GIGN. | n/a |
| E-07 | Autorisation humaine explicite, puis ajout du seul composant `com.jojotv.home/com.jojotv.home.NotificationResumeListenerService` dans `enabled_notification_listeners`, autres entrées préservées ; `dumpsys notification` et `dumpsys activity services com.jojotv.home` | Accès activé ; service lancé et liaison `INotificationListener` établie. Aucune erreur de capture trouvée dans le journal ciblé à ce moment. | n/a |
| E-08 | REA `inspect_android_method`, `com.netflix.mediaclient.media.MediaSessionManager.updateMetadata` et `setPlaybackMetadata`, même APK que E-03 | `setPlaybackMetadata` appelle `updateMetadata`. La méthode examinée peut quitter tôt selon l'environnement ; sa publication de métadonnées dépend du chargement d'une image et ajoute titre et images, sans durée. Cela n'établit pas quelle garde est prise sur l'appareil. | même SHA-256 que E-03 |
| E-09 | `adb -s 192.168.1.119:5555 shell content query --uri content://android.media.tv/watch_next_program --projection _id:package_name:title:short_description:duration_millis:last_playback_position_millis:internal_provider_id` ; dump UI Netflix et capture écran | Watch Next : aucun résultat. L'arbre UI Netflix ne fournit aucun texte utile ; les captures obtenues sont vides. Ces interfaces n'apportent donc pas d'identité GIGN dans ce relevé. La cause des captures vides n'est pas établie. | n/a |
| E-10 | REA `AmazonPlayNextManager.publish` et `buildWatchItemMetadata` ; inventaire des bibliothèques système de la .119 | Le code Netflix dispose d'une publication Amazon CWLM avec titre, lien, durée et bookmark multipliés par 1000, et éventuellement saison/épisode. La bibliothèque `com.amazon.tv.developer.sdk.cwlm` est déclarée optionnelle par Netflix et présente sur la TV. Sa présence et ces appels statiques ne prouvent ni l'exécution pour GIGN ni un accès en lecture depuis Jojo Home. | même SHA-256 que E-03 pour Netflix |
| E-11 | Retour humain sur Jojo Home, relevé Netflix après retour et capture Netflix fournie par l'utilisateur | Le hero affiche The Gentlemen avec 49 min restantes, GIGN est absent. La notification reste The Gentlemen. La capture Netflix montre aussi l'absence de GIGN dans « Reprendre la lecture ». L'utilisateur confirme ensuite que la fiche GIGN propose bien de reprendre : sa progression est enregistrée par Netflix, mais la liste et les interfaces examinées ne la publient pas. | SHA-256 capture : `11d7124476cee4820ff0021d3279eddc7ae542c5ab32d7e93c59602c10d49d5e` |

Références E-08 : `ev_ccba2429fc27a956979493ef7819679d5ea1bdf393e0a2418a240c0e39058b9b`, `ev_4607932049dc0b34e1192779f665133c35ae0a67575f26c22ebbbaf693f44d5d`, `ev_e6b57cec2b488212e87ea1660e587597e083d5618ed6c4b30a8beb969db4c950`. Relevé E-06 : [résumé du contre-test](C:/Users/arnau/AppData/Local/Temp/jojo-netflix-profiles119-7nsc4mf1/netflix-paused-summary.json).

Références E-10 : `ev_62a64003d52f223e911bc1b3d007d80fc9171ca333ce7c37c9226914649a4189`, `ev_81714a70f986d019f3c0ee9335c290d08d32fb7e1d81acb704e58814977eaaf6`, `ev_1274318202539006f82d9248712b6218246b6938311e4ec75ed120488aa87fce`. [Extraits REA conservés](C:/Users/arnau/AppData/Local/Temp/jojo-netflix-profiles119-7nsc4mf1/rea-session-amazon-evidence.json). Cette piste n'est pas intégrée au code et son accessibilité reste à établir.

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-04 : une seule recommandation Netflix ne prouve pas la vidéo actuellement lue | n/a_re | E-06 | élevée | `NotificationResumeListenerService.playbackNotificationForPackage` | contre-exemple observé ; rapprochement protégé dans le source |
| F-05 : l'autorisation manquante empêchait le service de lire les notifications Arnaud publiées | n/a_re | E-07 | élevée | réglage système de la .119 | accès corrigé et liaison vérifiée |
| F-06 : le test n'établit aucune source complète pour la reprise GIGN | n/a_re | E-06, E-08, E-09 | élevée pour les interfaces examinées | Netflix 13.2.1 sur Fire Stick .119 | non résolu ; aucune impossibilité générale déduite |
| F-07 : l'absence de GIGN ne signifie pas que Netflix a oublié sa position | n/a_re | E-11 | élevée pour la fiche et la liste observées | fiche GIGN et liste Netflix, profil Arnaud | progression conservée ; publication manquante, cause interne inconnue |

`P-02`, `path_type=callflow` : lancement GIGN par l'utilisateur → session sans identité et ancienne recommandation The Gentlemen (E-06/F-04) → rejet du rapprochement dans le source → recommandation conservée avec ses seules données publiées. L'accès système désormais actif (E-07/F-05) rend la capture possible mais ne crée pas les métadonnées manquantes (F-06).

La correction de protection ajoute trois tests ciblés : session sans identité, identifiants différents/identiques, et non-conservation d'une position ancienne non vérifiée avec bookmark nul. `MainActivity.continueWatchingRemainingLabel` masque aussi le temps restant Netflix en l'absence d'une position positive et d'une durée connues ; la durée totale ne remplace pas une progression inconnue. Contrôles de syntaxe et diff uniquement ; tests non exécutés, aucune compilation ni installation. Les interfaces inspectées ne permettent pas de reconstruire rétrospectivement la position d'une ancienne lecture Stéphanie, ni d'associer la position GIGN sans identité publiée. Le comportement final du hero reste à valider après installation.
