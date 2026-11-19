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
