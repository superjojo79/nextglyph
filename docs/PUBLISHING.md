# Publication

Procédure de publication sur GitHub, F-Droid et Google Play, en application du §18 de la [spécification](SPEC.md). Ce qui est automatisé est dans le dépôt ; ce qui demande un compte, une clé ou un choix est listé ici, dans l'ordre.

## Ce qui est en place

| Élément | Où | § |
|---|---|---|
| Signature de release lue dans l'environnement, jamais dans le dépôt | `ime/build.gradle.kts` (`NEXTGLYPH_KEYSTORE`…) | 18.1 |
| APK sans bloc de dépendances chiffré ni empreinte du commit | `ime/build.gradle.kts` (`dependenciesInfo`, `vcsInfo`) | 18.1, 18.3 |
| Contrôle de reproductibilité : deux builds de release depuis deux dossiers, comparés à l'octet | job « Build reproductible » de la CI, `scripts/check-reproducible.sh` | 18.1, 18.2 |
| Données figées : chaque source a un miroir dans une release du dépôt, essayé avant l'URL officielle | `tools/data/sources.json` (`mirrors`), workflow « Instantané des données » | 18.2, 18.3 |
| Deux variantes d'un même code, `fdroid` (aucune permission réseau) et `play` (INTERNET, mise à jour des dictionnaires sur opt-in) | `ime/build.gradle.kts` (`productFlavors`), `ime/src/play/` | 2.7, 18.1 |
| Release sur tag `vX.Y.Z` : APK signés (`fdroid`, `-play`), empreintes SHA-256, notes de version, app bundle `play` pour Play | workflow « Release » | 18.2, 18.4 |
| Métadonnées fastlane `fr-FR` et `en-US` (titre, descriptions, notes de version) | `fastlane/metadata/android/` | 18.3, 18.4 |
| Politique de confidentialité (français et anglais) | [`docs/PRIVACY.md`](PRIVACY.md) | 18.4 |
| Recette F-Droid prête à copier dans fdroiddata, et build reproductible vérifié entre JDK 21 (F-Droid) et 25 (release) | [`docs/fdroid/`](fdroid/io.github.superjojo79.nextglyph.yml), job « Build reproductible » | 18.1, 18.3 |
| Réponses aux formulaires de la Play Console (fiche, sécurité des données, IARC, public cible) | [`docs/PLAY-CONSOLE.md`](PLAY-CONSOLE.md) | 18.4 |

## 1. Figer les données (une fois, juste après la fusion)

Les exports Tatoeba sont écrasés chaque semaine et le zip Lexique a déjà été remplacé une fois : sans copie figée, `fetchData` échoue sur l'empreinte et aucun build indépendant (F-Droid) n'est possible.

