# Avancement

Suivi tenu par l'orchestrateur. Jalons définis au §16 de la [spécification](SPEC.md) ; décisions dans [DECISIONS.md](DECISIONS.md).

## Jalons

| Jalon | État |
|---|---|
| Socle (dépôt, CI, convention de tests, protection de `main`) | Terminé |
| M0 — données, lexique, analyse, prototype | Terminé (2026-11-19) |
| M1 — premier IME Android | Terminé (2026-11-19) |
| M2 — saisie complète (validation, ponctuation, bilinguisme) | En cours |
| M3 | À faire |
| M4 | À faire |
| M5 | Hors périmètre autonome : validation de l'utilisateur requise |

## M0 — bilan

| Livrable | PR |
|---|---|
| Sources FR/EN, licences, `:tools:fetchData` | #2 |
| Prototype web (`prototype/`) | #3 |
| Lexique binaire du moteur (`nextglyph.engine.lexicon`) | #4 |
| Listes de mots et corpus d'évaluation (`:tools:buildWordLists`) | #5 |
| Lexiques binaires et données du prototype (`:tools:buildLexicons`, `:tools:exportPrototypeData`) | #7 |
| Analyse de corpus et recalibrage de la spec ([rapport](analysis/M0-corpus.md)) | #8 |

Chiffres clés : `fr.nglx` 5,97 Mio (454 057 clés), `en.nglx` 1,31 Mio (130 735 clés), `distribution` ≈ 2 µs. Validation par espace : 2,10 % (FR) et 1,62 % (EN) de fausses validations à 5 % de fautes, après modification du §5.5.

## M1 — bilan

| Livrable | PR |
|---|---|
| Squelette de l'IME, modèle de disposition, job CI Android | #6 |
| Cœur adaptatif (poids, solveur, gel, décodeur) et intégration dans l'IME | #10 |
| Rangée 3 à 0,9 u (32 dp minimum, §13) | #11 |

Vérifié sur émulateur Android 16 (AZERTY et QWERTY) : adaptation visible après quelques lettres ; marge sous la barre de navigation corrigée à cette occasion. Coût par frappe ≈ 3 µs (budget 1 ms). APK de debug 8,7 Mo.

## M2 — tâches

| Tâche | État | PR |
|---|---|---|
| Prédiction : ensemble de lexiques, candidats, bilinguisme, validation par espace (`nextglyph.engine.predict`) | En cours | — |
| Édition : ponctuation, typographie française, majuscules (`nextglyph.engine.edit`) | En cours | — |
| Intégration IME : barre de suggestions, libellé de l'espace, barre de ponctuation, couche `?123`, accents | À faire | — |
| Séparation de `fr.nglx` par licence | À faire (après `LexiconSet`) | — |

## Problèmes ouverts

- **Licence de `fr.nglx`** : Lexique (CC BY-SA 4.0) et Dicollecte (MPL-2.0) sont incompatibles dans un même fichier. Piste : un fichier par source, fusionnés à l'exécution par le moteur (M2). À trancher avant M5.
- **Instantané des données Tatoeba** : les exports changent chaque semaine ; il faut un instantané figé pour une recette F-Droid reproductible (avant M5).
- **Fréquences anglaises** : tirées de Tatoeba (biais « Tom », « Mary ») ; chercher une meilleure source à licence compatible.
- **Prototype** : n'implémente pas encore la correction des préfixes connus du §5.5 révisé ; ses valeurs par défaut sont alignées sur la spec.
- **SCOWL** : mention de données COCA dans son README, à relire avant publication.
