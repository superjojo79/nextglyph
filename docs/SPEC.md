# Nextglyph — Spécification

| | |
|---|---|
| Projet | Nextglyph, clavier adaptatif open source |
| Statut | Brouillon v0.2 |
| Date | 2026-11-19 |
| Licence | Apache-2.0 |
| Plateforme cible | Android, minSdk 26 (Android 8.0) |
| Langues V1 | Français et anglais |
| Hébergement | GitHub |
| Distribution | F-Droid et Google Play |

---

## 1. Vision

Un clavier mobile **compact** qui garde une disposition AZERTY ou QWERTY immédiatement reconnaissable, et qui met en avant, à chaque lettre tapée, les lettres pouvant encore former un mot valide. Les lettres improbables sont atténuées et cèdent une partie de leur espace aux lettres probables.

### 1.1 Objectif principal

**Gagner en vitesse et en place à l'écran, sans perte de confort.**

- *Place* : le profil texte tient sur 3 rangées, barre de suggestions comprise (~150 dp contre ~260 dp pour un clavier standard).
- *Vitesse* : le décodage probabiliste des taps et la validation par espace réduisent les erreurs et les frappes.
- *Confort* : les positions des touches restent stables ; le comportement du clavier est toujours prévisible.

### 1.2 Non-objectifs de la V1

- Saisie par glissement (glide typing) : prévue après la V1 (cf. §16).
- iOS (le moteur est toutefois écrit pour rester portable, cf. §11).
- Toute fonctionnalité nécessitant le réseau.
- Langues autres que le français et l'anglais (l'architecture doit cependant les permettre, cf. §10).
- GIF, presse-papiers avancé (les émojis, d'abord exclus, sont couverts par le §5.11).

---

## 2. Principes de conception (invariants)

Ces règles priment sur toute autre considération. Toute fonctionnalité qui les enfreint doit être revue.

1. **Topologie immuable.** Une lettre ne change jamais de rangée et ne dépasse jamais sa voisine. Seules la largeur, l'opacité et la taille du libellé varient.
2. **Déplacement plafonné.** Le centre d'une touche ne s'éloigne jamais de plus de `adapt.maxDisplacement` (défaut : 0,5 largeur de touche au repos) de sa position de repos.
3. **Gel pendant le toucher.** La disposition n'est jamais modifiée tant qu'un doigt est posé. La mise à jour est appliquée après `ACTION_UP`.
4. **Atténuer, jamais désactiver.** Toute lettre reste tapable dans tous les modes du profil texte.
5. **Prévisibilité.** Le clavier montre toujours ce qu'il va faire avant de le faire (ex. : la touche espace affiche le mot qu'elle insérera).
6. **Tout est annulable.** Toute insertion automatique s'annule par un appui sur ⌫ immédiatement après.
7. **Local uniquement.** Pas de permission `INTERNET`. Aucune donnée ne quitte l'appareil.
8. **Le champ commande.** Le type du champ (`EditorInfo`) détermine le profil ; ses contraintes (chiffres uniquement, pas de suggestions, incognito) sont strictement respectées.

---

## 3. Glossaire

| Terme | Définition |
|---|---|
| Préfixe | Partie du mot en cours située avant le curseur, normalisée (minuscules, sans accents). |
| Continuation | Lettre `c` telle que `préfixe + c` est le début d'au moins un mot du lexique. |
| `P(c \| p)` | Probabilité que la prochaine lettre soit `c` sachant le préfixe `p` et le contexte. |
| Poids relatif `r(c)` | `P(c \| p) / max_x P(x \| p)`, dans [0, 1]. Pilote le rendu. |
| `P(fin \| p)` | Probabilité que le préfixe soit un mot complet. Pilote le rendu de l'espace. |
| Profil | Ensemble de comportements choisi d'après le type du champ (cf. §4). |
| Validation (commit) | Insertion définitive du mot en cours dans le champ. |
| Candidat dominant | Suggestion assez probable pour être insérée par l'espace (cf. §5.5). |
| Espace automatique | Espace insérée par le clavier lors d'une validation, que la ponctuation peut remplacer. |

---

## 4. Profils de saisie

À chaque `onStartInput` / `onStartInputView`, le clavier lit `EditorInfo.inputType`, `imeOptions` et les drapeaux, puis choisit le premier profil applicable dans cet ordre :

