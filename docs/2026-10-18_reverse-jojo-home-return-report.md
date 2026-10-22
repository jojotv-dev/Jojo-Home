# Retour a l'accueil : disparition du compte et du hero

Analyse du 18 octobre 2026. Corrections dans les sources uniquement : aucune
compilation de l'application, installation, touche distante ou ouverture d'app.

## Perimetre

- `auth` : demande de corriger le rechargement du profil au retour a l'accueil.
- `in_scope` : lecture passive des journaux de .163, APK locale et code de
  `MainActivity.java`. Ni comptes, ni fournisseurs, ni secrets modifies.
- `network_profile` : ADB en lecture seule sur `192.168.1.163:5555`.
- Symptome rapporte : nom/avatar et reprises du hero absents pendant environ
  dix secondes, sur tous les appareils et plusieurs chemins de retour.
- Une premiere tentative de retour depuis les parametres n'a pas reproduit le
  probleme. La suivante, signalee par l'utilisateur, coincide avec l'arret et
  le redemarrage du processus. Ne pas confondre ces deux tentatives.

## Evidence

| E-id | Source_ref | Observation | Repro_command | Content_hash |
| --- | --- | --- | --- | --- |
| E1 | Journaux ActivityManager de .163 | A 15:33:52, PID 8772 mort puis PID 10363 lance pour MainActivity ; `GO_HOME` provient de `onCreate`. Heures telles que journalisees par l'appareil. | `adb -s 192.168.1.163:5555 logcat -d -v time -s ActivityManager JojoHomeNavigationTrace` | n/a : tampon vivant |
| E2 | Journaux ART du meme PID | Avant l'arret, 191 Mo sur 192 Mo occupes, allocations meme de quelques dizaines d'octets refusees, GC bloquants et OutOfMemoryError. | `adb -s 192.168.1.163:5555 logcat -d -v time -s art` | n/a : tampon vivant |
| E3 | Tampon crash a 15:20:25 | Autre OutOfMemoryError sur le fil principal pendant la serialisation du journal de revisions ; pile MainActivity.Y:978, mapping R8 concordant. | `adb -s 192.168.1.163:5555 logcat -b crash -d -v time` | n/a : tampon vivant |
| E4 | REA `ev_e3800b405ccd75cee172da8f9c37c78f76f55ab239f124d5771b7ad96848a425` | APK locale 0.2.33, code 148. | `inspect_android_package` sur `app/build/outputs/apk/release/app-armeabi-v7a-release.apk` | SHA-256 `3f7f61aea557250cb5ccc40ac1f9014b759eb8dad04c33608c2574c0b76a5073` |
| E5 | REA `ev_a579c38eeeeb51731fddcf74b3dc299b3c3538513f76c56e100377ec7765a8a8` | MainActivity.E0, methode mappee `cachedContinueWatchingHomePrograms`, renvoie une liste vide en mode cloud dans cette APK. Le code actuel utilisait deja le snapshot recu. | `inspect_android_method`, meme APK, classe `com.jojotv.home.MainActivity`, methode `E0` | Meme SHA-256 que E4 |
| E6 | REA `ev_6b2222e5937b0e08aa7c20f3b5d8cf0a5cdea918822021c907302dcb4f0b48d2` | MainActivity.Y, mappee `applyMergedHomeSettingsLocked`, materialise un deuxieme arbre JSON de revisions puis le serialise. Cela correspond a E3. | `inspect_android_method`, meme APK/classe, methode `Y` | Meme SHA-256 que E4 |

Les deux methodes REA sont en Java decompile, sans repli smali, source annoncee
complete. L'APK n'est jamais executee par cette analyse. La version et le mapping
observes sur .163 concordent avec l'artefact local, mais l'APK installee n'a pas
ete extraite et comparee octet par octet.

## Findings

| F-id | Severity | Evidence_ids | Confidence | Location | Status |
| --- | --- | --- | --- | --- | --- |
| F1 | n/a_re | E1, E2 | Observe | Processus JoJoHome sur .163 | Saturation memoire et redemarrage confirmes pendant le probleme signale ; ce n'est pas uniquement un rafraichissement visuel. |
| F2 | n/a_re | E3, E6 | Observe et derive du mapping | `applyMergedHomeSettingsLocked` | Allocation transitoire reduite dans les sources ; plus de second arbre JSON pour cette ecriture. |
| F3 | n/a_re | Inspection source | Observe dans le code, contribution exacte au pic memoire inferee | `refreshContentRowsData` | Avant : un nouveau thread par demande, avec filtrage des resultats seulement a la fin. Apres : un worker, une seule demande recente en attente, abandon des generations perimees entre etapes. |
| F4 | n/a_re | Inspection source | Observe dans le code, effet visuel non reproduit ici | `homeAccountAvatarView` | Un avatar charge pendant le detachement de Home etait ignore sans nouvelle tentative sur cette vue. Le resultat est maintenant conserve ; l'initiale reste visible en attendant l'image. |
| F5 | n/a_re | E5 | Observe, conditionnel au mode cloud | `cachedContinueWatchingHomePrograms` | Correction deja presente avant ce lot, conservee et couverte par les controles. |

