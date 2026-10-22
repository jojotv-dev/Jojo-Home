# JojoHome web — interface fusionnée avec le Companion

Cette version reprend l’interface de l’application TV dans un nouveau dossier.
Le Companion de `http://192.168.1.32:8095/` reste strictement inchangé : serveur,
configuration et processus conservés. Le nouveau service sur 8096 relaie ses
API existantes et affiche ses données après connexion au même compte NAS.

## Tester sans déployer

Depuis ce dossier, avec le Node.js déjà installé :

```powershell
node .\server.mjs --host 127.0.0.1 --port 8096 --companion http://192.168.1.32:8095
```

Ouvrir <http://127.0.0.1:8096/jojohome/>. L’écoute est limitée au PC local.
Pour voir le grand visuel de reprise avec des fiches d’exemple, ouvrir
<http://127.0.0.1:8096/jojohome/?demo=1> ou choisir Paramètres → Afficher l’exemple.
Arrêter avec `Ctrl+C`. Ce serveur ne démarre, n’arrête et ne configure aucun autre service.
La consultation utilise les API existantes. Les commandes manuelles de Gestion
NAS agissent sur les mêmes comptes et données que le Companion.

Pour une validation sans aucune connexion au NAS, démarrer dans deux terminaux :

```powershell
node .\analysis\fusion-fixture.mjs
node .\server.mjs --host 127.0.0.1 --port 18096 --companion http://127.0.0.1:18095
```

Ouvrir <http://127.0.0.1:18096/jojohome/> ; connexion de test :
`validation@example.test` / `test`. Les comptes et titres sont synthétiques.

## Fichiers créés

- `server.mjs` : serveur HTTP autonome, sans dépendance, pour `/jojohome/`.
- `companion-proxy.mjs` : relais vers le Companion fixe, authentification et
  lecture en streaming, sans copie de base de données ni d’identifiants fournisseur.
- `public/jojohome/index.html` : en-tête, accueil, catégories, catalogues et fenêtres.
- `public/jojohome/style.css` : couleurs, proportions, focus, version mobile.
- `public/jojohome/app.js` : navigation, recherche, fiches, bibliothèque locale et NAS.
- `public/jojohome/nas.js` : connexion NAS, comptes, favoris, reprises, fournisseurs,
  groupes, pagination, épisodes et vignettes authentifiées.
- `public/jojohome/nas-model.js` : identités et formats des données existantes.
- `public/jojohome/nas-player.js` : lecteur NAS, HLS/MPEG-TS et fermeture des sessions.
- `analysis/fusion.test.mjs`, `analysis/fusion-fixture.mjs` : tests isolés et fixture.
- `analysis/nas-update.sh` : mise à jour du seul dossier/service sur 8096.
- `public/jojohome/catalog.example.json` : trois fiches de démonstration sans flux vidéo.
- `public/jojohome/assets/` : logo et bannière JojoHome, icônes de l’application,
  SVG adaptés depuis les vecteurs Android et police Roboto de la TV.
- `analysis/design.md` : observations REA/source/capture et limites de la reconstruction.
- `analysis/rea-evidence.json` : preuves REA complètes de l’inventaire de l’APK.
- `analysis/protected-site.json` : empreintes de référence du site à préserver.
- `analysis/validation.md` : résultats des tests et limites.
- `analysis/preview-*.png` : captures ordinateur, mobile et focus.
- `analysis/qa-catalog.json`, `analysis/qa-video.mp4` : fixtures locales de validation.
- `analysis/nas-audit.sh`, `analysis/nas-audit.txt` : audit NAS en lecture seule et résultat.
- `analysis/ssh-askpass.cmd` : assistant local pour l’accès déjà fourni ; aucun
  identifiant intégré et aucune copie de ce script ni du fichier d’accès sur le NAS.
- `analysis/jojohome-web.service` : configuration du nouveau service uniquement.
- `analysis/jojohome-web-nas.tar.gz`, `analysis/SHA256SUMS` : paquet de déploiement
  et empreintes des seuls fichiers web/serveur/nouveau service.
- `analysis/nas-deploy.sh` : création du nouveau dossier/service, avec refus
  d’écraser un chemin existant et comparaison des empreintes et du processus du Companion.
- `analysis/files-created.md` : inventaire exhaustif des fichiers de ce dossier.
- `analysis/nas-deployment.txt`, `analysis/nas-http-validation.json` : résultat
  du déploiement et comparaison des fichiers réellement servis.
- `README.md` : ce document.

Seul `public/jojohome/` est servi comme fichiers. `/api/` relaie les API du
Companion ; `/jojohome/companion/` affiche sa gestion au sein du nouveau site.
Les analyses, les sources serveur et le README ne sont pas accessibles par HTTP.

## Comportements disponibles

- Accueil et en-tête dans l’ordre de l’application : Accueil, Compte,
  Paramètres, Apps, IPTV, Rechercher, météo, date et heure.
- Visuel de reprise, pagination, synopsis, acteurs, durée en heures/minutes et progression.
- Quatre catégories : Vidéos, Films, Séries, TV ; recherche et filtre de favoris.
- Comptes JojoHome, favoris et reprises lus depuis le NAS après connexion.
- Fournisseurs synchronisés et fournisseurs conservés dans l’historique du NAS.
- Catalogues M3U/Xtream du NAS ; groupes et catalogues JojoHome, y compris
  Stalker, depuis la TV sélectionnée. Celle-ci doit avoir JojoHome actif.
