# Avancement

Suivi tenu par l'orchestrateur. Jalons définis au §16 de la [spécification](SPEC.md) ; décisions dans [DECISIONS.md](DECISIONS.md).

## Jalons

| Jalon | État |
|---|---|
| Socle (dépôt, CI, convention de tests, protection de `main`) | Terminé |
| M0 — données, lexique, analyse, prototype | Terminé (2026-11-19) |
| M1 — premier IME Android | Terminé (2026-11-19) |
| M2 — saisie complète (validation, ponctuation, bilinguisme) | Terminé (2026-11-19) |
| M3 — profils de saisie (mot de passe, pavés, e-mail, URL, disposition classique) | Terminé côté moteur et IME (2026-11-19) ; panel d'applications tierces à faire |
| M4 — apprentissage, contacts, réglages, accessibilité | Fait (2026-11-19), vérifié sur émulateur sauf TalkBack (absent de l'image) |
| M5 | En cours (mené par l'utilisateur) ; publication préparée (§18, [PUBLISHING.md](PUBLISHING.md)) : signature, build reproductible, release sur tag, métadonnées fastlane, politique de confidentialité |

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
| Intégration IME : session de saisie (`nextglyph.engine.session`), barre de suggestions et de ponctuation, libellé de l'espace, bilinguisme, couche `?123`, accents, profils texte | Fait (vérifié sur émulateur) | — |
| Séparation de `fr.nglx` par licence (`fr-lexique.nglx`, `fr-dicollecte.nglx`) | Fait | — |

## M3 — tâches

| Tâche | État | PR |
|---|---|---|
| Moteur : `InputProfileResolver`, `SpecialLayouts`, `DecimalSeparatorPolicy` (`nextglyph.engine.profile`) | Fait | #15 |
| IME : profils à chaque champ (`FieldKeyboard`), mot de passe sans lecture du champ (`⇧` explicite, `KEYCODE_DEL`), pavés numérique / téléphone / date-heure / PIN, séparateur décimal vérifié, disposition classique (§4), libellé de `↵` | Fait (vérifié sur émulateur) | — |
| Panel « Champs de test » (debug) et `docs/FIELD-TESTS.md` | Fait (25 cas sur Android 16) | — |
| Test instrumenté : aucune écriture après une saisie en mot de passe (§15.2) | Fait (local, hors CI) | — |
| Panel d'applications tierces et de pages web (§15.3) | À faire | — |

Constats sur Android 16 : un `EditText` `numberDecimal` refuse la virgule en `fr-FR` (repli sur le point, appris par champ) ; les champs signés refusent le signe moins U+2212 (la touche insère `-`).

## M4 — tâches

| Tâche | État | PR |
|---|---|---|
| Moteur : dictionnaire personnel, refus persistants, modèle spatial, contacts, fichier d'état (`nextglyph.engine.learn`, `.contacts`) | Fait | #17 |
| Session : `PredictionStack`, `SessionLearning`, `ContactContext.read`, réglages d'édition à chaud | Fait | — |
| IME : persistance atomique en arrière-plan (`LearnedStore`), suppression d'un mot par appui long | Fait (vérifié sur émulateur) | — |
| IME : contacts (`READ_CONTACTS` optionnelle, explication, `ContentObserver`, effacement) | Fait (vérifié sur émulateur) | — |
| IME : réglages du §14 (sauf variante anglaise et aperçu, sans objet), export / import TSV, effacement | Fait (vérifié sur émulateur) | — |
| IME : accessibilité (nœuds virtuels, annonces, contour du focus, contrastes testés) | Fait ; nœuds vérifiés par `uiautomator`, TalkBack non disponible sur l'émulateur | — |
| Retour haptique | Fait ; non ressenti sur l'émulateur (pas de vibreur) | — |

## Problèmes ouverts

- **Licences des lexiques français** : levé en M2, un fichier par source (`docs/DATA.md`) ; une relecture juridique de l'ensemble des attributions reste prévue avant M5.
- **Instantané des données** : miroirs figés dans `sources.json` et workflow « Instantané des données » en place ; il reste à lancer ce workflow après la fusion, tant que le cache de la CI contient les fichiers (voir [PUBLISHING.md](PUBLISHING.md)).
- **Fréquences anglaises** : tirées de Tatoeba (biais « Tom », « Mary ») ; chercher une meilleure source à licence compatible.
- **Prototype** : n'implémente pas encore la correction des préfixes connus du §5.5 révisé ; ses valeurs par défaut sont alignées sur la spec.
- **SCOWL** : mention de données COCA dans son README, à relire avant publication.
- **TalkBack** : à vérifier sur un appareil ou une image avec TalkBack (exploration, « relâcher pour saisir », annonces) ; l'ordre « lettres probables d'abord » du §13 n'est pas réalisé.
- **Variante anglaise** (§14) : sans lexique britannique, le réglage n'est pas proposé.
