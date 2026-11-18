# Nextglyph — Spécification

| | |
|---|---|
| Projet | Nextglyph, clavier adaptatif open source |
| Statut | Brouillon v0.2 |
| Date | 2026-11-18 |
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
- Emojis, GIF, presse-papiers avancé.

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

**Échappatoire manuelle.** Dans tout profil texte, un appui long sur `?123` propose « Disposition classique », pour basculer vers la disposition du profil mot de passe sans adaptation (champs web mal typés, saisie de code, etc.).

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

Rangée 3, répartition au repos : `?123` 1 u, 6 lettres à 0,85 u (5,1 u), espace 1,9 u, `↵` 1 u, `⌫` 1 u = 10 u. **Décision :** les lettres de la rangée 3 sont légèrement plus étroites, plutôt que de déplacer `↵` dans la barre.

**Variante QWERTY.** La rangée 3 QWERTY compte 7 lettres au lieu de 6. Pour garder une espace assez large, `↵` passe au bout de la rangée 2, à l'emplacement de la touche Entrée d'un clavier physique :

```
Rangée 1 : q w e r t y u i o p                                         10 u
Rangée 2 : (0,3 u) a s d f g h j k l | ↵                               0,3 + 9 × 0,95 + 1,15 = 10 u
Rangée 3 : ?123 | z x c v b n m | espace | ⌫                           1 + 7 × 0,85 + 2,05 + 1 = 10 u
```

**Choix de la disposition.** La disposition (AZERTY ou QWERTY) est un réglage indépendant de la langue saisie. Par défaut : AZERTY si la locale système est française, QWERTY sinon. Une personne francophone qui écrit en anglais garde son AZERTY.

| Élément | Hauteur |
|---|---|
| Barre de suggestions | 32 dp |
| Rangée de touches | 38 dp (réglable 34–46 dp) |
| Espacement vertical | 4 dp |
| **Total** | **~ 160 dp** maximum à la hauteur par défaut |

La touche `↵` est contextuelle d'après `imeOptions` : envoyer, rechercher, aller, suivant, terminé, ou saut de ligne si le champ est multiligne et n'a pas d'action.

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

- *Début de mot* (`p` vide) : les poids viennent de la distribution des initiales, conditionnée par le contexte (mot précédent) si disponible. L'adaptation est atténuée de moitié.
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
- `λ` (défaut : 0,6) limite l'influence du modèle de langue : un tap franchement au centre d'une touche atténuée doit produire cette lettre.
- Le mode mot de passe et les pavés numériques utilisent `λ = 0` (décodage purement spatial).

### 5.4 Barre de suggestions

La barre a trois états :

| État | Contenu |
|---|---|
| Mot en cours | 3 suggestions. Le candidat dominant éventuel est au centre et surligné. |
| Juste après une validation | Ponctuation (§5.6). |
| Champ vide / début de texte | Mots probables en début de phrase (prédiction du mot suivant, V2), sinon vide. |

Un tap sur une suggestion valide ce mot et ajoute une espace automatique.

### 5.5 Validation par espace

À l'appui sur espace, avec un mot en cours `t` :

1. **`t` est un mot du lexique** → on insère `t`, avec restitution des accents si elle est sans ambiguïté (`ecole` → `école`). Les cas ambigus (`a`/`à`, `ou`/`où`, `du`/`dû`, `la`/`là`) sont insérés tels quels ; les variantes accentuées restent dans la barre et sont choisies par le contexte quand le modèle de bigrammes est disponible.
2. **`t` n'est pas un mot, et un candidat `w` domine** → on insère `w`. Domine signifie :
   `P(w) ≥ autocommit.minProb` (défaut : 0,60) **et** `P(w) / P(2e candidat) ≥ autocommit.minRatio` (défaut : 2,0).
   Ce cas couvre la complétion (`bonj` → `bonjour`), la correction (`bonjoru` → `bonjour`), les élisions (`jai` → `j'ai`, `cest` → `c'est`, `lecole` → `l'école`), les traits d'union (`peutetre` → `peut-être`) et les contractions anglaises (`dont` → `don't`, `im` → `I'm`, `youre` → `you're`).
   Les contractions anglaises qui sont aussi des mots valides (`well`/`we'll`, `its`/`it's`, `were`/`we're`, `ill`/`I'll`, `cant`/`can't`, `id`/`I'd`) relèvent du cas 1 : jamais de remplacement automatique en V1, la variante reste proposée dans la barre.
3. **Sinon** → on insère `t` tel quel. Le mot n'est **pas** ajouté au dictionnaire personnel à ce stade (cf. §10.5).

Puis une espace automatique est insérée.

**Prévisibilité :** dès qu'un candidat dominant existe, la touche espace affiche `␣ <mot>` et le mot est surligné dans la barre.

**Annulation :** un appui sur ⌫ immédiatement après une validation automatique (curseur inchangé, aucune autre frappe) remplace le mot inséré par le texte tapé à l'origine, sans espace. Un deuxième ⌫ efface normalement.

**Apprentissage du refus :** si une même correction `t → w` est annulée `autocommit.rejectLimit` fois (défaut : 2), elle n'est plus proposée automatiquement (elle reste dans la barre).

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

**Accès aux autres signes :**
- Appui long ou glissement vers le haut sur `?123` : menu rapide `- ( ) " ; @ #`.
- Couche `?123` : chiffres et symboles complets, sur 3 rangées, même hauteur que la couche lettres.

