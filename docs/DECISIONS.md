# Journal des décisions

Chaque entrée consigne une décision d'architecture : contexte, décision, mesures, alternatives écartées. Les entrées ne sont pas réécrites ; une décision remise en cause fait l'objet d'une nouvelle entrée.

---

## 2026-11-19 — Structure et format binaire du lexique

### Contexte

Le lexique (spec §10.1) doit répondre, pour un préfixe normalisé `p`, à `P(c | p)` pour chaque lettre, à `P(fin | p)` et aux meilleures complétions, en moins de 1 ms pour 500 000 clés (§11.3), avec un fichier de 8 Mo au plus par langue, chargé par `mmap` sans reconstruire de graphe d'objets. Une clé peut porter plusieurs formes d'affichage (`a`/`à`, `ou`/`où`), chacune avec sa fréquence.

### Décision

**Trie compact (arbre radix) sérialisé en ordre postfixe**, lu directement dans le `ByteBuffer` (package `nextglyph.engine.lexicon`, format détaillé dans `LexiconFormat.kt`).

- **Radix** : les chaînes de nœuds à un seul enfant et non terminaux sont fusionnées en une étiquette. Sur un lexique synthétique de 500 000 clés, cela ramène environ 910 000 nœuds de trie à 560 000 enregistrements.
- **Un enregistrement par enfant**, rangé dans le bloc de son parent : un octet d'en-tête (index du premier caractère sur 5 bits, drapeaux `HAS_CHILDREN`, `TERMINAL`, `LONG_LABEL`), l'étiquette, puis selon les drapeaux :
  - nœud interne : masse du sous-arbre (16 bits), fréquence maximale d'une forme du sous-arbre (varint exact), distance vers le bloc enfant (varint) ;
  - nœud terminal : les formes, en ligne (fréquence varint, longueur UTF-8, octets ; longueur 0 quand la forme est identique à la clé, cas le plus fréquent).
- **Ordre postfixe** : les blocs enfants sont écrits avant leur parent. Les distances sont donc positives et petites pour les nœuds profonds (les plus nombreux), et toute descente progresse vers le début du fichier. Le lecteur vérifie qu'un bloc enfant se termine avant son parent : même un fichier corrompu ne peut pas faire boucler une requête.
- **Calcul de la distribution** : descente sur `p` (au plus 28 enregistrements lus par niveau), puis lecture des enfants du nœud atteint ; `P(c | p) = masse(c) / (masse_fin + Σ masses)`. Les probabilités et `P(fin)` somment donc à 1 par construction. Un préfixe au milieu d'une étiquette a une seule continuation, de probabilité 1.
- **Complétions** : recherche best-first ; la file de priorité ordonne nœuds et formes par fréquence (borne exacte pour un nœud : fréquence maximale stockée), puis par clé. Seuls les sous-arbres susceptibles de contenir un résultat sont ouverts.

**Représentation des fréquences.**

- Fréquence d'une forme : `Long` dans `[1, Long.MAX_VALUE]`, stockée exactement (varint, 9 octets au plus). Les doublons exacts s'additionnent avec saturation à `Long.MAX_VALUE`.
- Masses (sommes de fréquences) : calculées en `Double`, donc sans dépassement de capacité (500 000 × `Long.MAX_VALUE` ≈ 4,6·10²⁴). Celle d'une feuille est recalculée exactement à partir de ses formes ; celle d'un nœud interne est stockée quantifiée sur 16 bits, en échelle logarithmique (`round(log2(m) · 512)`, plage `[1, 2¹²⁸]`, erreur relative ≤ 0,07 %, calcul par `StrictMath` pour un codage identique sur toutes les plateformes). Cette erreur est sans effet visible sur l'adaptation du clavier.
- Fréquence maximale d'un sous-arbre : exacte (varint), pour que l'ordre des complétions soit exact, y compris près de `Long.MAX_VALUE`.