| Priorité | Profil | Détection | Disposition |
|---|---|---|---|
| 1 | Code PIN | `TYPE_CLASS_NUMBER` + `TYPE_NUMBER_VARIATION_PASSWORD` | Pavé numérique, règles « mot de passe » |
| 2 | Numérique | `TYPE_CLASS_NUMBER` | Pavé numérique (§8.1) |
| 3 | Téléphone | `TYPE_CLASS_PHONE` | Pavé téléphone (§8.2) |
| 4 | Date / heure | `TYPE_CLASS_DATETIME` | Pavé date/heure (§8.3) |
| 5 | Mot de passe | `TYPE_TEXT_VARIATION_PASSWORD`, `_WEB_PASSWORD`, `_VISIBLE_PASSWORD` | Classique 6 rangées (§7) |
| 6 | E-mail | `TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, `_WEB_EMAIL_ADDRESS` | Compacte + barre `@ . .com` (§9.1) |
| 7 | URL | `TYPE_TEXT_VARIATION_URI` | Compacte + barre `/ . .com` (§9.1) |
| 8 | Nom de personne | `TYPE_TEXT_VARIATION_PERSON_NAME` | Compacte, contacts prioritaires (§6) |
| 9 | Texte | Tout le reste | Compacte adaptative (§5) |

Modificateurs applicables à tous les profils texte :

| Drapeau | Effet |
|---|---|
| `IME_FLAG_NO_PERSONALIZED_LEARNING` | Mode incognito : aucun apprentissage, aucune écriture dans le dictionnaire personnel. |
| `TYPE_TEXT_FLAG_NO_SUGGESTIONS` | Pas de barre de suggestions de mots, pas de validation par espace. L'adaptation visuelle reste active. |
| `TYPE_TEXT_FLAG_CAP_*` | Règles de majuscules automatiques (§5.7). |
| `TYPE_TEXT_FLAG_MULTI_LINE` | La touche ↵ insère un saut de ligne. |

**Précisions de résolution.** Seule la classe `TYPE_CLASS_TEXT` lit les variations et drapeaux de texte ; `TYPE_NULL` et toute classe inconnue donnent le profil Texte ordinaire (variation et drapeaux ignorés). À l'inverse, les drapeaux d'une classe sont ignorés dans les autres (`NO_SUGGESTIONS`, `CAP_*`, `MULTI_LINE` n'ont aucun effet sur un nombre ; `SIGNED` et `DECIMAL` n'en ont aucun sur un texte). `TYPE_NUMBER_VARIATION_PASSWORD` est testé avant les drapeaux : un code PIN n'a jamais ni `−` ni séparateur. Plusieurs drapeaux `CAP_*` : caractères, puis mots, puis phrases (ordre de `TextUtils.getCapsMode`). Les majuscules automatiques ne s'appliquent qu'aux profils Texte et Nom de personne (§9.1). `privateImeOptions` est réservé et ignoré en V1. L'incognito se lit sur tous les profils.

**Échappatoire manuelle.** Dans tout profil texte, un appui long sur `?123` propose « Disposition classique », pour basculer vers la disposition du profil mot de passe sans adaptation (champs web mal typés, saisie de code, etc.). La case s'intitule « Classique » et vient en dernier, après la ponctuation rapide. La disposition classique applique aussi les règles du mot de passe (§7.2 : ni lecture du champ, ni suggestions, ni majuscules automatiques, ni apprentissage) ; le type du champ, l'incognito et le multiligne sont conservés. Dans la disposition classique, l'appui long sur `?123` propose « Adaptative », qui ramène à la disposition du champ. Le choix vaut pour le champ courant : un nouveau champ repart de son profil ; un champ redémarré (même champ, même type) le garde.

---

## 5. Profil Texte

### 5.1 Disposition compacte

Largeur exprimée en unités (1 u = largeur d'une touche lettre au repos, rangées 1 et 2).

```
Barre :    [ suggestion ] [ suggestion centrale ] [ suggestion ]      (ou ponctuation, §5.6)
Rangée 1 : a z e r t y u i o p                                         10 u
Rangée 2 : q s d f g h j k l m                                         10 u
Rangée 3 : ?123 | w x c v b n | espace | ↵ | ⌫                         10 u (cf. ci-dessous)
```

Rangée 3, répartition au repos : `?123` 1 u, 6 lettres à 0,9 u (5,4 u), espace 1,6 u, `↵` 1 u, `⌫` 1 u = 10 u. **Décision :** les lettres de la rangée 3 sont légèrement plus étroites (0,9 u, soit 32,4 dp à 360 dp, au-dessus du minimum de 32 dp du §13), plutôt que de déplacer `↵` dans la barre ; l'espace absorbe la différence et continue de grandir avec P(fin) (§5.2).

**Variante QWERTY.** La rangée 3 QWERTY compte 7 lettres au lieu de 6. Pour garder une espace assez large, `↵` passe au bout de la rangée 2, à l'emplacement de la touche Entrée d'un clavier physique :

```
Rangée 1 : q w e r t y u i o p                                         10 u
Rangée 2 : (0,3 u) a s d f g h j k l | ↵                               0,3 + 9 × 0,95 + 1,15 = 10 u
Rangée 3 : ?123 | z x c v b n m | espace | ⌫                           1 + 7 × 0,9 + 1,7 + 1 = 10 u
```

**Choix de la disposition.** La disposition (AZERTY ou QWERTY) est un réglage indépendant de la langue saisie. Par défaut : AZERTY si la locale système est française, QWERTY sinon. Une personne francophone qui écrit en anglais garde son AZERTY.

| Élément | Hauteur |
|---|---|
| Barre de suggestions | 32 dp |
| Rangée de touches | 38 dp (réglable 34–46 dp) |
| Espacement vertical | 4 dp |
| **Total** | **~ 160 dp** maximum à la hauteur par défaut |

**Couche `?123` (symboles).** Trois rangées de 10 u, même répartition de la rangée 3 que les lettres AZERTY :

```
Rangée 1 : 1 2 3 4 5 6 7 8 9 0                                         10 u
Rangée 2 : @ # € % & - + ( ) /                                         10 u
Rangée 3 : ABC | , . ! ? ' : | espace | ↵ | ⌫                          1 + 6 × 0,9 + 1,6 + 1 + 1 = 10 u
```

`ABC` ramène aux lettres. Les caractères moins courants (`* " ; = [ ]`, etc.) ne sont pas sur cette couche en V1 : ils sont sur la disposition classique (§7.1), accessible par l'échappatoire du §4, et dans les menus d'appui long de la couche (symboles voisins : `-` → `_ – —`, `+` → `= * × ÷`, `(` → `[ { <`, `)` → `] } >`, `/` → `\ |`, `€` → `$ £ ¥`, `%` → `‰ ^ ~`, `&` → `§ °`, `'` → `" « » ’`, `,` et `:` → `;`, `.` → `… ·`, `?` → `¿`, `!` → `¡`). Appui long sur `ABC` : majuscule verrouillée `⇪` (§5.7).

**Espace large** (réglage, issue #23). Sur les écrans d'au moins 400 dp de large, le réglage « Espace large » réduit `?123` (ou `ABC`), `↵` et `⌫` de la rangée de l'espace à 0,8 u ; l'espace reprend la largeur libérée (2,2 u en AZERTY et sur la couche `?123`, 2,1 u en QWERTY où `↵` est en rangée 2). Les lettres de la rangée 3 se décalent d'autant ; le décodeur utilise la même géométrie de repos. Désactivé par défaut ; sans effet sur le mot de passe, la disposition classique et les pavés.

**Indices.** Une touche qui a un menu d'appui long affiche dans son coin un petit indice (contraste ≥ 3:1, §13) : sur la couche `?123`, le premier symbole voisin (`_` sur `-`, `=` sur `+`…) ; `⇪` sur `ABC` ; `…` sur `?123` quand son menu n'est pas vide. Les lettres n'en ont pas (variantes accentuées attendues). Les réglages ont une section « Gestes » qui résume les gestes du §5.9 (issue #21).

La touche `↵` est contextuelle d'après `imeOptions` : envoyer, rechercher, aller, suivant, terminé, ou saut de ligne si le champ est multiligne et n'a pas d'action. Libellés : `➤` envoyer, `⌕` rechercher, `➔` aller, `⇥` suivant, `⇤` précédent, `✓` terminé, `↵` saut de ligne, touche Entrée ou action inconnue (`EnterKeyLabel`). Même règle sur toutes les dispositions (mot de passe, pavés).

### 5.2 Adaptation visuelle

Après chaque frappe validée (`ACTION_UP`), le moteur calcule `r(c)` pour les 26 lettres et `P(fin | p)`.

**Modes (réglage utilisateur) :**

| Mode | Opacité | Taille du libellé | Largeur | Défaut |
|---|---|---|---|---|
| Calme | oui, légère | non | non | |
| **Calme renforcé** | oui | légère | oui, plafonnée faible | **✓** |
| Élastique | oui, forte | oui | oui, plafonnée à `adapt.maxDisplacement` | |
| Désactivé | non | non | non | |

**Formules initiales (à calibrer) :**

```
s(c)        = sqrt(r(c))
opacité(c)  = r(c) == 0 ? adapt.minOpacity (0,30) : 0,55 + 0,45 · s(c)
largeur(c)  = r(c) == 0 ? adapt.minWidth (0,6 u) : 0,8 + adapt.gain · s(c)     (normalisée sur la rangée)
espace      = largeur de repos + adapt.spaceGain · P(fin | p)
```

La normalisation par rangée conserve la largeur totale ; le solveur ajuste ensuite les largeurs pour respecter le plafond de déplacement (§2.2).

**Cas particuliers :**

- *Début de mot* (`p` vide) : les poids viennent de la distribution des initiales, conditionnée par le contexte (mot précédent) si disponible. L'adaptation est atténuée de moitié (confirmé par l'analyse M0 : distribution des initiales presque plate, 4,2 bits, lettre tapée dans le top 3 une fois sur trois ; `docs/analysis/M0-corpus.md`).
- *Préfixe inconnu* (aucun mot ne commence par `p`) : on tente une correction à distance d'édition 1 sur le préfixe ; si elle ne donne rien, le clavier revient à l'état neutre (toutes les touches au repos).
- *Animation* : 100 ms, `ease-out`. Elle est supprimée si l'utilisateur a désactivé les animations système (`ANIMATOR_DURATION_SCALE == 0`).
- *Saisie rapide* : si l'intervalle entre deux frappes est inférieur à `adapt.fastTypingMs` (défaut : 120 ms), la mise à jour visuelle est différée jusqu'à une pause, pour ne pas faire clignoter le clavier. Le décodage (§5.3), lui, reste toujours actif.

### 5.3 Décodage du tap

Le point touché n'est pas associé à « la touche sous le doigt » mais à la lettre la plus probable :

```
score(c) = P(point | c) · P(c | p, contexte)^λ
P(point | c) = α · N(point ; centre_affiché(c), σ) + (1 − α) · N(point ; centre_repos(c), σ)
```

- Le mélange `α` reflète le fait qu'un utilisateur rapide vise de mémoire (position de repos) et un utilisateur lent vise ce qu'il voit. Valeur initiale : `α = 0,5`.
- `σ` est estimé par touche à partir de l'historique des frappes de l'utilisateur (modèle spatial personnalisé, hors mode incognito).
- **Modèle spatial personnalisé** (`nextglyph.engine.learn.SpatialModel`, `docs/DECISIONS.md`, 2026-11-19). Pour chacune des 26 lettres, estimation en ligne du décalage moyen (x, y) et de la dispersion des frappes **validées** : une frappe est mise en attente, intégrée quand le mot est validé sans annulation, écartée si la lettre est effacée ou le mot annulé. Les valeurs sont relatives à la touche au repos (fractions de sa largeur et de la hauteur de rangée) ; une frappe hors du rectangle de sa touche est ignorée. Le décodeur décale les deux centres du décalage appris et multiplie `σ` par un facteur `[0,7 ; 1,6]` (dispersion mesurée / 0,23) ; décalage borné à 0,25 touche ; estimation ramenée vers le défaut (décalage 0, facteur 1) tant que l'échantillon est petit (`n / (n + 20)`) ; fenêtre de 300 frappes par lettre. Un modèle vide ne change pas le décodage. Aucun apprentissage en incognito, mot de passe, code PIN et pavés numériques.
- `λ` (défaut : 0,6) limite l'influence du modèle de langue : un tap franchement au centre d'une touche atténuée doit produire cette lettre.
- Le mode mot de passe et les pavés numériques utilisent `λ = 0` (décodage purement spatial).

### 5.4 Barre de suggestions

La barre a trois états :

| État | Contenu |
|---|---|
| Mot en cours | 3 suggestions. Au centre, le mot que l'espace insérera (surligné s'il diffère du texte tapé : candidat dominant, accent ou majuscule restitués) ; à gauche, le texte tapé quand il serait remplacé, pour le garder ; sinon les meilleurs candidats. Les variantes d'un mot tapé (`à` pour `a`, `it's` pour `its`, `j'aime` pour `jaime`) y sont proposées sans jamais être insérées automatiquement. |
| Juste après une validation | Ponctuation (§5.6). |
| Champ vide / début de texte | Mots probables en début de phrase (prédiction du mot suivant, V2), sinon vide. |

Un tap sur une suggestion valide ce mot et ajoute une espace automatique. Glisser vers le haut sur l'espace fait de même avec la suggestion centrale (§5.9), sans viser la barre.

**Menu rapide `⚙`** (issue #27). À gauche de la barre, un bouton `⚙` (40 dp) ouvre dès l'appui un menu sur la première rangée : `⚙` (ouvre les réglages ; un simple tap le choisit), le mode d'adaptation suivant (Fixe → Calme → Renforcé → Élastique), l'autre disposition (AZERTY ↔ QWERTY), et « Classique » (échappatoire du §4) dans les profils qui la proposent. On glisse jusqu'à la case et on relâche, comme pour un menu d'appui long. Les choix sont enregistrés comme réglages (§14) et appliqués aussitôt. Les trois cases de suggestions se partagent le reste de la largeur.

### 5.5 Validation par espace

À l'appui sur espace, avec un mot en cours `t` :

1. **`t` est un mot du lexique** (d'au moins une langue active, §10.6) → on insère `t`, avec restitution des accents si elle est sans ambiguïté (`ecole` → `école`) : une orthographe représente au moins 95 % des occurrences de la clé dans chaque lexique actif qui la contient, et ces lexiques s'accordent (les variantes rares des sources, comme `écolé`, ne bloquent pas la restitution). Les cas ambigus (`a`/`à`, `ou`/`où`, `du`/`dû`, `la`/`là`) sont insérés tels quels ; les variantes accentuées restent dans la barre et sont choisies par le contexte quand le modèle de bigrammes est disponible. La casse est restituée de même (§5.7). Un mot tapé avec un accent (forme forcée, §5.8) n'est jamais modifié.
2. **`t` n'est pas un mot, et un candidat `w` domine** → on insère `w`. Domine signifie :
   `P(w) ≥ autocommit.minProb` (défaut : 0,70) **et** `P(w) / P(2e candidat) ≥ autocommit.minRatio` (défaut : 2,0).
   Les candidats comprennent les complétions de `t` **et** ses corrections (§10.4), **même si `t` est un préfixe connu** : une faute forme souvent un autre préfixe valide (`ke` pour `le`), dont la complétion dominerait sinon à tort. Réglage « Prudence de la validation » (§14) : `minProb` = 0,80 (Prudente), 0,70 (Normale), 0,60 (Audacieuse). Valeurs mesurées au §15.1 (`docs/analysis/M0-corpus.md`) : avec 5 % de fautes simulées, au plus 2,1 % de fausses validations en réglage Normal.
   Ce cas couvre la complétion (`bonj` → `bonjour`), la correction (`bonjoru` → `bonjour`), les élisions (`jai` → `j'ai`, `cest` → `c'est`, `lecole` → `l'école`), les traits d'union (`peutetre` → `peut-être`) et les contractions anglaises (`dont` → `don't`, `im` → `I'm`, `youre` → `you're`).
   Apostrophe ou trait d'union omis : édition de poids 0,5 (§10.4). Élision française omise (`t = e + r`, `e` parmi `c d j l m n s t qu jusqu lorsqu puisqu quoiqu`, `r` commençant par une voyelle ou un `h`) : candidat `e'w` pour chaque complétion `w` de `r` (seulement `e'r` si `r` est un mot), de score `0,5 × f(e') / masse des mots à initiale voyelle ou h × f(w)`.
   Les contractions anglaises qui sont aussi des mots valides (`well`/`we'll`, `its`/`it's`, `were`/`we're`, `ill`/`I'll`, `cant`/`can't`, `id`/`I'd`) relèvent du cas 1 : jamais de remplacement automatique en V1, la variante reste proposée dans la barre.
3. **Sinon** → on insère `t` tel quel. Le mot n'est **pas** ajouté au dictionnaire personnel à ce stade (cf. §10.5).

Puis une espace automatique est insérée.

**Prévisibilité :** dès que l'espace insérera autre chose que le texte tapé (candidat dominant, accent ou majuscule restitués), la touche espace affiche `␣ <mot>` et le mot est surligné dans la barre.

**Annulation :** un appui sur ⌫ immédiatement après une validation automatique (curseur inchangé, aucune autre frappe) remplace le mot inséré par le texte tapé à l'origine, sans espace. Un deuxième ⌫ efface normalement. Une validation suivie d'un signe de ponctuation (§5.6) n'est plus annulable ainsi : le ⌫ efface d'abord le signe.

**Apprentissage du refus :** si une même correction `t → w` est annulée `autocommit.rejectLimit` fois (défaut : 2), elle n'est plus proposée automatiquement (elle reste dans la barre). Il en va de même d'une restitution d'accent ou de majuscule annulée (`ecole → école`). Les compteurs (au plus 2 000 paires, la moins récemment refusée étant oubliée la première) sont enregistrés avec le reste de l'état appris (`PersistentRejectionStore`, §10.5) ; aucun compteur n'est écrit en incognito ni dans le profil mot de passe.

**Désactivation :** la validation par espace est inactive dans les profils mot de passe, e-mail, URL, numériques, avec `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, et via un réglage utilisateur.