**Pendant un mot en cours,** un tap sur un signe de la couche `?123` valide d'abord le mot (règles du §5.5) puis applique le signe.

### 5.7 Majuscules

- **Automatiques** selon `TYPE_TEXT_FLAG_CAP_SENTENCES` / `_WORDS` / `_CHARACTERS` et `InputConnection.getCursorCapsMode()` : début de champ, après `. ? ! …` suivis d'une espace.
- **Manuelles** : glissement vers le haut sur une lettre → majuscule pour cette lettre.
- **Verrouillage** : depuis la couche `?123` (touche `⇪`). L'état verrouillé est affiché en permanence.
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
| Glisser vers la gauche depuis ⌫ | Efface le mot précédent (répété en continuant) |
| Glisser horizontalement sur l'espace | Déplace le curseur |
| Appui long sur `?123` | Menu ponctuation rapide / « Disposition classique » |
| Appui long sur une lettre | Variantes accentuées |

---

## 6. Noms et prénoms des contacts

### 6.1 Permission

- `READ_CONTACTS`, **optionnelle**, demandée depuis l'activité de réglages (un `InputMethodService` ne peut pas afficher lui-même la demande).
- Le clavier fonctionne normalement sans cette permission.
- Un réglage permet de désactiver la fonctionnalité et d'effacer immédiatement le sous-lexique contacts.

### 6.2 Collecte

- Source : `ContactsContract.Data` avec `StructuredName` (prénom, nom, deuxième prénom) ; `Nickname` en option.
- Mise à jour : `ContentObserver` sur `ContactsContract.Contacts.CONTENT_URI`, avec reconstruction en arrière-plan.
- Le sous-lexique est stocké séparément du lexique principal, dans le stockage privé de l'application.

### 6.3 Utilisation

- Chaque nom est indexé sous forme normalisée (`helene`) avec sa forme d'affichage (`Hélène`). Les noms composés (`Jean-Pierre`) sont indexés en entier.
- Poids de base : équivalent à un mot de fréquence moyenne.
- **Bonus de contexte** (`contacts.contextBoost`, multiplicatif) :
  - champ de type `TYPE_TEXT_VARIATION_PERSON_NAME` ;
  - début de message ou de champ ;
  - après « bonjour », « salut », « coucou », « cher », « chère », « avec », « chez », « à », « pour », « @ » ;
  - premier caractère tapé en majuscule ;
  - après un autre nom de contact (prénom → nom).
- Un nom qui est aussi un mot courant (« Rose », « Pierre », « Marie », « Olive ») n'est mis en majuscule que si le contexte le justifie ; sinon le mot courant l'emporte.

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

- Hauteur indicative : ~ 230 dp. La compacité passe ici après la fiabilité.
- `⇧` explicite : un appui = une majuscule, double appui = verrouillage. État toujours visible.
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

Aucune donnée tapée dans ce profil n'est conservée en mémoire au-delà de la frappe, ni journalisée.

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

- `−` n'apparaît qu'avec `TYPE_NUMBER_FLAG_SIGNED`.
- Le séparateur décimal n'apparaît qu'avec `TYPE_NUMBER_FLAG_DECIMAL`. Il affiche le séparateur de la locale (`,` en français). **Stratégie d'insertion :** on insère `,` ; si `getTextBeforeCursor` montre que le champ l'a rejeté, on insère `.` et on mémorise ce choix pour ce champ (paquet + id du champ). À valider sur un panel d'applications.
- Les emplacements des touches absentes restent vides : la position des chiffres ne change jamais.

### 8.2 Téléphone (`TYPE_CLASS_PHONE`)

Pavé 3 × 4 standard (`1`–`9`, `*`, `0`, `#`), plus une colonne `+` (aussi via appui long sur `0`), `,` (pause), `;` (attente), `⌫`, `↵`.

### 8.3 Date / heure (`TYPE_CLASS_DATETIME`)

Pavé 3 × 4 avec, selon la variation : `/` et `-` (date), `:` (heure), ou les trois (date et heure).

