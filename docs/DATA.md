# Sources de données linguistiques

Ce document applique la règle du §10.2 de `SPEC.md`. Dernière vérification des licences : 19 novembre 2026, sur les pages officielles listées ci-dessous (et sur les fichiers de licence contenus dans les archives téléchargées).

## Règle de classement

| Licence | Décision |
|---|---|
| Apache, MIT, BSD, domaine public, ou équivalent permissif | OK |
| CC BY, CC BY-SA, MPL-2.0 | OK **seulement** comme fichier de données distinct qui garde sa licence, avec attribution |
| GPL, LGPL, AGPL, NC, ND, licence inconnue ou ambiguë, licence non vérifiable sur la page officielle | Exclue |

## Besoins

- (a) liste de mots avec formes fléchies (couverture du vocabulaire) ;
- (b) fréquences d'usage, idéalement de langue courante ;
- (c) corpus de phrases réelles pour l'analyse du §15.1 (usage hors ligne, non distribué).

## Sources retenues

Elles sont toutes décrites, avec URL, taille et SHA-256, dans `tools/data/sources.json`. `./gradlew :tools:fetchData` les télécharge dans `tools/build/data/`.

| Id | Langue | Besoin | Nom, version | URL officielle | Licence exacte | Page où la licence est indiquée | Usage | Taille |
|---|---|---|---|---|---|---|---|---|
| `lexique-4-10-fr` | fr | a, b | Lexique 4.10 (successeur de Lexique 3, voir remarque) | https://www.lexique.org/databases/Lexique/Lexique410.zip | CC BY-SA 4.0 | https://www.lexique.org/ (page d'accueil) ; texte `CC.BY.SA.txt` et `README-Lexique.txt` dans l'archive ; https://creativecommons.org/licenses/by-sa/4.0/legalcode.fr | Formes fléchies (environ 190 000), lemmes, catégories grammaticales, fréquences issues de sous-titres de films (corpus de 316 millions de mots) : langue parlée | 48 903 925 o |
| `dicollecte-hunspell-7-7-fr` | fr | a | Dictionnaires orthographiques français (Dicollecte / Grammalecte) v7.7, 2025 | https://www.grammalecte.net/dic/hunspell-french-dictionaries-v7.7.zip | MPL-2.0 | `README_dict_fr.txt` dans l'archive (« MPL : Mozilla Public License version 2.0 ») ; https://www.mozilla.org/MPL/2.0/ | Couverture du vocabulaire, variantes orthographiques (classique, réforme 1990, toutes variantes) ; format Hunspell, à développer en formes fléchies | 1 056 913 o |
| `scowl-hunspell-en-us-2026-02-25` | en | a | SCOWL / English Speller Database, dictionnaire Hunspell en_US (taille 60), version 2026.02.25 | https://github.com/en-wl/wordlist/releases/download/rel-2026.02.25/hunspell-en_US-2026.02.25.zip | Licence permissive de type MIT (« Permission to use, copy, modify, distribute, and sell … provided that the above copyright notice appears »), copyright 2000-2026 Kevin Atkinson ; le fichier `.aff` est sous licence BSD (Geoff Kuenning, clause 4 retirée) | https://github.com/en-wl/wordlist/blob/v2/Copyright ; `README_en_US.txt` dans l'archive | Couverture du vocabulaire anglais américain, à développer en formes fléchies | 187 271 o |
| `tatoeba-fra-sentences` | fr | c | Tatoeba, export hebdomadaire des phrases en français | https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences.tsv.bz2 | CC BY 2.0 FR | https://tatoeba.org/en/downloads (« These files are released under CC BY 2.0 FR ») ; https://tatoeba.org/en/terms_of_use | Corpus de phrases pour l'analyse du §15.1 ; phrases courtes, proches de la conversation | 9 805 546 o |
| `tatoeba-eng-sentences` | en | c | Tatoeba, export hebdomadaire des phrases en anglais | https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 | CC BY 2.0 FR | idem | idem | 24 947 715 o |