**Noms de contacts :** un nom ne peut être candidat dominant que si le contexte le justifie (§6.3). Il ne remplace jamais un mot courant valide.

### 5.6 Ponctuation

**Barre de ponctuation.** Juste après une validation, la barre affiche : `,` `.` `?` `!` `:` `…` `'`.

**Remplacement de l'espace automatique.** Si le caractère avant le curseur est l'espace automatique que le clavier vient d'insérer (état mémorisé et vérifié via `getTextBeforeCursor`), un tap sur un signe :
1. supprime l'espace automatique ;
2. insère le signe selon les règles typographiques ci-dessous ;
3. insère une nouvelle espace automatique (sauf pour `'`).

Exemple : `bonjour␣` puis `,` → `bonjour, `. Coût : 2 taps, comme la séquence classique.

**Typographie française (réglage, actif par défaut ; appliqué seulement quand la langue estimée de la phrase est le français, cf. §10.6) :**

| Signe | Espace avant | Espace après |
|---|---|---|
| `,` `.` `…` | aucune | espace |
| `;` `!` `?` | fine insécable U+202F | espace |
| `:` | insécable U+00A0 | espace |
| `«` | — | fine insécable U+202F |
| `»` | fine insécable U+202F | espace |

**Double espace :** deux espaces successives remplacent l'espace automatique par `. ` et activent la majuscule pour le mot suivant.

**Précisions d'implémentation (`nextglyph.engine.edit`, voir `docs/DECISIONS.md`, 2026-11-19) :**
- L'espace automatique n'est ajoutée qu'en fin de texte (rien après le curseur) et jamais après une élision ou un trait d'union final ; elle n'existe pas dans les profils e-mail, URL, mot de passe.
- `«` conserve l'espace qui le précède (`dit «`) et n'est pas suivi d'espace automatique ; `»` remplace l'espace automatique.
- Pas d'insécable en début de champ ni de ligne, ni derrière une espace déjà présente, ni entre deux signes (`?!`, `...!`). Une espace tapée par l'utilisateur n'est jamais remplacée.
- Le double espace s'applique à une espace ordinaire (automatique ou tapée) précédée d'une lettre, d'un chiffre ou d'un guillemet ou parenthèse fermant ; la majuscule suivante dépend du réglage du champ (`CAP_SENTENCES`).
- **Espace absorbée** : une espace tapée juste après l'espace automatique qui suit un signe (`. , ; : ! ? …`) n'en ajoute pas une deuxième (`va ? ` puis espace reste `va ? `) ; une seule fois, la suivante est insérée.

**Accès aux autres signes :**
- Appui long ou glissement vers le haut sur `?123` : menu rapide `- ( ) " ; @ #`.
- Couche `?123` : chiffres et symboles complets, sur 3 rangées, même hauteur que la couche lettres.

**Pendant un mot en cours,** un tap sur un signe de la couche `?123` valide d'abord le mot (règles du §5.5) puis applique le signe.

### 5.7 Majuscules