### 8.4 Code PIN (`TYPE_NUMBER_VARIATION_PASSWORD`)

Pavé du §8.1 sans `−` ni séparateur, avec toutes les règles du profil mot de passe (§7.2).

---

## 9. Autres profils

### 9.1 E-mail et URL

- Disposition compacte du §5.1, adaptation visuelle active sur le lexique des domaines et des contacts (e-mail) ou des domaines courants (URL).
- Barre permanente : `@` `.` `.com` `.fr` `-` `_` (e-mail) ou `/` `.` `.com` `.fr` `-` (URL).
- Pas de validation par espace, pas d'espace automatique, pas de majuscule automatique, pas de typographie française.

### 9.2 Incognito

Défini par `IME_FLAG_NO_PERSONALIZED_LEARNING` ou forcé par l'utilisateur. Le comportement est celui du profil sous-jacent, sans aucune écriture : ni dictionnaire personnel, ni modèle spatial, ni compteurs de refus.

---

## 10. Moteur linguistique

### 10.1 Lexique

- Structure : automate acyclique minimal (DAWG) ou FST, sur les formes normalisées.
- Chaque nœud stocke la somme des fréquences de son sous-arbre ; chaque état final stocke la fréquence du mot et l'index de ses formes d'affichage.
- Ainsi, `P(c | p)` et `P(fin | p)` se calculent en O(1) par lettre après la descente sur `p`.
- Format binaire mappé en mémoire (`mmap`), construit hors ligne par `tools/`.
- Ordre de grandeur : 300 000 à 600 000 formes fléchies pour le français, environ 150 000 à 250 000 pour l'anglais ; cible ≤ 8 Mo sur disque par langue.

### 10.2 Sources de données

| Langue | Source | Usage | Licence |
|---|---|---|---|
| FR | Lexique 3 | Formes fléchies, fréquences | **À vérifier** |
| FR | Dictionnaires Hunspell / Grammalecte | Couverture du vocabulaire | **À vérifier** |
| EN | SCOWL / dictionnaires Hunspell anglais | Couverture du vocabulaire, variantes US/UK | **À vérifier** |
| FR, EN | Listes de fréquences issues de sous-titres | Fréquences de la langue parlée | **À vérifier** |
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
- Limite V1 : distance 1 sur le préfixe, distance 2 sur le mot complet à la validation.

### 10.5 Dictionnaire personnel

- Un mot inconnu validé tel quel est mis en attente ; il entre dans le dictionnaire personnel après `learn.minOccurrences` (défaut : 2) validations sans annulation.
- Un appui long sur une suggestion permet de la supprimer du dictionnaire personnel.
- Stockage : base locale dans le stockage privé ; export/import manuels depuis les réglages.
- Chaque mot appris est rattaché à la langue estimée au moment de l'apprentissage (§10.6).

### 10.6 Bilinguisme français-anglais

Beaucoup d'utilisateurs alternent les deux langues, parfois dans une même phrase. Nextglyph ne demande donc pas de « changer de langue » : les deux lexiques sont actifs en même temps.

- **Score de langue.** Une estimation `π_FR` / `π_EN` est tenue à jour à partir des derniers mots validés (fenêtre glissante, défaut : 8 mots), en partant de la langue préférée de l'utilisateur.
- **Mélange.** `P(c | p) = π_FR · P_FR(c | p) + π_EN · P_EN(c | p)`. Le même mélange s'applique à `P(fin | p)` et au classement des suggestions. Le score évolue progressivement : pas de bascule brutale de l'adaptation visuelle.
- **Mots communs aux deux langues** (`table`, `question`, `important`) : ils comptent pour les deux langues dans le score.
- **Restitution des accents** : uniquement à partir du lexique français. Une forme sans accent qui est un mot anglais valide (`resume`, `cafe`) n'est jamais accentuée automatiquement ; la variante accentuée reste proposée dans la barre.
- **Règles propres à chaque langue** (typographie française, majuscule de `I`, élisions, contractions) : appliquées selon la langue de la phrase en cours, c'est-à-dire la langue dominante du score depuis la dernière ponctuation finale.
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
- Permissions : `READ_CONTACTS` (optionnelle), `VIBRATE`.
- Aucune journalisation du texte saisi, y compris en debug pour les profils mot de passe et code PIN.
- Toutes les données apprises sont effaçables en un geste depuis les réglages.
- Le dictionnaire personnel et le modèle spatial sont exclus des sauvegardes automatiques Android (`android:allowBackup`/`dataExtractionRules`), sauf choix contraire de l'utilisateur.

---

## 13. Accessibilité

