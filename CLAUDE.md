# Nextglyph

Clavier Android adaptatif open source (Apache-2.0). La spécification de référence est `docs/SPEC.md` ; toute évolution de comportement s'y reflète.

## Langue

Documentation, noms de tests et messages d'erreur en français. Identifiants de code en anglais.

## Structure

- `engine/` : moteur en Kotlin pur (JVM, bytecode 17), sans dépendance Android. Package racine `nextglyph.engine`.
- Modules à venir : `ime/` (application Android), `tools/` (construction des lexiques), `prototype/`.

## Commandes

```sh
./gradlew build     # compilation, tests, convention de tests
./gradlew pitest    # tests de mutation (seuils : 85 % mutants tués, 90 % lignes)
```

## Tests : règle obligatoire

Chaque classe de test contient trois classes `@Nested inner class` non vides : `HappyPath`, `ErrorCases`, `BoundaryValues` (détails dans `docs/TESTING.md`). `TestConventionTest` fait échouer le build sinon. Un mutant PIT qui survit se corrige par un test, jamais en baissant un seuil.

## Contraintes

- Aucune dépendance GPL/LGPL/AGPL ni propriétaire (F-Droid), aucune permission réseau.
- Les actions GitHub sont épinglées par SHA de commit, avec la version en commentaire.
- Le compilateur Kotlin traite les avertissements comme des erreurs.
