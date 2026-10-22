# Indicateur VPN dans la barre haute

## Objectif

Afficher un élément VPN entre « Réseau » et « Sources » uniquement lorsqu’un transport VPN est actif. La barre haute doit conserver la date complète et l’heure sans troncature, y compris avec Mercredi, Vendredi et Dimanche.

## Comportement

- L’état provient des `NetworkCapabilities` du réseau actif : `TRANSPORT_VPN`.
- Hors VPN, aucun espace ni contrôle VPN n’est créé.
- Avec VPN, un contrôle « VPN » avec une icône bouclier est inséré après Réseau.
- OK sur ce contrôle lance l’activité principale du fournisseur VPN détecté. Si Android ne permet pas d’identifier ou de lancer ce fournisseur, Jojo Home ouvre les paramètres VPN Android.
- La détection du fournisseur préfère un service déclaré avec l’interface `android.net.VpnService`; en cas de plusieurs candidats, elle privilégie l’application actuellement activée lorsque cette information est accessible, sinon le repli paramètres évite de lancer arbitrairement la mauvaise application.

## Mise en page

- La date/heure garde une zone fixe réservée à droite.
- La largeur disponible de la partie gauche est répartie entre ses contrôles. Avec VPN, les séparations se réduisent automatiquement; sans VPN, elles conservent l’espacement actuel.
- Aucun texte du jour ou de l’heure n’est réduit, tronqué ou converti dans un autre format.

## Validation

- Test unitaire : état VPN connecté/déconnecté, présence de la tuile et espacements adaptés.
- Test sur TV : hors VPN, absence de l’icône; avec VPN, icône présente, navigation D-pad correcte, ouverture du fournisseur ou des paramètres VPN.
- Vérification visuelle avec un jour long et date/heure complètes.