F2 et F3 reduisent des pointes d'allocation identifiees. Cette analyse ne mesure
pas encore le pic memoire de la nouvelle APK et ne demontre pas que toutes les
autres causes possibles d'OutOfMemoryError sont eliminees.

## Path

`P1`, `path_type=callflow` : allocations concurrentes et memoire saturee (E2)
-> arret du processus -> relance de MainActivity/onCreate (E1)
-> reconstruction du compte et du hero -> recuperation asynchrone des donnees.
Ce chemin explique la perte simultanee des deux ensembles d'informations lors
de la reproduction signalee ; il ne prouve pas que chaque retour difficile
rapporte sur les autres appareils suit exactement ce meme chemin.

`P2`, `path_type=callflow` : restauration NAS -> fusion sur worker
-> application des preferences sur UI -> second arbre JSON de revisions
-> serialisation -> manque de memoire observe dans E3.
Le lot remplace ce second arbre par une ecriture JSON directe et calcule
l'empreinte des preferences sur le worker apres la validation UI.
Les protections des suppressions recentes, choix manuels d'images et reglages
de compte cloud sont conservees. Aucun schema de donnees ou protocole ne change.

## Modifications

Dans `app/src/main/java/com/jojotv/home/MainActivity.java` :

- Chargements de reprises bornes par `homeResumeQueryExecutor` : un actif et
  uniquement la derniere demande en attente, politique standard DiscardOldest.
- Invalidation et vidage de l'attente dans `onPause`, arret dans `onDestroy`.
  Le snapshot de profil et les vues en cache ne sont pas effaces.
- Controle de generation avant les etapes couteuses et avant publication UI.
- `homeSettingRevisionsJson` ecrit les memes `changedAt`, `writer` et eventuel
  `deleted`, avec les memes exclusions, sans recopier l'arbre de revisions.
- `settingsFingerprint` deplace apres le commit, sur le worker deja existant.
- Image d'avatar conservee meme si sa vue est provisoirement detachee ; initiale
  visible jusqu'a la disponibilite d'un bitmap.

Les autres modifications deja presentes dans le depot ont ete conservees.
Sauvegarde avant ce lot :
`C:/Users/arnau/AppData/Local/Temp/jojo-home-return-memory-20261018-153836/MainActivity.java`.

## Validation

| Verification | Resultat | Limite |
| --- | --- | --- |
| Contrats de sources : hero paysage, carrousel, navigation IPTV et cinq nouveaux controles | 40/40 | Pas des tests d'instrumentation Android. |
| Cinq nouveaux controles contre la sauvegarde avant correction | 5 echecs attendus ; les neuf anciens controles du fichier restent verts | Verification avant/apres de contrats de sources uniquement. |
| Planificateur Java standard, initialisation extraite du code : 250 demandes pendant un premier chargement bloque | Seules les demandes 0 et 250 s'executent ; un worker et une attente maximum | Test JVM du planificateur, pas des fournisseurs Android. |
| Destruction du planificateur avec une demande active et une en attente | Worker interrompu, demande en attente retiree | Pas un essai de cycle de vie physique sur TV. |
| Analyse syntaxique Java, sans analyse de types ni generation de classes de l'application | 0 erreur de syntaxe | Ne remplace pas une compilation Android. |
| Diff cible depuis la sauvegarde et `git diff --check` | OK | Depot initialement modifie. |

Commande des controles statiques :

```powershell
node --test --test-reporter=tap output/home-hero-landscape-20261018/landscape-static.test.cjs output/home-hero-suggestions-carousel-20261018/carousel-static.test.cjs output/iptv-navigation-tivimate-2026-10-13/navigation-static.test.cjs
```

## Verification restante

Apres compilation et installation par l'utilisateur : refaire les retours Home,
Retour depuis le lecteur/IPTV, serveurs et parametres, puis une session longue.
Verifier la stabilite du PID, l'absence d'OutOfMemoryError, le maintien du
compte/hero et les changements reels de compte. La suppression du symptome et
la baisse effective du pic memoire restent a confirmer sur cette APK.

## Timeline

- 15:20:25, journal appareil : crash memoire pendant le journal des reglages.
- Premiere reproduction demandee : retour normal rapporte par l'utilisateur.
- 15:33:52, journal appareil : memoire saturee, mort du PID 8772 et relance 10363,
  en correlation avec la nouvelle reproduction signalee par l'utilisateur.
- Apres capture : corrections source, controles avant/apres, test du planificateur,
  analyse syntaxique et verification du diff ; aucune action de deploiement.
