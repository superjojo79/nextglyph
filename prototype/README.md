# Prototype web du clavier Nextglyph

Prototype HTML/JavaScript pour **tester au doigt, sur un vrai téléphone**, le ressenti des modes d'adaptation (SPEC §5) avant d'écrire l'IME Android. Il n'est pas livré aux utilisateurs.

- Aucune étape de build, aucune dépendance, aucun CDN : HTML + modules JavaScript (ES2022) + CSS.
- Aucune donnée n'est envoyée sur le réseau. Le journal des frappes et les réglages restent dans le navigateur.

## Ouvrir le prototype

Les modules ES et le chargement des lexiques (`fetch`) exigent un serveur HTTP : l'ouverture directe de `index.html` (`file://`) ne fonctionne pas.

Depuis le dossier `prototype/` :

```sh
python -m http.server 8000
```

(Sans Python : `php -S 0.0.0.0:8000`, ou tout autre serveur de fichiers statiques déjà installé. Ne pas utiliser `npx`.)

- Sur l'ordinateur : <http://localhost:8000>.
- Sur le téléphone, connecté **au même réseau local** (Wi-Fi) : `http://<adresse IP de l'ordinateur>:8000`, par exemple `http://192.168.1.20:8000`. L'adresse se lit avec `ipconfig` (Windows) ou `ip addr` / `ifconfig` (Linux, macOS). Si la page ne s'ouvre pas, autoriser le port 8000 dans le pare-feu de l'ordinateur. Par défaut, `python -m http.server` écoute sur toutes les interfaces ; ajouter `--bind 127.0.0.1` pour le limiter à la machine locale.
- Arrêter le serveur (Ctrl+C) quand le test est terminé.

Tests de la logique pure (Node 24) :

```sh
cd prototype
node --test
```

## Utilisation

Le clavier occupe le bas de l'écran. Le texte tapé s'affiche en haut (mot en cours souligné). Le bouton **Réglages** ouvre le panneau ; chaque réglage agit en direct et est conservé dans le navigateur (`localStorage`).

