# Publication

Procédure de publication sur GitHub, F-Droid et Google Play, en application du §18 de la [spécification](SPEC.md). Ce qui est automatisé est dans le dépôt ; ce qui demande un compte, une clé ou un choix est listé ici, dans l'ordre.

## Ce qui est en place

| Élément | Où | § |
|---|---|---|
| Signature de release lue dans l'environnement, jamais dans le dépôt | `ime/build.gradle.kts` (`NEXTGLYPH_KEYSTORE`…) | 18.1 |
| APK sans bloc de dépendances chiffré ni empreinte du commit | `ime/build.gradle.kts` (`dependenciesInfo`, `vcsInfo`) | 18.1, 18.3 |
| Contrôle de reproductibilité : deux builds de release depuis deux dossiers, comparés à l'octet | job « Build reproductible » de la CI, `scripts/check-reproducible.sh` | 18.1, 18.2 |
| Données figées : chaque source a un miroir dans une release du dépôt, essayé avant l'URL officielle | `tools/data/sources.json` (`mirrors`), workflow « Instantané des données » | 18.2, 18.3 |
| Release sur tag `vX.Y.Z` : APK signé, empreinte SHA-256, notes de version, app bundle pour Play | workflow « Release » | 18.2, 18.4 |
| Métadonnées fastlane `fr-FR` et `en-US` (titre, descriptions, notes de version) | `fastlane/metadata/android/` | 18.3, 18.4 |
| Politique de confidentialité (français et anglais) | [`docs/PRIVACY.md`](PRIVACY.md) | 18.4 |

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

Pour signer en local : exporter `NEXTGLYPH_KEYSTORE` (chemin du `.jks`) et les trois autres variables, puis `./gradlew :ime:assembleRelease`. Sans ces variables, l'APK de release n'est pas signé.

## 4. Préparer une version

1. Dans `ime/build.gradle.kts` : `versionName` sémantique (`0.1.0`), `versionCode` strictement supérieur au précédent (`1`, `2`, `3`…). Les trois canaux utilisent les mêmes valeurs.
2. Notes de version : `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt` et `en-US/…`, 500 caractères au plus. Le workflow refuse un tag sans elles.
3. Captures d'écran (une fois, puis quand l'interface change) : PNG de téléphone dans `fastlane/metadata/android/<locale>/images/phoneScreenshots/` (`1.png`, `2.png`…, 2 au minimum pour Play, rapport 9:16 à 9:20). Icône `images/icon.png` 512 × 512 et, pour Play, bannière `images/featureGraphic.png` 1024 × 500. Chaque image doit rester sous 1 Mo (règle du dépôt).
4. Fusionner sur `main` avec la CI verte (le job « Build reproductible » compris).

## 5. Publier sur GitHub

```sh
git tag -a v0.1.0 -m "Nextglyph 0.1.0" && git push origin v0.1.0
```

Le workflow **Release** vérifie que le tag égale `versionName`, attend votre accord (environnement `release`), construit l'APK et l'app bundle signés, vérifie la signature (`apksigner`), atteste sa provenance (SLSA, signée via Sigstore), puis crée la release avec `nextglyph-0.1.0.apk`, son `.sha256`, son attestation `.sigstore.json` et les notes de version dans les deux langues. L'app bundle est dans les artefacts du workflow (90 jours).

Vérifier qu'un APK sort bien de ce workflow, à partir du tag (CLI GitHub) :

```sh
gh attestation verify nextglyph-0.1.0.apk --repo superjojo79/nextglyph
```

## 6. Publier sur F-Droid

F-Droid construit l'application depuis les sources. Comme l'APK est reproductible, il peut publier **notre** APK signé, à condition que son propre build soit identique (§18.1) : même signature partout.

1. Compte sur gitlab.com, fork de [`fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata).
2. Ajouter `metadata/io.github.superjojo79.nextglyph.yml` (brouillon ci-dessous, à ajuster avec les relecteurs).
3. Tester en local avec `fdroid build -v -l io.github.superjojo79.nextglyph` (outil `fdroidserver`), puis ouvrir la merge request.

```yaml
Categories:
  - Keyboard & IME
License: Apache-2.0
SourceCode: https://github.com/superjojo79/nextglyph
IssueTracker: https://github.com/superjojo79/nextglyph/issues
Changelog: https://github.com/superjojo79/nextglyph/releases

AutoName: Nextglyph

RepoType: git
Repo: https://github.com/superjojo79/nextglyph.git
Binaries: https://github.com/superjojo79/nextglyph/releases/download/v%v/nextglyph-%v.apk

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: ime
    sudo:
      - apt-get update
      - apt-get install -y openjdk-21-jdk-headless
      - update-alternatives --auto java
    gradle:
      - yes

AllowedAPKSigningKeys: <empreinte SHA-256 du certificat, en minuscules, sans « : »>

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

Points à confirmer avec les relecteurs de F-Droid :

- **Données téléchargées pendant le build** : `:tools:fetchData` récupère ~100 Mo depuis la release `donnees-…` du dépôt (fichiers sous licences libres, empreintes vérifiées). S'ils préfèrent, la commande peut passer dans `prebuild`.
- **JDK** : Gradle 9.9 et le build demandent un JDK 21 ou plus récent ; la CI utilise le JDK 25. Une différence de JDK peut changer le bytecode produit par D8/R8 et casser la comparaison : en cas d'écart, aligner le JDK de la recette sur celui de la release.
- **Anti-fonctionnalités** : aucune attendue (pas de réseau, pas de bibliothèque propriétaire, pas de suivi). La permission `READ_CONTACTS` est facultative et expliquée.

## 7. Publier sur Google Play

1. Compte développeur Play Console (frais uniques de 25 $). **Un compte personnel récent doit faire un test fermé avec au moins 12 testeurs pendant 14 jours** avant de pouvoir publier en production (règle à revérifier au moment de l'inscription).
2. Créer l'application (nom « Nextglyph », gratuite, application et non jeu).
3. **Signature** : à l'envoi du premier bundle, choisir de **fournir votre propre clé de signature d'application** (outil PEPK proposé par la console) avec `nextglyph-release.jks`, et non une clé générée par Google (§18.1). La même clé peut servir de clé d'importation.
4. Envoyer `ime-release.aab` (artefact du workflow Release) dans la piste de test fermé.
5. **Fiche du store** : textes de `fastlane/metadata/android/fr-FR` et `en-US` (titre ≤ 30, description courte ≤ 80, description complète ≤ 4 000 caractères), captures, icône et bannière.
6. **Politique de confidentialité** : `https://github.com/superjojo79/nextglyph/blob/main/docs/PRIVACY.md` (ou la même page servie par GitHub Pages).
7. **Sécurité des données** : « L'application ne collecte ni ne partage aucune donnée utilisateur ». Le texte saisi, le dictionnaire personnel, l'historique du presse-papiers, les mesures de saisie et les noms des contacts sont traités uniquement sur l'appareil et ne sont donc pas « collectés » au sens de Play ; chiffrement en transit : sans objet ; suppression des données : depuis les réglages.
8. **Autres déclarations** : pas de publicité ; classification du contenu (questionnaire IARC, aucune catégorie sensible) ; public cible 13 ans et plus ; pas d'application gouvernementale ni financière.
9. **API cible** : `targetSdk = 37` dépasse l'exigence actuelle de Play ; à revérifier avant chaque publication (§18.4).

## Avant la première publication

Points ouverts repris de [PROGRESS.md](PROGRESS.md) :

- Relecture des attributions des lexiques (licences françaises, mention COCA dans le README de SCOWL).
- Captures d'écran, icône 512 px et bannière Play.
