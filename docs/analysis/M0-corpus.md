# M0 — Analyse de corpus (spec §15.1)

Ce document rend compte de l'analyse hors ligne prévue au §15.1 de la spécification et des valeurs par défaut qu'elle a permis de fixer (§5.2, §5.5, §10.6). Les résultats bruts complets (toute la grille de seuils, toutes les variantes) sont produits par la commande ci-dessous dans `tools/build/analysis/M0-corpus-results.md` ; les tableaux de ce document en sont extraits.

```sh
./gradlew :tools:buildWordLists     # listes de mots et corpus (si absents)
./gradlew :tools:analyzeCorpus      # analyse complète (-PanalysisSample=N pour un échantillon de N %)
```

Code : `tools/src/main/kotlin/nextglyph/tools/analysis/` (testé, PIT). Analyse déterministe : mêmes entrées, mêmes résultats (fautes tirées d'un générateur à graine fixe, parcours des mots dans un ordre fixe).

## 1. Données et méthode

### Entrées

- **Lexiques** : `tools/build/wordlists/<lang>.tsv` (sortie de `buildWordLists`, voir `docs/DATA.md`), chargés en mémoire avec `LexiconBuilder` (politique `SKIP`), c'est-à-dire exactement la structure et les masses du lexique binaire du moteur.
- **Corpus** : uniquement les parties **test** de Tatoeba (`fr-test.txt`, `en-test.txt`, `mixed-test.txt` et ses étiquettes). Les parties d'entraînement ne sont jamais lues : les fréquences anglaises ayant été calculées sur l'entraînement, les lire fausserait la mesure.
- **Tokenisation** : celle de `buildWordLists` (`Tokenizer`) ; en français, l'élision est un mot à part (`l'`, `école`), conformément au §5.8. Chaque mot est ramené à sa clé (`TextNormalizer`) ; les mots dont la clé sort de l'alphabet du lexique sont écartés et comptés.
- Toutes les mesures sont **pondérées par les occurrences** du corpus de test.

### Partie 1 — ce que le clavier peut mettre en avant

Pour chaque mot du test et chaque position `i` (1 à 8, les positions suivantes regroupées en « 8+ »), on lit `distribution(p)` du lexique, où `p` est formé des `i − 1` premières lettres du mot, puis on relève :

- le nombre de continuations (lettres de probabilité non nulle, `'` et `-` compris) ;
- l'entropie de `P(· | p)` en bits, **la fin de mot comptée comme une issue** ;
- la probabilité `P(c | p)` de la lettre réellement tapée, son poids relatif `r(c)` (§3), son rang parmi les continuations (1 = la plus probable, ex aequo au meilleur rang), la part des cas où elle est dans le top 3 / top 5 ;
- la part des cas où cette lettre est **fortement atténuée** (`r < 0,1`, soit une opacité ≤ 0,69 avec les formules du §5.2) ;
- la part des « préfixes inconnus » (aucun mot du lexique ne commence par `p` : le mot tapé est hors lexique et a déjà divergé). Ces cas sont exclus des autres moyennes. Une lettre qui n'est pas une continuation d'un préfixe connu compte avec une probabilité nulle et hors du top 3 / 5.

### Partie 2 — validation par espace (§5.5)

**Décision du moteur.** Pour un texte tapé `t` (clé normalisée) :

1. si `t` est une clé du lexique → insertion telle quelle (cas 1) ;
2. sinon, on calcule les candidats et `P(w)` ; si le meilleur `w` vérifie `P(w) ≥ minProb` et `P(w) ≥ minRatio · P(2e)` (et, pour la variante testée, `|t| ≥ minLength`), il est inséré (cas 2) ;
3. sinon `t` est inséré tel quel (cas 3).

**Candidats et correction (§10.4), version simple et documentée** (`CandidateSource`) :

- *Complétion* : les mots dont la clé commence par `t`, de score `fréquence(w)` ; leur masse totale est celle du nœud `t` du lexique.
- *Correction, distance 1 sur le préfixe* : pour chaque préfixe connu `e` obtenu par **une** édition de `t`, les complétions de `e`, de score `poids(édition) × fréquence(w)`. Poids : substitution par une **touche voisine** 0,01 ; autre substitution 0,0005 ; caractère omis 0,002 ; caractère en trop 0,002 ; inversion de deux caractères 0,002 ; apostrophe ou trait d'union omis 0,5 (`jai` → `j'ai`). Seules les positions dont le début `t[0, i)` est un préfixe connu sont explorées.
- `P(w) = score(w) / Σ` (masses pondérées de tous les préfixes candidats). Un mot atteint par deux éditions garde son meilleur score mais compte deux fois au dénominateur (approximation prudente). Le score d'une clé additionne ses formes (`ou`/`où`) parmi les 6 formes les plus fréquentes de chaque préfixe.
- Deux variantes : correction **seulement si `t` est un préfixe inconnu** (lecture littérale du §5.2), ou correction **aussi si `t` est un préfixe connu qui n'est pas un mot** (une faute forme souvent un autre préfixe valide : `ke` pour `le`).
- Non mesuré : distance 2 sur le mot complet (§10.4), élisions et contractions comme candidats spéciaux, restitution des accents (on compare des clés), modèle de bigrammes.

**Adjacence** (`KeyboardLayout`) : centres des 26 lettres de la disposition compacte au repos du §5.1 (AZERTY : rangées 1 et 2 alignées, rangée 3 décalée de 1 u et lettres de 0,9 u ; QWERTY : rangée 2 décalée de 0,3 u à 0,95 u, rangée 3 à 0,9 u ; une rangée = 1 u de haut). Deux lettres sont voisines si leurs centres sont à 1,5 u au plus (1 à 6 voisines par lettre ; en QWERTY, `p` n'a que `o`).

**Fautes simulées** (`TypoModel`) : chaque lettre est remplacée, avec une probabilité de 2 % ou 5 %, par une voisine tirée uniformément (AZERTY pour le français, QWERTY pour l'anglais) ; `'` et `-` ne sont jamais modifiés. Ni omission, ni insertion, ni inversion : le modèle de fautes est **plus favorable** à la correction que la réalité (il ne produit que des fautes que la correction sait traiter).

**Deux utilisateurs simulés** :

- *Utilisateur attentif* (mesure principale) : il tape le mot lettre par lettre (fautes comprises) et appuie sur espace **dès que la touche espace affiche le mot voulu** (§5.5, « Prévisibilité »), sinon après la dernière lettre. Il ne corrige pas ses fautes. C'est le comportement que la spec cherche à obtenir.
- *Espace après k lettres, pour tout k* : chaque préfixe `k = 1 … longueur` du mot est validé par espace, comme le demande le §15.1. C'est le pire cas d'un utilisateur qui ne regarde pas la touche espace ; il montre surtout ce que coûterait un appui « à l'aveugle ».

**Mesures** :

- *Complétées/corrigées* : part des mots validés automatiquement **et correctement** (complétion, ou correction d'une faute).
- *Fausses (% auto)* : part des validations automatiques dont le mot inséré n'est pas le mot voulu. C'est l'approximation retenue du critère du §15.2 (< 3 % de validations annulées) : on suppose qu'un utilisateur annule toute fausse validation et seulement celles-là.
- *KSPC* : frappes (lettres + espace) divisées par le nombre de caractères du mot plus l'espace ; 1 = aucun gain. La variante « avec réparation » ajoute 2 frappes (⌫ d'annulation, espace) par fausse validation.
- *Fautes non corrigées* : mots insérés tels quels avec une faute.

### Partie 3 — bilinguisme (§10.6)

**Score de langue** (`LanguageScore`) : fenêtre glissante des `N` derniers mots validés (ici, les mots voulus : on suppose les validations correctes), `π_FR = (Σ a_FR + π₀) / (Σ a_FR + Σ a_EN + 1)`, avec `π₀ = 0,75` (français préféré au départ). Deux définitions de l'indice `a` d'un mot :

- **appartenance** (règle actuelle du §10.6) : `(1, 0)`, `(0, 1)`, `(1, 1)` pour un mot des deux lexiques, `(0, 0)` pour un mot inconnu ;
- **fréquence relative** : `(f_FR, f_EN) / (f_FR + f_EN)`, où `f_L` est la fréquence du mot divisée par la masse totale du lexique `L` (un mot commun compte selon sa fréquence dans chaque langue).

**Mesures** : sur le corpus mixte étiqueté (blocs alternés de 1 à 8 phrases de test), part des mots dont la langue dominante *avant la frappe* est celle de la phrase ; à chaque changement de langue, nombre de mots tapés avant que la langue dominante bascule (« délai » ; un changement jamais rattrapé avant le suivant est compté avec la longueur du bloc et signalé « non rattrapé »).

**Fausses validations dues à la langue** : l'utilisateur attentif tape le corpus mixte en AZERTY (francophone qui écrit aussi en anglais, §5.1) avec les deux lexiques actifs, la correction des préfixes connus (variante retenue en partie 2) et le mélange `P(w) = Σ π_L · score_L(w) / Σ π_L · total_L` (scores normalisés par la masse de chaque lexique) ; on compare à une référence où la langue de la phrase est connue et seule active. Une fausse validation est **attribuable à la langue** si elle n'a pas lieu dans la référence.

### Exécution

Analyse complète, sans échantillonnage : 563 059 mots de test français, 1 568 759 anglais, 1 101 507 mots du corpus mixte (32 501 changements de langue). Durée mesurée sur le poste de développement (Windows 11, JDK 25) : **651 s** pour l'analyse (11 min 13 s pour la tâche Gradle complète). Le coût vient de la correction (recherche des éditions dans le lexique) ; les candidats sont mis en cache par texte tapé. `-PanalysisSample=5` (5 % des phrases monolingues, échantillon déterministe par hachage du rang de la phrase) ramène les parties 1 et 2 à environ 40 s ; la partie 3 porte toujours sur tout le corpus mixte.

## 2. Résultats — partie 1 : lettre suivante

Français (563 021 mots analysés ; 38 hors alphabet) :

| Position | Préfixe inconnu | Continuations | Entropie (bits) | P(lettre tapée) | r(lettre tapée) | Rang | Top 3 | Top 5 | Fortement atténuée |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0,00 % | 26,0 | 4,18 | 0,063 | 0,667 | 7,54 | 28,3 % | 43,5 % | 2,3 % |
| 2 | 0,00 % | 23,3 | 2,19 | 0,303 | 0,706 | 2,60 | 76,9 % | 91,9 % | 6,8 % |
| 3 | 0,01 % | 18,9 | 1,91 | 0,331 | 0,675 | 2,47 | 79,7 % | 90,6 % | 12,0 % |
| 4 | 0,31 % | 10,6 | 1,33 | 0,515 | 0,770 | 1,79 | 89,6 % | 95,7 % | 8,0 % |
| 5 | 1,17 % | 5,3 | 0,78 | 0,674 | 0,861 | 1,32 | 95,9 % | 98,1 % | 5,8 % |
| 6 | 2,54 % | 3,7 | 0,58 | 0,750 | 0,902 | 1,23 | 97,3 % | 98,7 % | 3,9 % |
| 7 | 3,72 % | 3,0 | 0,55 | 0,742 | 0,899 | 1,22 | 96,9 % | 98,4 % | 4,0 % |
| 8+ | 9,69 % | 2,3 | 0,47 | 0,767 | 0,919 | 1,15 | 97,4 % | 98,4 % | 3,6 % |

Anglais (1 568 717 mots analysés ; 42 hors alphabet) :

| Position | Préfixe inconnu | Continuations | Entropie (bits) | P(lettre tapée) | r(lettre tapée) | Rang | Top 3 | Top 5 | Fortement atténuée |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0,00 % | 26,0 | 4,28 | 0,063 | 0,559 | 7,73 | 33,9 % | 46,2 % | 2,6 % |
| 2 | 0,00 % | 23,5 | 2,57 | 0,245 | 0,709 | 2,66 | 76,2 % | 90,8 % | 2,7 % |
| 3 | 0,01 % | 17,9 | 2,57 | 0,282 | 0,709 | 2,51 | 79,4 % | 89,3 % | 4,4 % |
| 4 | 0,09 % | 10,0 | 1,55 | 0,520 | 0,841 | 1,65 | 91,2 % | 95,7 % | 4,1 % |
| 5 | 1,12 % | 4,4 | 0,96 | 0,674 | 0,911 | 1,28 | 96,8 % | 98,6 % | 1,6 % |
| 6 | 1,78 % | 2,8 | 0,63 | 0,779 | 0,945 | 1,17 | 98,3 % | 99,2 % | 1,0 % |
| 7 | 2,27 % | 2,0 | 0,51 | 0,815 | 0,956 | 1,11 | 98,9 % | 99,3 % | 0,9 % |
| 8+ | 6,58 % | 1,7 | 0,46 | 0,819 | 0,965 | 1,09 | 99,4 % | 99,6 % | 0,6 % |

`P(fin | mot complet)` moyen : 0,78 (FR), 0,69 (EN).

**Lecture.**

- **Première lettre** : la distribution des initiales est presque plate (4,2 à 4,3 bits, soit l'équivalent de ~19 lettres équiprobables) ; la lettre tapée n'est dans le top 3 qu'une fois sur trois. Mettre en avant les initiales apporte peu d'information et risque d'atténuer la lettre voulue : l'**atténuation de moitié en début de mot (§5.2) est justifiée** et conservée. Aucune donnée ne justifie de la renforcer ou de la supprimer.
- **Positions 2 et 3** : le gain devient net (entropie ~2 à 2,6 bits, lettre tapée dans le top 3 dans ~77-80 % des cas), mais c'est aussi là que la lettre tapée est le plus souvent fortement atténuée en français (7 à 12 % des cas). Avec les formules du §5.2, une lettre à `r < 0,1` garde une opacité ≥ 0,55 et reste tapable (principe 4), mais c'est le premier point à observer dans le prototype ; pas de changement de formule proposé sans test utilisateur.
- **À partir de la position 4-5**, l'adaptation est très informative (rang moyen < 1,8, top 3 > 89 %, moins d'une continuation sur deux à l'atténuation forte).
- **Préfixes inconnus** : rares avant 5 lettres (< 1,2 %), jusqu'à 10 % (FR) et 7 % (EN) après 8 lettres (mots hors lexique : noms propres, mots composés rares). L'état neutre de repli du §5.2 concerne donc surtout les mots longs.

## 3. Résultats — partie 2 : validation par espace

Tableaux pour `minRatio = 2,0` et `minLength = 1` (règle actuelle sans longueur minimale). Les autres valeurs de `minRatio` (1,5 à 10) changent peu les résultats : dès que `minProb ≥ 0,67`, la condition `P(w) ≥ 2 · P(2e)` est automatiquement remplie (`P(2e) ≤ 1 − P(w)`), et `minRatio` n'agit plus. La grille complète est dans les résultats bruts.

### 3.1 Correction des préfixes inconnus seulement (lecture littérale du §5.2)

| Langue, fautes | minProb | Complétées/corrigées (% mots) | Fausses (% des validations auto) | KSPC |
|---|---|---|---|---|
| FR, 0 % | 0,6 / 0,7 / 0,8 | 19,9 / 18,3 / 15,4 % | 0,66 / 0,59 / 0,51 % | 0,932 / 0,940 / 0,950 |
| FR, 2 % | 0,6 / 0,7 / 0,8 | 21,6 / 19,8 / 16,7 % | 2,65 / 2,32 / 2,27 % | 0,933 / 0,941 / 0,951 |
| FR, 5 % | 0,6 / 0,7 / 0,8 | 23,2 / 21,2 / 17,8 % | **5,43 / 4,83 / 4,76 %** | 0,936 / 0,944 / 0,954 |
| EN, 0 % | 0,6 / 0,7 / 0,8 | 24,5 / 21,3 / 18,6 % | 1,05 / 1,15 / 1,05 % | 0,926 / 0,937 / 0,947 |
| EN, 2 % | 0,6 / 0,7 / 0,8 | 25,6 / 22,1 / 19,2 % | 2,00 / 2,16 / 2,14 % | 0,929 / 0,939 / 0,949 |
| EN, 5 % | 0,6 / 0,7 / 0,8 | 26,5 / 22,8 / 19,7 % | **3,48 / 3,74 / 3,84 %** | 0,932 / 0,942 / 0,952 |

À 5 % de fautes, **aucun réglage de la grille ne passe sous 3 % en français** dans cette variante (minimum de toute la grille : 3,26 % avec `minProb = 0,8`, `minRatio = 5`, `minLength = 3`) ; en anglais, seule une longueur minimale de 3 lettres y parvient (minimum 2,71 %). Relever `minProb` n'aide guère : les fausses validations sont presque toutes **des fautes de frappe qui forment un autre préfixe valide** (`ke` pour `le`, `vien` pour `bien`). Le texte tapé étant un préfixe connu, il n'est pas corrigé, et sa complétion la plus fréquente « domine » avec une confiance élevée. Les mots courts sont les plus touchés (FR, 5 % de fautes, `minProb = 0,6` : 1,3 à 1,6 % des mots de 2 à 4 lettres donnent une fausse validation, contre 0,7 % des mots de 8 lettres et plus).

### 3.2 Correction aussi des préfixes connus qui ne sont pas des mots

| Langue, fautes | minProb | Complétées/corrigées (% mots) | dont corrections | Fausses (% des validations auto) | KSPC | Fautes non corrigées (% mots) |
|---|---|---|---|---|---|---|
| FR, 0 % | 0,6 / **0,7** / 0,8 | 19,3 / **17,3** / 14,7 % | — | 0,73 / **0,67** / 0,57 % | 0,936 / **0,944** / 0,953 | — |
| FR, 2 % | 0,6 / **0,7** / 0,8 | 21,2 / **19,0** / 16,0 % | 3,7 / **3,3** / 2,8 % | 1,41 / **1,17** / 0,98 % | 0,937 / **0,945** / 0,954 | 3,6 / **4,1** / 4,8 % |
| FR, 5 % | 0,6 / **0,7** / 0,8 | 23,0 / **20,6** / 17,4 % | 7,9 / **7,1** / 5,9 % | 2,59 / **2,10** / 1,76 % | 0,939 / **0,947** / 0,956 | 9,1 / **10,2** / 11,7 % |
| EN, 0 % | 0,6 / **0,7** / 0,8 | 24,2 / **20,9** / 17,1 % | — | 1,01 / **1,11** / 1,03 % | 0,928 / **0,939** / 0,951 | — |
| EN, 2 % | 0,6 / **0,7** / 0,8 | 25,4 / **21,8** / 17,8 % | 3,2 / **2,6** / 2,2 % | 1,15 / **1,19** / 1,09 % | 0,930 / **0,941** / 0,953 | 4,0 / **4,7** / 5,2 % |
| EN, 5 % | 0,6 / **0,7** / 0,8 | 26,4 / **22,5** / 18,5 % | 6,9 / **5,7** / 4,8 % | 1,61 / **1,62** / 1,47 % | 0,934 / **0,944** / 0,955 | 10,0 / **11,5** / 12,7 % |

Corriger aussi les préfixes connus **divise par deux à trois les fausses validations dues aux fautes** (FR 5 % : 4,83 → 2,10 % à `minProb = 0,7`) pour un coût faible sur les complétions sans faute (FR : 18,3 → 17,3 % des mots ; KSPC 0,940 → 0,944). Les mots courts cessent d'être un problème (FR, 5 %, `minProb = 0,7` : 0 % des mots de 1-2 lettres, 0,19 % des mots de 3 lettres) et la longueur minimale `minLength` n'a alors **plus aucun effet mesurable** (résultats identiques à 0,01 point près pour 1, 2 et 3).

Sans faute de frappe, les fausses validations restantes (0,6 à 1,1 %) viennent des mots hors lexique tapés en entier (noms propres, formes rares) qu'un mot du lexique « complète » ou corrige.

### 3.3 Appui sur espace « à l'aveugle »

Si l'utilisateur appuie sur espace après `k` lettres sans regarder la touche espace, la part de fausses validations parmi les validations automatiques est de **12 à 30 % en français** et de 3 à 15 % en anglais selon le seuil, la variante et le taux de fautes (FR, `minProb = 0,7`, variante 3.2, sans faute : 0 % à k = 1, 2,4 % à k = 2, 18 à 23 % de k = 3 à 5). C'est inhérent au principe : un préfixe a souvent un candidat dominant qui n'est pas le mot voulu. La sécurité de la validation par espace repose donc entièrement sur la **prévisibilité** (§2.5, §5.5 : la touche espace affiche le mot qu'elle insérera) et sur l'annulation par ⌫ ; c'est à vérifier en priorité dans l'étude utilisateur (M5), et un argument pour garder le réglage « Prudence de la validation ».

### 3.4 Synthèse et recalibrage

| Paramètre | Avant | Proposé | Mesure qui le justifie |
|---|---|---|---|
| Correction à la validation | préfixe inconnu seulement (§5.2) | aussi pour un préfixe connu qui n'est pas un mot (§5.5, §10.4) | sans elle, aucun réglage < 3 % en français à 5 % de fautes (minimum 3,26 %) ; avec elle, FR 2,10 %, EN 1,62 % |
| `autocommit.minProb` | 0,60 | **0,70** | pire cas mesuré 2,10 % (FR, 5 % de fautes) contre 2,59 % à 0,60 : marge d'un tiers sous 3 % pour un modèle de fautes optimiste ; coût : −2 points de mots complétés, KSPC +0,008 |
| `autocommit.minRatio` | 2,0 | 2,0 (inchangé) | sans effet mesurable dès `minProb ≥ 0,67` ; conservé comme garde-fou et pour le réglage « Audacieuse » |
| Longueur minimale | — | aucune | sans effet une fois les préfixes connus corrigés |
| Prudence (§14) | non chiffrée | Prudente 0,80 ; Normale 0,70 ; Audacieuse 0,60 | toutes < 3 % mesurés (pires cas 1,76 / 2,10 / 2,59 %) |

**Objectif du §15.2 (< 3 % d'annulations)** : atteint en simulation dans les deux langues, sans faute et avec 2 % ou 5 % de fautes, **à condition de corriger aussi les préfixes connus**. Avec la règle actuelle de la spec (correction des seuls préfixes inconnus), il n'est **pas** atteint à 5 % de fautes, quel que soit le seuil.

## 4. Résultats — partie 3 : bilinguisme

Convergence du score de langue (corpus mixte, 1 101 507 mots, 32 501 changements de langue) :

| Indice | Fenêtre | Langue dominante correcte | Délai moyen (mots) | Médiane | p90 | Non rattrapés |
|---|---|---|---|---|---|---|
| appartenance | 2 | 87,0 % | 1,59 | 2 | 2 | 52 |
| appartenance | 3 | 90,4 % | 2,07 | 2 | 3 | 123 |
| appartenance | 4 | 91,1 % | 2,53 | 3 | 4 | 228 |
| appartenance | 6 | 89,4 % | 3,48 | 3 | 5 | 607 |
| appartenance | 8 (spec actuelle) | 86,9 % | 4,41 | 4 | 6 | 1 103 |
| appartenance | 12 | 81,9 % | 6,08 | 6 | 8 | 2 222 |
| fréquence | 2 | 94,1 % | 1,47 | 1 | 2 | 31 |
| fréquence | 3 | 93,7 % | 1,95 | 2 | 2 | 62 |
| **fréquence** | **4** | **92,6 %** | **2,48** | **2** | **3** | **179** |
| fréquence | 6 | 89,8 % | 3,45 | 3 | 4 | 509 |
| fréquence | 8 | 87,1 % | 4,38 | 4 | 5 | 1 050 |
| fréquence | 12 | 82,1 % | 6,04 | 6 | 7 | 2 156 |

Fausses validations sur le corpus mixte (utilisateur attentif, AZERTY, correction des préfixes connus, indice par fréquence ; fenêtres 3, 4, 6 et 8 à ±0,02 point près) :

| Fautes | minProb | Validations auto (% mots) | Fausses (% auto) | Référence, langue connue (% auto) | Attribuables à la langue (% des fausses) |
|---|---|---|---|---|---|
| 0 % | 0,6 | 18,3 % | 0,61 % | 0,56 % | 24 % |
| 0 % | **0,7** | **15,7 %** | **0,58 %** | **0,54 %** | **26 %** |
| 0 % | 0,8 | 12,7 % | 0,54 % | 0,51 % | 25 % |
| 2 % | 0,6 | 20,0 % | 1,07 % | 1,01 % | 19 % |
| 2 % | **0,7** | **17,2 %** | **0,90 %** | **0,84 %** | **22 %** |
| 2 % | 0,8 | 13,9 % | 0,80 % | 0,73 % | 23 % |

**Lecture.**

- La fenêtre de 8 mots est **trop lente** pour ce corpus : la langue dominante n'est correcte que pour 87 % des mots et il faut 4 à 5 mots pour suivre un changement. Plus la fenêtre est courte, meilleure est la précision, jusqu'à 2 mots.
- L'indice **par fréquence relative** est meilleur que l'appartenance à toutes les tailles (+1,5 point à 4 mots, 3 fois moins de changements non rattrapés), et il réduit un peu la part des fausses validations attribuables à la langue (26 → 22 % à 2 % de fautes). Avec l'appartenance, les mots communs (`a`, `on`, `son`, `plus`, `message`) tirent le score vers 50/50.
- La fenêtre a un effet négligeable sur les fausses validations (< 0,02 point). Environ **un quart des fausses validations du corpus mixte sont attribuables à la langue**, mais le total reste très proche de la référence monolingue (0,58 % contre 0,54 % sans faute ; 0,90 % contre 0,84 % à 2 % de fautes), largement sous 3 %. Activer les deux lexiques ne menace donc pas l'objectif du §15.2.
- **Recalibrage** : fenêtre de **4 mots** et indice par **fréquence relative**. Les fenêtres de 2 et 3 mots font un peu mieux sur ce corpus, mais (a) les blocs du corpus mixte sont courts (un changement tous les 34 mots en moyenne, phrases Tatoeba courtes), ce qui favorise artificiellement la réactivité ; (b) la stabilité (absence de bascules intempestives dans un long texte monolingue, exigence « pas de bascule brutale » du §10.6) n'est pas mesurée ici, et une fenêtre de 2 mots bascule sur un seul mot ambigu. 4 mots perd 1,5 point de précision par rapport à 2 et divise par deux le délai de la fenêtre actuelle.

## 5. Limites

- **Corpus** : Tatoeba est fait de phrases courtes et souvent didactiques, pas de messages réels. Les fréquences **anglaises** sont calculées sur la partie entraînement du **même** corpus (Tatoeba) que le test : la mesure anglaise est « dans le domaine » et donc optimiste. Elles sont aussi biaisées : `Tom` est le 3e mot le plus fréquent et `Mary` est sur-représenté, ce qui gonfle les complétions de `t`/`m` en début de mot. Les fréquences **françaises** viennent de sous-titres (Lexique), hors du domaine du test : mesure plus réaliste, mais les **noms propres français** homographes d'un nom commun sont au plancher (fréquence 1) et les autres noms propres absents de Lexique aussi, d'où une partie des préfixes inconnus et des fausses validations sur les mots hors lexique.
- **Modèle de fautes** : uniquement des substitutions par une voisine, à taux constant ; ni omission, ni insertion, ni inversion, ni faute d'accent, ni double faute corrélée. La correction simulée sait traiter exactement ces fautes : les taux de correction sont optimistes et les fausses validations réelles probablement plus élevées. Les poids de correction ne sont pas optimisés.
- **Correction simplifiée** : distance 1 seulement (la spec prévoit 2 sur le mot complet), dénominateur approché (un mot atteint par deux éditions compte deux fois), 6 formes lues par préfixe. Pas de candidats spéciaux pour les élisions et contractions ; les accents ne sont pas évalués (on compare des clés).
- **Utilisateur simulé** : il voit toujours la touche espace et ne corrige jamais ses fautes ; le score de langue est alimenté par les mots voulus. Les fausses validations sont une approximation des annulations du §15.2 : un utilisateur réel peut ne pas annuler une fausse validation, ou annuler une validation correcte.
- **Pas de contexte** : V1 sans bigrammes (§10.3), comme la spec.
- **Adjacence** : distances calculées sur la disposition au repos ; la disposition adaptée (largeurs variables) et le décodage spatial du §5.3 ne sont pas simulés. En QWERTY, `p` n'a qu'une voisine (`o`) avec le seuil de 1,5 u (`l` est à 1,51 u).

## 6. Fonctions à porter dans le moteur (M2)

Implémentées dans `tools` pour l'analyse, absentes de `engine` :

- `CandidateSource` : candidats de la validation par espace avec correction à distance 1 pondérée par l'adjacence, y compris pour un préfixe connu qui n'est pas un mot ;
- `CandidateMixer` : mélange bilingue des candidats ;
- `KeyboardLayout` : géométrie des lettres et voisinage ;
- `LanguageScore` / `LanguageEvidence` : score de langue à fenêtre glissante, indice par fréquence relative.
