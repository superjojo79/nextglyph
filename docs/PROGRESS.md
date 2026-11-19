# Avancement

Suivi tenu par l'orchestrateur. Jalons définis au §16 de la [spécification](SPEC.md) ; décisions dans [DECISIONS.md](DECISIONS.md).

## Jalons

| Jalon | État |
|---|---|
| Socle (dépôt, CI, convention de tests, protection de `main`) | Terminé |
| M0 — données, lexique, analyse, prototype | Terminé (2026-11-19) |
| M1 — premier IME Android | En cours |
| M2 | À faire |
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

## M1 — tâches

| Tâche | État | PR |
|---|---|---|
| Squelette de l'IME, modèle de disposition, job CI Android | En revue | #6 |
| Cœur adaptatif : poids, solveur, gel, décodeur (`nextglyph.engine.adapt`) | En revue | — |
| Intégration dans l'IME : lexique embarqué, mot en cours, rendu adaptatif, décodage | À faire | — |
| Vérification sur émulateur (captures des modes) | À faire (redémarrage Windows requis pour l'accélération) | — |

## Problèmes ouverts

- **Licence de `fr.nglx`** : Lexique (CC BY-SA 4.0) et Dicollecte (MPL-2.0) sont incompatibles dans un même fichier. Piste : un fichier par source, fusionnés à l'exécution par le moteur (M2). À trancher avant M5.
- **Instantané des données Tatoeba** : les exports changent chaque semaine ; il faut un instantané figé pour une recette F-Droid reproductible (avant M5).
- **Fréquences anglaises** : tirées de Tatoeba (biais « Tom », « Mary ») ; chercher une meilleure source à licence compatible.
- **Taille des touches** : à 360 dp de large, les lettres de la rangée 3 (0,85 u) font moins de 32 dp (§13). À vérifier sur émulateur et à arbitrer en M1.
- **Prototype** : n'implémente pas encore la correction des préfixes connus du §5.5 révisé ; ses valeurs par défaut sont alignées sur la spec.
- **SCOWL** : mention de données COCA dans son README, à relire avant publication.
