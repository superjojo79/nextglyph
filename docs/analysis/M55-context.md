# M55 — Contexte de la correction (issue #55)

Mesures de la désambiguïsation par le mot précédent (SPEC §5.5, « Contexte de la correction », §10.3) et de la protection des sigles et mots techniques, produites par `:tools:analyzeContext` (`nextglyph.tools.analysis.ContextAnalysis`) avec le moteur de l'application (`Predictor.predict`, texte avant le curseur reconstitué), les lexiques de `buildWordLists` et les modèles `fr.ngrm` / `en.ngrm` de `buildLexicons` (zone des confusions, format version 2). Résultats bruts : `tools/build/analysis/M55-context-results.md` (non versionné).

## Méthode

- **Corpus** : phrases du corpus de **test** Tatoeba (le modèle ne les a pas vues), 20 000 lignes par langue (échantillon déterministe), soit 144 050 mots validés en français et 153 131 en anglais. Les phrases sont découpées en groupes de mots sans virgule ni autre signe (comme à la saisie) ; chaque mot est tapé avec, avant lui, le texte juste de la phrase.
- **Référence** : le même moteur et le même modèle **sans ensemble de confusion** ; « changé par le contexte » = texte inséré différent de la référence (la seule différence est donc la nouvelle règle, pas les bigrammes des erreurs d'espace).
- **Texte juste** : chaque mot est tapé tel qu'écrit. Un accent tapé n'est jamais modifié (§5.8) : seules les formes écrites sans accent (`a`, `ou`, `la`, `du`, `ces`, `ses`, `et`, `est`) peuvent être changées à tort.
- **Confusions injectées**, à chaque occurrence de la forme voulue : `à` tapé `a`, `où` → `ou`, `là` → `la`, `dû` → `du`, `ses` ↔ `ces`, `est` ↔ `et`. Issues : *corrigée* (texte inséré = forme voulue), *proposée en premier* (texte tapé gardé, forme voulue juste à côté dans la barre), *laissée* (ni l'un ni l'autre), *changée à tort* (autre texte).
- **Sigles** : chaque mot (au moins deux lettres) retapé en majuscules et sans accent (verrouillage majuscule), avec et sans la protection.
- **Prudence** : les trois réglages.

## Réglages retenus

`PredictionConfig.confusion` (`ConfusionConfig`) et `Prudence` :

| Réglage | Prudente | Normale | Audacieuse |
|---|---|---|---|
| `confusionShare` (part minimale de la forme proposée) | 0,99 | 0,98 | 0,97 |
| `confusionCount` (occurrences minimales du mot précédent suivi d'une forme de l'ensemble) | 50 | 30 | 20 |

Proposition dans la barre : part ≥ 0,5 sur au moins 5 occurrences (`offerShare`, `minOfferCount`). Comptes appris avec au moins 3 occurrences (`NgramPruning.minConfusionCount`). Ensembles français : `a`/`à`, `ou`/`où`, `la`/`là`, `du`/`dû`, `ces`/`ses`, `et`/`est`, tous automatiques ; aucun en anglais.

Ordre de réglage : les fausses corrections d'abord. Une première série (Audacieuse 0,95 sur 15, avant le garde de la majuscule) donnait 0,99 % de `et` changés à tort et des cas comme `est A` → `est À` (groupe sanguin) : la majuscule en milieu de phrase n'est plus changée et l'Audacieuse passe à 0,97 sur 20 (0,59 % de `et`).

## Résultats (français ; l'anglais n'a aucun ensemble)

### Texte juste

| Prudence | Mots changés à tort par le contexte (% des mots) | `a` | `et` | `ou`, `la`, `du`, `est`, `ces`, `ses` |
|---|---|---|---|---|
| Prudente | 1 (0,0007 %) | 0,04 % | 0 | 0 |
| Normale | 7 (0,0049 %) | 0,09 % | 0,49 % | 0 |
| Audacieuse | 12 (0,0083 %) | 0,27 % | 0,59 % | 0 (`ou` : 0) |

Objectifs tenus : bien moins de 0,1 % des mots, et moins de 1 % des occurrences de chaque forme. Exemples (Normale) : `Elle et` → `est` (2), `L'invité a` → `à`, `La a` → `à` : des contextes où la forme jugée très probable n'est pas celle voulue.

### Confusions injectées (Normale ; Prudente / Audacieuse entre parenthèses pour « corrigées »)

| Confusion (tapé → voulu) | Occurrences | Corrigées | Proposées en premier | Laissées | Changées à tort |
|---|---|---|---|---|---|
| `a → à` | 2 707 | 42,2 % (33,6 / 47,0) | 57,8 % | 0 % | 0 |
| `ou → où` | 274 | 8,0 % (3,3 / 16,8) | 92,0 % | 0 % | 0 |
| `la → là` | 116 | 0 % (0 / 0) | 100 % | 0 % | 0 |
| `du → dû` | 58 | 37,9 % (34,5 / 37,9) | 62,1 % | 0 % | 0 |
| `ces → ses` | 242 | 0,4 % (0 / 0,4) | 67,4 % | 32,2 % | 0 |
| `ses → ces` | 153 | 0 % | 18,3 % | 81,7 % | 0 |
| `et → est` | 3 391 | 19,1 % (13,9 / 19,6) | 29,1 % | 51,8 % | 0 |
| `est → et` | 1 012 | 0,7 % (0 / 1,1) | 23,9 % | 75,4 % | 0 |

Aucune confusion n'est changée en un troisième mot. `a`/`à` et `du`/`dû` sont corrigés automatiquement un peu plus d'une fois sur trois ; `ou`/`où`, `la`/`là`, `ces`/`ses` (la forme tapée est presque toujours la bonne) ne sont, à cause de l'équilibre réel des deux formes après un même mot, que **proposés** : c'est la forme voulue qui apparaît à côté du texte tapé dans 92 %, 100 % et 67 % des cas. `est → et` et `ses → ces` ne sont presque jamais décidés : à raison, un seul mot précédent ne suffit pas (`il est`/`il et`).

### Mots tapés en majuscules

| Prudence | Mots | Changés sans la protection (fr / en) | Avec la protection |
|---|---|---|---|
| Normale | 138 496 / 145 680 | 8,7 % / 0,27 % | 0 % |

Sans la protection, 8,7 % des mots français tapés en majuscules sans accent étaient accentués ou corrigés (`ECOLE` → `ÉCOLE`, `SNCF` → un mot proche), 0,27 % des mots anglais (corrections) ; avec elle, jamais. Le coût est réel en français : un mot courant tapé en majuscules (`ECOLE`) n'est plus accentué, c'est le choix demandé (sigles jamais modifiés).

## Limites

- Un seul mot précédent : les confusions de type `est`/`et` ou `ses`/`ces` sont mal décidées sans le mot qui suit ; elles restent à l'utilisateur (barre).
- Tatoeba est un corpus de phrases courtes et soignées ; les parts réelles (messagerie) seront autres. Les seuils sont dans `Prudence` pour être recalibrés.
- `on`/`ont`, `son`/`sont`, `ce`/`se` n'ont pas été ajoutés : à mesurer à part avant de les inscrire dans `ConfusionConfig.FRENCH_SETS` (le modèle et l'outil les prennent sans autre changement).

## Reproduire

```sh
./gradlew :tools:analyzeContext -PcontextSentences=20000   # écrit tools/build/analysis/M55-context-results.md
```