**En-tête** : magic `NGLX`, version (u16), étiquette de langue BCP 47, nombres de clés et de formes, position du bloc racine, taille des données. Le chargement vérifie l'en-tête et la taille exacte du tampon en O(1) ; chaque lecture de nœud est bornée et toute incohérence lève `LexiconFormatException`.

**Lexique en mémoire = lexique sérialisé** : `LexiconBuilder.build()` sérialise puis charge le résultat. Il n'existe qu'une implémentation de lecture, et l'aller-retour binaire est identique par construction.

**Formes invalides** : `LexiconBuilder(tag, InvalidFormPolicy.REJECT | SKIP)`. Par défaut, une forme non indexable lève une `IllegalArgumentException` qui la cite ; en mode `SKIP`, elle est ignorée et consignée dans `skippedForms`, pour que les outils d'import (`tools/`) publient ce qu'ils ont écarté.

### Mesures

Lexique synthétique de 500 000 clés (`SyntheticLexicon` : radicaux syllabiques, familles de flexions verbales, nominales et adjectivales, 514 917 formes dont 148 356 accentuées, fréquences de Zipf), programme `LexiconBenchmark.kt`. Poste de développement Windows 11, JDK 25 (HotSpot), lexique chargé par `mmap`.

| Mesure | Résultat | Budget |
|---|---|---|
| Taille du fichier | 6 351 172 octets (6,06 Mio, 12,7 octets par clé) | ≤ 8 Mo |
| Chargement (`mmap` + en-tête) | 16 ms | ouverture < 150 ms |
| Construction (hors `tools/`) | 1,2 s | — |
| `distribution` (200 000 préfixes aléatoires) | moyenne 1,7 µs, p99 3,5 µs | < 1 ms |
| `completions(p, 5)` (50 000 préfixes) | moyenne 13 µs, médiane 7 µs, p99 41 µs | — |
| `completions("", 5)`, pire préfixe | moyenne 32 µs, p99 45 µs | — |

Les maxima isolés de quelques millisecondes relevés par le programme correspondent à des pauses du ramasse-miettes ou du compilateur JIT : répéter 20 000 fois le préfixe concerné donne un p99 inférieur à 50 µs. Ces mesures restent à refaire sur un téléphone de milieu de gamme ; la marge sur le budget de `distribution` est d'environ 300× au p99.

### Alternatives écartées

- **DAWG minimal** (suggéré par §10.1) : le partage des suffixes empêche de stocker sur un nœud la masse et la fréquence maximale de son sous-arbre, puisqu'un même nœud appartient à des mots de fréquences différentes. Il faudrait des sorties pondérées sur les arcs (FST), plus complexes à construire et à lire, pour un gain de taille non nécessaire : le trie radix tient déjà dans le budget. À reconsidérer si les vrais lexiques dépassent 8 Mo.
- **Trie non compressé** : environ 910 000 nœuds au lieu de 560 000, environ 9 Mo estimés avec le même encodage, hors budget.
- **Masses exactes sur 64 bits** : déborderaient avec des fréquences proches de `Long.MAX_VALUE` et coûteraient 2 à 6 octets de plus par nœud interne. Masses en `float` 32 bits : 450 Ko de plus pour une précision inutile.
- **Fréquences des formes quantifiées** : gagnerait environ 1 Mo, mais les complétions et `displayForms` ne restitueraient plus les fréquences fournies.
- **Formes d'affichage dans une table séparée** : un pointeur de 3 à 4 octets par mot (environ 1,7 Mo) au lieu de formes en ligne.
- **Ordre préfixe (parent avant enfants)** : les distances vers l'avant dépendent de la taille des sous-arbres encore à écrire, ce qui impose deux passes ; l'ordre postfixe s'écrit en une passe.
- **Validation complète au chargement, ou somme de contrôle** : coûte un parcours de tout le fichier (plusieurs millisecondes) à chaque ouverture du clavier. La vérification paresseuse à chaque lecture suffit à garantir qu'aucune requête ne plante ni ne boucle.
- **Distribution de la lettre suivante précalculée par nœud** (28 probabilités) : multiplierait la taille par 10 pour gagner quelques microsecondes.