- Barre de suggestions : 3 mots (le candidat dominant, s'il existe, est au centre et surligné) ; juste après une validation, la barre de ponctuation `, . ? ! : … '`.
- Espace : affiche la langue estimée (`FR`/`EN`) et, si un candidat domine, `␣ mot` (le mot qu'elle va insérer). Un `⌫` immédiatement après une insertion automatique rétablit le texte tapé.
- Double espace : remplace l'espace automatique par `. ` et active la majuscule.
- La touche `?123` n'est pas implémentée dans le prototype (pas de couche symboles).

## Réglages

Les noms sont ceux de la spécification.

### Clavier

| Réglage | Rôle |
|---|---|
| Disposition | AZERTY ou QWERTY (§5.1). |
| Hauteur des rangées | Hauteur d'une rangée en pixels (la spec vise 38 dp). |

### Adaptation (§5.2)

| Réglage | Rôle |
|---|---|
| Mode | Désactivé ; Calme (opacité légère seulement) ; Calme renforcé (opacité, taille du libellé, largeur plafonnée faible, défaut) ; Élastique (opacité forte, libellé, largeur plafonnée à `adapt.maxDisplacement`). |
| `adapt.minOpacity` | Opacité d'une lettre sans continuation possible (r = 0). |
| `adapt.minWidth` | Largeur (en u) d'une lettre sans continuation, avant normalisation de la rangée. |
| `adapt.gain` | Pente de la largeur : `largeur = 0,8 + gain·√r`. |
| `adapt.spaceGain` | Élargissement de l'espace : `repos + spaceGain·P(fin)`. |
| `adapt.maxDisplacement` | Plafond du déplacement du centre d'une touche, en fraction de sa largeur au repos (défaut 0,5). |
| `adapt.fastTypingMs` | Si deux frappes sont séparées de moins que cette durée, la mise à jour visuelle est différée jusqu'à une pause. Le décodage reste actif. |
| Animation | Durée de la transition (100 ms par défaut ; 0 si l'utilisateur demande moins d'animations). |

La disposition est toujours **gelée tant qu'un doigt est posé** : elle ne change qu'après le relâchement. Au début d'un mot, l'adaptation est atténuée de moitié. Un préfixe inconnu (même après correction à distance 1) ramène le clavier à l'état neutre.

### Décodage du tap (§5.3)

`score(c) = P(point | c) · P(c | p)^λ`, avec `P(point | c)` mélange de la position affichée et de la position de repos.

| Réglage | Rôle |
|---|---|
| `decode.alpha` (α) | Part de la position affichée (utilisateur qui vise ce qu'il voit) contre la position de repos (vise de mémoire). |
| `decode.lambda` (λ) | Poids du modèle de langue. 0 = décodage purement spatial. |
| `decode.sigma` (σ) | Dispersion du tap, en unités de touche (u). Plus il est petit, plus un tap précis sur une touche atténuée l'emporte. |

La lettre produite est flashée brièvement : c'est celle de score maximal, pas forcément la touche sous le doigt.

### Validation par espace (§5.5)

| Réglage | Rôle |
|---|---|
| Validation par espace | Active le cas 2 (remplacement par un candidat dominant). |
| `autocommit.minProb` | Probabilité minimale du candidat dominant (0,60). |
| `autocommit.minRatio` | Rapport minimal entre le 1er et le 2e candidat (2,0). |
| `autocommit.rejectLimit` | Nombre d'annulations (⌫) d'une même correction avant de ne plus la proposer automatiquement. |

### Texte (§5.6, §5.7)

| Réglage | Rôle |
|---|---|
| Typographie française | Espaces fines insécables (U+202F) avant `; ! ?`, insécable (U+00A0) avant `:`, uniquement quand la langue estimée de la phrase est le français. |
| Double espace → point | Active le remplacement décrit plus haut. |
| Majuscules automatiques | Début de champ et après `. ? ! …`. |

### Langues (§10.6)

| Réglage | Rôle |
|---|---|
| Langues actives | Français, anglais ou les deux (mélange selon le score de langue). |
| Langue préférée de départ | Point de départ du score. |
| Verrouillage | Force FR ou EN (équivalent du menu de l'espace) ; « Automatique » laisse le score décider. |
| Fenêtre du score | Nombre de mots validés pris en compte (8). |

### Débogage

Affiche `r(c)` sur chaque touche, `P(fin)`, la langue estimée (π en %) et la langue de la phrase.

### Journal des frappes

Chaque tap (position x, y en unités u sur la largeur du clavier = 10 u ; touche sous le doigt ; touche décodée ; préfixe ; temps en ms ; disposition ; mode) est conservé en mémoire (20 000 maximum). **Exporter le journal (JSON)** télécharge un fichier local ; **Effacer le journal** le vide. Il est perdu au rechargement de la page.

## Données

`data/<langue>.json` : tableau `[{"w": "école", "f": 12345}, ...]` (mot avec accents, apostrophes et traits d'union ; fréquence relative). Les fichiers fournis (`fr.json`, `en.json`) sont de petits lexiques d'exemple écrits pour le prototype, avec des fréquences plausibles de type loi de Zipf ; ils seront remplacés par les vrais lexiques issus des dictionnaires. Le prototype fonctionne avec des fichiers de 100 000 mots (trie construit au chargement ; voir `test/lexicon.test.js`).

Les formes avec apostrophe ou trait d'union (`j'ai`, `peut-être`, `don't`) sont indexées sous leur clé de lettres (`jai`, `peutetre`, `dont`) : c'est ainsi que la validation les restitue (§5.5, cas 2).

## Organisation

```
prototype/
  index.html, style.css      page et styles (clair / sombre)
  data/                     lexiques d'exemple
  src/
    normalize.js            normalisation (§5.8)
    lexicon.js              trie de lexique (§10.1)
    language.js             score de langue (§10.6)
    distribution.js         P(c | p), P(fin | p), candidats, corrections (§5.2, §10.4)
    layout.js               dispositions §5.1 et solveur d'adaptation §5.2
    freeze.js               gel pendant le toucher, saisie rapide
    decoder.js              décodage du tap (§5.3)
    validation.js           validation par espace (§5.5)
    punctuation.js          ponctuation, typographie, majuscules (§5.6, §5.7)
    session.js              texte, mot en cours, annulation, barre de suggestions
    settings.js, log.js     réglages, journal
    engine.js               assemblage de la logique pure
    app.js                  affichage, Pointer Events, panneau de réglages
  test/                     tests `node --test` (trois blocs par fichier)
  testkit/                  données de test partagées
```

## Écarts connus par rapport à la spécification

- Le mélange bilingue pondère π par la masse du préfixe dans chaque lexique (a posteriori bayésien) : un préfixe présent seulement en anglais pousse vers l'anglais.
- Corrections : distance d'édition 1 seulement (pas de distance 2 à la validation), pénalités fixes selon le type d'édition et la proximité des touches.
- Pas d'apprentissage du modèle spatial (σ unique réglable), pas de contacts, pas de dictionnaire personnel, pas de bigrammes.
- Pas de couche `?123`, de majuscule manuelle, de variantes accentuées par appui long ni de menu de l'espace (le verrouillage de langue passe par les réglages).
- Largeurs : la formule `0,8 + gain·√r` est appliquée telle quelle puis mélangée vers le repos selon le mode ; l'espace est élargi de `spaceGain·P(fin)`, les lettres de la rangée absorbent la différence.
- Le décodage s'effectue au relâchement, avec la disposition affichée au moment du toucher (gelée).
- Les touches `↵`/`⌫` et `?123` ne sont jamais adaptées.
