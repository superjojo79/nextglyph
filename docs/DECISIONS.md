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

## 2026-11-20 — Prédiction, validation par espace et bilinguisme dans le moteur (M2)

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
