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

## 2026-11-19 — Édition de texte : espace automatique, typographie, majuscules (M2, moteur)

### Contexte

Le §5.6 et le §5.7 décrivent la ponctuation, la typographie française, le double espace et les majuscules sans fixer plusieurs cas : espace tapée par l'utilisateur, `«`, texte après le curseur, signes consécutifs, début de champ, abréviations.

### Décision

Package `nextglyph.engine.edit` (pur, sans Android) : `TextEditor` (`onWordCommitted`, `onPunctuation`, `onSpace`) reçoit un `EditContext` (texte avant et après le curseur, profil, langue de la phrase, mode de majuscules, état de shift, `EditState`) et renvoie un `EditResult` (opérations, nouvel état, état de shift). Les opérations sont `EditOperation.DeleteBeforeCursor(count)` et `EditOperation.InsertText(text)` (`nextglyph.engine.input`), appliquées dans un `beginBatchEdit`. `AutoCapitalization` (pure) calcule la majuscule ; réglages et constantes dans `EditConfig` (immuable).

- **Vérification du texte réel** : l'espace automatique n'est supprimée que si `textBefore` se termine bien par une espace ordinaire ; l'état seul ne suffit jamais. L'IME remet l'état à `INITIAL` après toute autre frappe.
- **Espace tapée** (`SpaceOrigin.TYPED`) : jamais remplacée par un signe (choix de l'utilisateur) ; elle sert au double espace.
- **Espace automatique** : seulement en fin de texte ; ni après `'`, `«`, élision ou trait d'union final ; jamais dans les profils sans typographie.
- **`«`** garde l'espace qui le précède et est suivi d'une fine insécable ; `»` remplace l'espace automatique et prend une fine insécable avant.
- **Insécables** : pas en début de champ ni de ligne, pas derrière une espace existante, pas après `. , ; : ! ? …` (`?!` reste collé), sauf `»`.
- **Double espace** : espace ordinaire (automatique ou tapée) précédée d'une lettre, d'un chiffre ou d'un guillemet ou parenthèse fermant, hors champ à profil sans typographie ; donne `. `. La majuscule suivante respecte le champ : sans `CAP_SENTENCES`, aucune majuscule forcée.
- **Majuscules** : début de ligne compte comme début de phrase ; abréviations en liste courte et configurable (voir §5.7) ; un état manuel (une lettre ou verrouillé) l'emporte sur l'automatique ; plusieurs drapeaux `CAP_*` : l'application passe le plus fort.
- **Apostrophe** insérée : U+0027, comme dans les lexiques.

### Alternatives écartées

- **Remplacer aussi l'espace tapée par un signe** : surprenant pour qui a tapé l'espace volontairement.
- **Espace automatique au milieu du texte** : on ne peut pas deviner si elle est voulue.
- **Forcer la majuscule après double espace même sans `CAP_SENTENCES`** : contredit le champ (identifiant, code).

---

## 2026-11-19 — Prédiction, validation par espace et bilinguisme dans le moteur (M2)

### Contexte

M2 porte dans le moteur (`nextglyph.engine.predict`, Kotlin pur) ce que l'analyse M0 avait simulé dans `tools` : candidats de la validation par espace avec correction pondérée par l'adjacence (§5.5, §10.4), mélange bilingue et score de langue (§10.6), plus ce que M0 ne mesurait pas : élisions, apostrophes et traits d'union, restitution des accents et de la casse (§5.7, §5.8), annulation, apprentissage du refus, barre de suggestions (§5.4). L'IME appliquera les décisions via `InputConnection` ; la ponctuation et la typographie (`nextglyph.engine.edit`) sont traitées par une autre tâche.

### Décision

**API pure.** `Predictor` prend l'état (texte avant le curseur, `LanguageState`, `PredictionSettings`) et renvoie une `Prediction` (cas de l'espace, texte inséré, libellé de l'espace, candidats, barre) ; `commitSpace`, `commitBeforePunctuation`, `pickSuggestion` et `undo` renvoient des modifications abstraites (`TextEdit` : `DeleteBefore(n)`, `Insert(texte)`, drapeau « se termine par une espace automatique »), définies dans `nextglyph.engine.input` pour que `edit` produise les mêmes et que les deux se composent (`TextEdit.plus`). `LanguageState` est une valeur immuable (fenêtre, verrouillage, langue de la phrase) ; seule la mémoire des refus (`RejectionStore`, en mémoire, interface prévue pour la persistance) a un état. Constantes dans `PredictionConfig` (immuable). Un cache LRU facultatif des candidats par texte tapé sert aux simulations.

**Ensemble de lexiques (`LexiconSet`, package `lexicon`).** Plusieurs lexiques d'une même langue fusionnés à la lecture, sans copie. **Les fréquences d'une même forme s'additionnent**, comme les masses des préfixes : c'est la seule règle cohérente avec `distribution` (les masses des nœuds sont des sommes de fréquences). Les membres partagent donc une même échelle, et un fichier de couverture ne recopie pas les comptes du fichier de fréquences. `completions` est exacte quand chaque forme n'appartient qu'à un membre (fichiers découpés par source) ; sinon une forme répartie entre membres sans figurer dans les meilleures d'aucun peut manquer (la fréquence des formes renvoyées reste exacte). Ouvre la voie au découpage de `fr.nglx` par licence (Lexique / Dicollecte), au dictionnaire personnel et aux contacts.

**Candidats.** Méthode et poids de M0 sans changement (distance 1, y compris sur un préfixe connu qui n'est pas un mot ; 6 formes par préfixe ; substitution voisine 0,01, autre 0,0005, omission, lettre en trop, inversion 0,002, apostrophe ou trait d'union omis 0,5), sur l'adjacence calculée à partir de la disposition du moteur (`KeyAdjacency`, 1,5 u). Ajout : **élisions françaises omises** (`lecole` → `l'école`), absentes des clés du lexique puisque l'élision y est un mot à part (§5.8) : candidat `e'w` de score `0,5 × f(e') / masse des initiales voyelles ou h × f(w)` (probabilité de l'élision devant un mot qui l'admet, multipliée par le coût d'une apostrophe omise) ; si le mot élidé est complet, lui seul est candidat (`lami` → `l'ami`, pas `l'amis`).

**Coût.** Pour tenir 2 ms par frappe en bilingue : `Lexicon.prefixMass` (masse d'un préfixe sans lire ses enfants, pour tester les éditions) et arrêt anticipé de l'exploration des préfixes candidats, triés par la borne `poids × masse` de leurs mots (exact).

**Validation.** Les trois cas du §5.5, `minProb` selon la prudence (0,80 / 0,70 / 0,60), `minRatio` 2. Paires ambiguës : un mot d'au moins une langue active relève du cas 1, donc `its`, `well`, `a`, `ou`, et en bilingue `dont` (mot français) ne sont jamais remplacés ; la variante (`it's`, `we'll`, `à`, `où`, `don't`, ainsi que l'élision cachée : `jaime` → `j'aime`) reste dans la barre. Désactivation : profils mot de passe, e-mail, URL, numériques, `NO_SUGGESTIONS`, réglage utilisateur ; incognito et mot de passe n'écrivent aucun compteur de refus.

