# M51 — Modèles de mot suivant (issue #51)

Mesures des modèles `fr.ngrm` et `en.ngrm` (bigrammes et trigrammes de mots, repli « stupid backoff », SPEC §10.3), produites par `:tools:buildLexicons` dans `tools/build/lexicons/ngram-report.md`.

## Méthode

- **Corpus** : phrases Tatoeba (CC BY 2.0 FR), découpées en mots avec les élisions séparées (`j' ai`). Le découpage entraînement / test est celui de `SentencePartition` : le modèle ne voit jamais les phrases de test.
- **Élagage** (`NgramPruning`, valeurs par défaut) : comptes minimaux 2 pour les bigrammes et 2 pour les trigrammes, 8 suivants gardés par contexte de bigramme et 4 par contexte de trigramme, vocabulaire des 30 000 mots les plus fréquents. Les probabilités sont calculées sur toutes les occurrences du contexte, élaguées comprises.
- **Prédiction** : les trois meilleurs mots après les deux derniers mots (repli sur le bigramme avec un facteur 0,4).
- **Évaluation** : pour chaque position de chaque phrase de test, on demande les trois meilleurs mots d'après le contexte précédent. Les positions « éligibles » sont celles où le mot à prédire est proposable et dans le vocabulaire ; « toutes positions » compte aussi les autres comme des échecs.
- **Budget** : au plus 5 Mio par langue.

## Résultats

| Langue | Taille | Top-1 (éligibles) | Top-3 (éligibles) |
|---|---|---|---|
| fr | 2 442 231 octets (2,33 Mio) | 21,62 % | 36,17 % |
| en | 4 002 533 octets (3,82 Mio) | 23,21 % | 38,67 % |

| Mesure | fr | en |
|---|---|---|
| Vocabulaire | 30 000 mots | 30 000 mots |
| Bigrammes gardés | 71 085 | 99 032 |
| Contextes de trigrammes | 121 526 | 237 511 |
| Trigrammes gardés | 208 234 | 420 822 |
| Phrases d'entraînement | 676 598 | 1 889 611 |
| Mots d'entraînement | 5 099 695 | 14 150 246 |
| Chargement (map + `NgramModel.load`) | 1,55 ms | 0,63 ms |
| Positions du test | 563 059 | 1 568 759 |
| Positions éligibles | 536 869 | 1 491 000 |
| Top-1, éligibles | 21,62 % | 23,21 % |
| Top-3, éligibles | 36,17 % | 38,67 % |
| Top-1, toutes positions | 20,62 % | 22,06 % |
| Top-3, toutes positions | 34,48 % | 36,76 % |

Lecture : le bon mot figure parmi les trois propositions plus d'une fois sur trois, pour 2,3 à 3,8 Mio. Le chargement est instantané (projection en mémoire, lecture paresseuse). Tatoeba est un corpus de phrases courtes d'apprentissage : le taux sur du texte réel de messagerie sera différent, à mesurer sur le terrain.

## Reproduire

```sh
./gradlew :tools:buildLexicons   # télécharge les données (fetchData), écrit fr.ngrm, en.ngrm et ngram-report.md
```

Sortie dans `tools/build/lexicons/` (ignoré par git). Les `.ngrm` ne sont jamais versionnés ; l'IME les embarque comme les `.nglx`.
