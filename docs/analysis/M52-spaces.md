# M52 — Erreurs d'espace (issue #52)

Mesures des corrections d'erreurs d'espace de la validation par espace (SPEC §5.5, « Erreurs d'espace », §10.4), produites par `:tools:analyzeSpaces` (`nextglyph.tools.analysis.SpaceErrorAnalysis`) avec le moteur de l'application (`Predictor.decide`), les lexiques de `buildWordLists` et les modèles de bigrammes de `buildLexicons`. Résultats bruts : `tools/build/analysis/M52-spaces-results.md` (non versionné).

## Méthode

- **Corpus** : phrases du corpus de **test** Tatoeba (jamais l'entraînement : le modèle de bigrammes ne les a pas vues), 20 000 phrases par langue (échantillon déterministe réparti sur tout le corpus), soit 154 063 mots en français (AZERTY) et 153 129 en anglais (QWERTY).
- **Frappes** : `GaussianTapModel`, σ = 0,20 touche (même modèle que M2, §9). Un texte « juste » est tapé tel quel, chaque frappe tirée dans sa touche (tirage répété jusqu'à tomber sur la bonne lettre) ; le texte « frappes simulées » est tapé avec les fautes naturelles du modèle (1,8 à 1,9 % de lettres fausses, 8 % de mots qui ne sont plus des mots).
- **Fausses corrections** : pour chaque mot validé du texte juste, part des mots où une coupure ou une suppression d'espace est appliquée automatiquement (cas 2 dominant). Le mot précédent est celui que le moteur a laissé dans le texte.
- **Erreurs injectées**, pour chaque couple de mots voisins (connus du lexique, lettres `a`–`z` seules) du corpus :
  - *espace tapée sur `n`, `b`, `v` ou `c`* : `mot1` + lettre tirée + `mot2`, avec une frappe tirée dans le bas de la touche (`dx` ∈ [−0,4 ; 0,4], `dy` ∈ [0,25 ; 0,5]), comme un doigt visant la barre d'espace ;
  - *espace oubliée* : `mot1mot2` ;
  - *espace en trop* : chaque mot d'au moins 4 lettres coupé à une position intérieure tirée au hasard. Les deux fragments sont validés l'un après l'autre comme dans l'application (le premier est complété ou corrigé s'il domine) ; la ligne « fragments inconnus » ne garde que les cas où aucun des deux n'est un mot du lexique, les seuls que la règle du §5.5 permet de joindre.
  - Issue : *corrigée exactement* (le texte final est celui voulu), *laissée telle quelle* (le texte tapé est conservé, accents restitués compris), *changée à tort* (tout autre texte, dont la correction d'un fragment ou d'un mot collé par une autre règle).
- **Prudence** : les trois réglages, avec les seuils du §5.5. « Sans bigrammes » : mêmes réglages sans `useNgrams` (unigrammes seuls).
- **Référence** : le moteur sans correction d'espace (poids 0 pour les trois erreurs), même échantillon, réglage Normale.

## Réglages retenus

`PredictionConfig.spaceEdits` (`SpaceEditConfig`) :

| Réglage | Valeur |
|---|---|
| `spaceBarWeight` (espace tapée sur `n`, `b`, `v`, `c`, frappe dans le bas de la touche) | 1 |
| `gluedWeight` (mots collés) | 0,5 |
| `joinWeight` (espace en trop) | 1 |
| `minTapDy` | 0,25 hauteur de rangée |
| `minPartProbability` / `minSingleLetterProbability` | 10⁻⁶ / 2·10⁻³ |
| `minScore` | 10⁻⁹ |
| `unigramBackoff` (bigramme élagué) | 0,5 |
| `rareWordProbability` (fragment « très rare ») | 2·10⁻⁵ |
| `minJoinProbability` | 10⁻⁷ |

Calibrage (500 à 3 000 phrases par langue, réglage Normale, avant la mesure finale) : les fausses corrections d'abord. Les planchers `minScore` / `minPartProbability` ont été choisis parmi trois couples ; les poids et le repli ensuite.

| Variante (3 000 phrases) | Fausses corrections fr / en | Espace sur `n`… fr / en | Mots collés fr / en |
|---|---|---|---|
| 10⁻⁸ / 3·10⁻⁶ | 0,004 % / 0,000 % | 81,3 % / 87,8 % | 68,3 % / 74,2 % |
| **10⁻⁹ / 10⁻⁶ (retenu)** | 0,009 % / 0,013 % | 89,0 % / 95,2 % | 77,3 % / 83,3 % |
| 10⁻¹⁰ / 10⁻⁷ | 0,017 % / 0,031 % | 94,5 % / 98,0 % | 82,8 % / 87,0 % |
| retenu, mots collés de poids 1 | 0,009 % / 0,022 % | 89,0 % / 94,8 % | 80,9 % / 86,0 % |
| retenu, repli du bigramme 0,1 | 0,004 % / 0,000 % | 86,6 % / 91,9 % | 72,9 % / 78,3 % |
| retenu, repli du bigramme 0,6 | 0,009 % / 0,022 % | 90,1 % / 96,5 % | 80,8 % / 85,7 % |

Les fausses corrections de toutes les variantes sont très en dessous de l'objectif de 1 % : le choix retient les valeurs intermédiaires (poids 0,5 pour les mots collés, comme proposé, repli 0,5 entre 0,3 et 0,6) plutôt que la variante qui gagne cinq points de rappel pour deux à trois fois plus de fausses corrections. Pour l'espace en trop, `rareWordProbability` de 10⁻⁶, 10⁻⁵, 5·10⁻⁵ donnait 9,7 %, 15,5 %, 28,5 % de corrections exactes (fr, Normale) sans aucune fausse jonction sur le texte juste ; 2·10⁻⁵ (moins d'un mot sur 50 000) est le compromis retenu (22,1 % à la mesure finale), les fausses jonctions restant nulles.

## Résultats

### Fausses corrections sur texte juste (% des mots validés)

| Langue | Texte | Prudente | Normale | Audacieuse | Normale, sans bigrammes |
|---|---|---|---|---|---|
| fr | juste | 0,005 % | 0,009 % | 0,009 % | 0,010 % |
| fr | frappes simulées | 0,006 % | 0,010 % | 0,010 % | 0,011 % |
| en | juste | 0,012 % | 0,012 % | 0,013 % | 0,016 % |
| en | frappes simulées | 0,011 % | 0,011 % | 0,013 % | 0,016 % |

Toutes sont des coupures ; **aucune espace en trop n'a été supprimée à tort** (0 sur plus de 300 000 mots). Cela fait une correction à tort tous les 7 700 à 20 000 mots, très en dessous des 3 % d'annulations visés (§15.2). Fausses corrections les plus fréquentes (Normale) : français `jackson → jack son`, `teslent → tes lent`, `lagarde → la garde`, `leblanc → le blanc` ; anglais `rainforest → rain forest`, `treehouse → tree house`, `callgirl → call girl`, `lifejacket → life jacket` : des mots hors lexique ou des composés. Le ⌫ restitue le texte tapé et, après deux refus, le mot est adopté (§5.5).

### Erreurs injectées (réglage Normale)

| Erreur | Langue | Occurrences | Corrigées exactement | Laissées telles quelles | Changées à tort | Référence : changées à tort |
|---|---|---|---|---|---|---|
| espace tapée sur `n`, `b`, `v`, `c` | fr | 111 262 | 90,04 % | 9,84 % | 0,13 % | 5,42 % |
| | en | 118 631 | 95,96 % | 3,99 % | 0,05 % | 4,21 % |
| espace oubliée | fr | 111 262 | 80,54 % | 18,57 % | 0,89 % | 15,49 % |
| | en | 118 631 | 84,71 % | 14,51 % | 0,77 % | 11,95 % |
| espace en trop | fr | 76 023 | 22,08 % | 62,58 % | 15,34 % | 18,81 % |
| | en | 77 270 | 27,67 % | 60,45 % | 11,88 % | 14,41 % |
| espace en trop, fragments inconnus | fr | 7 575 | 39,29 % | 44,09 % | 16,62 % | 27,88 % |
| | en | 5 590 | 48,19 % | 30,13 % | 21,68 % | 26,76 % |

La référence (moteur sans correction d'espace) ne corrige jamais ces erreurs et « corrige » en un autre mot 4 à 5 % des espaces tapées sur `n`…, 12 à 15 % des mots collés et 14 à 19 % des espaces en trop (une des deux parties est complétée ou corrigée en un autre mot). Avec les corrections, ces changements à tort tombent à moins de 1 % pour les deux premières erreurs et diminuent pour la troisième.

Par prudence (fr / en, corrigées exactement ; changées à tort) :

| Erreur | Prudente | Normale | Audacieuse |
|---|---|---|---|
| espace tapée sur `n`… | 89,89 % ; 0,10 % / 95,50 % ; 0,03 % | 90,04 % ; 0,13 % / 95,96 % ; 0,05 % | 90,11 % ; 0,16 % / 96,20 % ; 0,07 % |
| espace oubliée | 78,35 % ; 0,70 % / 83,53 % ; 0,53 % | 80,54 % ; 0,89 % / 84,71 % ; 0,77 % | 81,54 % ; 1,24 % / 85,89 % ; 1,05 % |
| espace en trop | 20,63 % ; 11,87 % / 25,84 % ; 8,91 % | 22,08 % ; 15,34 % / 27,67 % ; 11,88 % | 23,28 % ; 19,32 % / 28,48 % ; 14,86 % |
| espace en trop, fragments inconnus | 36,42 % ; 12,16 % / 45,99 % ; 16,89 % | 39,29 % ; 16,62 % / 48,19 % ; 21,68 % | 40,86 % ; 23,30 % / 49,53 % ; 26,60 % |

Sans bigrammes (Normale) : espace tapée sur `n`… 90,29 % / 96,59 %, mots collés 80,52 % / 83,47 % (changés à tort 1,05 % / 1,12 %) : les bigrammes servent surtout à départager les coupures ambiguës (moins de changements à tort) ; en leur absence, les unigrammes suffisent presque pour le rappel.

## Lecture et limites

- **Espace tapée sur `n`, `b`, `v`, `c`** : près de 90 % (fr) et 96 % (en) corrigées. Le reste est laissé tel quel (une des deux parties est un mot trop rare ou le texte tapé est un mot). La preuve spatiale est la condition : sans position de frappe, ce cas n'existe pas.
- **Mots collés** : 80 à 85 % corrigés. Les mots collés non corrigés ont surtout un morceau en dessous des planchers (mots rares) ou une coupure que les autres candidats concurrencent (sous la règle de dominance).
- **Espace en trop** : c'est la limite de la règle « fragments inconnus ou très rares ». Un fragment est le début du mot voulu (`ordina`, `toujo`) : à sa propre validation par espace il est souvent **complété** (`toujo` → `toujours`, dominant) ou corrigé avant même que le second soit tapé, ce qui rend la jonction impossible (environ 11 % des cas ; les exemples non corrigés les plus fréquents sont `pourqu oi`, `jama is`, `goi ng`, `doi ng`). Ou bien un fragment est un mot courant (`so ir`, `all er`), que la règle du §5.5 ne touche pas : 22 à 28 % des espaces en trop, 39 à 48 % quand les deux fragments sont inconnus. Aucune fausse jonction n'est apparue sur du texte juste ; élargir la règle (fragments courants) en produirait.
- **Texte tapé** : les corpus Tatoeba sont des phrases courtes et soignées ; la proportion de noms propres, de mots hors lexique et d'abréviations des messages réels (`jsuis`, `cv`) sera plus grande. Le taux d'annulation du §15.4 mesuré sur le terrain dira si le plancher `minScore` doit monter.
- **Coût** : les coupures sont calculées à chaque frappe d'un mot qui n'est pas un mot, comme les autres candidats ; elles ajoutent environ 0,05 ms (5 %) à une décision de 0,9 ms pour deux mots collés tirés du corpus, sans cache de candidats (mesure locale à titre indicatif). L'analyse complète (3 prudences, 2 langues, 20 000 phrases) prend dix minutes sur quatre cœurs.

## Reproduire

```sh
./gradlew :tools:analyzeSpaces                      # 20 000 phrases par langue (≈ 10 min), après buildLexicons
./gradlew :tools:analyzeSpaces -PspaceSentences=2000 # essai rapide
```

Sortie : `tools/build/analysis/M52-spaces-results.md` (ignoré par git). Les modèles `fr.ngrm` et `en.ngrm` sont lus s'ils existent (`tools/build/lexicons/`), sinon les unigrammes sont utilisés.
