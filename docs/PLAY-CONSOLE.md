# Réponses pour la Play Console

Ce qu'il faut saisir dans la Play Console pour NextGlyph, écran par écran, dans l'ordre du tableau de bord « Configurer votre application ». Procédure d'ensemble (clé, bundle, pistes) : [PUBLISHING.md](PUBLISHING.md) §7. Règles vérifiées le 25 novembre 2026 ; les relire si l'écran ne correspond plus.

## Création de l'application

| Champ | Réponse |
|---|---|
| Nom de l'application | NextGlyph |
| Langue par défaut | Français (France) – fr-FR |
| Application ou jeu | Application |
| Sans frais ou payante | Sans frais (définitif : une application gratuite ne peut plus devenir payante) |
| Déclarations | Règles du programme Play pour les développeurs et lois américaines sur l'exportation : accepter |

## Fiche principale du Play Store

Pour chaque langue (fr-FR, puis « Gérer les traductions » → en-US) :

| Champ | Source dans le dépôt | Limite |
|---|---|---|
| Nom | `fastlane/metadata/android/<locale>/title.txt` | 30 caractères |
| Description courte | `…/short_description.txt` | 80 caractères |
| Description complète | `…/full_description.txt` (les balises `<b>`, `<ul>`, `<li>` sont acceptées) | 4 000 caractères |
| Icône | `…/images/icon.png` | 512 × 512, PNG 32 bits |
| Image de présentation | `…/images/featureGraphic.png` | 1024 × 500 |
| Captures de téléphone | `…/images/phoneScreenshots/1.png` à `5.png` | 2 à 8 |
| Vidéo (facultative) | URL YouTube de la vidéo promo (fichiers `nextglyph-promo-fr-FR.mp4` / `-en-US.mp4`), non répertoriée ou publique, sans publicité | — |

