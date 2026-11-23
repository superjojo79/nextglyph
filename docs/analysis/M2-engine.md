# M2 — Validation par espace et bilinguisme avec le moteur

Ce document rejoue l'analyse de validation de M0 (`docs/analysis/M0-corpus.md`, parties 2 et 3) avec l'implémentation du moteur (`nextglyph.engine.predict.Predictor`), mesure ce que M0 ne mesurait pas (apostrophes et traits d'union omis, accents) et le coût par frappe. Décisions : `docs/DECISIONS.md` (2026-11-19).

```sh
./gradlew :tools:buildLexicons                       # listes de mots, corpus, lexiques (si absents)
./gradlew :tools:analyzeEngine                       # échantillon de 5 % (-PanalysisSample=N pour N %)
./gradlew :engine:testClasses
java -cp "engine/build/classes/kotlin/main;engine/build/classes/kotlin/test;<kotlin-stdlib.jar>" \
    nextglyph.engine.predict.PredictionBenchmarkKt tools/build/lexicons
```

Résultats bruts : `tools/build/analysis/M2-engine-results.md`. Code : `tools/src/main/kotlin/nextglyph/tools/analysis/EngineAnalysis.kt` (même point d'entrée que `analyzeCorpus`, option `--engine`).

## 1. Méthode

- **Données** : mêmes entrées que M0 (listes de mots de `buildWordLists` chargées en lexiques par `LexiconBuilder`, corpus de **test** Tatoeba uniquement), mêmes fautes simulées (substitution par une voisine, 0, 2 ou 5 % par lettre, graine fixe), même utilisateur attentif (espace dès que la touche espace affiche le mot voulu).
- **Échantillon déterministe** : 5 % des phrases de test monolingues (même hachage du rang que `-PanalysisSample` de M0) et les premiers 5 % du corpus mixte (blocs de phrases contigus, pour garder les changements de langue).
- **Décision** : celle du moteur (`Predictor.decide`, cas `WORD` / `DOMINANT` / `LITERAL`), pour chaque réglage de prudence ; la simulation ne fait que la comparer au mot voulu (clés normalisées). La référence M0 (`CandidateSource` des outils, correction des préfixes connus, mêmes seuils, même disposition à 0,9 u pour la rangée 3) est rejouée **sur le même échantillon**, ce qui isole l'écart dû à l'implémentation de l'écart dû à l'échantillon.
- **Corpus mixte** : deux lexiques actifs, AZERTY, score de langue du moteur (`LanguageState`) alimenté par les mots voulus, fin de phrase signalée après chaque phrase ; référence : la langue de la phrase seule active.
- **Séparateurs** (nouveau) : mots tapés sans apostrophe ni trait d'union, sans faute, réglage Normal : élisions françaises suivies de leur mot (`l'` + `école` tapé `lecole`), mots anglais à apostrophe (`dont`), mots à trait d'union (`peutetre`).
- **Accents** (nouveau) : chaque mot du test français tapé en entier, sans accent ni majuscule ; on compare le texte inséré au mot du corpus (en minuscules), avec le français seul puis les deux lexiques.

## 2. Validation par espace — moteur contre M0

Fausses validations (part des validations automatiques), utilisateur attentif, échantillon de 5 % (FR : 28 131 mots, AZERTY ; EN : 79 641 mots, QWERTY).

| Langue, fautes | Prudente (0,80) moteur / M0 | **Normale (0,70) moteur / M0** | Audacieuse (0,60) moteur / M0 | M0, corpus complet, Normale |
|---|---|---|---|---|
| FR, 0 % | 0,59 / 0,58 % | **0,66 / 0,63 %** | 0,83 / 0,81 % | 0,67 % |
| FR, 2 % | 0,94 / 0,95 % | **1,10 / 1,08 %** | 1,40 / 1,36 % | 1,17 % |
| FR, 5 % | 1,90 / 1,94 % | **2,17 / 2,14 %** | 2,76 / 2,73 % | 2,10 % |
| EN, 0 % | 0,88 / 0,88 % | **0,90 / 0,90 %** | 0,82 / 0,82 % | 1,11 % |
| EN, 2 % | 1,03 / 1,03 % | **1,08 / 1,08 %** | 1,02 / 1,02 % | 1,19 % |
| EN, 5 % | 1,27 / 1,27 % | **1,37 / 1,37 %** | 1,36 / 1,36 % | 1,62 % |

Mots complétés ou corrigés correctement (Normale) : FR 17,18 / 18,82 / 20,70 % (0 / 2 / 5 % de fautes), EN 20,75 / 21,66 / 22,34 % ; KSPC sans faute FR 0,944, EN 0,939. M0 sur le même échantillon : FR 17,24 / 18,90 / 20,77 %, EN identique.

**Objectif du §15.2 (< 3 %) tenu** dans les deux langues, à tous les taux de fautes et pour les trois réglages de prudence (pire cas : 2,76 %, FR, 5 % de fautes, Audacieuse ; 2,17 % en Normale).

**Écart avec M0.**

- *Même échantillon* : l'anglais est identique à M0 au centième près. En français, le moteur fait au plus 0,04 point de fausses validations de plus et 0,1 point de mots complétés de moins. L'écart vient des **élisions**, absentes de M0 (seule différence de méthode en français) : un texte comme `lam` a désormais aussi les candidats `l'ami`, `l'amour`, qui ajoutent de la masse au dénominateur (un peu moins de complétions dominantes) et, rarement, l'emportent sur le mot voulu.
- *Échantillon contre corpus complet* : écarts de −0,1 à +0,07 point (FR) et −0,1 à −0,25 point (EN) en Normale, de l'ordre de la variance d'échantillonnage (quelques dizaines de fausses validations). Les chiffres M0 du corpus complet ont en outre été obtenus avec la rangée 3 à 0,85 u.

## 3. Apostrophes, traits d'union et élisions omis

Réglage Normale, sans faute, mots tapés sans séparateur :

| Cas | Occurrences | Restitués | Faux | Laissés tels quels |
|---|---|---|---|---|
| FR, élision et son mot (`lecole`) | 1 776 | 68,6 % | 2,1 % | 29,2 % |
| FR, apostrophe dans le mot (`aujourdhui`) | 37 | 94,6 % | 0 % | 5,4 % |
| FR, trait d'union (`peutetre`) | 576 | 17,2 % | 16,3 % | 66,5 % |
| EN, apostrophe (`dont`) | 3 606 | 57,4 % | 0,4 % | 42,3 % |
| EN, trait d'union | 141 | 0 % | 4,3 % | 95,7 % |

Lecture :

- Les cas laissés tels quels sont surtout les **paires ambiguës voulues par la spec** : la forme sans séparateur est elle-même un mot (`na`, `ma`, `la`, `sil` en français ; `its`, `cant`, `hes`, `ill`, `id`, `wont`, `shes`, `were` en anglais) et relève du cas 1 ; la variante reste dans la barre. `im` → `I'm` n'atteint pas le seuil (`P` = 0,15) : `im` est un préfixe très fréquent (`important`, `impossible`).
- Les traits d'union français faux viennent surtout des **inversions** : `estce` donne `est-ce-que`, graphie du lexique plus fréquente que `est-ce`. Les inversions verbe-pronom (`avez-vous`, `as-tu`, `a-t-il`) ne sont pas des clés du lexique et restent telles quelles. Une règle d'inversion (verbe, trait d'union, pronom) est une piste si l'étude utilisateur (M5) montre que ces formes sont tapées sans trait d'union.
- Ces mesures portent sur un utilisateur qui omet **toujours** le séparateur ; les fausses validations du §2 (mots tapés avec leur séparateur, comme dans M0) restent la mesure de référence.

## 4. Accents

Mots du test français tapés en entier sans accent (3 533 occurrences de mots accentués dans l'échantillon) :

| Lexiques actifs | Restitués | Faux | Laissés sans accent | Mots sans accent accentués à tort |
|---|---|---|---|---|
| français | 58,6 % | 0,23 % | 41,2 % | 0,02 % |
| français + anglais | 50,4 % | 0,14 % | 49,4 % | 0,01 % |

Les mots laissés sans accent sont les ambiguïtés du §5.5 (`a`/`à`, `ou`/`où`, `la`/`là` ; `ca`, car `ça` ne fait que 87 % des occurrences de la clé à cause des formes `CA` et `cA` des sources) et, avec l'anglais actif, les mots que le lexique anglais écrit sans accent (§10.6). La lecture littérale « une seule orthographe » ne restituait que 46,5 % des mots accentués sur l'échantillon de 1 % : les variantes rares des sources (`écolé`, `fenêtré`) suffisaient à bloquer la restitution. Le seuil de 95 % (`docs/DECISIONS.md`) corrige ce défaut, au prix de 0,2 % d'accents faux.

## 5. Bilinguisme (corpus mixte)

Premiers 5 % du corpus mixte (7 273 phrases, 59 413 mots), AZERTY, deux lexiques actifs, score de langue du moteur :

| Fautes | Prudence | Auto (% mots) | Fausses (% auto) | Référence, langue connue | Attribuables à la langue |
|---|---|---|---|---|---|
| 0 % | Prudente | 11,3 % | 0,30 % | 0,33 % | 30 % |
| 0 % | **Normale** | **14,2 %** | **0,36 %** | **0,34 %** | **23 %** |
| 0 % | Audacieuse | 16,9 % | 0,39 % | 0,36 % | 23 % |
| 2 % | Prudente | 12,4 % | 0,52 % | 0,51 % | 32 % |
| 2 % | **Normale** | **15,6 %** | **0,69 %** | **0,62 %** | **25 %** |
| 2 % | Audacieuse | 18,5 % | 0,86 % | 0,80 % | 24 % |

Langue dominante avant la frappe correcte pour 93,2 % des mots (M0, corpus complet : 92,6 %) ; **langue de la phrase** (§10.6) correcte pour 96,7 % des mots. Comme dans M0, environ un quart des fausses validations du corpus mixte sont attribuables à la langue, pour un total proche de la référence monolingue et loin des 3 % (M0, corpus complet, Normale : 0,58 % sans faute, 0,90 % à 2 % ; l'échantillon des premiers 5 % est plus facile que la moyenne).

## 6. Coût par frappe

Programme `PredictionBenchmark` (`Predictor.predict` après chaque lettre : extraction du mot, candidats, décision, barre ; sans cache), lexiques de production, mots tirés selon leur fréquence, poste de développement Windows 11, JDK 25 :

| Cas | moyenne | médiane | p99 | p99,9 |
|---|---|---|---|---|
| FR, AZERTY, sans faute | 142 µs | 72 µs | 0,70 ms | 1,24 ms |
| FR, avec faute | 235 µs | 219 µs | 0,86 ms | 1,32 ms |
| EN, QWERTY, sans faute | 187 µs | 80 µs | 0,78 ms | 1,29 ms |
| EN, avec faute | 254 µs | 254 µs | 0,84 ms | 1,23 ms |
| Bilingue, AZERTY, sans faute | 311 µs | 152 µs | 1,28 ms | 1,95 ms |
| Bilingue, avec faute | 415 µs | 393 µs | 1,35 ms | 1,96 ms |

**Budget de 2 ms tenu** jusqu'au p99,9, y compris en bilingue ; quelques frappes isolées (moins de 0,1 %) dépassent 2 ms, ce qui correspond aux pauses du ramasse-miettes et du JIT déjà relevées dans les mesures précédentes. Les frappes les plus chères sont les préfixes courts, où chaque position admet des dizaines d'éditions connues. Deux optimisations ont été nécessaires (avant : p99 bilingue de 2,1 à 2,4 ms) : `Lexicon.prefixMass` (connaissance et masse d'un préfixe sans lire ses enfants) et l'arrêt anticipé de l'exploration des préfixes candidats, triés par borne `poids × masse` (exact : aucun mot d'un préfixe ne dépasse cette borne). À refaire sur un téléphone de milieu de gamme ; un cache des candidats par texte tapé (`Predictor(cacheSize = …)`) est disponible si besoin.

## 7. Limites

- Celles de M0 (corpus Tatoeba, modèle de fautes par substitution seulement, utilisateur attentif, pas de bigrammes) s'appliquent.
- Échantillon de 5 % (environ 7 minutes d'analyse) : la variance est de l'ordre de 0,1 à 0,2 point sur les taux de fausses validations.
- Les mesures des §3 et §4 supposent un utilisateur qui tape systématiquement sans séparateur ni accent ; un utilisateur réel mélange les deux.
- Distance d'édition 2 (§10.4) non implémentée.

## 8. Français séparé par licence (fichiers fusionnés à la lecture)

Depuis la séparation de `fr.nglx` (`docs/DECISIONS.md`, 2026-11-19), le moteur lit le français en deux fichiers fusionnés (`fr-lexique`, sans casse, et `fr-dicollecte`). `./gradlew :tools:analyzeEngine` lit les fichiers séparés, comme l'application ; `-PanalysisCombined` relit la liste unique `fr.tsv` (non distribuée) et redonne exactement les chiffres des §2 à §5. Même échantillon, moteur seul, réglage Normale :

| Mesure | Liste unique | Fichiers séparés |
|---|---|---|
| FR, fausses validations, 0 / 2 / 5 % de fautes | 0,66 / 1,10 / 2,17 % | 0,72 / 1,16 / 2,26 % |
| FR, complétées ou corrigées, 0 / 2 / 5 % | 17,18 / 18,82 / 20,70 % | 17,12 / 18,78 / 20,64 % |
| FR, pire cas toutes prudences (5 %, Audacieuse) | 2,76 % | 2,74 % |
| Élisions restituées / fausses | 68,6 / 2,1 % | 68,6 / 2,1 % |
| Accents restitués / faux (français seul) | 58,6 / 0,23 % | 58,5 / 0,20 % |
| Corpus mixte, 0 / 2 % de fautes | 0,36 / 0,69 % | 0,36 / 0,68 % |
| Langue de la phrase correcte | 96,7 % | 96,7 % |
| Anglais | — | identique au centième |

Les prédictions sont équivalentes : écarts de 0,02 à 0,09 point (quelques validations sur environ 4 800), dans la variance de l'échantillon et loin de l'objectif de 3 %. Ils viennent du seul écart de données : une forme présente dans les deux sources compte en plus le plancher de Dicollecte (1, soit 0,01 occurrence par million), ce qui déplace à la marge quelques rapports `P(w)` proches des seuils.

## 9. Classement des candidats (issue #53)

Mesures de `./gradlew :tools:analyzeEngine` (échantillon de 5 %, fichiers français séparés, même graine) après les trois changements de l'issue #53 (`docs/DECISIONS.md`, 2026-11-23).

### 9.1 Score spatial des substitutions

Nouvelle section de l'analyse (« Score spatial (frappes simulées) », `GaussianTapModel`) : chaque lettre du mot voulu est frappée en un point tiré d'une gaussienne centrée sur sa touche au repos (écart type `σu` × largeur de la touche, `σu` × hauteur de rangée) ; la lettre tapée est celle de la touche au repos qui contient le point (ce que le décodeur fait sans modèle de langue). On compare le moteur **binaire** (sans les frappes : voisine 0,01, autre 0,0005) au moteur **spatial** (`WordTaps` des k premières frappes pour le préfixe de k lettres). Utilisateur attentif, mêmes tirages pour tous les réglages.

| Langue, `σu` (lettres fausses) | Prudence | Complétées/corrigées binaire → spatial | Fausses (% auto) binaire → spatial | Fautes non corrigées (% mots) binaire → spatial |
|---|---|---|---|---|
| FR, 0,20 (1,8 %) | Prudente | 15,56 → 16,17 % | 0,97 → 0,83 % | 4,55 → 3,62 % |
| FR, 0,20 (1,8 %) | **Normale** | **18,60 → 19,43 %** | **1,25 → 1,07 %** | **3,85 → 2,95 %** |
| FR, 0,20 (1,8 %) | Audacieuse | 21,10 → 21,79 % | 1,59 → 1,32 % | 3,34 → 2,57 % |
| FR, 0,25 (6,6 %) | Prudente | 17,24 → 19,99 % | 2,34 → 1,97 % | 16,14 → 13,09 % |
| FR, 0,25 (6,6 %) | **Normale** | **20,64 → 23,73 %** | **2,84 → 2,27 %** | **14,26 → 11,13 %** |
| FR, 0,25 (6,6 %) | Audacieuse | 23,64 → 26,26 % | 3,75 → 2,97 % | 12,50 → 9,87 % |
| EN, 0,20 (1,9 %) | Prudente | 17,64 → 18,33 % | 0,94 → 0,80 % | 5,12 → 4,29 % |
| EN, 0,20 (1,9 %) | **Normale** | **21,54 → 22,28 %** | **1,06 → 0,82 %** | **4,64 → 3,67 %** |
| EN, 0,20 (1,9 %) | Audacieuse | 25,40 → 26,24 % | 1,04 → 0,83 % | 3,95 → 3,04 % |
| EN, 0,25 (7,0 %) | Prudente | 18,26 → 20,49 % | 1,88 → 1,69 % | 17,87 → 15,45 % |
| EN, 0,25 (7,0 %) | **Normale** | **22,30 → 24,96 %** | **2,02 → 1,66 %** | **16,38 → 13,44 %** |
| EN, 0,25 (7,0 %) | Audacieuse | 26,75 → 29,40 % | 2,06 → 1,73 % | 14,17 → 11,42 % |

Le score spatial gagne sur les trois mesures à la fois, dans toutes les cases : plus de mots corrigés, moins de fausses validations, environ un cinquième de fautes non corrigées en moins. Avec beaucoup de fautes (6,6 %), le réglage Audacieuse passe sous l'objectif de 3 % en français (3,75 → 2,97 %). Sans frappes connues (tests, champ sans position), le moteur est exactement le moteur binaire : les tableaux du §2 sont inchangés.

Limites : la simulation ignore le modèle de langue du décodeur (`λ`), qui corrige déjà une partie des frappes ambiguës avant la validation, et des frappes réelles ont des queues plus lourdes qu'une gaussienne (d'où le plancher à 0,0005). Le gain réel est à confirmer par les mesures locales (§15.4 de la spec).

### 9.2 Accents

| Lexiques actifs | Restitués avant → après | Faux avant → après | Mots sans accent accentués à tort |
|---|---|---|---|
| français | 58,5 → 61,3 % | 0,20 → 0,20 % | 0,02 → 0,02 % |
| français + anglais (score de départ, français préféré) | 50,4 (liste unique) → 56,6 % | 0,14 → 0,11 % | 0,01 → 0,02 % |

Gains : `ca` → `ça` (les sigles `CA` et les formes `cA` ne comptent plus) et, avec l'anglais actif, les mots que le lexique anglais écrit sans accent ne bloquent plus quand le français domine. Les mots encore laissés sans accent sont les ambiguïtés voulues (`à` 507 occurrences, `où` 49, `là` 18, `dû` 15, `plaît`/`plait`, `sûr`/`sur`) et les participes passés qui sont aussi des formes du présent (`demandé`/`demande`, `donné`/`donne`, `trouvé`/`trouve`, `passé`/`passe`) : seul le contexte (#51) peut les départager.

### 9.3 Écart avec le deuxième candidat

`minRatio` dépend désormais du réglage de prudence. Mais `P(w2) ≤ 1 − P(w1)` : dès que `P(w1) ≥ minProb`, le rapport vaut au moins `minProb / (1 − minProb)`, soit 4 (Prudente), 2,33 (Normale) et 1,5 (Audacieuse). Les rapports 3 / 2 / 1,5 ne changent donc aucune décision (tableaux identiques au centième). Des rapports plus stricts, 5 / 3 / 2, ont été mesurés : fausses validations FR 5 % de fautes 1,84 → 1,81 % (Prudente), 2,26 → 2,30 % (Normale), 2,86 → 2,74 % (Audacieuse), corpus mixte ±0,1 point, pour 0,2 à 0,5 point de mots complétés en moins. Écart dans la variance de l'échantillon : les rapports 3 / 2 / 1,5 sont gardés, et le seuil de confiance reste porté par `minProb`.