Deux sources s'y ajoutent pour la construction des listes de mots (voir « Construction des listes de mots ») : les mêmes projets, sous la même licence, mais sous forme de **listes déjà fléchies**, ce qui évite d'implémenter l'expansion des affixes Hunspell.

| Id | Langue | Besoin | Nom, version | URL officielle | Licence exacte | Page où la licence est indiquée | Usage | Taille |
|---|---|---|---|---|---|---|---|---|
| `dicollecte-lexique-7-7-fr` | fr | a | Lexique des formes fléchies (Dicollecte / Grammalecte) v7.7, 2025 (même version que le dictionnaire Hunspell) | https://www.grammalecte.net/dic/lexique-grammalecte-fr-v7.7.zip | MPL-2.0 | `README_lexique.txt` dans l'archive (« MPL : Mozilla Public License version 2.0 ») et en-tête du fichier `lexique-grammalecte-fr-v7.7.txt` (« Licence : MPL v2.0 ») ; la page de téléchargement https://grammalecte.net/ ne donne que la licence GPL 3 du programme, comme pour le dictionnaire Hunspell | Formes fléchies (environ 476 000 formes distinctes) avec lemme et étiquettes ; seule la colonne « Flexion » est lue | 12 325 782 o |
| `scowl-final-lists-2020-12-07-en` | en | a | SCOWL (SCOWLv1) 2020.12.07 : listes déjà développées `final/{english,american}-{words,upper,proper-names,contractions,abbreviations}.NN`, `NN` = taille 10 (très courant) à 95 (rare) | https://downloads.sourceforge.net/project/wordlist/SCOWL/2020.12.07/scowl-2020.12.07.tar.gz | Même licence permissive de type MIT que SCOWL 2026 (copyright 2000-2018 Kevin Atkinson, fichier `Copyright` de l'archive) | `Copyright` dans l'archive ; https://wordlist.aspell.net/ | Vocabulaire anglais US avec niveau de fréquence. **Seuls les niveaux ≤ 70 sont utilisés** : ils viennent de sources du domaine public (Moby, 12dicts, 5desk...) ; les niveaux 80 et 95 incluent UKACD et d'autres sources à conditions d'attribution particulières | 2 569 810 o |

Pourquoi SCOWL 2020.12.07 et non 2026.02.25 : la version 2026 ne publie pas de liste développée avec niveaux (seulement des dictionnaires Hunspell à affixes, une taille à la fois, et un générateur en ligne non versionné) ; la version 1 est la dernière à publier les listes par taille, et son archive est immuable (SHA-256 vérifié). Le dictionnaire Hunspell en_US 2026 et le dictionnaire Hunspell français restent dans le manifeste mais **ne sont pas utilisés** par `buildWordLists`.

Total téléchargé : 99 796 962 octets (environ 95 Mio).

### Couverture des besoins

| Besoin | Français | Anglais |
|---|---|---|
| (a) vocabulaire | Dicollecte (MPL-2.0) + Lexique 4.10 (CC BY-SA) | SCOWL en_US (permissive) |
| (b) fréquences | Lexique 4.10 (sous-titres, CC BY-SA) | **Aucune source de fréquences conversationnelles à licence clairement compatible.** Repli retenu : fréquences calculées par `tools/` sur le corpus Tatoeba anglais (CC BY), registre court et conversationnel mais biaisé vers des phrases d'apprentissage. Complément possible : Google Books Ngram (CC BY 3.0), registre livresque et volumétrie très lourde, non téléchargé pour l'instant. |
| (c) phrases | Tatoeba fra (CC BY 2.0 FR) | Tatoeba eng (CC BY 2.0 FR) |

### Remarques