1. Onglet **Actions** → **Instantané des données** → **Run workflow** sur `main`, tag `donnees-2026-11-19` (valeur par défaut, c'est celui des miroirs de `sources.json`).
2. Le workflow reprend les fichiers du cache de la CI, vérifie leurs empreintes et crée la release `donnees-2026-11-19` (non marquée « dernière version »).

À faire vite : GitHub efface un cache inutilisé depuis 7 jours, et les fichiers Tatoeba aux empreintes actuelles ne sont plus téléchargeables à la source. Si le workflow échoue faute de fichier, il faut mettre à jour `sources.json` (nouvelles empreintes, relecture de licence), relancer la CI, puis l'instantané avec un nouveau tag.

Pour un futur changement de données : modifier les empreintes et le tag des miroirs dans `sources.json`, laisser la CI remplir le cache, lancer l'instantané avec le nouveau tag.

## 2. Créer la clé de signature (une fois)

Sur un poste de confiance, hors du dépôt :

```sh
keytool -genkeypair -v -keystore nextglyph-release.jks -alias nextglyph \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Nextglyph"
```

- Garder deux copies hors ligne du fichier `.jks` et des mots de passe (ex. clé USB chiffrée et gestionnaire de mots de passe). **Une clé perdue empêche toute mise à jour** sur F-Droid (APK reproductible) comme sur GitHub.
- Relever l'empreinte du certificat, utile pour F-Droid :

  ```sh
  keytool -list -v -keystore nextglyph-release.jks -alias nextglyph | grep SHA256
  ```

## 3. Configurer GitHub (une fois)

1. **Settings → Environments → New environment** : `release`. Cocher **Required reviewers** (vous-même) : chaque release attend votre accord avant d'accéder à la clé. Dans **Deployment branches and tags**, n'autoriser que les tags `v*`.
2. Dans cet environnement, ajouter quatre secrets :

   | Secret | Valeur |
   |---|---|
   | `NEXTGLYPH_KEYSTORE_BASE64` | sortie de `base64 -w0 nextglyph-release.jks` |
   | `NEXTGLYPH_KEYSTORE_PASSWORD` | mot de passe du fichier |
   | `NEXTGLYPH_KEY_ALIAS` | `nextglyph` |
   | `NEXTGLYPH_KEY_PASSWORD` | mot de passe de la clé |

3. Facultatif : **Settings → Rules** pour protéger les tags `v*` (création réservée aux administrateurs).

Pour signer en local : exporter `NEXTGLYPH_KEYSTORE` (chemin du `.jks`) et les trois autres variables, puis `./gradlew :ime:assembleFdroidRelease :ime:assemblePlayRelease` (ou `:ime:bundlePlayRelease`). Sans ces variables, les APK de release ne sont pas signés.

## 4. Préparer une version

1. Dans `ime/build.gradle.kts` : `versionName` sémantique (`0.1.0`), `versionCode` strictement supérieur au précédent (`1`, `2`, `3`…). Les trois canaux et les deux variantes (`fdroid`, `play`) utilisent les mêmes valeurs : même `applicationId`, même `versionCode`, même clé, pour passer de l'une à l'autre sans désinstaller.
2. Notes de version : `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt` et `en-US/…`, 500 caractères au plus. Le workflow refuse un tag sans elles.
3. Captures d'écran (une fois, puis quand l'interface change) : PNG de téléphone dans `fastlane/metadata/android/<locale>/images/phoneScreenshots/` (`1.png`, `2.png`…, 2 au minimum pour Play, rapport 9:16 à 9:20). Icône `images/icon.png` 512 × 512 et, pour Play, bannière `images/featureGraphic.png` 1024 × 500. Chaque image doit rester sous 1 Mo (règle du dépôt).
4. Fusionner sur `main` avec la CI verte (le job « Build reproductible » compris).

## 5. Publier sur GitHub

```sh
git tag -a v0.1.0 -m "Nextglyph 0.1.0" && git push origin v0.1.0
```

Le workflow **Release** vérifie que le tag égale `versionName`, attend votre accord (environnement `release`), construit les APK `fdroid` et `play` et l'app bundle `play`, signés, vérifie la signature (`apksigner`), atteste la provenance des APK (SLSA, signée via Sigstore), puis crée la release avec `nextglyph-0.1.0.apk` (variante `fdroid`, celle du build reproductible), `nextglyph-0.1.0-play.apk`, leurs `.sha256`, leurs attestations `.sigstore.json` et les notes de version dans les deux langues. L'app bundle `play` est dans les artefacts du workflow (90 jours).

Vérifier qu'un APK sort bien de ce workflow, à partir du tag (CLI GitHub) :

```sh
gh attestation verify nextglyph-0.1.0.apk --repo superjojo79/nextglyph
```

## 6. Publier sur F-Droid

F-Droid construit l'application depuis les sources, **variante `fdroid` seulement** (`gradle: [fdroid]`), qui n'a aucune permission réseau. Comme l'APK est reproductible, il peut publier **notre** APK signé, à condition que son propre build soit identique (§18.1) : même signature partout.