- **Automatiques** selon `TYPE_TEXT_FLAG_CAP_SENTENCES` / `_WORDS` / `_CHARACTERS` et `InputConnection.getCursorCapsMode()` : début de champ, après `. ? ! …` suivis d'une espace.
- Détail : début de ligne = début de phrase ; guillemets et parenthèses fermants peuvent s'intercaler entre le signe et l'espace ; pas de majuscule après une abréviation (liste courte, commune FR/EN, sans point final : `m mm mme mmes mlle mlles mr mrs ms dr pr prof me st ste sr jr vs etc cf env approx e.g i.e u.s`). `_WORDS` : après une espace ou un saut de ligne ; `_CHARACTERS` : toujours. Aucune majuscule automatique en e-mail, URL, mot de passe (§9.1).
- **Manuelles** : glissement vers le haut sur une lettre → majuscule pour cette lettre.
- **Verrouillage** : depuis la couche `?123` (appui long sur `ABC` : `⇪`, la couche n'ayant pas de touche `⇪`). L'état verrouillé est affiché en permanence (`⇪` sur `?123` et `ABC`, lettres en majuscules).
- Les noms de contacts reçoivent leur majuscule à la validation (§6).
- Anglais : le pronom `i` isolé devient `I`, ainsi que ses contractions (`I'm`, `I'll`, `I've`, `I'd`). Les jours, les mois et les noms de langues portent leur majuscule dans le lexique anglais (`monday` → `Monday`).

### 5.8 Accents, élisions, traits d'union

- Le lexique est indexé sur des formes normalisées (sans accents, `œ` → `oe`, `æ` → `ae`). La forme d'affichage est restituée à la validation.
- Appui long sur une voyelle ou sur `c` : menu des variantes accentuées (`é è ê ë`, `ç`, etc.), pour forcer une forme.
- L'apostrophe et le trait d'union sont des caractères du lexique (`aujourd'hui`, `peut-être`) et sont insérés automatiquement par la validation (§5.5).
- Après une élision validée (`l'`, `d'`, `j'`, `qu'`, `n'`, `c'`, `s'`, `m'`, `t'`), le mot suivant commence sans espace.

### 5.9 Gestes

| Geste | Effet |
|---|---|
| Glisser vers le haut sur une lettre | Majuscule |
| Glisser vers le haut sur l'espace | Insère la suggestion centrale, même si elle ne domine pas (comme un tap sur elle), puis une espace ; sans suggestion, une espace |
| Glisser vers la gauche depuis ⌫ | Efface le mot précédent (répété en continuant) |
| Glisser horizontalement sur l'espace | Déplace le curseur |
| Appui long sur `?123` | Menu ponctuation rapide / panneau « PC » (§5.10) / « Disposition classique » |
| Appui long sur une lettre | Variantes accentuées |
| Appui long sur `↵` | Panneau des émojis (§5.11), dans les profils qui affichent la barre de suggestions |

### 5.10 Panneau des touches PC

Pour les usages techniques (SSH, RDP, TeamViewer ; issue #28). Ouvert depuis le menu d'appui long de `?123` (case « PC », profils à disposition compacte), il remplace la couche `?123` ; `ABC` ramène aux lettres.

```
Rangée 1 : Échap Tab Ctrl Alt Début Fin PgPr PgSv Inser Suppr        10 × 1 u
Rangée 2 : F1 … F10                                                  10 × 1 u
Rangée 3 : ABC | F11 F12 ← ↑ ↓ → | C+A+Sup | ⌫                       1,5 + 6 + 1,5 + 1 = 10 u
```

- Chaque touche envoie un vrai événement clavier (`InputConnection.sendKeyEvent`, appui puis relâchement), après validation du mot en cours tel quel.
- `Ctrl` et `Alt` sont **collants** : un tap les active pour la prochaine touche, deux taps rapides les verrouillent, un tap les relâche ; actifs, ils sont affichés enfoncés. Pendant qu'un modificateur est actif, une lettre ou un chiffre tapé sur les lettres ou la couche `?123` part en événement clavier avec le modificateur (`Ctrl+C`, `Ctrl+V`, `Alt+F4`…). Les modificateurs sont relâchés à chaque changement de champ.
- `C+A+Sup` envoie `Ctrl+Alt+Suppr` en une touche ; les clients RDP l'interceptent souvent et ont alors leur propre bouton.
- Ni adaptation, ni suggestions, ni apprentissage sur le panneau.

### 5.11 Panneau des émojis

Issue #26. Ouvert par un appui long sur `↵` (un indice `☺` dans le coin de la touche le signale), seulement dans les profils qui affichent la barre de suggestions (texte : jamais en mot de passe, pavés ni URL). Il remplace la barre et les touches, à hauteur égale :

- **Onglets** (hauteur de la barre) : `ABC` (retour aux lettres), `🕘` (récents), un onglet par groupe Unicode (son premier émoji ; le groupe « composants » est exclu), `⌫` (efface comme la touche).
- **Grille** de 8 × 3 émojis ; glisser horizontalement change de page (numéro `n/N` en bas à droite). Un tap insère l'émoji au curseur, après validation du mot en cours tel quel.
- **Récents** : 32 au plus, le plus récent d'abord, enregistrés dans les préférences ; jamais alimentés en incognito ni dans un champ sans apprentissage (§9.2). Le panneau s'ouvre sur les récents s'il y en a, sinon sur le premier groupe.
- **Données** : l'asset `emoji.txt` (groupe, émoji, version), généré par `./gradlew :tools:buildEmoji` depuis le paquet `unicode-emoji-json` (`docs/DATA.md`). Au chargement, seuls les émojis que la police du système sait dessiner (`Paint.hasGlyph`) sont proposés.
- Pas de recherche par mot-clé ni de choix de couleur de peau en V1. Accessibilité : chaque onglet et chaque émoji est un nœud virtuel.

---

## 6. Noms et prénoms des contacts

### 6.1 Permission

- `READ_CONTACTS`, **optionnelle**, demandée depuis l'activité de réglages (un `InputMethodService` ne peut pas afficher lui-même la demande).
- Le clavier fonctionne normalement sans cette permission.
- Un réglage permet de désactiver la fonctionnalité et d'effacer immédiatement le sous-lexique contacts.

### 6.2 Collecte

- Source : `ContactsContract.Data` avec `StructuredName` (prénom, nom, deuxième prénom) ; `Nickname` en option.
- Mise à jour : `ContentObserver` sur `ContactsContract.Contacts.CONTENT_URI`, avec reconstruction en arrière-plan.
- Le sous-lexique est séparé du lexique principal et **n'est jamais écrit sur le disque** : les noms sont relus du carnet à chaque démarrage du clavier et à chaque modification, puis gardés en mémoire seulement (`docs/DECISIONS.md`, 2026-11-19, « M4 dans l'IME »). Couper la fonction (ou retirer la permission) les efface aussitôt.
- Lecture : prénom, nom et deuxième prénom (`StructuredName`), surnom (`Nickname`), lignes réunies par contact ; au plus 20 000 contacts. Aucun nom n'est journalisé.

### 6.3 Utilisation

- Chaque nom est indexé sous forme normalisée (`helene`) avec sa forme d'affichage (`Hélène`). Les noms composés (`Jean-Pierre`) sont indexés en entier.
- Poids de base : équivalent à un mot de fréquence moyenne (`contacts.baseShare`, 1e-5 de la masse du lexique), croissant avec le nombre de contacts qui portent le nom (`1 + ln n`, plafonné à 3).
- **Bonus de contexte** (`contacts.contextBoost`, multiplicatif) :
  - champ de type `TYPE_TEXT_VARIATION_PERSON_NAME` ;
  - début de message ou de champ ;
  - après « bonjour », « salut », « coucou », « cher », « chère », « avec », « chez », « à », « pour », « @ » ;
  - premier caractère tapé en majuscule ;
  - après un autre nom de contact (prénom → nom).
- Un nom qui est aussi un mot courant (« Rose », « Pierre », « Marie », « Olive ») n'est mis en majuscule que si le contexte le justifie ; sinon le mot courant l'emporte.
- **Lecture du contexte** (`ContactContext.read`) : le mot en cours est écarté ; s'il ne reste que des blancs, c'est un début de message ; sinon le mot précédent est la dernière suite de lettres, apostrophes et traits d'union, une virgule étant sautée (« Bonjour, Gw ») ; un `@` est lui-même le mot précédent ; tout autre signe : pas de mot précédent. « Majuscule tapée » : première lettre du mot en capitale sans que la majuscule automatique l'impose.
- **Réalisation** (`nextglyph.engine.contacts`, `docs/DECISIONS.md`, 2026-11-19). Chaque indice vrai ajoute un niveau (0 à 2) ; le poids des noms est multiplié par `contacts.contextBoost`^niveau (4 : ×1, ×4, ×16). Mots déclencheurs français et anglais (`bonjour`, `salut`, `coucou`, `cher`, `chère`, `avec`, `chez`, `à`, `pour`, `@`, `hello`, `hi`, `hey`, `dear`, `with`, `to`, `for`, `from`). « Majuscule tapée » : majuscule de l'utilisateur, pas la majuscule automatique de début de phrase. Au niveau 0, un nom qui est aussi un mot du lexique pèse au plus le quart de la fréquence de ce mot, et aucun nom ne peut être inséré par complétion ou correction (§5.5, cas 2) ; il reste proposé dans la barre. Les particules (`de`, `van`...) ne sont pas indexées ; les noms sont communs aux deux langues.

---

## 7. Profil Mot de passe

### 7.1 Disposition

```
Rangée 1 : @ # $ % & * - _ ! ?
Rangée 2 : 1 2 3 4 5 6 7 8 9 0
Rangée 3 : a z e r t y u i o p
Rangée 4 : q s d f g h j k l m
Rangée 5 : ⇧ w x c v b n ⌫
Rangée 6 : ?123 , espace . ↵
```

- QWERTY : mêmes rangées 1 et 2 ; rangée 3 `q w e r t y u i o p`, rangée 4 `a s d f g h j k l` (retrait de 0,5 u, lettres de 19/18 u pour tenir 10 u), rangée 5 `⇧ z x c v b n m ⌫` (`⇧` et `⌫` à 1,5 u).
- AZERTY, rangée 5 : `⇧` 2 u, 6 lettres de 1 u, `⌫` 2 u. Rangée 6 : `?123` 1,5 u, `,` 1 u, espace 4,5 u, `.` 1 u, `↵` 2 u. Toutes les touches font au moins 1 u (36 dp à 360 dp, §13).
- Couche `?123` du mot de passe, 3 rangées : `[ ] { } < > ^ ~ | \`, `/ + = ' " ; : € £` suivi d'un accent grave, puis `ABC` (1,5 u), `( ) § ° ¥ ¢`, `⌫` (2,5 u).
- Hauteur indicative : ~ 230 dp. La compacité passe ici après la fiabilité.
- `⇧` explicite : un appui = une majuscule, double appui = verrouillage (second appui dans le délai de double appui du système, 300 ms par défaut), un appui sur la majuscule active ou verrouillée l'annule. État toujours visible : touche `⇧` surlignée quand la majuscule est active, libellé `⇪` quand elle est verrouillée, lettres affichées en majuscules.
- Pas de barre au-dessus des touches (ni suggestions ni ponctuation à proposer) ; la couche `?123`, plus courte, est alignée en bas (`ABC` à la place de `?123`). Pas de menu d'appui long sur `?123` (tous les symboles sont sur la disposition ou sa couche).
- `⌫` envoie la touche Retour arrière (`KEYCODE_DEL`) sans lire le champ.
- `?123` donne accès aux symboles restants (`[ ] { } < > ^ ~ | \ / + = ' " ; : €  £`, etc.).

### 7.2 Comportements

| Fonction | État |
|---|---|
| Adaptation visuelle | Désactivée |
| Décodage par modèle de langue | Désactivé (`λ = 0`) |
| Suggestions, validation par espace | Désactivées |
| Majuscule automatique | Désactivée |
| Apprentissage (lexique et modèle spatial) | Désactivé |
| Aperçu agrandi de la touche | Désactivé par défaut (réglage) |
| Historique de presse-papiers | Non alimenté |
| Retour haptique | Conservé |

Aucune donnée tapée dans ce profil n'est conservée en mémoire au-delà de la frappe, ni journalisée. Le clavier ne lit jamais le texte du champ (ni avant ni après le curseur) : chaque touche est transmise telle quelle (`commitText`, `KEYCODE_DEL`, action de l'éditeur). Vérifié par test instrumenté : aucune écriture dans le stockage de l'application après une saisie (§15.2).