**Accents et casse.** Restitution « sans ambiguïté » précisée : une orthographe représente au moins **95 %** des occurrences de la clé dans chaque lexique actif qui la contient, et ces lexiques s'accordent. Sans ce seuil, les variantes rares des sources (`écolé` 3 contre `école` 20 593, `fenêtré` 1) bloquaient la restitution de la plupart des mots. L'accord entre lexiques réalise la règle du §10.6 (`resume` anglais bloque `résumé`, mais `café` est restitué : le lexique anglais l'accentue aussi). Casse : celle de la forme la plus fréquente de l'orthographe retenue, dans le lexique de la langue de la phrase (`i` → `I` en anglais seulement, `paris` reste en minuscules), sauf casse invraisemblable des sources (`daV`) ; puis la casse tapée (initiale, tout en majuscules).

**Barre.** Au centre le mot que l'espace insérera, surligné s'il diffère du texte tapé ; à gauche le texte tapé quand il serait remplacé (pour le garder) ; puis variantes et candidats. Le libellé de l'espace est le texte inséré quand il diffère du texte tapé (correction, mais aussi accent ou majuscule restitués), rien sinon.

**Bilinguisme.** Score de M0 (fenêtre de 4 mots, indice par fréquence relative, `π0` = 0,75 pour la langue préférée). Mélange des candidats `P(w) = Σ π_L score_L(w) / Σ π_L total_L` ; distribution mélangée pour l'adaptation renormalisée sur les langues où le préfixe est connu. Langue de la phrase : langue dominante du score restreint aux mots validés depuis la dernière ponctuation finale, avec pour a priori le score au début de la phrase. Les élisions sont retirées du mot en cours dès que le français a un poids non nul.

### Mesures

Détail dans `docs/analysis/M2-engine.md` (`./gradlew :tools:analyzeEngine`, échantillon déterministe de 5 % du corpus de test).

- Fausses validations, réglage Normal, 0 / 2 / 5 % de fautes : FR 0,66 / 1,10 / 2,17 %, EN 0,90 / 1,08 / 1,37 % ; pire cas toutes prudences 2,76 % (FR, 5 %, Audacieuse). Référence M0 rejouée sur le même échantillon : FR 0,63 / 1,08 / 2,14 %, EN identique ; l'écart français (≤ 0,04 point) vient des candidats d'élision.
- Corpus mixte, Normal : 0,36 % (sans faute) et 0,69 % (2 %) de fausses validations, contre 0,34 et 0,62 % avec la langue connue ; langue de la phrase correcte pour 96,7 % des mots.
- Séparateurs omis (FR) : 68,6 % des élisions restituées, 2,1 % fausses ; accents (FR seul) : 58,6 % restitués, 0,23 % faux.
- Coût de `predict` par frappe (JVM de bureau) : bilingue, moyenne 0,31 à 0,42 ms, p99 1,3 ms, p99,9 < 2 ms ; une langue : p99 < 0,9 ms.

### Alternatives écartées

- **Maximum des fréquences** entre membres d'un `LexiconSet` : incohérent avec les masses des nœuds.
- **Restitution des accents seulement si une unique orthographe existe** (lecture littérale) : bloque les mots dont les sources contiennent une variante rare (moins de la moitié des mots accentués restitués sur le corpus de test).
- **Candidats d'élision sans le facteur `f(e') / masse des initiales`** : une élision rare (`lorsqu'`) pèserait autant que `l'`.
- **Élision complétée même quand le mot élidé est complet** (`dautre` → `d'autres`) : même travers que la complétion d'un mot tapé en entier, écarté par le cas 1.
- **Distance 2 sur le mot complet** (§10.4) : non nécessaire pour l'objectif de 3 %, coûteuse ; reportée.
- **Annulation après ponctuation** : l'annulation reste réservée au ⌫ immédiatement après l'espace ; après un signe, le ⌫ efface le signe.

---

## 2026-11-19 — M3 (moteur) : profils de saisie, dispositions spécialisées, séparateur décimal

**Contexte.** Le §4 ordonne les profils, le §7.1 et le §8 décrivent les dispositions, sans fixer tous les détails de géométrie ni de résolution.

**Décision.**

