# Nextglyph

Clavier Android adaptatif open source (Apache-2.0). La spécification de référence est `docs/SPEC.md` ; toute évolution de comportement s'y reflète.

## Langue

Documentation, noms de tests et messages d'erreur en français. Identifiants de code en anglais.

## Structure

- `engine/` : moteur en Kotlin pur (JVM, bytecode 17), sans dépendance Android. Package racine `nextglyph.engine`.
- `tools/` : outils hors ligne (JVM, package `nextglyph.tools`). `./gradlew :tools:fetchData` télécharge les données listées dans `tools/data/sources.json` (sources et licences : `docs/DATA.md`).
- `ime/` : application Android (`InputMethodService`, `KeyboardView`, activité de réglages), package `nextglyph.ime`. Nécessite le SDK Android (`local.properties` avec `sdk.dir`, ignoré par git). `./gradlew :ime:lintFdroidDebug :ime:lintPlayDebug :ime:testFdroidDebugUnitTest :ime:testPlayDebugUnitTest :ime:assembleDebug` (deux variantes `fdroid` et `play`, voir `docs/SPEC.md` §18.1). L'APK embarque `fr.nglx` et `en.nglx` : leur assemblage lance `:tools:buildLexicons` (donc `fetchData` ; ~100 Mo en cache dans `tools/build/data`).
- `prototype/` : prototype web (HTML + JS sans build), tests `node --test` dans `prototype/`.
- `fastlane/metadata/android/` : textes des stores (fr-FR, en-US). Publication (clé, tags, F-Droid, Play) : `docs/PUBLISHING.md` ; reproductibilité : `scripts/check-reproducible.sh`.

## Commandes

```sh
./gradlew build     # compilation, tests, convention de tests
./gradlew pitest    # tests de mutation (seuils : 85 % mutants tués, 90 % lignes)
```

## Tests : règle obligatoire

Chaque classe de test contient trois classes `@Nested inner class` non vides : `HappyPath`, `ErrorCases`, `BoundaryValues` (détails dans `docs/TESTING.md`). `TestConventionTest` fait échouer le build sinon. Un mutant PIT qui survit se corrige par un test, jamais en baissant un seuil.

Le contrôle `TestConvention` est partagé via les test fixtures d'`engine` (`engine/src/testFixtures`, package `nextglyph.testing`) ; chaque module a son `TestConventionTest`.

## Contraintes

- Aucune dépendance GPL/LGPL/AGPL ni propriétaire (F-Droid). Aucune permission réseau, sauf INTERNET dans la variante `play` pour la seule mise à jour des dictionnaires à la demande (§2.7, §10.7).
- Données linguistiques : uniquement les sources classées OK dans `docs/DATA.md` (règle §10.2). Aucun fichier de données brut > 1 Mo dans git.
- Les actions GitHub sont épinglées par SHA de commit, avec la version en commentaire.
- Le compilateur Kotlin traite les avertissements comme des erreurs.