---

## 8. Profils numériques

Règle commune : **seuls les caractères acceptés par le champ sont proposés.** Aucune bascule vers les lettres. Décodage purement spatial, pas d'apprentissage. Touches de 44 dp de haut.

### 8.1 Numérique (`TYPE_CLASS_NUMBER`)

```
1 2 3  −
4 5 6  ,
7 8 9  ⌫
  0    ↵
```

- `−` n'apparaît qu'avec `TYPE_NUMBER_FLAG_SIGNED`. La touche insère le trait d'union ASCII `-` (U+002D) : les champs numériques d'Android refusent le signe moins typographique U+2212 (constaté sur Android 16).
- Le séparateur décimal n'apparaît qu'avec `TYPE_NUMBER_FLAG_DECIMAL`. Il affiche le séparateur de la locale (`,` en français). **Stratégie d'insertion :** on insère `,` ; si `getTextBeforeCursor` montre que le champ l'a rejeté, on insère `.` et on mémorise ce choix pour ce champ (paquet + id du champ) ; la touche affiche alors `.`. Constat sur Android 16 en `fr-FR` : un `EditText` `numberDecimal` refuse `,` (filtre `DigitsKeyListener` sans locale) ; le repli sur `.` est donc le cas courant des champs natifs. À valider sur un panel d'applications.
- Les emplacements des touches absentes restent vides : la position des chiffres ne change jamais.
- Géométrie : 4 colonnes de 2,5 u, 4 rangées de 44 dp. Rangée 4 : emplacement vide, `0`, emplacement vide, `↵`. `−` est en colonne 4 rangée 1, le séparateur en colonne 4 rangée 2, `⌫` en colonne 4 rangée 3.
- **Insertion du séparateur** (`DecimalSeparatorPolicy`) : on tente `,` ; le refus se lit au texte avant le curseur (inchangé, ou ne finissant pas par le caractère). On ne conclut au refus que si le nombre en cours n'a pas déjà de séparateur (sinon c'est peut-être le second séparateur qui est refusé) ; sans accès au texte, l'insertion est supposée réussie. Si les deux sont refusés, rien n'est inséré. La mémoire (paquet + id du champ) est volatile et bornée à 256 champs ; un champ mémorisé sur `.` qui accepte de nouveau `,` est oublié.

### 8.2 Téléphone (`TYPE_CLASS_PHONE`)

Pavé 3 × 4 standard (`1`–`9`, `*`, `0`, `#`), plus deux colonnes (5 colonnes de 2 u au total) : `+` (aussi via appui long sur `0`), `,` (pause), `;` (attente) en colonne 4, rangées 1 à 3 ; `⌫` en colonne 5 rangée 1 ; `↵` en colonne 5 rangée 4 ; emplacements vides ailleurs.

### 8.3 Date / heure (`TYPE_CLASS_DATETIME`)

Pavé 3 × 4 (mêmes positions que le §8.1) avec, selon la variation : `/` (colonne 4 rangée 1) et `-` (colonne 4 rangée 2) pour la date, `:` (colonne 3 rangée 4) pour l'heure, ou les trois (date et heure, variation normale) ; `⌫` colonne 4 rangée 3, `↵` colonne 4 rangée 4. Les séparateurs hors variation laissent un emplacement vide.

### 8.4 Code PIN (`TYPE_NUMBER_VARIATION_PASSWORD`)

Pavé du §8.1 sans `−` ni séparateur, avec toutes les règles du profil mot de passe (§7.2). Aperçu de touche désactivé sur tous les pavés (les touches font 90 dp de large).

Tous les pavés : ni barre au-dessus des touches, ni menu d'appui long, ni couche `?123` ; les emplacements vides ne sont pas dessinés et ne réagissent pas. Aucune lecture du champ, sauf la vérification du séparateur décimal (§8.1).

---

## 9. Autres profils

### 9.1 E-mail et URL

- Disposition compacte du §5.1, adaptation visuelle active sur le lexique des domaines et des contacts (e-mail) ou des domaines courants (URL). En attendant ce lexique (M4), la disposition reste au repos dans ces champs.
- Barre permanente : `@` `.` `.com` `.fr` `-` `_` (e-mail) ou `/` `.` `.com` `.fr` `-` (URL).
- Pas de validation par espace, pas d'espace automatique, pas de majuscule automatique, pas de typographie française.

### 9.2 Incognito

Défini par `IME_FLAG_NO_PERSONALIZED_LEARNING` ou forcé par l'utilisateur. Le comportement est celui du profil sous-jacent, sans aucune écriture : ni dictionnaire personnel, ni modèle spatial, ni compteurs de refus.

---

## 10. Moteur linguistique

### 10.1 Lexique