- **Lexique 3 vs Lexique 4.** La page officielle ne propose plus que Lexique 4.10 (et 3.82 en archive, sans licence indiquée sur la page). Lexique 4.10 contient les mêmes informations que Lexique 3 (formes, lemmes, fréquences de sous-titres) et déclare CC BY-SA 4.0 ; c'est lui qui est retenu.
- **Lexique 4.10 pèse 49 Mo** : seul `Lexique4.1.tsv` (34 Mo) est utile. Le fichier brut n'est jamais versionné ; seul le lexique binaire dérivé l'est dans l'APK (≤ 8 Mo par langue).
- **SCOWL.** Le fichier Hunspell est une liste de mots de base et de drapeaux d'affixes ; il n'est pas développé : `buildWordLists` utilise à la place les listes déjà fléchies de SCOWL 2020.12.07 (ci-dessus). Le README de SCOWL précise que des données COCA (3-grammes, non librement distribuées) ont servi à classer les mots, « within the rights given by the NDA » ; la liste distribuée est elle-même sous licence permissive. Point à relire avant publication F-Droid.
- **Tatoeba.** Le fichier est une exportation hebdomadaire écrasée à chaque semaine : son SHA-256 devient obsolète au prochain export. Voir « Points d'attention » plus bas.
- **Dicollecte.** La page de téléchargement de grammalecte.net ne mentionne pas de licence pour les dictionnaires : elle est indiquée dans `README_dict_fr.txt` de l'archive. Le programme Grammalecte lui-même est sous GPL 3 et n'est **pas** utilisé.

## Construction des listes de mots

`./gradlew :tools:buildWordLists` (dépend de `fetchData` ; code dans `nextglyph.tools.wordlists`, testé) produit, sous `tools/build/` (ignoré par git, jamais distribué) :

- `wordlists/<lang>.tsv` : `forme d'affichage<TAB>fréquence entière > 0`, UTF-8, fins de ligne `\n`, trié par fréquence décroissante puis par forme (ordre des unités UTF-16) ;
- `wordlists/fr-lexique.tsv` et `wordlists/fr-dicollecte.tsv` : même format, une liste par source et par licence (voir « Français » ci-dessous), d'où sont construits les fichiers distribués ; `fr.tsv` (les deux sources mêlées) ne sert plus qu'aux analyses et au prototype et n'est pas distribué ;
- `wordlists/<lang>-stats.txt` : entrées, clés distinctes, exclusions par motif, compteurs, couverture ;
- `corpus/<lang>-train.txt`, `corpus/<lang>-test.txt` (une phrase par ligne), `corpus/mixed-test.txt` et `corpus/mixed-test-languages.txt` (`fr` ou `en` pour chaque ligne du corpus mixte, nécessaire au §15.1 pour mesurer la convergence du score de langue).

Les sorties sont déterministes (mêmes entrées, mêmes fichiers).

### Filtre commun