- **TalkBack** : l'adaptation visuelle est désactivée (disposition de repos) ; les lettres probables sont annoncées en priorité lors de l'exploration tactile ; la validation par espace annonce le mot inséré.
- **Contrastes** : une touche atténuée garde un contraste de libellé d'au moins 3:1 ; les touches au repos respectent 4,5:1.
- **Animations** : supprimées si les animations système sont désactivées.
- **Tailles** : hauteur de rangée réglable de 34 à 46 dp ; zones de tap jamais inférieures à 32 × 32 dp après adaptation (sauf mode élastique explicitement choisi).

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
| Validation par espace | Oui / Non | Oui |
| Prudence de la validation | Prudente / Normale / Audacieuse (modifie les seuils du §5.5) | Normale |
| Typographie française | Oui / Non | Oui |
| Double espace → point | Oui / Non | Oui |
| Noms des contacts | Oui / Non (+ effacer) | Non (jusqu'à l'octroi de la permission) |
| Aperçu agrandi des touches | Oui / Non / Sauf mots de passe | Sauf mots de passe |
| Retour haptique | Intensité | Moyen |
| Données apprises | Exporter / Importer / Effacer | — |

---

## 15. Mesure et critères d'acceptation V1

### 15.1 Analyse hors ligne (avant implémentation)

Sur un corpus représentatif pour chaque langue (français, anglais), puis sur un corpus mixte :
- nombre moyen de continuations possibles et entropie de `P(c | p)` selon la position dans le mot ;
- part des mots que la validation par espace complète ou corrige correctement, et taux de fausses validations, pour une grille de seuils (`minProb`, `minRatio`) ;
- sur le corpus mixte : vitesse de convergence du score de langue (§10.6) et taux de fausses validations dues à la mauvaise langue.

Ces résultats fixent les valeurs par défaut du §5.2 et du §5.5.

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
| Rangée 3 | Lettres à 0,85 u ; en QWERTY, `↵` au bout de la rangée 2 (§5.1) |
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
- **Clé de signature** : générée une seule fois, conservée hors du dépôt, avec une sauvegarde hors ligne. Sur Google Play, l'inscription à Play App Signing doit utiliser cette même clé comme clé d'application (et non une clé générée par Google), sinon les signatures divergent.
- **Versionnage** : `versionName` sémantique (`1.2.0`) et `versionCode` strictement croissant, identiques dans les trois canaux.

### 18.2 GitHub

- Dépôt public ; fichiers `LICENSE` (Apache-2.0), `NOTICE` (attributions des données, cf. §10.2), `README`, `CONTRIBUTING`, `SECURITY`.
- **Intégration continue** (GitHub Actions) : tests du module `engine`, lint, build de l'APK, vérification de la reproductibilité (deux builds successifs comparés).
- **Publication** : chaque tag `vX.Y.Z` produit une release GitHub avec l'APK signé et son empreinte SHA-256.
- Les lexiques binaires sont générés par `tools/` pendant le build, pas versionnés. Seules les sources de données (ou un script de téléchargement avec sommes de contrôle) sont dans le dépôt.

### 18.3 F-Droid

- Métadonnées dans le dépôt au format fastlane : `fastlane/metadata/android/<locale>/` (titre, descriptions, captures, notes de version), en `fr-FR` et `en-US`.
- La recette de build F-Droid doit pouvoir construire l'APK depuis les sources, y compris les lexiques. Les sources de données doivent donc être accessibles et sous licences libres (§10.2).
- Objectif : aucune « anti-fonctionnalité » signalée par F-Droid.

### 18.4 Google Play

- **Cible SDK** : Google Play impose de cibler une version récente d'Android (`targetSdk`), relevée chaque année. La règle exacte est à vérifier à chaque publication.
- **Section « Sécurité des données »** : déclarer qu'aucune donnée n'est collectée ni partagée. Les données traitées uniquement sur l'appareil (texte saisi, contacts, dictionnaire personnel) ne sont en principe pas considérées comme collectées. À confirmer avec la politique en vigueur au moment de la publication.
- **Contacts** : la permission `READ_CONTACTS` impose une explication claire avant la demande (écran de réglages, §6.1) et une politique de confidentialité publiée (page GitHub Pages du dépôt).
- **Politique de confidentialité** : requise par Google Play ; une page courte qui reprend le §12.
- Fiche du store en français et en anglais, générée à partir des mêmes textes que les métadonnées fastlane.

---

## Annexe — Projets de référence

| Projet | Licence | Intérêt |
|---|---|---|
| FlorisBoard | Apache-2.0 | Architecture Kotlin moderne, gestion des profils de champ. |
| HeliBoard | GPL-3.0 | Dérivé d'AOSP LatinIME : dictionnaires, correction, bigrammes. |
| AnySoftKeyboard | Apache-2.0 | Dispositions, packs de langues. |