- Structure : trie compact (arbre radix) sur les formes normalisées ; choix, format et mesures dans `docs/DECISIONS.md` (2026-11-19).
- Un lexique peut être réparti en plusieurs fichiers d'une même langue (sources sous licences différentes, dictionnaire personnel, contacts), fusionnés à la lecture sans copie : les fréquences d'une même forme s'additionnent, comme les masses des préfixes (`LexiconSet`, `docs/DECISIONS.md`, 2026-11-19). Le français est livré en deux fichiers, un par licence : `fr-lexique.nglx` (Lexique 4.10, orthographes sans casse et fréquences) et `fr-dicollecte.nglx` (formes de Dicollecte, fréquence plancher). Un membre « sans casse » donne la fréquence d'une orthographe : elle va à la forme minuscule des autres membres si elle existe, sinon à leurs variantes de casse (`docs/DATA.md`).
- Chaque nœud stocke la somme des fréquences de son sous-arbre et la fréquence maximale d'une forme du sous-arbre (complétions best-first) ; chaque nœud terminal stocke ses formes d'affichage avec leur fréquence.
- Ainsi, `P(c | p)` et `P(fin | p)` se calculent en O(1) par lettre après la descente sur `p`.
- Format binaire mappé en mémoire (`mmap`), construit hors ligne par `tools/`.
- Ordre de grandeur : 300 000 à 600 000 formes fléchies pour le français, environ 150 000 à 250 000 pour l'anglais ; cible ≤ 8 Mo sur disque par langue.

### 10.2 Sources de données

| Langue | Source | Usage | Licence |
|---|---|---|---|
| FR | Lexique 4.10 (successeur de Lexique 3) | Formes fléchies, fréquences | CC BY-SA 4.0 (voir `DATA.md`) |
| FR | Dictionnaires Hunspell Dicollecte | Couverture du vocabulaire | MPL-2.0 (voir `DATA.md`) |
| EN | SCOWL (Hunspell en_US) | Couverture du vocabulaire | Permissive (voir `DATA.md`) |
| FR | Fréquences de sous-titres (Lexique 4.10) ; EN : aucune source conversationnelle claire, repli sur Tatoeba | Fréquences | Voir `DATA.md` |
| FR, EN | Dictionnaires AOSP / HeliBoard | Comparaison, bigrammes | **À vérifier** |

Le code de Nextglyph est sous Apache-2.0. Règles pour les données :
- une donnée sous licence permissive (Apache, MIT, BSD, domaine public) peut être intégrée librement, avec attribution ;
- une donnée sous CC BY ou CC BY-SA peut être distribuée **à côté** du code, dans un fichier distinct qui garde sa propre licence, avec attribution dans `NOTICE` et dans l'écran « À propos » ;
- une donnée sous GPL ou LGPL n'est pas intégrée, car elle imposerait sa licence à l'ensemble.

La licence de chaque source doit être confirmée, ainsi que la compatibilité avec une distribution sur F-Droid, avant d'intégrer la donnée au dépôt.

### 10.3 Contexte

- V1 : `P(c | p)` conditionné uniquement par le préfixe.
- V2 : modèle de bigrammes de mots (« mot précédent → mot »), utilisé pour pondérer les initiales, départager les ambiguïtés d'accent (`a`/`à`) et prédire le mot suivant.

### 10.4 Correction

- Candidats par distance d'édition pondérée par la géométrie du clavier (une substitution entre touches voisines coûte moins cher).
- Limite V1 : distance 1 sur le préfixe, distance 2 sur le mot complet à la validation. M2 n'implémente que la distance 1, y compris à la validation (méthode et poids de l'analyse M0 : substitution par une touche voisine 0,01, autre substitution 0,0005, omission, lettre en trop, inversion 0,002, apostrophe ou trait d'union omis 0,5) ; l'objectif de moins de 3 % de fausses validations est atteint sans la distance 2 (`docs/analysis/M2-engine.md`).
- Pour l'adaptation visuelle (§5.2), la correction n'est tentée que si le préfixe est inconnu. À la validation par espace (§5.5), elle est tentée aussi quand le texte tapé est un préfixe connu qui n'est pas un mot : sans cela, l'objectif de moins de 3 % de validations annulées (§15.2) n'est pas atteint avec 5 % de fautes de frappe (analyse M0).

### 10.5 Dictionnaire personnel