---

## 2026-11-19 — Validation par espace : correction des préfixes connus

### Contexte

Le §5.2 ne prévoit la correction à distance d'édition que pour un préfixe inconnu, et le §5.5 ne précisait pas si les candidats de la validation par espace comprennent des corrections quand le texte tapé est un préfixe valide. L'analyse M0 (`docs/analysis/M0-corpus.md`, partie 2) simule un utilisateur qui tape les mots des corpus de test Tatoeba avec 0, 2 ou 5 % de fautes de substitution par une touche voisine et appuie sur espace dès que la touche espace affiche le mot voulu.

### Décision

À la validation par espace, les candidats comprennent les corrections à distance 1 (pondérées par l'adjacence) **même si le texte tapé est un préfixe connu**, dès lors que ce n'est pas un mot du lexique (§5.5, §10.4). L'adaptation visuelle (§5.2) n'est pas modifiée.

### Mesures

Fausses validations (part des validations automatiques), 5 % de fautes, `minProb = 0,7` : français 4,83 % → 2,10 %, anglais 3,74 % → 1,62 %. Sans cette correction, aucun réglage de la grille testée (`minProb` 0,4 à 0,9, `minRatio` 1,5 à 10, longueur minimale 1 à 3) ne passe sous 3 % en français (minimum 3,26 %) : les fausses validations viennent de fautes qui forment un autre préfixe valide (`ke` pour `le`), surtout sur les mots de 2 à 4 lettres. Coût sans faute de frappe : 18,3 → 17,3 % de mots complétés en français, KSPC 0,940 → 0,944.

### Alternatives écartées

- **Longueur minimale avant validation automatique** (2 ou 3 lettres) : réduit les fausses validations sans les ramener sous 3 % en français ; sans effet une fois la correction étendue.
- **Seuils plus élevés seulement** (`minProb` 0,8 ou 0,9) : les fausses validations dues aux fautes ont une confiance élevée ; 4,76 % au mieux en français à 5 % de fautes, et beaucoup moins de complétions.

---

## 2026-11-19 — Seuils de la validation par espace

### Contexte

Le §5.5 fixait `autocommit.minProb = 0,60` et `autocommit.minRatio = 2,0` à titre provisoire, à recalibrer par l'analyse du §15.1. Objectif du §15.2 : moins de 3 % de validations automatiques annulées, approximé par le taux de fausses validations simulé.

### Décision

`autocommit.minProb = 0,70` (au lieu de 0,60) ; `autocommit.minRatio = 2,0` inchangé. Réglage « Prudence de la validation » (§14) : Prudente 0,80, Normale 0,70, Audacieuse 0,60. Pas de longueur minimale.

### Mesures

Avec la correction des préfixes connus (entrée précédente), fausses validations sur les corpus de test :

| `minProb` | FR 0 / 2 / 5 % de fautes | EN 0 / 2 / 5 % | Mots complétés ou corrigés, FR / EN sans faute | KSPC FR / EN |
|---|---|---|---|---|
| 0,60 | 0,73 / 1,41 / 2,59 % | 1,01 / 1,15 / 1,61 % | 19,3 / 24,2 % | 0,936 / 0,928 |
| **0,70** | **0,67 / 1,17 / 2,10 %** | **1,11 / 1,19 / 1,62 %** | **17,3 / 20,9 %** | **0,944 / 0,939** |
| 0,80 | 0,57 / 0,98 / 1,76 % | 1,03 / 1,09 / 1,47 % | 14,7 / 17,1 % | 0,953 / 0,951 |

0,60 respecte aussi l'objectif, mais avec une marge faible (2,59 %) pour un modèle de fautes optimiste (substitutions seulement, que la correction sait traiter) ; 0,70 garde une marge d'environ un tiers pour un coût de 2 à 3 points de mots complétés. `minRatio` n'a pas d'effet mesurable dès que `minProb ≥ 0,67` (la condition est alors toujours remplie) ; il est conservé comme garde-fou et pour le réglage Audacieuse.

### Alternatives écartées

- **0,60 (valeur initiale)** : marge trop faible à 5 % de fautes en français.
- **0,80 et plus** : gain de 0,3 point sur les fausses validations pour 2,6 points de mots complétés en moins (FR) ; retenu pour le réglage Prudente.

---

## 2026-11-19 — Score de langue : fenêtre de 4 mots, indice par fréquence relative

### Contexte

Le §10.6 fixait une fenêtre glissante de 8 mots, et comptait les mots communs aux deux langues « pour les deux langues ». L'analyse M0 (partie 3) mesure, sur le corpus mixte étiqueté (1,1 million de mots, 32 501 changements de langue), la part des mots dont la langue dominante avant la frappe est la bonne, le délai pour suivre un changement de langue et les fausses validations attribuables à la langue.

### Décision

Fenêtre de **4 mots**. Chaque mot validé apporte un indice **proportionnel à sa fréquence relative** dans chaque lexique, `(f_FR, f_EN) / (f_FR + f_EN)`, au lieu d'un simple test d'appartenance.

### Mesures

| Indice, fenêtre | Langue dominante correcte | Délai moyen | Changements non rattrapés |
|---|---|---|---|
| appartenance, 8 (ancienne règle) | 86,9 % | 4,41 mots | 1 103 |
| appartenance, 4 | 91,1 % | 2,53 mots | 228 |
| fréquence, 2 | 94,1 % | 1,47 mot | 31 |
| **fréquence, 4** | **92,6 %** | **2,48 mots** | **179** |

Les fausses validations du corpus mixte dépendent peu de la fenêtre (< 0,02 point) : 0,58 % sans faute et 0,90 % à 2 % de fautes (`minProb = 0,7`), contre 0,54 % et 0,84 % si la langue de la phrase est connue ; environ un quart d'entre elles sont attribuables à la langue (22 à 26 % avec l'indice par fréquence, 26 à 30 % avec l'appartenance).

### Alternatives écartées

- **Fenêtre de 2 ou 3 mots** : légèrement meilleure sur ce corpus (+1,1 à +1,5 point), mais le corpus mixte change de langue tous les 34 mots en moyenne, ce qui favorise la réactivité, et la stabilité sur un long texte monolingue n'est pas mesurée ; une fenêtre de 2 mots bascule sur un seul mot ambigu, contraire à l'exigence « pas de bascule brutale » du §10.6. À réévaluer sur des textes réels (M5).
- **Appartenance aux lexiques** : les mots courts communs (`a`, `on`, `son`, `plus`) tirent le score vers 50/50 ; moins précise à toutes les tailles de fenêtre.

## 2026-11-19 — Cœur adaptatif du moteur : poids, solveur, animation, décodeur

### Contexte

Le profil Texte adapte la disposition après chaque frappe (spec §5.2) et décode les taps avec le modèle de langue (§5.3), sous les invariants du §2 (topologie immuable, déplacement plafonné, gel pendant le toucher, atténuer sans désactiver) et en moins de 1 ms par frappe (§11.3). Le package `nextglyph.engine.adapt` (Kotlin pur) fournit ce cœur à l'IME.

### Décision

**API.** Tampons mutables réutilisables, sans allocation dans les chemins appelés à chaque frappe : `KeyWeightCalculator` remplit des `KeyWeights` (`r(c)`, `P(fin)`, intensité, origine), `LayoutSolver` en déduit une `AdaptedLayout` (largeur, opacité, échelle du libellé par touche ; `widthsByRow()` se passe à `KeyboardGeometry.compute`), `AdaptiveLayoutState` applique gel, report et animation, `TapDecoder` remplit une `TapDecoding`. Toutes les constantes réglables sont dans `AdaptationConfig` / `TapDecoderConfig` (immuables), pour qu'une recalibration ne touche qu'un endroit.

**Poids.** `r(c) = P(c | p) / max`, le maximum étant pris sur les lettres de la disposition (l'apostrophe et le trait d'union ne sont pas des touches du profil Texte). Début de mot : intensité 0,5. Préfixe inconnu : correction à distance d'édition 1 (substitution par une touche voisine pondérée par `exp(−d² / 2σ²)` sur la distance entre centres au repos, lettre en trop, lettre manquante), des positions proches de l'endroit où le préfixe devient inconnu vers le début, au plus 96 candidats ; la distribution retenue mélange celles des candidats connus, pondérées par poids d'édition × masse ; intensité 0,5 ; sans candidat, état neutre. La transposition (distance 2 au sens de Levenshtein) n'est pas tentée.

**Solveur.** Les largeurs cibles suivent les formules du §5.2, normalisées sur les lettres de la rangée (les touches de fonction gardent leur largeur ; l'espace vise `repos + spaceGain · P(fin)` et ne descend jamais sous son repos), puis sont mélangées avec le repos selon l'intensité. Le respect des contraintes est exact et en O(n) : chaque bord intérieur d'une rangée est une variable ; une passe de droite à gauche calcule l'intervalle des positions admissibles de chaque bord (largeur minimale, largeur fixe, somme des deux bords bornée par le plafond de déplacement du centre) ; la disposition au repos est toujours admissible, donc aucun intervalle n'est vide ; une passe de gauche à droite place chaque bord au plus près de sa cible dans son intervalle. Le plafond est appliqué avec une marge de 10⁻⁵ u, si bien qu'il tient aussi pour les centres recalculés à partir des largeurs `Float` (testé sans tolérance sur 3 000 distributions aléatoires).

**Modes.** Le plafond est par touche, en fraction de sa propre largeur au repos (0,5 × 0,9 u pour une lettre de la rangée 3). Calme : opacité seule. Calme renforcé (défaut) : opacité, libellé, plafond 0,15. Élastique : tout, plafond `adapt.maxDisplacement` = 0,5.

**Décodeur : règles de frontière.**
- La nature de la touche est celle de la touche *affichée* sous le doigt : un tap sur espace, ⌫, ↵ ou `?123` produit toujours cette touche, et un tap sur une lettre produit toujours une lettre, même tout près de l'espace. Le décodage probabiliste ne départage que des lettres. Une faute « lettre au lieu d'espace » relève de la correction à la validation (§10.4), pas du décodeur.
- **Noyau garanti** : un tap dans la moitié centrale (en largeur et en hauteur) d'une lettre affichée produit cette lettre, quel que soit `λ`. Avec le mélange de gaussiennes, le centre *affiché* d'une lettre atténuée peut être à 0,5 u de son centre de repos en mode Élastique, et la formule seule ne garantit plus le §5.3 (« un tap franchement au centre d'une touche atténuée doit produire cette lettre ») ; le noyau le garantit dans tous les modes et rend le clavier prévisible (§2.5). Le modèle de langue n'agit qu'en périphérie des touches.
- `P(c | p)` est remplacé par `max(r(c), 0,05)` : même ordre des scores, et une lettre de poids nul reste décodable hors du noyau.

**Animation et gel.** Interpolation linéaire après une courbe `1 − (1 − t)²` ; toute image intermédiaire entre deux dispositions valides est valide (les centres dépendent linéairement des largeurs). L'animation est gelée, image comprise, tant qu'un pointeur est posé, et reprend au relâchement du dernier. En saisie rapide (intervalle < 120 ms), la disposition attend 120 ms sans frappe.

**Valeurs choisies** (non fixées par la spec, à recalibrer par l'analyse de corpus) : `adapt.gain` 0,4 ; `adapt.spaceGain` 0,6 u ; plafond du Calme renforcé 0,15 ; libellé minimal 0,7 ; intensités d'opacité 0,5 / 0,8 / 1 et de libellé 0 / 0,4 / 1 (Calme / Calme renforcé / Élastique) ; intensité après correction 0,5 ; voisinage de correction 1,5 u, σ 1,0 u, poids de suppression et d'insertion 0,3, 96 candidats ; décodeur `σ` 0,35 largeur de touche, plancher d'a priori 0,05, noyau 0,5 ; courbe ease-out d'exposant 2.

### Mesures

Programme `AdaptationBenchmark.kt`, lexique français réel (`tools/build/lexicons/fr.nglx`, 454 057 clés), AZERTY, 81 097 frappes issues de 20 000 mots tirés selon leur fréquence, puis 20 000 préfixes avec une faute de touche voisine (10 601 inconnus du lexique). Poste de développement Windows 11, JDK 25 (HotSpot).

| Mesure (µs) | moyenne | médiane | p99 | p99,9 |
|---|---|---|---|---|
| Poids, préfixes corrects | 2,0 | 1,8 | 4,0 | 8,4 |
| Solveur | 0,44 | 0,40 | 0,60 | 1,9 |
| Décodage | 0,64 | 0,60 | 1,0 | 3,0 |
| **Total poids + solveur + décodage**, préfixes corrects | 3,1 | 2,9 | 5,5 | 12 |
| **Total**, préfixes avec faute (correction) | 75 | 76 | 259 | 453 |

Mêmes ordres de grandeur en mode Élastique. Les maxima isolés de 2 ms correspondent à des pauses du ramasse-miettes ou du compilateur JIT (cf. entrée précédente). Le budget de 1 ms est tenu avec une marge d'environ 180× au p99 sur les préfixes corrects et de 4× sur les préfixes à corriger ; ces mesures restent à refaire sur un téléphone de milieu de gamme.

Indication de calibrage : des taps tirés uniformément dans la touche visée sont décodés sur cette lettre dans 95,8 % des cas en Calme renforcé et 92,9 % en Élastique ; les autres sont attirés hors du noyau par une voisine plus probable. `λ`, `σ` et le noyau sont à recalibrer avec des données de frappe réelles.

### Alternatives écartées

- **Mise à l'échelle uniforme de l'écart au repos** (plus grand `t ≤ 1` tel que `repos + t · (cible − repos)` respecte le plafond) : exacte et simple, mais une seule touche contrainte écrase l'adaptation de toute la rangée.
- **Projection par moindres carrés** (programme quadratique, ou projections alternées) : optimale mais itérative, au coût et à la précision variables ; la méthode par intervalles admissibles est exacte, déterministe et en O(n).
- **Formule de décodage seule, sans noyau** : ne garantit pas le §5.3 quand le centre affiché s'écarte du centre de repos ; il faudrait `σ` très petit ou `λ` faible, au détriment de la correction en périphérie.
- **Laisser le décodeur convertir une lettre en espace (ou l'inverse)** près des frontières : imprévisible, et l'espace valide un mot (§5.5) ; une erreur coûterait cher.
- **Transposition dans la correction du préfixe** : relève de la distance 2 (§10.4), à la validation du mot.

---

## 2026-11-19 — Cœur adaptatif dans l'IME : lexiques embarqués, mot en cours, taille minimale des touches

### Contexte

Fin du périmètre M1 : l'IME charge les lexiques, suit le mot en cours, adapte la disposition affichée et décode les taps avec `TapDecoder`. Le §13 impose des zones de tap d'au moins 32 × 32 dp après adaptation (hors mode Élastique) ; or le solveur ne bornait les largeurs que par rapport au repos (`adapt.minWidth` = 0,6 u).

### Décision

**Lexiques.** `fr.nglx` et `en.nglx`, produits par `:tools:buildLexicons`, sont des assets non compressés (`noCompress`) projetés en mémoire (`AssetFileDescriptor` + `FileChannel.map`) en arrière-plan par `LexiconLoader` ; tant que le chargement n'est pas terminé, ou s'il échoue, le clavier est neutre (repos, `λ = 0`). Une seule langue est active (M1), choisie d'après le sous-type de méthode de saisie courant, puis la locale système, puis l'anglais (`LexiconLanguage`). Côté Gradle, `tools` expose `build/lexicons` par une configuration sortante (`lexiconElements`, attribut d'usage `nextglyph-lexicons`) et `ime` la consomme par une configuration résolvable ; la tâche `copy<Variante>Lexicons` alimente les assets, donc `:ime:assemble*`, `lint*` et `build` construisent les lexiques sans copie manuelle (compatible cache de configuration). Modification de `tools/build.gradle.kts` indispensable : `buildLexicons` ne déclarait ni entrées ni sorties (« toujours exécutée ») et n'exposait rien ; elle déclare maintenant ses entrées (listes de mots, corpus de test) et sa sortie, si bien qu'un second assemblage la trouve à jour. `fetchData` et `buildWordLists` restent inchangées.

**Mot en cours.** `WordPrefix.of(textBeforeCursor, selectionLength, language)` (engine, pur) : dernière suite de lettres avant le curseur, apostrophe et trait d'union entre lettres compris, normalisée par `TextNormalizer`. Une élision française (`l'`, `qu'`...) est un mot à part entière (§5.8, comme dans le découpage des lexiques) : le préfixe repart de vide. Chiffre, caractère hors de l'alphabet du lexique, plus de 64 caractères ou texte indisponible : `null`, clavier neutre. Une sélection donne le préfixe vide (la frappe suivante la remplace). L'IME relit le texte (66 caractères) après chaque frappe et à chaque `onUpdateSelection` ; `AdaptiveKeyboard.onPrefix` ignore un préfixe inchangé, si bien que les deux appels ne comptent qu'une frappe. Pas de lecture du texte, ni d'adaptation, dans les champs mot de passe, e-mail, adresse web, numériques, téléphone et date/heure (`AdaptationPolicy`, §2.8, §12).

**Rendu et accessibilité.** `AdaptiveKeyboard` (engine) assemble poids, solveur, `AdaptiveLayoutState` et décodeur ; `KeyboardView` lui transmet les pointeurs réels et l'horloge (`SystemClock.uptimeMillis`), lit `ANIMATOR_DURATION_SCALE` et dessine l'image courante sans allocation (`postInvalidateOnAnimation` pendant l'animation, `postInvalidateDelayed` jusqu'à l'échéance d'une mise à jour différée, aucun redessin pendant le gel). Mode par défaut : Calme renforcé. Avec TalkBack (exploration tactile), disposition au repos et décodage purement spatial. L'opacité du libellé ne descend pas sous 0,55 (`KeyStyle`) : à l'opacité de la touche seule (0,30 en Élastique, 0,44 en Calme renforcé pour une lettre de poids nul), le contraste clair tombe sous 3:1 ; avec ce plancher il reste supérieur à 3:1 sur les deux thèmes, ce que `KeyStyleTest` calcule à partir des couleurs des ressources.

**Taille minimale des touches (§13).** Mesure sur AZERTY et QWERTY, 300 000 préfixes réels (FR et EN, lexiques de production) et 20 000 jeux de poids aléatoires, avant correction :

| Largeur de l'écran | Lettre de la rangée 3 au repos | Plus petite lettre après adaptation (Calme renforcé, comme Élastique) |
|---|---|---|
| 360 dp | 30,6 dp | 18,4 dp (rangée 3) ; 21,6 dp (rangées 1 et 2) ; 20,5 dp (rangée 2 QWERTY) |
| 411 dp | 34,9 dp | 21,0 dp (rangée 3) ; 24,7 dp (rangées 1 et 2) ; 23,4 dp (rangée 2 QWERTY) |

La contrainte de 32 dp était donc violée, à l'écran de 360 dp déjà au repos pour la rangée 3, et après adaptation partout, y compris en Calme renforcé (le plafond de déplacement du centre limite le décalage, pas la largeur). Solution retenue : **largeur minimale en dp dans le solveur**. `AdaptationConfig.minKeyWidthDp` (32) et `LayoutSolver.keyboardWidthDp` (renseignée par la vue) donnent un plancher `min(repos, 32 dp)` aux lettres, en plus de `minWidth × repos` ; la disposition au repos reste admissible, donc les garanties du solveur (topologie, rangées de 10 u, plafond de déplacement) sont inchangées. Le mode Élastique, explicitement choisi, en est exempté (§13). Après correction : 32 dp au minimum pour les lettres des rangées 1 et 2, aux deux largeurs ; rangée 3 : 32 dp à 411 dp, et à 360 dp la largeur de repos (30,6 dp) qu'aucune adaptation ne réduit. Vérifié par `MinimumKeyWidthTest`.

Reste ouvert : au repos, la rangée 3 fait 30,6 dp à 360 dp (0,85 u) ; corrigé par l'entrée « Rangée 3 à 0,9 u » ci-dessous.

### Alternatives écartées

- **Plancher exprimé en unités relatives seulement** (relever `adapt.minWidth` à 0,9) : supprime presque toute l'adaptation de largeur, et reste insuffisant sur un écran étroit.
- **Plancher appliqué aussi en mode Élastique** : contredit le §13 (« sauf mode élastique explicitement choisi »).
- **Copie manuelle des lexiques dans `ime/src/main/assets`** : fichiers de plusieurs Mo dans git ou étape à la main, non reproductible.
- **Lexiques compressés dans l'APK puis copiés au démarrage** : lecture complète à chaque installation, `mmap` direct impossible.
- **Calcul du préfixe par état tenu dans l'IME** (ajout et retrait de lettres) : se désynchronise à chaque déplacement du curseur ou édition par l'application ; relire le texte avant le curseur est plus robuste et reste sous le budget.

## 2026-11-19 — Rangée 3 à 0,9 u (taille minimale de 32 dp, §13)

**Contexte.** Au repos, les lettres de la rangée 3 (0,85 u) mesurent 30,6 dp à 360 dp, soit 1,4 dp sous le minimum de 32 dp du §13 ; aucune adaptation ne pouvait y remédier.

**Décision.** Les lettres de la rangée 3 passent à 0,9 u (32,4 dp à 360 dp ; 36,9 dp à 411 dp). L'espace absorbe la différence : 1,6 u en AZERTY (`?123` 1 + 6 × 0,9 + espace 1,6 + `↵` 1 + `⌫` 1 = 10 u) et 1,7 u en QWERTY (`?123` 1 + 7 × 0,9 + espace 1,7 + `⌫` 1 = 10 u ; la rangée 2 QWERTY ne change pas). L'espace continue de grandir avec P(fin) (§5.2). Mis à jour : §5.1 et récapitulatif, `KeyboardLayouts`, prototype, géométrie de l'analyse M0 (`tools`, sans relancer l'analyse complète : l'adjacence ne change pas pour l'essentiel, les chiffres du rapport restent indicatifs). `MinimumKeyWidthTest` exige désormais 32 dp au repos et après adaptation en Calme renforcé pour toutes les lettres, à 360 et 411 dp, en AZERTY et QWERTY.

### Alternatives écartées

- **Rangée 3 à 0,85 u avec exception au §13** : affaiblit une contrainte d'accessibilité pour 1,4 dp.
- **`↵` déplacée dans la barre de suggestions** : retire une touche essentielle de la zone de frappe et charge la barre.