1. Compte sur gitlab.com, fork de [`fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata).
2. Copier [`docs/fdroid/io.github.superjojo79.nextglyph.yml`](fdroid/io.github.superjojo79.nextglyph.yml) dans `metadata/` du fork et remplacer `AllowedAPKSigningKeys` par l'empreinte SHA-256 du certificat (§2), en minuscules et sans « : ». La recette passe `fdroid lint` et `fdroid rewritemeta` sans modification, et `fdroid scanner` ne signale rien sur les sources (vérifié avec fdroidserver 2.4.6 sur le commit 725af3f).
3. Attendre la release GitHub `v0.1.0` (§5) : la recette construit le tag `v0.1.0` et compare son APK à `nextglyph-0.1.0.apk` de la release.
4. Tester en local si possible (`fdroid build -v -l io.github.superjojo79.nextglyph`, demande le SDK Android), puis ouvrir la merge request avec le modèle « App inclusion » de fdroiddata, en cochant les points demandés (licence, pas d'anti-fonctionnalité, auteur du projet).

Points à confirmer avec les relecteurs de F-Droid :

- **Données téléchargées pendant le build** : `:tools:fetchData` récupère ~100 Mo depuis la release `donnees-…` du dépôt (fichiers sous licences libres, empreintes vérifiées). S'ils préfèrent, la commande peut passer dans `prebuild`.
- **JDK** : Gradle 9.9 et le build demandent un JDK 21 ou plus récent. La recette installe l'OpenJDK 21 de Debian, la release utilise Temurin 25 ; le job « Build reproductible » de la CI fait son second build avec le JDK 21 et vérifie que les deux APK sont identiques.
- **SDK** : `compileSdk = 37` et build-tools 37.0.0 ; le plugin Android télécharge la plateforme si elle manque sur le serveur de build. Sinon, ajouter `sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'` en `prebuild`.
- **Gradle** : F-Droid remplace `gradlew` par son propre script, qui doit connaître Gradle 9.9.1 (version du wrapper).
- **Signature** : F-Droid retire le bloc `signingConfigs` de `ime/build.gradle.kts` avant le build (sans effet : sans `NEXTGLYPH_KEYSTORE`, l'APK n'est pas signé).
- **Anti-fonctionnalités** : aucune attendue (variante `fdroid` : pas de permission réseau, pas de bibliothèque propriétaire, pas de suivi). Les dictionnaires s'y mettent à jour par fichier signé, téléchargé dans le navigateur puis importé. La permission `READ_CONTACTS` est facultative et expliquée.

## 7. Publier sur Google Play

1. Compte développeur Play Console (frais uniques de 25 $). **Un compte personnel récent doit faire un test fermé avec au moins 12 testeurs pendant 14 jours** avant de pouvoir publier en production (règle vérifiée le 25 novembre 2026).
2. Créer l'application (nom « Nextglyph », gratuite, application et non jeu).
3. **Signature** : à l'envoi du premier bundle, choisir de **fournir votre propre clé de signature d'application** (outil PEPK proposé par la console) avec `nextglyph-release.jks`, et non une clé générée par Google (§18.1). La même clé peut servir de clé d'importation.
4. Envoyer `ime-play-release.aab` (artefact `nextglyph-<version>-google-play` du workflow Release, variante `play`) dans la piste de test fermé, avec `mapping.txt` du même artefact comme fichier de désobfuscation.
5. Remplir la fiche et la section « Contenu de l'application » : toutes les réponses (fiche, sécurité des données, classification IARC, public cible, annonces, identifiant publicitaire, accès à l'application) sont dans [PLAY-CONSOLE.md](PLAY-CONSOLE.md).
6. Après 14 jours de test fermé avec 12 testeurs, demander l'accès à la production, puis promouvoir la version testée.
7. **API cible** : `targetSdk = 37` dépasse l'exigence actuelle de Play (API 36 depuis le 31 août 2026) ; à revérifier avant chaque publication (§18.4).

## 8. Paquets de dictionnaires

Un paquet `.ngpk` (archive ZIP signée : lexiques, modèles de mot suivant, `NOTICE.txt`) met à jour les dictionnaires sans nouvelle version de l'application ; le clavier le vérifie (signature, empreintes, chargement à l'essai) avant de l'utiliser. Il est signé avec une clé ECDSA P-256 **distincte** de celle de l'APK.

### Créer la clé (une fois)