- Un mot inconnu validé tel quel est mis en attente ; il entre dans le dictionnaire personnel après `learn.minOccurrences` (défaut : 2) validations sans annulation.
- Un appui long sur une suggestion permet de la supprimer du dictionnaire personnel (si elle y est ; un message le confirme).
- **Dans la session** (`SessionLearning`) : un mot est observé quand il est validé par espace, par ponctuation ou par un choix dans la barre (le texte tapé gardé alors qu'un candidat dominait compte comme inconnu). Un ⌫ juste après une validation (annulation du §5.5, ou correction du mot) oublie le mot en attente et écarte ses frappes. Les frappes d'un mot validé ne sont intégrées au modèle spatial qu'au début du mot suivant, à la sortie du champ ou à `↵` ; une lettre effacée retire la sienne ; un déplacement du curseur écarte celles du mot en cours. Dans un champ sans validation par espace (e-mail, URL), les frappes sont intégrées à la fin de chaque mot.
- **Persistance dans l'IME** : fichier `learned.ngls` du stockage privé non sauvegardé (`noBackupFilesDir`), écrit en arrière-plan (fichier temporaire synchronisé puis renommé), environ 3 s après un changement et à la sortie du champ ; jamais sur le fil de la frappe. Lu en arrière-plan au démarrage ; un fichier temporaire abandonné est supprimé.
- Stockage : fichier local dans le stockage privé (`LearnedDataFile` : dictionnaire personnel, mémoire des refus, modèle spatial ; binaire versionné, une somme de contrôle par section, lecture tolérante : un fichier corrompu ou d'une version inconnue donne un état vide et un signalement, jamais un plantage). Export/import manuels depuis les réglages en TSV lisible (`PersonalDictionaryTsv` : `langue<TAB>mot<TAB>utilisations`).
- Plafonds : 2 000 mots promus (le moins récemment utilisé est évincé) et 500 mots en attente (le moins récemment vu est oublié) ; 2 à 32 caractères, lettres, apostrophes et traits d'union ; majuscule initiale due au début de phrase ramenée en minuscules. L'annulation d'une validation oublie le mot en attente ; une suppression n'empêche pas de l'apprendre de nouveau. Rien n'est appris en incognito, mot de passe, e-mail, URL, champ numérique ni avec `NO_SUGGESTIONS`.
- Intégration : les mots promus forment un lexique supplémentaire de la langue du mot dans le `LexiconSet` (fréquence : 1e-5 de la masse du lexique × `min(4, 1 + ln utilisations)`) ; il est reconstruit quand l'ensemble des mots change. Sans mot appris, la prédiction est exactement celle du lexique principal.
- Chaque mot appris est rattaché à la langue estimée au moment de l'apprentissage (§10.6).

### 10.6 Bilinguisme français-anglais

Beaucoup d'utilisateurs alternent les deux langues, parfois dans une même phrase. Nextglyph ne demande donc pas de « changer de langue » : les deux lexiques sont actifs en même temps.

- **Score de langue.** Une estimation `π_FR` / `π_EN` est tenue à jour à partir des derniers mots validés (fenêtre glissante, défaut : 4 mots), en partant de la langue préférée de l'utilisateur.
- **Mélange.** `P(c | p) = π_FR · P_FR(c | p) + π_EN · P_EN(c | p)`, les poids étant renormalisés sur les langues où `p` est un préfixe connu. Le même mélange s'applique à `P(fin | p)`. Pour les candidats de la validation, `P(w) = Σ_L π_L · score_L(w) / Σ_L π_L · total_L`, les scores étant rapportés à la masse de chaque lexique. Le score évolue progressivement : pas de bascule brutale de l'adaptation visuelle.
- **Indice d'un mot validé.** Chaque mot apporte au score un poids par langue proportionnel à sa fréquence relative dans chaque lexique : `(f_FR, f_EN) / (f_FR + f_EN)`, où `f_L` est la fréquence du mot divisée par la masse totale du lexique `L` ; un mot inconnu des deux lexiques n'apporte rien. Les **mots communs aux deux langues** (`table`, `question`, `important`, mais aussi `a`, `on`, `son`) comptent donc pour les deux, selon leur fréquence dans chacune. Mesure M0 : 92,6 % de mots avec la bonne langue dominante et 2,5 mots pour suivre un changement de langue, contre 86,9 % et 4,4 mots avec une fenêtre de 8 mots et un simple test d'appartenance (`docs/analysis/M0-corpus.md`).
- **Restitution des accents** : uniquement à partir du lexique français. Une forme sans accent qui est un mot anglais valide (`resume`) n'est jamais accentuée automatiquement ; la variante accentuée reste proposée dans la barre. Un mot que le lexique anglais écrit lui aussi avec l'accent (`café`) est restitué.
- **Règles propres à chaque langue** (typographie française, majuscule de `I`, élisions, contractions) : appliquées selon la langue de la phrase en cours, c'est-à-dire la langue dominante du score restreint aux mots validés depuis la dernière ponctuation finale, avec pour a priori le score au début de la phrase (sans mot validé dans la phrase : la langue dominante). Une langue verrouillée est aussi la langue de la phrase.
- **Indicateur.** La touche espace affiche discrètement la langue dominante (`FR`, `EN`). Un appui long sur l'espace ouvre le menu des langues : verrouiller le français, verrouiller l'anglais, ou mode automatique (par défaut).
- **Réglages.** Langues actives : français, anglais, ou les deux (défaut : les deux) ; langue préférée de départ ; variante anglaise US ou UK.

---

## 11. Architecture technique

### 11.1 Modules

```
engine/      Kotlin pur (JVM) : lexique, calcul des probabilités, correction,
             solveur de disposition, décodeur de tap, logique de validation.
             Aucune dépendance Android. Testé unitairement. Portable (KMP) plus tard.
ime/         Application Android : InputMethodService, KeyboardView (Canvas),
             sélection des profils, InputConnection, contacts, réglages (Compose).
tools/       Construction du lexique binaire à partir des sources, analyse de corpus,
             calibration des seuils.
prototype/   Prototype web pour tester le ressenti sur téléphone.
docs/        Cette spécification, décisions d'architecture.
```

### 11.2 Intégration Android

- **Lecture du contexte** : `getTextBeforeCursor()` pour le préfixe et le mot précédent ; `onUpdateSelection()` pour détecter un déplacement du curseur et réinitialiser l'état (mot en cours, espace automatique, annulation).
- **Mot en cours** : `setComposingText()` ; validation par `commitText()`.
- **Remplacements** (annulation, remplacement de l'espace automatique) : `deleteSurroundingText()` puis `commitText()`, regroupés entre `beginBatchEdit()` et `endBatchEdit()`.
- **Actions** : `performEditorAction()` selon `imeOptions`.
- **Rendu** : `View` custom dessinée sur `Canvas`, interpolation de la disposition par frame ; aucune allocation dans `onDraw`.
- **Session de saisie** (`nextglyph.engine.session.InputSession`, moteur pur) : toute la logique ci-dessus (composition, lots, validation, annulation, ponctuation, majuscules, langues, barre) ; l'IME ne fait que relayer les événements de la vue et d'`InputConnection`. Les mises à jour de sélection qui suivent les propres modifications de la session sont reconnues (positions attendues) ; toute autre est un déplacement externe : composition validée telle quelle, annulation et espace automatique oubliées.
- **Retour arrière** : dans le mot en composition, il raccourcit la composition ; ailleurs, il efface un caractère complet (émoji compris) puis remet en composition le mot qui précède le curseur s'il en est la fin.

### 11.3 Budgets de performance

| Mesure | Cible (appareil milieu de gamme) |
|---|---|
| Calcul de `P(c \| p)` pour 26 lettres | < 1 ms |
| Mise à jour de la disposition après `ACTION_UP` | visible à la frame suivante |
| Décodage d'un tap | < 2 ms |
| Ouverture du clavier (lexique en cache) | < 150 ms |
| Mémoire résidente (deux lexiques mappés) | < 50 Mo |
| Taille de l'APK, lexiques français et anglais inclus | < 20 Mo |

---

## 12. Confidentialité et sécurité

- Pas de permission `INTERNET`, pas de SDK tiers, pas de télémétrie.
- Permissions : `READ_CONTACTS` (optionnelle) seulement. `VIBRATE` n'est pas nécessaire : le retour haptique passe par `View.performHapticFeedback`, qui n'en demande pas. `ManifestTest` interdit toute autre permission dangereuse.
- Aucune journalisation du texte saisi, y compris en debug pour les profils mot de passe et code PIN.
- Toutes les données apprises (mots promus et en attente, refus, modèle spatial) sont effaçables en un geste depuis les réglages (`LearnedData.clear()`).
- Le dictionnaire personnel et le modèle spatial sont exclus des sauvegardes automatiques Android (`android:allowBackup`/`dataExtractionRules`), sauf choix contraire de l'utilisateur.

---

## 13. Accessibilité

- **TalkBack** : l'adaptation visuelle est désactivée (disposition de repos) ; les lettres probables sont annoncées en priorité lors de l'exploration tactile ; la validation par espace annonce le mot inséré.
- **Réalisation** (`KeyboardAccessibility`, `AccessibilityNodeProvider` du cadre Android, sans dépendance) : chaque touche de la couche affichée et chaque case de la barre est un nœud virtuel aux limites de la disposition au repos ; libellé : la lettre (en capitale si la majuscule est active), le nom des touches de fonction (Effacer, Espace, Symboles, Lettres, Majuscule, action de `↵`), « Espace, insère <mot> » quand l'espace remplacera le mot, état de `⇧` (« activée », « verrouillée »). Exploration au toucher par les événements de survol ; activation par `ACTION_CLICK` (double appui ou « relâcher pour saisir »). La touche qui a le focus de TalkBack est entourée d'un contour (contraste ≥ 3:1). L'annonce des lettres probables en priorité n'est pas réalisée en V1 (l'ordre des nœuds est celui des rangées).
- **Contrastes** : une touche atténuée garde un contraste de libellé d'au moins 3:1 ; les touches au repos respectent 4,5:1, de même que les touches enfoncées, de fonction, `⇧` active, la suggestion surlignée et le texte tapé proposé dans la barre ; l'indicateur de langue et le contour du focus TalkBack gardent 3:1 (vérifié sur les deux thèmes par `KeyStyleTest`).
- **Animations** : supprimées si les animations système sont désactivées.
- **Tailles** : hauteur de rangée réglable de 34 à 46 dp ; zones de tap jamais inférieures à 32 × 32 dp après adaptation (sauf mode élastique explicitement choisi).
- **Marge basse** : la dernière rangée reste au-dessus des éléments système du bas. Le padding bas est le plus grand des encarts `navigationBars()`, `captionBar()`, `tappableElement()` et `displayCutout()` (en navigation par gestes, la barre des boutons « changer de clavier » / « masquer le clavier » est déclarée comme `captionBar()` dans la fenêtre du clavier, issue #20), plus la marge basse réglée (§14).

---

## 14. Réglages utilisateur

| Réglage | Valeurs | Défaut |
|---|---|---|
| Disposition | AZERTY / QWERTY | Selon la locale système |
| Langues actives | Français / Anglais / Les deux | Les deux |
| Langue préférée de départ | Français / Anglais | Selon la locale système |
| Variante anglaise | US / UK | Selon la locale système |
| Mode d'adaptation | Désactivé / Calme / Calme renforcé / Élastique | Calme renforcé |
| Hauteur des rangées | 34–46 dp | 38 dp |
| Marge basse | 0–48 dp, ajoutée aux encarts système (§13) | 0 dp |
| Espace large | `?123`, `↵`, `⌫` à 0,8 u, espace élargie, écrans ≥ 400 dp (§5.1) | Désactivé |
| Validation par espace | Oui / Non | Oui |
| Prudence de la validation | Prudente / Normale / Audacieuse (modifie les seuils du §5.5) | Normale |
| Typographie française | Oui / Non | Oui |
| Double espace → point | Oui / Non | Oui |
| Noms des contacts | Oui / Non (+ effacer) | Non (jusqu'à l'octroi de la permission) |
| Aperçu agrandi des touches | Oui / Non / Sauf mots de passe | Sauf mots de passe |
| Retour haptique | Intensité | Moyen |
| Données apprises | Exporter / Importer / Effacer | — |

**Réalisation** (`SettingsActivity`, vues du cadre Android ; `docs/DECISIONS.md`, 2026-11-19) : les réglages sont enregistrés dans des `SharedPreferences` exclues des sauvegardes et appliqués aussitôt par le service, sans redémarrage. Deux réglages du tableau ne sont pas proposés en V1 : **Variante anglaise** (un seul lexique anglais est embarqué, le réglage serait sans effet) et **Aperçu agrandi des touches** (le clavier n'a pas d'aperçu). Retour haptique : Désactivé / Léger / Moyen / Fort (`performHapticFeedback` à l'appui d'une touche, sous réserve du réglage système « Retour tactile »). Export et import du dictionnaire personnel en TSV par le Storage Access Framework (`ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT`, aucune permission) ; effacement de toutes les données apprises après confirmation.

---

## 15. Mesure et critères d'acceptation V1

### 15.1 Analyse hors ligne (avant implémentation)

Sur un corpus représentatif pour chaque langue (français, anglais), puis sur un corpus mixte :
- nombre moyen de continuations possibles et entropie de `P(c | p)` selon la position dans le mot ;
- part des mots que la validation par espace complète ou corrige correctement, et taux de fausses validations, pour une grille de seuils (`minProb`, `minRatio`) ;
- sur le corpus mixte : vitesse de convergence du score de langue (§10.6) et taux de fausses validations dues à la mauvaise langue.

Ces résultats fixent les valeurs par défaut du §5.2 et du §5.5. Méthode, résultats et limites de l'analyse M0 : `docs/analysis/M0-corpus.md` (commande `./gradlew :tools:analyzeCorpus`).

### 15.2 Critères d'acceptation

| Critère | Seuil |
|---|---|
| Hauteur du profil texte, barre comprise, à 38 dp | ≤ 160 dp |
| Vitesse (mots/min) après 5 sessions d'apprentissage | ≥ clavier de référence du testeur |
| Taux d'erreurs non corrigées | ≤ clavier de référence du testeur |
| Validations par espace annulées par l'utilisateur | < 3 % des validations automatiques, dans chaque langue |
| Champs « chiffres uniquement » : caractère refusé par le champ | 0 sur le panel de test |
| Profil mot de passe : donnée persistée | aucune (vérifié par test instrumenté) |
| Budgets du §11.3 | tous respectés |

### 15.3 Panel de test des champs

Liste d'applications et de pages web couvrant : champs numériques décimaux, téléphone, date, code PIN, mot de passe natif et web, e-mail, URL, recherche, messagerie multiligne, champs sans suggestions.

**Panel intégré.** La variante debug de l'IME contient une activité « Champs de test » (`nextglyph.ime.debug.FieldTestsActivity`, absente de la release, vérifié par `ManifestTest`) : un `EditText` par profil, drapeau et action de `↵`, chacun suivi d'un écho du texte exact (points de code hors ASCII en clair) et de la dernière action d'éditeur reçue. Cas, résultats attendus et observés : `docs/FIELD-TESTS.md`.

---

## 16. Jalons

| Jalon | Contenu |
|---|---|
| M0 | Analyse de corpus (§15.1), construction des lexiques binaires français et anglais, prototype web des modes d'adaptation. |
| M1 | IME Android minimal : profil texte AZERTY et QWERTY, adaptation « calme renforcé », décodage du tap, état neutre de repli. |
| M2 | Validation par espace et annulation, accents, élisions et contractions, bilinguisme (§10.6), barre de ponctuation, typographie française, majuscules. |
| M3 | Profils mot de passe, numériques, e-mail/URL, incognito ; panel de test des champs. |
| M4 | Contacts, dictionnaire personnel, modèle spatial personnalisé, réglages, accessibilité. |
| M5 | Étude utilisateur, calibration, publication (F-Droid). |

Ensuite (V2) : bigrammes et prédiction du mot suivant, saisie par glissement, disposition sur 2 rangées en mode expérimental, autres langues.

---

## 17. Décisions et questions ouvertes

### 17.1 Décisions (2026-11-18)

| Sujet | Décision |
|---|---|
| Nom | Nextglyph |
| Licence | Apache-2.0 (règles pour les données au §10.2) |
| Langues V1 | Français et anglais, actifs simultanément (§10.6) |
| minSdk | 26 (Android 8.0) |
| Rangée 3 | Lettres à 0,9 u (32,4 dp à 360 dp) ; en QWERTY, `↵` au bout de la rangée 2 (§5.1) |
| Saisie par glissement | Après la V1 |
| Hébergement | GitHub |
| Distribution | F-Droid et Google Play (§18) |

### 17.2 Questions ouvertes

1. **Identifiant d'application** (`applicationId`, ex. `org.nextglyph.keyboard` ou `io.github.<compte>.nextglyph`) : il dépend du nom de domaine ou du compte GitHub qui hébergera le projet, et ne pourra plus changer une fois publié.

---

## 18. Hébergement et distribution

### 18.1 Contraintes communes

- **Un seul build pour les deux stores.** Aucune dépendance propriétaire : ni Google Play Services, ni Firebase, ni SDK d'analyse ou de publicité. C'est une condition d'entrée sur F-Droid, et c'est cohérent avec le §12.
- **Builds reproductibles.** Le même code source doit produire un APK identique à l'octet près (versions de Gradle et du JDK figées, pas d'horodatage ni de chemin absolu dans l'APK). F-Droid peut alors publier l'APK signé par le projet au lieu de le re-signer avec sa propre clé. Résultat : une même signature sur F-Droid, Google Play et GitHub, et des utilisateurs qui peuvent passer d'une source à l'autre sans désinstaller.
- **Clé de signature** : générée une seule fois, conservée hors du dépôt, avec une sauvegarde hors ligne. Le build la lit dans l'environnement (`NEXTGLYPH_KEYSTORE` et trois variables associées) ; sans elle, l'APK de release n'est pas signé. Sur Google Play, l'inscription à Play App Signing doit utiliser cette même clé comme clé d'application (et non une clé générée par Google), sinon les signatures divergent.
- **Versionnage** : `versionName` sémantique (`1.2.0`) et `versionCode` strictement croissant, identiques dans les trois canaux.

### 18.2 GitHub

- Dépôt public ; fichiers `LICENSE` (Apache-2.0), `NOTICE` (attributions des données, cf. §10.2), `README`, `CONTRIBUTING`, `SECURITY`.
- **Intégration continue** (GitHub Actions) : tests du module `engine`, lint, build de l'APK, vérification de la reproductibilité (deux builds de l'APK de release non signé, depuis deux dossiers et sans cache, comparés à l'octet : `scripts/check-reproducible.sh`).
- **Publication** : chaque tag `vX.Y.Z` produit une release GitHub avec l'APK signé et son empreinte SHA-256.
- Les lexiques binaires sont générés par `tools/` pendant le build, pas versionnés. Seules les sources de données (ou un script de téléchargement avec sommes de contrôle) sont dans le dépôt. Chaque source a un miroir figé dans une release du dépôt (`mirrors` dans `tools/data/sources.json`), essayé avant l'URL officielle : un fichier écrasé en place par son auteur ne casse pas le build.
- Procédure complète (clé, secrets, tags, F-Droid, Play) : [PUBLISHING.md](PUBLISHING.md).

### 18.3 F-Droid

- Métadonnées dans le dépôt au format fastlane : `fastlane/metadata/android/<locale>/` (titre, descriptions, captures, notes de version), en `fr-FR` et `en-US`.
- La recette de build F-Droid doit pouvoir construire l'APK depuis les sources, y compris les lexiques. Les sources de données doivent donc être accessibles et sous licences libres (§10.2).
- Objectif : aucune « anti-fonctionnalité » signalée par F-Droid.

### 18.4 Google Play

- **Cible SDK** : Google Play impose de cibler une version récente d'Android (`targetSdk`), relevée chaque année. La règle exacte est à vérifier à chaque publication.
- **Section « Sécurité des données »** : déclarer qu'aucune donnée n'est collectée ni partagée. Les données traitées uniquement sur l'appareil (texte saisi, contacts, dictionnaire personnel) ne sont en principe pas considérées comme collectées. À confirmer avec la politique en vigueur au moment de la publication.
- **Contacts** : la permission `READ_CONTACTS` impose une explication claire avant la demande (écran de réglages, §6.1) et une politique de confidentialité publiée.
- **Politique de confidentialité** : requise par Google Play ; une page courte qui reprend le §12, en français et en anglais : [PRIVACY.md](PRIVACY.md), publiée par le dépôt (ou GitHub Pages).
- **Format** : Google Play exige un app bundle (`.aab`) pour une nouvelle application ; le workflow de release le produit, signé avec la même clé que l'APK. Play App Signing doit être configuré avec cette clé (« fournir votre propre clé »).
- Fiche du store en français et en anglais, générée à partir des mêmes textes que les métadonnées fastlane.

---

## Annexe — Projets de référence

| Projet | Licence | Intérêt |
|---|---|---|
| FlorisBoard | Apache-2.0 | Architecture Kotlin moderne, gestion des profils de champ. |
| HeliBoard | GPL-3.0 | Dérivé d'AOSP LatinIME : dictionnaires, correction, bigrammes. |
| AnySoftKeyboard | Apache-2.0 | Dispositions, packs de langues. |