Captures de tablette : facultatives (le clavier n'a pas de mise en page tablette dédiée) ; sans elles, la fiche n'est pas mise en avant sur tablette.

## Paramètres de la fiche (« Paramètres du Store »)

| Champ | Réponse |
|---|---|
| Catégorie | Outils |
| Tags | Clavier, Productivité (choisir dans la liste proposée ; jusqu'à 5) |
| Adresse e-mail | adresse publique de contact, **obligatoire et affichée sur la fiche** : à choisir (une adresse dédiée est conseillée) |
| Site Web | https://github.com/superjojo79/nextglyph |
| Téléphone | vide |

## Contenu de l'application (« Règles et programmes » → « Contenu de l'application »)

### Règles de confidentialité

URL : `https://github.com/superjojo79/nextglyph/blob/main/docs/PRIVACY.md` (page publique, en français et en anglais, sans connexion).

### Accès à l'application

« Toutes les fonctionnalités sont disponibles sans restriction d'accès particulière. » Pas de compte, pas d'identifiant.

Si un examinateur bloque sur l'activation du clavier, ajouter en instructions : « Ouvrir NextGlyph, appuyer sur "Activer le clavier", cocher NextGlyph dans les claviers d'Android, puis le choisir comme clavier actif. Toutes les fonctions sont ensuite disponibles dans n'importe quel champ de texte. »

### Annonces

« Non, mon application ne contient pas d'annonces. »

### Identifiant publicitaire

« Non » : l'application n'utilise pas l'identifiant publicitaire (aucune permission `AD_ID` dans le manifeste fusionné, aucune bibliothèque tierce).

### Classification du contenu (questionnaire IARC)

| Question | Réponse |
|---|---|
| Adresse e-mail pour l'IARC | la même que la fiche |
| Catégorie | « Toutes les autres catégories d'applications » (utilitaires, productivité, communication…) |
| Violence, peur, sexualité, langage grossier, substances, humour vulgaire | Non à tout |
| Jeux d'argent, simulés ou réels | Non |
| Les utilisateurs peuvent-ils interagir ou échanger du contenu entre eux par l'application ? | Non (le clavier saisit du texte dans d'autres applications ; il n'a ni messagerie, ni partage, ni compte) |
| L'application partage-t-elle la position de l'utilisateur ? | Non |
| Achat de biens numériques | Non |
| Accès illimité à Internet (navigateur, moteur de recherche) | Non |
| Application ou contenu axé sur l'actualité / l'éducation | Non |

Classification attendue : PEGI 3, ESRB « Everyone », USK 0.

### Public cible et contenu

| Question | Réponse |
|---|---|
| Tranches d'âge cibles | **13 à 15 ans, 16 à 17 ans, 18 ans et plus** (ne pas cocher les moins de 13 ans : cela soumettrait l'application au programme Familles, avec ses exigences propres, sans bénéfice ici) |
| L'application peut-elle involontairement attirer les enfants ? | Non (utilitaire sans personnage ni jeu ni contenu destiné aux enfants) |

### Sécurité des données

Google définit « collecter » comme « transmettre des données hors de l'appareil ». Les données traitées seulement sur l'appareil, et le traitement éphémère en mémoire, ne se déclarent pas ([aide Play](https://support.google.com/googleplay/android-developer/answer/10787469)).

| Question | Réponse |
|---|---|
| Votre application collecte-t-elle ou partage-t-elle des données utilisateur des types requis ? | **Non** |

Justification, à garder pour un éventuel échange avec Google :

- Texte saisi, dictionnaire personnel, corrections refusées, modèle d'appuis, carte de frappe, compteurs de saisie, noms des contacts (permission facultative) : traités et stockés **uniquement sur l'appareil**, exclus des sauvegardes Android, effaçables depuis les réglages.
- Historique du presse-papiers : en mémoire uniquement, jamais écrit sur le disque.
- Seul accès réseau (variante `play`) : sur action de l'utilisateur, après activation d'un réglage désactivé par défaut, téléchargement HTTPS de fichiers publics depuis `github.com` (manifeste, signature, paquet de dictionnaires). La requête ne contient aucune donnée de l'utilisateur ni identifiant (`User-Agent: Nextglyph`, ni cookie, ni version) ; l'adresse IP inhérente à la connexion n'est ni reçue ni conservée par le développeur.
- Ni publicité, ni statistiques, ni rapport de plantage, ni SDK tiers.

La question « Les données sont-elles chiffrées en transit ? » et la demande de suppression des données ne s'affichent pas après un « Non ».

### Applications gouvernementales, fonctionnalités financières, santé, actualités

Non à chacune : application non gouvernementale ; aucune fonctionnalité financière ; aucune fonctionnalité de santé ; pas une application d'actualités.

### Autorisations

Aucun formulaire de déclaration : `READ_CONTACTS` et `INTERNET` ne font pas partie des autorisations soumises à déclaration (SMS, journal d'appels, localisation en arrière-plan, accès à tous les fichiers, etc.). `READ_CONTACTS` est demandée seulement depuis les réglages, après une explication (§6.1 de la spécification), et décrite dans la politique de confidentialité.

## Version et pistes

- **Signature** : au premier envoi, « Utiliser une autre clé » / « Exporter et importer une clé depuis un keystore Java » (outil PEPK) avec `nextglyph-release.jks`. Ne pas laisser Google générer la clé : l'APK publié sur F-Droid et GitHub doit porter la même signature.
- **Bundle** : `ime-play-release.aab` de l'artefact `nextglyph-<version>-google-play` du workflow Release ; joindre `mapping.txt` (même artefact) comme fichier de désobfuscation, pour des rapports de plantage lisibles dans Android Vitals.
- **Notes de version** : `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (500 caractères).
- **Test fermé obligatoire** (compte personnel créé après le 13 novembre 2023) : au moins **12 testeurs inscrits sans interruption pendant 14 jours** avant de pouvoir demander l'accès à la production. Un testeur qui se désinscrit puis revient repart de zéro. Liste de testeurs par adresses Gmail (ou groupe Google), lien d'inscription à leur envoyer.
- **API cible** : depuis le 31 août 2026, une nouvelle application ou une mise à jour doit cibler l'API 36 ; `targetSdk = 37`, conforme. À revérifier chaque été.
- **Taille** : module de base limité à 500 Mo compressés ; l'application est très en dessous.
- **Pays** : tous, ou au minimum l'Union européenne, la Suisse, le Canada et les États-Unis (application en français et en anglais).

## Vérification du développeur Android

Google impose désormais que chaque application installée sur un appareil Android certifié, **même hors du Play Store (F-Droid, GitHub)**, soit enregistrée par un développeur vérifié : obligatoire au Brésil, en Indonésie, à Singapour et en Thaïlande depuis le 30 septembre 2026, dans le monde en 2027. Le compte Play Console vérifié couvre aussi les APK distribués par F-Droid et GitHub, à condition d'y enregistrer l'identifiant `io.github.superjojo79.nextglyph` avec le certificat de `nextglyph-release.jks` : en principe le cas, puisque F-Droid publie notre APK signé (build reproductible). À confirmer dans la console au moment de l'enregistrement.