Sur un poste de confiance, hors du dépôt :

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out nextglyph-pack.pem
openssl pkey -in nextglyph-pack.pem -pubout -outform DER | base64 -w0
```

- La première commande écrit la clé privée (PEM PKCS#8). **La garder hors ligne en deux copies** (comme `nextglyph-release.jks`) : une clé perdue interdit tout nouveau paquet pour les applications installées, sauf à publier une version de l'application avec une nouvelle clé de confiance.
- La seconde affiche la clé publique (SPKI DER en base64) : l'ajouter à `PackKeys.TRUSTED` (`engine/src/main/kotlin/nextglyph/engine/pack/PackSignature.kt`), puis publier une version de l'application. Tant que la liste est vide, le clavier refuse tout paquet, et `buildDictionaryPack` échoue (« la clé de signature ne correspond à aucune clé de `PackKeys.TRUSTED` ») : ajouter la clé publique avant de lancer le workflow.

### Configurer GitHub (une fois)

1. **Settings → Environments → New environment** : `dictionnaires`. Cocher **Required reviewers** (vous-même) et n'autoriser que la branche `main` dans **Deployment branches and tags**.
2. Dans cet environnement, ajouter le secret `NEXTGLYPH_PACK_KEY` : le contenu complet de `nextglyph-pack.pem` (lignes `-----BEGIN PRIVATE KEY-----` et `-----END PRIVATE KEY-----` comprises).

### Publier un paquet

Quand : après une PR fusionnée qui change `tools/data/sources.json` (nouvelles empreintes) ou les outils qui construisent les lexiques et les modèles (`tools/`, `engine/`, format des fichiers). Pas de planification : les sources sont figées, un paquet n'a de raison d'exister que si son contenu change.

1. Onglet **Actions** → **Paquet de dictionnaires** → **Run workflow** sur `main`, puis approuver le déploiement.
2. Le workflow reconstruit les lexiques, signe le manifeste (la version est la date UTC `yyyyMMddHHmm`), vérifie le paquet par une installation à blanc, atteste sa provenance et publie :
   - la release `dictionnaires-<version>` (archive : `nextglyph-dictionnaires.ngpk`, `.sha256`, `.sigstore.json`, `manifest.txt`, `manifest.sig`), non marquée « dernière version » ;
   - la release fixe `dictionnaires` (créée au premier lancement), dont `nextglyph-dictionnaires.ngpk`, `manifest.txt` et `manifest.sig` sont remplacés : `https://github.com/superjojo79/nextglyph/releases/download/dictionnaires/nextglyph-dictionnaires.ngpk` est l'URL stable lue par le clavier.
3. Si les formats binaires changent (`LexiconFormat.VERSION`, `NgramFormat.VERSION`), les anciennes applications refusent le nouveau paquet (« incompatible ») : publier d'abord la version de l'application qui les lit.

En local : `NEXTGLYPH_PACK_KEY="$(cat nextglyph-pack.pem)" ./gradlew :tools:buildDictionaryPack` (sortie dans `tools/build/dictionary-pack/`, version imposée avec `-PpackVersion=<yyyyMMddHHmm>`).

Vérifier la provenance d'un paquet : `gh attestation verify nextglyph-dictionnaires.ngpk --repo superjojo79/nextglyph`.

### Rotation de la clé

`PackKeys.TRUSTED` accepte deux clés. Pour changer de clé sans casser les applications installées : générer la nouvelle clé, ajouter sa clé publique **à côté** de l'ancienne, publier une version de l'application, attendre qu'elle soit largement installée, puis remplacer le secret `NEXTGLYPH_PACK_KEY` par la nouvelle clé. Retirer l'ancienne clé publique à la version suivante. En cas de compromission, retirer la clé de `TRUSTED` immédiatement, publier une version de l'application et révoquer le secret.

## Avant la première publication

Ce qui reste, dans l'ordre. Seul le mainteneur peut faire ces étapes (compte, clé, secrets, paiement) :

1. [ ] Fusionner la PR des visuels (captures, icône, bannière) et relire les attributions des lexiques (licences françaises, mention COCA dans le README de SCOWL), points ouverts de [PROGRESS.md](PROGRESS.md).
2. [ ] Créer la clé de signature et en garder deux copies hors ligne (§2) ; noter l'empreinte SHA-256 du certificat.
3. [ ] Créer l'environnement GitHub `release` et ses quatre secrets (§3).
4. [ ] Pousser le tag `v0.1.0` et approuver le déploiement (§5) ; vérifier la release (deux APK, empreintes, attestations).
5. [ ] F-Droid : compte gitlab.com, fork de fdroiddata, recette avec l'empreinte, merge request (§6). Délai habituel : quelques semaines de relecture.
6. [ ] Google Play : compte développeur (25 $, vérification d'identité), adresse e-mail publique de contact, création de l'application, clé importée via PEPK, bundle en test fermé, fiche et formulaires selon [PLAY-CONSOLE.md](PLAY-CONSOLE.md) (§7).
7. [ ] Recruter 12 testeurs pour le test fermé, 14 jours sans interruption, puis demander l'accès à la production.
8. [ ] Facultatif : mettre la vidéo promo sur YouTube pour la fiche Play.