- Gestion NAS intégrée : comptes, fournisseurs, favoris, Free TV et maintenance.
  La connexion est partagée entre l’accueil et la gestion sur 8096.
- Fiche d’un titre et lecteur HTML vidéo pour les sources lisibles par le navigateur.
- Comptes séparés dans le navigateur, réglage de la couleur et de la taille du visuel.
- Navigation au clavier : Tab/Entrée, flèches et Échap pour fermer une fenêtre.
- Ajout de vidéos depuis l’appareil, sans téléversement.
- Import d’un catalogue JSON : ajouter des entrées avec `title`, `type`
  (`videos`, `movies`, `series`, `live`), `description`, `cast`, `director`,
  `year`, `genre`, `durationMinutes`, `positionSeconds`, `artwork`, `url`.
  Les adresses d’image et de vidéo doivent être HTTP ou HTTPS.

Les imports locaux sont enregistrés sous la clé distincte `jojohome_web_v1` dans le
navigateur. Les URL temporaires des fichiers sélectionnés restent en mémoire :
ces fichiers doivent être choisis à nouveau après rechargement. Le même fichier
est rattaché à sa fiche existante et conserve sa progression.

Les données NAS sont chargées à la demande. Le mot de passe NAS est vidé après
connexion et n’est pas enregistré. Le jeton de session utilise le mécanisme du
Companion, limité à l’onglet sur 8096 ; il n’est pas transmis aux images externes.
Les favoris NAS gardent leurs synopsis, acteurs, réalisateur et identités.
Les reprises appliquent les suppressions par profil, chemin, fichier externe et
tombstone, les marques « déjà vu » et les restaurations explicites synchronisées.
L’accueil suit la sélection de JojoHome : fichiers Freebox/vidéos, VOD IPTV et
reprises externes disposant d’un lien de lecture. L’historique brut des autres
applications n’est pas présenté comme une reprise JojoHome. Une panne
de lecture des réglages Home conserve les favoris et masque les reprises.
Les références `jojo-home-asset:` sont converties vers l’API de fichiers du NAS ;
ces images sont chargées avec l’authentification dans l’onglet. Les choix de
vignettes enregistrés pour le chemin exact d’une vidéo sont réutilisés.
Les catalogues de gestion TV ne fournissent que les noms et visibilités : les
métadonnées manquantes ne sont pas inventées. Un titre TV absent des favoris
reste consultable, avec lecture sur la TV. Les favoris peuvent être lus sur le
web via le lecteur du NAS ; la compatibilité dépend du flux et des codecs.
L’interface ne reproduit pas les fonctions Android natives (installation d’apps,
Shizuku, SMB, veille ou lecteur Android).
La météo indique l’absence de source configurée. Les exemples sont signalés
comme tels et ne sont pas des médias de l’utilisateur.

## Version déployée sur le NAS

Adresse : <http://192.168.1.32:8096/jojohome/>.

Après présentation de l’aperçu local, des fichiers et des instructions de test,
l’utilisateur a choisi le port 8096 pour préserver strictement le site 8095.

Les seuls chemins de déploiement créés sont :

- `/opt/jojohome-web/` : serveur autonome, dossier public, fichier de service et empreintes.
- `/etc/systemd/system/jojohome-web.service` : nouveau service, démarrage automatique.
- `/etc/systemd/system/multi-user.target.wants/jojohome-web.service` : lien du nouveau service.
- `/tmp/jojohome-web-fusion-55e7f65eb226.tar.gz` : dernier paquet de transfert indépendant.

Le nouveau service utilise le Node.js déjà installé, sans installation de paquet.
Il écoute sur l’adresse LAN 192.168.1.32 et le port 8096. Ses fichiers sont en
lecture seule pour son utilisateur de service temporaire. Le dossier public est
le seul accessible par HTTP.

Le Companion sur 8095 n’a pas été redémarré : PID 16911 avant et après. Son
fichier serveur, sa configuration de service et le HTML servi gardent les mêmes
empreintes. Les 20 ressources publiques déployées correspondent aux fichiers locaux.

Voir `analysis/nas-fusion-deployment.txt`, `analysis/nas-fusion-http-validation.json` et
`analysis/protected-site.json` pour les vérifications. Les tests navigateur sur
le NAS de la première version confirment l’accueil vide, le mode exemple, le
carrousel et les catégories. Les comptes et fournisseurs réels de la fusion ont
été confirmés par l’utilisateur, ainsi que la disparition de Cap Farewell et
Les K d’or des reprises. Le chargement des images protégées est vérifié avec
une fixture locale et un chargement réussi dans la session NAS. La présence
des vignettes sur l’ensemble des listes reste à confirmer par l’utilisateur.
Les diagnostics temporaires ont été retirés du client et du serveur ; les
corrections de filtrage et de chargement des images restent en place.

Pour tester le grand visuel de reprise, ouvrir la page et choisir
**Paramètres → Afficher l’exemple**, ou ouvrir
<http://192.168.1.32:8096/jojohome/?demo=1>.
Les exemples et les imports locaux restent dans le navigateur. Une fois le
NAS connecté, l’accueil affiche ses données réelles à la place de l’exemple.

L’aperçu local a été arrêté après vérification ; la commande en début de README
permet de le relancer. Pour arrêter seulement le nouveau service sur le NAS,
utiliser `sudo systemctl disable --now jojohome-web.service` dans une session SSH.
Cette commande ne supprime aucun fichier et ne concerne pas le Companion.