La clé d'une forme est `TextNormalizer.normalize(forme)` (module `engine`). Une forme est **exclue** si `normalize` la refuse (vide, > 64 caractères, espace, seulement des diacritiques), si sa clé contient un caractère hors `a-z ' -`, ou si sa clé n'a aucune lettre (`-`, `'`). Chaque exclusion est comptée par motif dans le fichier de statistiques. Les apostrophes typographiques sont ramenées à `'` avant tout. La casse d'affichage de la source est conservée (`Tom`, `I`, `Monday`, `TV`) ; plusieurs formes d'affichage peuvent partager une clé (`paris`, `Paris`) : une ligne par forme.

### Français

- Vocabulaire : formes du lexique Dicollecte 7.7 (toutes variantes orthographiques, 1990 comprise), plus les formes de Lexique 4.10 que Dicollecte ne contient pas (à la casse près). L'élision `l'`, `qu'`... vient de Lexique.
- Fréquence : colonne `11_FreqOrtho` de Lexique 4.10 (fréquence par million de mots, sous-titres de films ; répétée sur chaque ligne d'une forme, on garde le maximum). **Fréquence entière = max(1, arrondi(fréquence par million × 100))**, soit une résolution de 0,01 par million ; plafonnée à 2³¹−1. Un mot absent de Lexique (ou de fréquence nulle) reçoit la **fréquence plancher 1**. Lexique ne distingue pas la casse : si un groupe contient une variante tout en minuscules (`paris`), elle porte la fréquence et les autres (`Paris`) reçoivent le plancher ; sans variante minuscule, toutes la portent. Limite connue : les noms propres homographes d'un nom commun sont donc au plancher.
- Les exclusions sont comptées sur les formes de Dicollecte et de Lexique ; les formes en double après normalisation des apostrophes sont fusionnées.
- **Listes séparées par licence** (`FrenchWordList.split`) : `fr-lexique.tsv` = toutes les orthographes de Lexique (minuscules) avec leur fréquence entière ; `fr-dicollecte.tsv` = toutes les formes de Dicollecte au plancher 1. Le moteur reconstitue à la lecture la règle de casse ci-dessus (membre « sans casse » de `LexiconSet`).

### Anglais

- Vocabulaire : listes SCOWL 2020.12.07 de niveau ≤ 70 (`MAX_LEVEL`) : mots (`english-words`, `american-words`, orthographe américaine), majuscules (`-upper`), noms propres (`-proper-names`), contractions (`-contractions`) et abréviations (`-abbreviations`, presque toutes exclues car elles contiennent un point). Une forme présente à plusieurs niveaux garde le plus courant. Encodage de l'archive : ISO-8859-1.
- Fréquence : **fréquence = occurrences dans la partie entraînement de Tatoeba anglais + a priori(niveau)**, avec a priori(niveau) = `floor(1000 × 0,5^((niveau − 10) / 10))`, minimum 1 : 10 → 1000, 20 → 500, 35 → 176, 40 → 125, 50 → 62, 55 → 44, 60 → 31, 70 → 15. Un mot sans occurrence reçoit donc exactement le plancher de son niveau. Les occurrences d'un mot en début de phrase compté capitalisé (`The`) sont attribuées à la forme d'affichage du lexique (`the`) si le lexique n'a pas la forme capitalisée.
- Possessifs : une forme en `'s` hors contractions (`ability's`), jamais vue dans le corpus d'entraînement, est exclue (motif compté ; 32 922 formes). Les contractions (`don't`, `it's`) restent.
- Biais à connaître : Tatoeba contient beaucoup de phrases d'apprentissage avec `Tom` et `Mary` (`Tom` est le 3e mot le plus fréquent).

### Corpus et partition

- Source : export Tatoeba de chaque langue (`id<TAB>langue<TAB>texte`) ; lignes mal formées et autres langues ignorées et comptées.
- Doublons : les phrases de texte identique (espaces normalisés) sont dédoublonnées en gardant le plus petit identifiant, **avant** la partition, pour qu'une phrase répétée ne fuie pas du test vers l'entraînement.
- Partition : phrase de test si `floorMod(SplitMix64(id), 100) < 10` (10 % de test), indépendante de l'ordre du fichier et des autres phrases ; une phrase est dans un seul des deux fichiers. Les phrases sortent triées par identifiant.
- Corpus mixte : blocs alternés de phrases de test, français d'abord ; taille de bloc = 1 + `floorMod(SplitMix64(20261119 + n° du bloc), 8)`, soit 1 à 8 phrases ; s'arrête quand la langue dont c'est le tour est épuisée (le français, moins nombreux).

### Tokenisation (comptage anglais, couverture)

- Un mot est une suite de lettres ; `'` et `-` y sont admis entre deux lettres. Une suite contenant un chiffre est ignorée. La casse est conservée.
- Anglais : `don't`, `Tom's`, `rock'n'roll`, `well-known` sont un seul mot.
- Français : **`l'école` est deux mots, `l'` et `école`** (convention du §5.8 : l'élision est validée seule, le mot suivant commence sans espace ; le lexique contient `l'`, `d'`, `j'`, `qu'`...). Préfixes d'élision séparés : `c d j l m n s t qu jusqu lorsqu puisqu quoiqu`. `aujourd'hui`, `quelqu'un`, `prud'homme` restent entiers ; `peut-être`, `c'est-à-dire` (`c'` + `est-à-dire`) gardent leurs traits d'union.
- Couverture : parmi les 1 000 clés les plus fréquentes du corpus de test (égalités départagées par ordre alphabétique), part présente dans la liste ; et part des occurrences couverte. Les mots du test que le filtre exclut (hors alphabet) sont comptés à part, hors dénominateur.

## Sources examinées et écartées

| Source | URL officielle | Licence constatée | Motif d'exclusion |
|---|---|---|---|
| FrequencyWords (Hermit Dave), 2016 et 2018, fr et en | https://github.com/hermitdave/FrequencyWords | Code : MIT. Données : « CC-by-sa-4.0 for content » (README) | Licence ambiguë : les listes sont dérivées d'OpenSubtitles (OPUS), dont le README ne donne aucune déclaration de droits sur les sous-titres d'origine. Le CC BY-SA affirmé par l'auteur ne repose sur aucune autorisation vérifiable des ayants droit amont. Réexaminer si l'auteur clarifie. |
| wordfreq (Robyn Speer) | https://github.com/rspeer/wordfreq | Code : Apache. Données : CC BY-SA 4.0 | Données agrégées de sources dont les conditions ne sont pas données (OSCAR, Reddit, NewsCrawl, GlobalVoices, ParaCrawl, Twitter, OpenSubtitles) ; SUBTLEX sous autorisation par courriel. Licence composite non vérifiable. Projet en fin de maintenance (instantané vers 2021). |
| Google Books Ngram (v20120701 et v20200217) | https://storage.googleapis.com/books/ngrams/books/datasetsv3.html | CC BY 3.0 (licence compatible) | Non retenu **pour l'instant**, pour des raisons pratiques et non de licence : registre écrit, volumétrie de plusieurs dizaines de Go. À reconsidérer pour la langue écrite. |
| Corpus Leipzig (Wortschatz) | https://wortschatz.uni-leipzig.de/ | Non vérifiée | La page officielle est protégée par un contrôle anti-robot (Anubis) : la licence n'a pas pu être lue à la source, et ce contrôle n'est pas contourné. Exclue faute de vérification ; à lire manuellement. |
| SUBTLEX (US, UK) | page officielle non consultée | Autorisation de réutilisation par courriel, sans licence standard (d après le README de wordfreq) | Licence non standard et non vérifiée à la source. |
| Grammalecte (programme) | https://grammalecte.net/ | GPL 3 | GPL : exclue (seuls les dictionnaires MPL-2.0 sont retenus). |
| Hunspell en_US de LibreOffice, aspell6-en | https://wordlist.aspell.net/dicts/ | Dérivés de SCOWL | Redondants avec la source SCOWL retenue. |
| Dictionnaires AOSP / HeliBoard | https://github.com/Helium314/dictionaries | Non examinées | Le projet HeliBoard est GPL-3.0 ; licence des dictionnaires non vérifiée à la source. À examiner séparément (§10.2, ligne « comparaison, bigrammes »). |

## Texte d'attribution

À afficher dans l'écran « À propos » et à reprendre dans `NOTICE` :

> **Lexique 4.10** : New, B., Pallier, C., Schalchli, G., Bourgin, J., Gimenes, M. (2026), Lexique 4, Behavior Research Methods. https://www.lexique.org/ — licence CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/). Données modifiées : conversion en lexique binaire.
>
> **Dictionnaires orthographiques français (Dicollecte)** v7.7 : Olivier R. et contributeurs. https://grammalecte.net/ — Mozilla Public License 2.0 (https://www.mozilla.org/MPL/2.0/). Données modifiées : conversion en lexique binaire.
>
> **SCOWL / English Speller Database** : Copyright 2000-2026 Kevin Atkinson (listes développées : SCOWL 2020.12.07, copyright 2000-2018). https://wordlist.aspell.net/ — licence permissive (voir `NOTICE`).
>
> **Lexique des formes fléchies (Dicollecte)** v7.7 : même attribution et même licence MPL-2.0 que les dictionnaires orthographiques ci-dessus.
>
> **Tatoeba** (corpus non distribué ; les fréquences anglaises qui en sont dérivées figurent dans `en.nglx`, et les modèles de mot suivant français et anglais dérivés de ses phrases dans `fr.ngrm` et `en.ngrm`) : phrases des contributeurs de Tatoeba, https://tatoeba.org/ — licence CC BY 2.0 FR (https://creativecommons.org/licenses/by/2.0/fr/).

## Fichiers de données distribués

`./gradlew :tools:buildLexicons` produit, sous `tools/build/lexicons/` (ignoré par git), les lexiques binaires (`LexiconLanguage.ALL_FILES` : deux fichiers pour le français, un par licence, et un pour l'anglais) ainsi que `report.md` (clés, formes, taille, temps de chargement, latence de `distribution`, comparaison au budget de 8 Mio du §11.3). Chaque `.nglx` est une adaptation des données ci-dessous : conversion des listes de mots (`buildWordLists`) en arbre radix binaire (`LexiconBuilder`, formes hors alphabet ignorées et comptées dans le rapport). Ces fichiers sont livrés **à côté** du code, comme fichiers de données distincts ; ils ne sont pas versionnés dans git (ils se régénèrent) et ne portent pas la licence Apache-2.0 du code.

| Fichier | Sources | Licence | Attribution à afficher dans l'application |
|---|---|---|---|
| `fr-lexique.nglx` | Lexique 4.10 uniquement : ses orthographes (en minuscules, Lexique ne distinguant pas la casse) et leur fréquence (`11_FreqOrtho` × 100) ; liste `fr-lexique.tsv` | **CC BY-SA 4.0** | L'attribution « Lexique 4.10 », avec mention « données modifiées : formes en minuscules, fréquences entières, conversion en lexique binaire » et le lien vers la licence |
| `fr-dicollecte.nglx` | Dicollecte v7.7, lexique des formes fléchies, uniquement : ses formes (casse comprise), toutes à la fréquence plancher 1 ; liste `fr-dicollecte.tsv` | **MPL-2.0** | L'attribution « Lexique des formes fléchies (Dicollecte) », avec mention « données modifiées : conversion en lexique binaire », le texte de la licence et le lien vers la source |
| `en.nglx` | SCOWL 2020.12.07, niveaux ≤ 70 (vocabulaire, niveaux utilisés comme a priori) ; fréquences comptées sur la partie entraînement du corpus Tatoeba anglais | Licence permissive de type MIT pour SCOWL ; **CC BY 2.0 FR** pour les fréquences dérivées de Tatoeba | L'attribution « SCOWL » (avec l'avis de copyright, repris dans `NOTICE`) et l'attribution « Tatoeba » |
| `fr.ngrm` | Phrases du corpus Tatoeba français (partie entraînement) : bigrammes et trigrammes de mots, élagués (30 000 mots, comptes minimaux 2 / 2, 8 suivants par bigramme, 4 par trigramme), sans phrase entière | **CC BY 2.0 FR** (données dérivées) | L'attribution « Tatoeba » |
| `en.ngrm` | Phrases du corpus Tatoeba anglais (partie entraînement), même construction | **CC BY 2.0 FR** (données dérivées) | L'attribution « Tatoeba » |

Réserves à lever avant la première publication :

- **Français : un fichier par licence** (levée de la réserve sur l'ancien `fr.nglx`, qui mélangeait CC BY-SA 4.0 et MPL-2.0, deux licences incompatibles au sens des licences elles-mêmes). Aucun des deux fichiers ne contient de données de l'autre source : `fr-lexique.nglx` ne reprend ni les formes ni la casse de Dicollecte, `fr-dicollecte.nglx` ne reprend aucune fréquence de Lexique (plancher uniforme) et contient toutes les formes de Dicollecte, présentes ou non dans Lexique. Le moteur les fusionne à la lecture (`LexiconSet`, Lexique déclaré « sans casse » : la fréquence d'une orthographe va à la forme minuscule de Dicollecte si elle existe, sinon à ses variantes de casse), ce qui redonne la liste unique d'avant, à un plancher près pour les formes présentes dans les deux sources. Vérification : `:tools:analyzeEngine` (fichiers séparés) et `-PanalysisCombined` (liste unique `fr.tsv`, non distribuée) donnent les mêmes résultats (`docs/analysis/M2-engine.md`). Taille : 2,03 + 6,07 Mo (7,73 Mio) contre 5,97 Mio, dans le budget de 8 Mio par langue.
- **Les données françaises de Tatoeba sont désormais distribuées sous forme dérivée** (issue #51) : `fr.ngrm` contient des statistiques de suites de mots (pas de phrases) calculées sur le corpus français, comme `en.ngrm` pour l'anglais ; le corpus lui-même reste non distribué. Les fichiers `.ngrm` sont produits par `:tools:buildLexicons` (rapport : `ngram-report.md` ; mesures : `analysis/M51-ngrams.md`), ne sont pas versionnés dans git et suivent le même chemin que les `.nglx` (artefact `lexiconElements`, assets de l'APK). L'attribution Tatoeba (CC BY 2.0 FR) figure dans `NOTICE`.
- **`en.nglx` contient des comptes dérivés de Tatoeba**, alors que le corpus lui-même n'est pas distribué (voir plus bas) : l'attribution Tatoeba doit donc figurer dans l'application, pas seulement dans `NOTICE`.
- **SCOWL** : voir la remarque sur la mention COCA (relecture juridique).
- **Prototype web** : `prototype/data/fr.json` et `en.json` (générés par `:tools:exportPrototypeData`) sont un sous-ensemble des mêmes données ; ils portent les mêmes licences, ne sont pas versionnés et ne sont pas distribués. Le prototype n'est pas livré aux utilisateurs.

## Émojis

L'asset `ime/src/main/assets/emoji.txt` (environ 42 Ko, versionné) liste les émojis du panneau (§5.11) : une ligne `groupe<TAB>émoji<TAB>version`, dans l'ordre Unicode, sans le groupe « composants ».

| Nom, version | URL | SHA-256 | Taille | Licence |
|---|---|---|---|---|
| `unicode-emoji-json` 0.9.0 (npm), fichier `data-by-group.json` | https://registry.npmjs.org/unicode-emoji-json/-/unicode-emoji-json-0.9.0.tgz | `ae7efacb55533385bf8767d613dbd36c71226669759d95919a6e9059f5e4c401` | 71 855 o | MIT (Mu-An Chiou) ; données d'origine Unicode, sous la Unicode License v3 |

Régénération : extraire l'archive puis `./gradlew :tools:buildEmoji -PemojiJson=<chemin>/package/data-by-group.json` (logique dans `nextglyph.tools.emoji`, testée). Attribution reprise dans `NOTICE`.

## Points d'attention pour F-Droid

- **Reproductibilité des sommes de contrôle.** Les exports Tatoeba sont écrasés chaque semaine : après quelques jours, `fetchData` échoue sur l'empreinte (comportement voulu). La recette F-Droid ne doit pas en dépendre. Solution retenue : chaque source de `sources.json` a un champ `mirrors` vers une copie figée, pièce jointe de la release `donnees-2026-11-19` du dépôt (publiée le 2026-11-20 par le workflow « Instantané des données », licences compatibles avec la redistribution) ; `fetchData` essaie les miroirs avant l'URL officielle et vérifie l'empreinte dans tous les cas. Toutes les sources sont figées, pas seulement Tatoeba : le zip Lexique a été modifié en place le 17 novembre 2026. Procédure : [PUBLISHING.md](PUBLISHING.md), §1.
- **Le corpus Tatoeba n'est pas distribué** dans l'APK : il ne sert qu'à l'analyse du §15.1, à calculer des fréquences et à construire les modèles de mot suivant (`.ngrm`, données dérivées distribuées). L'attribution est quand même conservée.
- **CC BY-SA (Lexique)** : le lexique binaire dérivé est une adaptation ; il doit être livré comme fichier de données distinct, sous CC BY-SA 4.0, avec attribution et mention des modifications. **MPL-2.0 (Dicollecte)** : même principe, avec le texte de la licence fourni et les fichiers sources MPL disponibles. Ne pas fusionner ces données dans un fichier unique sous Apache-2.0.
- **SCOWL** : l'avis de copyright doit figurer dans toute copie ; la mention COCA mérite une relecture juridique.
- Aucune permission réseau dans l'APK : le téléchargement n'a lieu que dans le build (`tools/`), jamais sur l'appareil.
- Les fichiers bruts (zip, bz2) restent dans `tools/build/data/`, ignoré par git ; aucun fichier de données brut de plus de 1 Mo n'est versionné.