- **Résolution** (`nextglyph.engine.profile.InputProfileResolver`, pure et totale). Les constantes Android sont recopiées dans `AndroidInputConstants` (valeurs vérifiées une à une, exhaustivité vérifiée par réflexion). Seule `TYPE_CLASS_TEXT` lit variations et drapeaux de texte ; `TYPE_NULL` et les classes inconnues donnent le texte ordinaire ; les drapeaux d'une classe n'ont aucun effet dans les autres (combinaisons contradictoires couvertes par un balayage de 16 classes × 256 variations × 5 jeux de drapeaux × incognito). `privateImeOptions` est accepté et ignoré (aucune option privée en V1).
- **`InputProfile`** encode les tableaux du §7.2, §8, §9 : pavés et mot de passe = pas d'adaptation, de suggestions, de lecture du texte, d'apprentissage, de majuscules ni de typographie, `λ = 0`, pas d'aperçu ; e-mail et URL = adaptation et lecture actives (§9.1), mais ni suggestions de mots (barre de raccourcis à la place), ni majuscules, ni typographie ; texte et nom de personne = tout actif (suggestions coupées par `NO_SUGGESTIONS`, majuscules selon `CAP_*`). L'incognito se lit partout et force `learning = false`. Précédence des `CAP_*` : caractères, mots, phrases. L'apprentissage reste actif pour e-mail et URL hors incognito (modificateur « tous profils texte »). L'IME actuel ne lit pas le texte ni n'adapte en e-mail/URL (`AdaptationPolicy`) : choix provisoire de l'IME ; le profil suit le §9.1 (lexique des domaines à venir).
- **Dispositions** (`SpecialLayouts`, `ProfileLayouts`). `KeyKind` gagne `CHARACTER` (chiffre, symbole), `SHIFT`, `LETTERS` (`ABC`), `DECIMAL` (texte décidé par la politique) et `EMPTY` (emplacement vide, position fixe) ; `KeyboardLayout` gagne un `role` (`LayoutRole`, `TEXT` par défaut) pour distinguer lettres, symboles, mot de passe et pavés, sans toucher à `LayoutKind` (choix AZERTY/QWERTY de l'utilisateur, sans objet pour les pavés). Les chiffres et symboles ne sont pas des `LETTER`, pour rester hors du poids des lettres, du solveur et du décodeur. Géométrie : mot de passe en 10 u par rangée (rangée 4 QWERTY : retrait 0,5 u et lettres de 19/18 u) ; pavés numérique, PIN et date/heure en 4 colonnes de 2,5 u ; téléphone en 5 colonnes de 2 u (le §8.2 énumère cinq touches de plus que ne porte une colonne de 4 rangées, d'où deux colonnes) ; touches de pavé de 44 dp (`KeyboardMetrics.PAD_ROW_HEIGHT_DP`). Aucune touche réelle sous 32 dp à 360 dp (la plus étroite : 0,9 u = 32,4 dp, rangée 3 de la couche `?123`).
- **Couche `?123` du texte** : elle n'existait sur aucune branche (`origin/main`, `origin/engine/predict`, `origin/engine/edit`). Ajoutée en 3 rangées de 10 u (§5.1) ; `*`, `"`, `;`, `=`, `[` `]` n'y figurent pas en V1 (disponibles dans la disposition classique). Couche `?123` du mot de passe ajoutée aussi (§7.1).
- **Séparateur décimal** (`DecimalSeparatorPolicy`, §8.1) : essai de `,` puis repli sur `.`, mémoire volatile bornée (256 champs, LRU) par (paquet, id). Le refus se lit à `getTextBeforeCursor` avant/après ; on n'apprend pas quand le nombre en cours a déjà un séparateur (le refus peut venir du second séparateur), ni sans accès au texte.
- **`KeyDispatcher`** : `CHARACTER` insère son libellé ; `SHIFT`, `LETTERS`, `DECIMAL`, `EMPTY` n'ont pas d'effet direct (gérés par l'appelant). L'IME n'est pas modifié dans cette tâche.

### Alternatives écartées

- **Chiffres et symboles en `KeyKind.LETTER`** : les poids, le solveur et le décodeur les traiteraient comme des lettres du lexique.
- **Valeurs `LayoutKind` supplémentaires pour chaque pavé** : mélange le choix de l'utilisateur et le rôle de la disposition.
- **Pavé téléphone à 4 colonnes** : ne porte pas `+ , ;`, `⌫` et `↵` sans perdre la position fixe des chiffres.
- **Conclure au refus du séparateur sans regarder le nombre en cours** : mémoriserait `.` à tort dès qu'un second séparateur est refusé.

---

## 2026-11-19 — Français séparé par licence ; saisie complète de M2 dans l'IME

### Contexte

`fr.nglx` mêlait Lexique 4.10 (CC BY-SA 4.0) et Dicollecte (MPL-2.0), deux licences incompatibles dans un même fichier (`docs/PROGRESS.md`, problèmes ouverts). Les packages `predict` et `edit` existaient dans le moteur, mais l'IME n'insérait encore que des lettres.

### Décision

**Un fichier par licence.** `:tools:buildWordLists` écrit en plus `fr-lexique.tsv` (toutes les orthographes de Lexique, en minuscules, fréquence × 100) et `fr-dicollecte.tsv` (toutes les formes de Dicollecte, casse comprise, au plancher 1) ; `:tools:buildLexicons` en fait `fr-lexique.nglx` (2,03 Mo) et `fr-dicollecte.nglx` (6,07 Mo), soit 7,73 Mio pour le français, dans le budget de 8 Mio par langue (vérifié sur la somme des fichiers d'une langue). Aucun fichier ne contient de données de l'autre source. `LexiconLanguage.files` décrit les fichiers de chaque langue ; `LexiconLanguage.assemble` les fusionne en un `LexiconSet`.

**Membre sans casse.** Lexique ne distingue pas la casse : ses formes sont des orthographes. À la fusion, la fréquence d'une orthographe `s` d'un membre déclaré sans casse va à la forme `s` des autres membres si elle existe, sinon à chacune de leurs variantes de casse, sinon reste à `s` : c'est exactement la règle de l'ancienne liste unique (`paris` porte la fréquence, `Paris` le plancher ; `Marseille` la porte faute de `marseille`). Les masses des préfixes ne dépendent pas de la casse et restent des sommes. `LexiconSet.completions` renvoie désormais toutes les formes fusionnées des clés vues (une forme transférée n'est la meilleure d'aucun membre). Seul écart avec l'ancienne liste : une forme présente dans les deux sources compte le plancher (1, soit 0,01 occurrence par million) en plus. Vérification par `:tools:analyzeEngine` : voir « Mesures ».

**Session de saisie dans le moteur** (`nextglyph.engine.session`, pur, testé avec un éditeur simulé) : `InputSession` reçoit les événements (touche, lettre accentuée, caractère, espace, ⌫, Entrée, case de barre, menu de langue, `⇪`, `onUpdateSelection`) et pilote un `SessionTarget` (interface au-dessus d'`InputConnection`). L'IME (`InputConnectionTarget`, `KeyboardView`, service) ne fait que câbler.

- **Composition** : le mot en cours est en `setComposingText` ; toute validation passe par `finishComposingText` puis les opérations de `TextEdit` (`deleteSurroundingText`, `commitText`), chaque événement dans un seul `beginBatchEdit`/`endBatchEdit`. Après ⌫ ou annulation, le mot qui précède le curseur est remis en composition (`setComposingRegion`).
- **Sélection** : la session tient la position attendue du curseur après chacune de ses modifications (file des positions en attente, les mises à jour pouvant arriver en retard) ; une mise à jour qui n'en fait pas partie est un déplacement externe : composition validée telle quelle, annulation et espace automatique oubliées. Position inconnue (champ sans `initialSelStart`) : toute mise à jour est acceptée.
- **Validation** : espace → `Predictor.commitSpace` ; si le mot inséré finit par une apostrophe ou un trait d'union, pas d'espace (§5.8). Signe de la barre ou de la couche `?123` pendant un mot → `commitBeforePunctuation` puis `TextEditor.onPunctuation` (typographie selon la langue de la phrase) ; `'` et `-` à l'intérieur d'un mot restent dans la composition ; chiffres insérés tels quels ; autres symboles : le mot est validé d'abord. Une espace insérée devant du texte est mémorisée comme tapée (pas de remplacement par un signe).
- **Espace absorbée** (évolution du §5.6) : après l'espace automatique d'un signe (`va ? `), une espace tapée ne double pas l'espace (une fois). Sans cela, l'habitude « signe puis espace » produisait deux espaces.
- **Barre** : 3 cases (centre : le mot que l'espace insérera) ; ponctuation `, . ? ! : … '` quand le texte finit par l'espace automatique et qu'aucun mot n'est en cours ; raccourcis permanents en e-mail et URL. Libellé de l'espace `␣ mot`, indicateur `FR`/`EN` (`[FR]` si verrouillée).
- **Profils** : `InputProfileResolver` (M3, moteur) choisit le comportement ; `SessionField` le traduit. Mot de passe et pavés : aucune lecture du texte (sauf les deux caractères du ⌫, comme en M1), chaque touche insérée telle quelle, disposition au repos. E-mail, URL : raccourcis, ni composition, ni majuscule, ni espace automatique, disposition au repos (pas encore de lexique des domaines). `NO_SUGGESTIONS` : ni barre ni validation, adaptation conservée. Incognito ou mot de passe : aucun refus mémorisé.
- **Majuscules** : automatiques par `AutoCapitalization` sur le texte lu, manuelles par glissé vers le haut sur une lettre (seuil 0,6 rangée), verrouillées par appui long sur `ABC` (la couche `?123` livrée par M3 n'a pas de touche `⇪`).
- **Menus d'appui long** (`KeyPopups`) : accents (`a e i o u y c`), ponctuation rapide sur `?123`, langues sur l'espace (verrouiller FR / EN, Auto), symboles voisins sur la couche `?123` (pour les caractères absents de ses 3 rangées), `⇪` sur `ABC`. Choix en glissant puis en levant le doigt.
- **Adaptation** : `AdaptiveKeyboard.setSource` accepte n'importe quelle `DistributionSource` ; l'IME lui donne `Predictor.distribution` (mélange bilingue, §10.6), réinstallée à chaque nouvel état de langue.

### Alternatives écartées

- **`fr-dicollecte` réduit aux formes absentes de Lexique** : plus petit, mais le choix des formes dépendrait des données de Lexique, et la casse de Dicollecte serait perdue pour les mots communs.
- **Casse recopiée de Dicollecte dans `fr-lexique`** : met des données MPL dans le fichier CC BY-SA.
- **Logique de session dans l'IME** : non testable sur la JVM ; contraire à la règle « l'IME ne fait que câbler ».
- **Deux espaces après `?` puis espace** (comportement littéral du §5.6) : surprenant pour qui tape l'espace par habitude.

---

## 2026-11-19 — Apprentissage local et contacts dans le moteur (M4)

### Contexte

M4 apporte au moteur (Kotlin pur) ce qui s'apprend : dictionnaire personnel (§10.5), mémoire des refus persistante (§5.5), modèle spatial personnalisé (§5.3), noms des contacts (§6), persistance, export et effacement (§12, §14). L'IME fournit les flux et la liste de noms ; aucune API Android dans le moteur.

### Décision

**Dictionnaire personnel (`nextglyph.engine.learn.PersonalDictionary`).** Un mot inconnu validé tel quel (cas 3 du §5.5) est mis en attente ; la `learn.minOccurrences`-ième validation (2) le promeut ; l'annulation d'une validation (`discard`) oublie le mot en attente ; `remove` le supprime (il peut être réappris). Rattachement à la langue dominante estimée au moment de la validation ; un mot n'enrichit que le lexique de sa langue. Plafonds : 2 000 mots promus (éviction du moins récemment utilisé : l'usage, par validation ou importation, rafraîchit un mot) et 500 mots en attente (éviction du moins récemment vu), 3 formes d'affichage par mot. Mots acceptés : 2 à 32 caractères, lettres, apostrophes et traits d'union, clé dans l'alphabet des lexiques ; une initiale majuscule due au début de phrase est ramenée en minuscules (la majuscule vient de la phrase, pas du mot). Politique d'apprentissage unique (`LearningPolicy`) : jamais en incognito, mot de passe, code PIN, e-mail, URL, champ numérique, ni avec `NO_SUGGESTIONS` pour les mots ; le modèle spatial apprend en texte, nom, e-mail et URL (statistiques par touche, sans texte).

**Intégration dans la prédiction : un `Lexicon` binaire reconstruit, pas une source parallèle.** Les mots promus sont construits par `LexiconBuilder` en un lexique par langue, ajouté au `LexiconSet` de cette langue (`PersonalDictionary.sets(base)`). Un lexique binaire immuable convient : le dictionnaire change rarement (une promotion par mot appris ; la reconstruction de 2 000 mots est mesurée sous la seconde par un test), et tout le reste (distribution, complétions, correction, restitution de la casse) fonctionne sans une ligne de plus dans le prédicteur. L'IME rappelle `sets` quand `lexiconRevision` change puis recrée le `Predictor` (bon marché ; le cache de candidats repart à vide, ce qui est voulu). Fréquence d'un mot : `baseShare` (1e-5) fois la masse du lexique de la langue, fois `min(4, 1 + ln validations)`. Les fréquences s'additionnant (décision du `LexiconSet`), un mot appris puis ajouté plus tard au lexique principal ne pose pas de conflit. Sans mot promu, `sets` renvoie ses arguments : comportement strictement inchangé (testé par identité et par équivalence des prédictions).

**Mémoire des refus (`PersistentRejectionStore`).** Même sémantique que `InMemoryRejectionStore`, plafonnée (2 000 paires, éviction de la moins récemment refusée, compteur plafonné à 1 000).

**Modèle spatial (`SpatialModel`, interface `adapt.TapSpatialModel`).** Estimation en ligne, par lettre, de la moyenne et de la variance du décalage de la frappe par rapport au centre de la touche au repos, en fractions de la largeur de la touche et de la hauteur de rangée (indépendant de l'écran, de la rotation et de l'adaptation). Les frappes sont mises en attente (`stage`) et intégrées à la validation du mot (`confirm`) ; une lettre effacée (`discardLast`) ou un mot annulé (`discardStaged`) les écarte. Moyenne et variance à pondération exponentielle (poids `1/min(n, 300)`), frappes hors du rectangle de leur touche ignorées (elles ont probablement été attribuées à cette lettre par le modèle de langue). Valeur exposée = valeur par défaut + (estimation − défaut) × `n / (n + 20)` : le retour vers le défaut est progressif, un modèle vide expose exactement le défaut. Bornes : décalage ≤ 0,25 touche ; facteur de `σ` dans [0,7 ; 1,6], facteur = dispersion mesurée / 0,23 (les frappes retenues étant censurées par la touche, la dispersion est sous-estimée ; la borne basse limite l'effet). `TapDecoder` reçoit le modèle en paramètre optionnel (`TapSpatialModel.NONE` par défaut) : il décale les deux centres de la gaussienne et multiplie `σ` ; le noyau et la nature de la touche ne changent pas. Avec un modèle vide, scores et décisions sont identiques bit à bit (test sur 2 000 taps aléatoires). Les constantes (0,23, 0,7, 1,6, 20, 300, 0,25) sont des valeurs choisies, à calibrer en M5 (`SpatialConfig`).

**Contacts (`nextglyph.engine.contacts`).** `ContactDirectory(contacts, bases)` indexe prénom, nom, deuxième prénom et surnom (en option), noms composés entiers, casse d'un nom propre pour les noms tout en minuscules ou en capitales (deux capitales = initiales, conservées), particules exclues. Poids de base : 1e-5 de la masse du lexique, croissant en `1 + ln n` (plafond 3) avec le nombre de contacts. Bonus de contexte discrétisé : chaque indice du §6.3 (champ nom de personne, début de message, mot déclencheur FR/EN ou nom de contact en majuscule initiale juste avant, majuscule tapée) ajoute un niveau, de 0 à 2, et multiplie le poids par 4^niveau. Un lexique binaire étant immuable, un jeu de lexiques est construit par niveau à la construction (`sets(niveau)`) et l'IME utilise le `Predictor` du niveau courant (trois instances au plus). **Garde-fous** : (1) au niveau 0, un nom qui est aussi un mot du lexique principal pèse au plus 25 % de la fréquence de ce mot, donc le mot courant garde sa forme (`rose` reste `rose`) ; (2) un mot tapé déjà valide n'est jamais remplacé par un autre (cas 1 du §5.5) ; (3) au niveau 0, aucun nom ne peut être inséré par complétion ou correction, ce qui réalise « un nom ne peut être candidat dominant que si le contexte le justifie » : paramètre `Predictor(blockedAutocommit)`, sans effet sur le cas 1 ; le nom reste proposé dans la barre.

**Persistance (`LearnedDataFile`).** Un fichier binaire pour les trois états : `NGLS`, version de fichier (u16), nombre de sections (u8), puis sections `tag, version, longueur u32, charge, CRC32`. Mots, refus et modèle spatial sont écrits du moins récent au plus récent (l'ordre d'éviction se restaure sans horloge). Lecture tolérante : chaque octet altéré ou tronqué est détecté (CRC par section, nombre de sections, octets en trop) ; la lecture s'arrête à la première section illisible en gardant les précédentes ; une section intacte mais invalide (plafonds, valeurs absurdes, doublon) est ignorée ; une version de fichier inconnue ou un en-tête invalide donne un état vide ; une section de tag inconnu est ignorée. Aucune exception : un `LoadResult` porte l'état et la liste des problèmes (sans texte de l'utilisateur). Toute taille lue est bornée par les plafonds avant allocation. Export et import du dictionnaire en TSV UTF-8 (`# Nextglyph dictionnaire personnel v1`, puis `langue<TAB>mot<TAB>utilisations`), import tolérant (lignes invalides comptées et décrites, jamais bloquantes), borné en taille et en lignes.

**Effacement.** `LearnedData.clear()` vide mots promus et en attente, refus, modèle spatial et frappes en attente (§12) ; l'IME réécrit ensuite le fichier.

### Mesure

Le dictionnaire personnel vide ne change rien : `sets` renvoie les lexiques d'origine (identité), et un test rejoue les prédictions d'un clavier après effacement et vérifie qu'elles sont celles du clavier d'origine. `:tools:analyzeEngine` n'a pas été rejoué : il construit son prédicteur sans dictionnaire personnel et ne peut donc voir aucune différence.

### Alternatives écartées

- **Source de candidats parallèle pour le dictionnaire personnel** (table de hachage consultée par le prédicteur) : double le code des complétions, de la correction et de la casse pour un contenu qui change rarement.
- **Apprendre dès la première validation** : un mot mal tapé deviendrait un mot valide ; le seuil de deux validations, la mise en attente et l'annulation filtrent la plupart des fautes.
- **Horloge pour l'éviction** : l'ordre d'insertion d'une `LinkedHashMap` suffit et se persiste sans horodatage.
- **Statistiques spatiales en pixels** : invalides après rotation ou changement d'écran.
- **Bonus de contexte continu** (une constante multiplicative par appel) : exigerait de reconstruire un lexique à chaque frappe ou de modifier le calcul des candidats ; trois niveaux préconstruits suffisent.
- **Interdire tout nom de contact au niveau 0** : priverait la barre de suggestions des noms.
- **Persister les frappes en attente** : elles n'ont de sens que dans le mot en cours.

---

## 2026-11-19 — M3 dans l'IME : profils, dispositions spécialisées, panel de champs de test

### Contexte

Le moteur fournissait depuis M3 (moteur) `InputProfileResolver`, `SpecialLayouts` et `DecimalSeparatorPolicy`, mais l'IME affichait toujours la disposition compacte, et ⌫ lisait deux caractères du champ même dans un mot de passe.

### Décision

- **Clavier du champ** (`nextglyph.engine.session.FieldKeyboard`, pur) : à chaque `onStartInputView`, le profil résolu donne la disposition principale, la couche `?123` (absente des pavés), la hauteur des rangées (44 dp pour les pavés), la barre (profils à disposition compacte seulement) et le menu de `?123`. La vue (`KeyboardView.setKeyboard`) ne reconstruit ses dispositions que si l'identifiant change. Une couche plus courte que la disposition principale (couche `?123` du mot de passe) est alignée en bas, pour que `ABC` tombe à la place de `?123` ; la hauteur du clavier ne change pas d'une couche à l'autre.
- **Mot de passe, code PIN, pavés, disposition classique : aucune lecture du champ.** `InputSession.onBackspace` envoie `KEYCODE_DEL` sans lire le texte (au lieu de lire deux caractères pour effacer un point de code entier) ; aucun autre chemin ne lit le champ dans ces profils (vérifié par un éditeur simulé qui compte les lectures). La session ne retient rien du texte tapé (ni composition, ni prédiction, ni barre).
- **`⇧` explicite** (`InputSession.onShiftKey`) : appui = majuscule simple, second appui dans le délai de double appui du système (`ViewConfiguration.getDoubleTapTimeout`, 300 ms) = verrouillage, appui sur une majuscule active = annulation. La touche est surlignée tant que la majuscule est active, libellée `⇪` quand elle est verrouillée.
- **Séparateur décimal** (`InputSession.onDecimalSeparator`) : `DecimalSeparatorPolicy` avec le séparateur de la locale (ramené à `,` ou `.`) ; c'est la seule lecture du champ dans un pavé. Le curseur est ensuite tenu pour inconnu (le champ a pu filtrer l'insertion). Quand le repli sur `.` est appris, la touche affiche `.` aussitôt. **Constat sur Android 16 (`fr-FR`) : un `EditText` `numberDecimal` refuse la virgule** ; le repli est donc le cas courant des champs natifs, et la mémoire par champ évite de tâtonner à chaque séparateur.
- **Signe moins des pavés** : la touche insère `-` (U+002D). Le libellé `−` (U+2212) du §8.1 était inséré tel quel et refusé par les champs signés d'Android (constaté sur l'émulateur).
- **Échappatoire « Disposition classique »** (§4) : case « Classique » en dernière position du menu de `?123` des profils à disposition compacte ; profil `InputProfileResolver.classic` = disposition et règles du mot de passe, type, incognito et multiligne conservés. Retour par « Adaptative » dans le menu de `?123` de la disposition classique. Le choix est oublié au champ suivant, gardé si le même champ redémarre.
- **Libellé de `↵`** (`EnterKeyLabel`) : un symbole par action d'`imeOptions` (`➤ ⌕ ➔ ⇥ ⇤ ✓`), `↵` sinon. `⇧` et `↵` sont dessinés à la taille des lettres (les flèches étaient illisibles à la taille des touches de fonction).
- **Aperçu agrandi** : l'IME n'a pas encore d'aperçu de touche ; le profil le désactive déjà (`keyPreview = false`) pour le jour où il existera. Rien à couper.
- **Panel « Champs de test »** (§15.3) dans le source set `debug` du module `ime` : activité `nextglyph.ime.debug.FieldTestsActivity` (icône de lancement « Champs de test » en debug), 25 champs, écho du texte exact et de la dernière action. `ManifestTest` lit les manifestes fusionnés des deux variantes et échoue si l'activité apparaît en release.
- **Test instrumenté** `PasswordStorageTest` (AndroidX Test et UI Automator, Apache-2.0, `androidTest` uniquement) : active Nextglyph, tape dans le champ mot de passe du panel par coordonnées (géométrie calculée par le moteur) et compare l'état de `dataDir` avant et après. JUnit 4 n'a pas de `@Nested` : classes imbriquées avec le lanceur `Enclosed`, noms sans espaces (DEX des API < 30). Non ajouté à la CI : un émulateur sur GitHub demande à lui seul plusieurs minutes de démarrage, au-delà du budget de 10 minutes du job ; lancement local décrit dans `docs/FIELD-TESTS.md`.

### Alternatives écartées

- **`deleteSurroundingText(1, 0)` dans le mot de passe** : sans lecture, il couperait une paire de substitution (émoji) ; `KEYCODE_DEL` laisse l'éditeur effacer un caractère entier.
- **Couche `?123` du mot de passe étirée sur six rangées** : touches de plus de 80 dp de haut, sans intérêt ; alignée en haut, `ABC` serait loin de `?123`.
- **Libellé « Disposition classique » en entier** : ne tient pas dans une case du menu (8 cases sur 360 dp) ; « Classique » est réduit si besoin pour tenir.
- **Garder la disposition classique pour tous les champs de l'application** : un champ correctement typé retomberait sur une disposition sans adaptation sans que la personne le demande.
## 2026-11-19 — M4 dans l'IME : apprentissage, contacts, réglages, accessibilité

### Contexte

Le moteur fournissait le dictionnaire personnel, la mémoire des refus persistante, le modèle spatial, les contacts et leur persistance, sans rien de branché dans l'IME : la session utilisait un `InMemoryRejectionStore`, aucun mot n'était appris, l'écran de réglages ne faisait qu'expliquer l'activation, et TalkBack ne voyait qu'une vue opaque.

### Décision

- **Session** (`nextglyph.engine.session`, pur, testé) : `PredictionStack` réunit les prédicteurs (lexiques des langues actives, augmentés des mots promus puis des noms, un prédicteur par niveau de contexte des contacts) ; la session choisit le niveau à chaque prédiction d'après `ContactContext.read` (texte avant le curseur, champ, majuscule tapée). `SessionLearning` relie les événements au dictionnaire personnel et au modèle spatial (voir §10.5) ; la vue transmet la position de chaque frappe de lettre relativement à la touche au repos (`TapOffset`). Les règles de `LearningPolicy` restent la seule source de vérité (rien en incognito, mot de passe, code PIN, pavés ; pas de mots en e-mail ni en URL).
- **Reconstruction en arrière-plan** : quand les mots promus, les contacts, les langues actives ou la disposition changent, l'IME copie le dictionnaire personnel sur le fil de l'interface (`PersonalDictionary.copy`, quelques millisecondes), construit la `PredictionStack` sur le fil des lexiques, puis l'installe ; une reconstruction plus récente rend les précédentes caduques. La frappe continue avec les anciens prédicteurs entre-temps.
- **Persistance** (`LearnedStore`) : état sérialisé sur le fil de l'interface (quelques dizaines de kilo-octets au plus), écrit en arrière-plan par un fil unique, de façon atomique (`AtomicFiles` : fichier temporaire, `fsync`, `Files.move` atomique), 3 s après un changement, à la sortie du champ et à l'arrêt du service. `InputStream.readNBytes` n'existant sur Android qu'à partir de l'API 33, les lectures bornées du moteur passent par `ByteCodec.readAtMost`.
- **Contacts : mémoire seulement.** Les noms sont relus du carnet (`ContactsContract.Data`, prénom, nom, deuxième prénom, surnom) au démarrage du clavier, à l'activation, et 2 s après une notification du `ContentObserver` ; ils ne sont jamais écrits sur le disque (le §6.2 prévoyait un stockage séparé : moins de données au repos, et la lecture du carnet est rapide). Permission `READ_CONTACTS` déclarée, demandée seulement depuis les réglages, après une explication ; refusée ou retirée, la fonction est coupée et les noms effacés.
- **Réglages** : activité en vues du cadre Android (pas d'AndroidX : aucune dépendance nouvelle) ; `SharedPreferences` partagées avec le service (même processus) qui les écoute et applique tout à chaud : disposition, langues, langue préférée, mode d'adaptation, hauteur des rangées, validation par espace, prudence, typographie, double espace, contacts, retour haptique. **Variante anglaise** et **aperçu agrandi** non proposés (un seul lexique anglais ; pas d'aperçu). Lecture tolérante (`KeyboardSettings.read`) : toute valeur invalide prend la valeur par défaut.
- **Retour haptique** : `performHapticFeedback` à l'appui d'une touche (`CLOCK_TICK`, `KEYBOARD_TAP`, `LONG_PRESS` selon l'intensité), aucune permission `VIBRATE`. Le réglage système « Retour tactile » reste prioritaire.
- **Accessibilité** : `AccessibilityNodeProvider` maison (cadre Android) plutôt qu'`ExploreByTouchHelper` (androidx.customview) : une centaine de lignes, aucune dépendance. Les événements d'accessibilité ne sont envoyés que si l'`AccessibilityManager` est actif (sinon Android lève une exception — constaté sur l'émulateur au premier essai). Contour du focus TalkBack ajouté (couleurs vérifiées ≥ 3:1). La validation par espace annonce « Inséré : <mot> ».
- **Suppression d'un mot appris** : appui long sur une suggestion de la barre (§10.5), confirmé par un message.

### Alternatives écartées

- **PreferenceFragment d'AndroidX** (Apache-2.0) : tire AndroidX Fragment, Core, Lifecycle… pour une quinzaine de réglages ; les vues du cadre suffisent.
- **`PreferenceActivity` du cadre** : dépréciée ; les avertissements sont des erreurs.
- **Écriture synchrone dans `onFinishInput`** : bloque le fil de l'interface sur le disque.
- **`android.util.AtomicFile`** : garde une copie de secours plutôt qu'un renommage, et n'est pas testable sur la JVM.
- **Persister le sous-lexique des contacts** : demandé par le §6.2, mais sans gain mesurable et avec des noms au repos sur le disque.
- **Annoncer les lettres probables en premier** (§13) : demanderait un ordre des nœuds qui change à chaque frappe, déroutant à l'exploration ; reporté.

---

## 2026-11-20 — Marge basse du clavier (issue #20)

### Contexte

Sur Galaxy S25 Ultra (navigation par gestes, boutons « changer de clavier » et « masquer le clavier » activés), la rangée du bas était recouverte par ces boutons et par la poignée de gestes : le clavier ne réservait aucune marge. Le padding bas ne lisait que `navigationBars() | displayCutout()`, vérifié seulement sur émulateur.

### Décision

- Padding bas = maximum des encarts du bas `navigationBars()`, `captionBar()`, `tappableElement()`, `displayCutout()` (`BottomInset`, testé sur la JVM). Quand le système dessine lui-même la barre des boutons du clavier dans la fenêtre de l'IME, il la déclare comme `captionBar()`, et non comme barre de navigation. Les encarts se superposent depuis le bord bas : maximum, pas somme.
- Réglage **Marge basse** (0–48 dp, défaut 0) ajouté au padding, appliqué à chaud : filet de sécurité pour les surcouches de constructeur qui ne déclarent aucun encart, et choix de confort (« pouvoir paramétrer le positionnement »).

### Alternatives écartées

- **`mandatorySystemGestures()`** : réserverait la zone de gestes même quand le système ne dessine rien dessus ; la marge réglable couvre ce besoin.
- **Hauteur de la barre de navigation lue dans les ressources du système** (`navigation_bar_height`) : identifiant interne, non garanti, signalé par lint.

---

## 2026-11-20 — Préparation de la publication (§18)

### Contexte

M5 démarre : publication sur GitHub, F-Droid et Google Play avec une seule signature (§18.1). Il manquait la signature de release, le contrôle de reproductibilité, la release sur tag, les métadonnées des stores et la politique de confidentialité. Les données téléchargées étaient fragiles : les exports Tatoeba sont écrasés chaque semaine, et l'empreinte de `sources.json` ne correspond plus qu'au cache de la CI.

### Décision

- **Miroirs figés** : champ facultatif `mirrors` dans `sources.json`, essayé avant `url` ; l'empreinte reste vérifiée quelle que soit l'adresse. Les copies sont des pièces jointes d'une release du dépôt (`donnees-AAAA-MM-JJ`), créée par un workflow manuel à partir du cache de la CI. Toutes les sources sont figées.
- **Clé du cache des données** calculée sur les seules empreintes de `sources.json`, avec repli sur le cache le plus récent : ajouter un miroir n'oblige pas à retélécharger.
- **Signature** lue dans l'environnement (`NEXTGLYPH_KEYSTORE`…), jamais dans le dépôt ; secrets dans un environnement GitHub `release` avec approbation. Schémas v2 et v3 seulement (`minSdk` 26).
- **Reproductibilité** : `dependenciesInfo` désactivé (bloc chiffré pour Google, signalé par F-Droid), `vcsInfo` désactivé. Job de CI : deux builds de release non signés depuis deux dossiers, sans cache de build, comparés à l'octet ; en cas d'écart, les deux APK sont publiés en artefact.
- **Release sur tag `vX.Y.Z`** : le tag doit égaler `versionName` et les notes de version `changelogs/<versionCode>.txt` doivent exister en `fr-FR` et `en-US` ; APK signé, `.sha256` et notes dans la release GitHub ; app bundle en artefact pour Play.
- **Politique de confidentialité** dans `docs/PRIVACY.md`, servie par GitHub : aucun site à maintenir.

### Alternatives écartées

- **Versionner les données dans git** : interdit au-delà de 1 Mo (CLAUDE.md), et ~100 Mo.
- **Git LFS** : quota de bande passante, et F-Droid ne le suit pas par défaut.
- **`versionCode` dérivé de `versionName`** : plus d'automatisme mais moins lisible ; un entier incrémenté à la main suffit et la release vérifie le reste.
- **Action tierce de création de release** : `gh`, présent sur les runners, évite une dépendance de plus à épingler.

---

## 2026-11-22 — Zones de frappe des touches de fonction (issue #30)

### Contexte

Le modèle spatial personnalisé (M4) n'apprenait que les 26 lettres, et la règle de frontière du décodeur (2026-11-19) fixait la nature de la touche à celle de la touche affichée sous le doigt. Or la frontière entre la rangée 3 et l'espace (`n`|espace, rangée 2 au-dessus de l'espace) et celle de `?123`, `↵` et `⌫` sont des sources fréquentes d'erreurs, que ni le score ni la correction ne rattrapent : un tap sur une touche de fonction n'est jamais redécodé. Après quelques jours d'usage, l'utilisateur constate une nette amélioration de la frappe sur les lettres ; on étend donc la même idée aux touches de fonction.

### Décision

**Apprentissage.** `SpatialModel` gagne quatre emplacements (`LEARNED_KEYS` : espace, `↵`, `⌫`, `?123`), avec le même estimateur, les mêmes bornes et le même retour vers le défaut que les lettres, relatifs à la touche au repos. Les frappes de touches de fonction ont une case d'attente unique (`stageKey`), distincte de la file des lettres, résolue par l'événement suivant (`SessionLearning.onKeyTap`) : espace et `↵` écartés si un `⌫` suit ; `?123` écarté si `ABC` ou `⌫` suit ; un `⌫` appris seulement s'il est suivi d'un autre `⌫` (un `⌫` isolé est peut-être un `n` ou un `m` manqué) ; tout abandon (champ quitté, curseur déplacé) écarte la frappe. Rien n'est appris dans la couche des symboles (géométrie différente), ni pour les glissés et gestes. `LearningPolicy` s'applique comme pour les lettres.

**Décodage : frontières personnalisées, pas de redécodage probabiliste.** La frontière entre deux touches dont l'une au moins n'est pas une lettre est déplacée de la moyenne des décalages appris des deux touches, convertis en pixels, bornée par `TapDecoderConfig.maxBoundaryShift` = 0,2 de la plus petite des deux largeurs au repos (horizontal) ou de la hauteur de rangée (vertical). Les frontières lettre/lettre ne bougent pas : le score s'en charge déjà. Si une frontière horizontale et une verticale revendiquent le point, l'horizontale l'emporte. La règle de 2026-11-19 devient : la nature de la touche est celle de la touche sous le doigt **dans les zones personnalisées**. Ce qu'elle protégeait reste vrai : le modèle de langue ne convertit jamais une lettre en espace ni l'inverse ; le déplacement est déterministe pour un modèle donné, borné et lent (fenêtre de 300 frappes, retour vers le défaut `n / (n + 20)`). Le noyau des lettres (moitié centrale) n'est jamais atteint, puisque 0,2 < 0,25.

**Stabilité.** Une frappe n'est apprise que dans le rectangle **au repos** de sa touche (`maxSampleOffset` = 0,5) : les taps captés au-delà par une frontière déplacée ne sont pas appris, donc pas d'emballement (l'estimation est censurée par une borne fixe).

**Équivalence.** Avec un modèle vide (ou `maxBoundaryShift` = 0), la touche retenue est exactement celle de `keyAt` : test d'équivalence sur toutes les arêtes de touches, AZERTY et QWERTY, espacements de rangée 0, 12 et 20 px. Un décalage appris nul laisse `keyAt` décider seul (pas d'écart d'arrondi).

**Persistance.** Section du modèle spatial en version 2 (26 lettres puis les 4 touches) ; la version 1 reste lue (touches vides). Version du fichier inchangée.

**Visibilité.** Les réglages affichent « Zones de frappe : apprises sur N frappes » (`SpatialModel.totalSamples`, somme des fenêtres).

### Mesure

Simulation déterministe (`TapKeyZonesSimulationTest`, AZERTY 1 000 × 100 px) : un utilisateur qui vise l'espace avec un biais moyen de −60 px horizontalement et −30 px verticalement (écarts types 40 et 20 px). Après 600 frappes (dont 333 apprises, celles qui tombent dans le rectangle de l'espace), décalages appris −0,23 et −0,22 ; taux de frappes manquées sur l'espace : **41,8 % → 23,8 %**. Contrepartie : pour un tap centré sur `n` (même dispersion, sans biais), le taux d'erreur passe de 27,1 % à 38,8 %. L'espace étant environ deux à trois fois plus fréquent que `n`, le bilan est nettement positif pour ce profil ; un utilisateur centré ne perd rien (testé). Les constantes (0,2, règles de validation) sont à calibrer en M5 avec `TypingStats`.

### Alternatives écartées

- **Redécoder lettre ↔ espace avec le modèle de langue** : déjà écarté (2026-11-19), l'espace valide un mot.
- **Apprendre tous les `⌫`** : un `⌫` isolé est souvent une lettre manquée, il tirerait la frontière vers la lettre et renforcerait l'erreur.
- **Score gaussien pour toutes les touches** : la normalisation favorise les petites touches et l'espace (large) perdrait sans modèle ; il faudrait recalibrer tout le décodeur, sans garantie d'équivalence pour un modèle vide.
- **Apprendre dans la couche des symboles** : autre géométrie, peu de frappes, gain marginal.

## 2026-11-23 — Classement des candidats : score spatial, accents, seuil de confiance (issue #53)

### Contexte

La validation par espace classait les corrections par `poids(édition) × fréquence`, avec un poids de substitution fixe (0,01 pour une touche voisine, 0,0005 sinon) qui ignorait où le doigt avait réellement touché, alors que l'IME connaît cette position (modèle spatial du §5.3, #30). La restitution des accents était bloquée par les sigles des sources (`CA` empêchait `ca` → `ça`) et par tout mot anglais sans accent dès que l'anglais était actif. L'issue demandait aussi un seuil d'écart avec le deuxième candidat réglable.

### Décision

**Score spatial.** `InputSession` garde en mémoire les frappes du mot en cours (`TypedTap` : lettre décodée et décalage relatif à la touche au repos, au plus 64, vidées à chaque fin de mot) et les passe à `Predictor.predict` (`WordTaps`, avec le modèle spatial appris). `SpatialSubstitution` remplace le poids d'une substitution par `clamp(0,6 × L(p | c) / L(p | t), 0,0005, 1)`, `L` étant la gaussienne de repos du décodeur (σ = 0,35, décalages et facteurs appris). Calibrage : 0,6 redonne l'ancien 0,01 pour une frappe au centre de la touche et une voisine horizontale, si bien que le moteur reste comparable à M0. Les frappes ne s'appliquent que si elles s'alignent sur la clé (même lettre à chaque position connue) ; sinon, et sans positions, les poids fixes s'appliquent et le cache des candidats sert comme avant. Rien n'est persisté : c'est la même donnée que celle déjà mise en attente pour le modèle spatial, gardée le temps du mot.

**Accents.** Les sigles (au moins deux lettres toutes majuscules) et les casses implausibles ne comptent plus dans la dominance d'une orthographe. Entre lexiques, chaque lexique vote pour son orthographe dominante avec `π_L · f_L / masse_L` et l'orthographe retenue doit porter 95 % du total : avec un seul lexique ou des lexiques d'accord, rien ne change ; un mot anglais sans accent ne bloque plus quand le français domine nettement (règle du §10.6 assouplie).

**Seuil de confiance.** Fondu dans le réglage existant « Prudence de la validation » plutôt qu'un second réglage : `Prudence` porte `minProb` et `minRatio` (3 / 2 / 1,5). La mesure montre que le rapport est déjà impliqué par `minProb` (`P(w2) ≤ 1 − P(w1)`) ; des rapports plus stricts ne changent rien de significatif (`docs/analysis/M2-engine.md`, §9.3).

### Mesure

`docs/analysis/M2-engine.md`, §9 : avec des frappes gaussiennes simulées (6,6 % de lettres fausses en français), réglage Normale, fausses validations 2,84 → 2,27 %, fautes non corrigées 14,3 → 11,1 %, mots complétés ou corrigés 20,6 → 23,7 % ; anglais 2,02 → 1,66 %. Accents restitués : 58,5 → 61,3 % (français seul), 50,4 → 56,6 % (deux langues).

### Alternatives écartées

- **Transmettre les scores du décodeur** (`TapDecoding.scores`) : ils incluent l'a priori du modèle de langue de la lettre suivante, qui serait compté deux fois avec la fréquence du mot.
- **Rapport sans facteur (0,6 → 1)** : bayésien au sens strict, mais il double le poids d'une frappe centrée par rapport à M0 et déplacerait tous les seuils calibrés.
- **Un second réglage « seuil de confiance »** : redondant avec `minProb`, il aurait ajouté un choix sans effet mesurable.
- **Restituer `à`, `où`, `là` ou les participes (`demandé`/`demande`)** : ambigus sans contexte ; relèvent de la prédiction par bigrammes (#51).

## 2026-11-23 — Erreurs d'espace : coupures et espace en trop (issue #52)

### Contexte

Une espace ratée est une faute fréquente sur un clavier compact : la barre d'espace est étroite et les touches `n`, `b`, `v`, `c` sont juste au-dessus ; une espace oubliée colle deux mots ; une espace tapée au milieu d'un mot le coupe. Le texte résultant n'est pas un mot, et la validation par espace (§5.5, cas 2) le « corrigeait » en un mot sans rapport (15 % des mots collés d'après la mesure de référence). Les bigrammes de #51 et les frappes de #53 donnent de quoi décider.

### Décision

**Candidats dans le classement existant.** Les coupures (`gauche droite`) et l'espace en trop supprimée sont des candidats de `Predictor.decide`, de score `poids × P(gauche) × P(droite | gauche)` dans l'échelle des autres, et ajoutés au dénominateur : la règle de dominance et `Prudence` s'appliquent sans second réglage. Ils ne sont jamais tentés quand le texte tapé est un mot (sauf second fragment très rare d'une espace en trop).

**Preuve spatiale pour la touche voisine.** Une lettre `n`, `b`, `v` ou `c` n'est une espace que si sa frappe est dans le bas de la touche (`minTapDy` = 0,25) : sans cela, les mots qui contiennent ces lettres seraient coupés dès que deux mots les expliquent. Sans position de frappe, ce cas n'existe pas.

**Bigrammes optionnels.** `Predictor.useNgrams` (appelé par `InputSession.setNextWordModels` et à l'installation des prédicteurs, donc sans changement de l'IME) donne `P(droite | gauche)` ; sans modèle ou pour un bigramme élagué, repli sur `0,5 × P(droite)` (unigramme seul sans modèle). Le score se calcule langue par langue : les deux mots d'une coupure sont de la même langue.

**Annulation sans état supplémentaire.** `Prediction.span` (texte remplacé : le mot en cours, ou le mot précédent, l'espace et le mot en cours) et `Candidate.replaces` suffisent à `commitSpace`, `undo` et `pickSuggestion` ; `UndoToken.typed` porte le texte tapé exact (`ordina teur`), le compteur de refus garde la paire. Une espace en trop supprimée n'adopte rien dans le dictionnaire personnel (le texte tapé contient une espace) et fait oublier le premier fragment, mis en attente à sa validation.

**Réglages** (`SpaceEditConfig`, mesures `docs/analysis/M52-spaces.md`) : choisis pour garder les fausses corrections très en dessous de 1 % (mesuré : au plus 0,013 % des mots justes, 20 000 phrases par langue) avant d'accroître le rappel.

### Alternatives écartées

- **Joindre tout couple de mots dont la concaténation est un mot** (`bon jour`) : trop de faux positifs ; seuls les fragments inconnus ou très rares sont joints, comme demandé.
- **Un fragment « trop court » pour la jonction connu de l'utilisateur** : la validation du premier fragment (`ordina`) a déjà eu lieu ; la complétion ou la correction qu'elle applique (`toujo` → `toujours`) limite le rappel de l'espace en trop (mesuré : 22 à 28 % corrigées ; environ 11 % des premiers fragments sont déjà complétés ou corrigés). Retarder la validation du premier fragment changerait l'ensemble de la validation par espace.
- **Coupure sur toute lettre voisine sans preuve spatiale** : écartée ci-dessus.
- **Seuil de score relatif au lieu d'un plancher absolu** : une coupure sans autre candidat aurait une probabilité 1 même pour un mot rare ; le plancher `minScore` borne la plausibilité en valeur absolue.

## 2026-11-24 — Contexte de la correction : confusions décidées par le mot précédent (issue #55)

### Contexte

`a`/`à`, `ou`/`où`, `la`/`là`, `du`/`dû` ont la même clé de lexique ; `ces`/`ses` et `et`/`est` sont deux mots valides. Les modèles de l'issue #51 fusionnent les formes de même clé et n'élaguent pas à l'identique : ils ne peuvent pas dire `va à` contre `va a`.

### Décision

Le format `.ngrm` passe en version 2 avec une zone des confusions (compte de chaque forme après chaque mot du vocabulaire, groupes de mots sans signe). Remplacement automatique seulement sur une part très nette (0,99 / 0,98 / 0,97 sur 50 / 30 / 20 occurrences selon la prudence), sinon proposition en premier dans la barre. Pas de remplacement d'un accent tapé ni d'une majuscule en milieu de phrase. Sigles, mots collés à `@ / _` ou à un point intérieur : aucun remplacement. Filtre et adresse postale : suggestions sans validation par espace.

### Alternatives écartées

- **Lire le bigramme du modèle existant** : impossible pour les paires à même clé, et `ses` est élagué après la plupart des mots.
- **Fichier de confusions séparé** : demandait de charger un troisième fichier dans l'IME.
- **Trigrammes pour les confusions** : plus de précision pour `et`/`est`, mais fichier plus gros ; à mesurer si le terrain montre trop de propositions manquées.
- **`on`/`ont`, `son`/`sont`, `ce`/`se`** : non mesurés, non inscrits.

