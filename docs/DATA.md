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

Total téléchargé : 84 901 370 octets (environ 81 Mio).

### Couverture des besoins

| Besoin | Français | Anglais |
|---|---|---|
| (a) vocabulaire | Dicollecte (MPL-2.0) + Lexique 4.10 (CC BY-SA) | SCOWL en_US (permissive) |
| (b) fréquences | Lexique 4.10 (sous-titres, CC BY-SA) | **Aucune source de fréquences conversationnelles à licence clairement compatible.** Repli retenu : fréquences calculées par `tools/` sur le corpus Tatoeba anglais (CC BY), registre court et conversationnel mais biaisé vers des phrases d'apprentissage. Complément possible : Google Books Ngram (CC BY 3.0), registre livresque et volumétrie très lourde, non téléchargé pour l'instant. |
| (c) phrases | Tatoeba fra (CC BY 2.0 FR) | Tatoeba eng (CC BY 2.0 FR) |

### Remarques

- **Lexique 3 vs Lexique 4.** La page officielle ne propose plus que Lexique 4.10 (et 3.82 en archive, sans licence indiquée sur la page). Lexique 4.10 contient les mêmes informations que Lexique 3 (formes, lemmes, fréquences de sous-titres) et déclare CC BY-SA 4.0 ; c'est lui qui est retenu.
- **Lexique 4.10 pèse 49 Mo** : seul `Lexique4.1.tsv` (34 Mo) est utile. Le fichier brut n'est jamais versionné ; seul le lexique binaire dérivé l'est dans l'APK (≤ 8 Mo par langue).
- **SCOWL.** Le fichier est une liste Hunspell (mots de base et drapeaux d'affixes) : `tools/` devra la développer (équivalent de `unmunch`) pour obtenir les formes fléchies. Le README de SCOWL précise que des données COCA (3-grammes, non librement distribuées) ont servi à classer les mots, « within the rights given by the NDA » ; la liste distribuée est elle-même sous licence permissive. Point à relire avant publication F-Droid.
- **Tatoeba.** Le fichier est une exportation hebdomadaire écrasée à chaque semaine : son SHA-256 devient obsolète au prochain export. Voir « Points d'attention » plus bas.
- **Dicollecte.** La page de téléchargement de grammalecte.net ne mentionne pas de licence pour les dictionnaires : elle est indiquée dans `README_dict_fr.txt` de l'archive. Le programme Grammalecte lui-même est sous GPL 3 et n'est **pas** utilisé.

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
> **SCOWL / English Speller Database** : Copyright 2000-2026 Kevin Atkinson. https://wordlist.aspell.net/ — licence permissive (voir `NOTICE`).
>
> **Tatoeba** (analyse hors ligne, non distribué) : phrases des contributeurs de Tatoeba, https://tatoeba.org/ — licence CC BY 2.0 FR (https://creativecommons.org/licenses/by/2.0/fr/).

## Points d'attention pour F-Droid

- **Reproductibilité des sommes de contrôle.** Les exports Tatoeba sont écrasés chaque semaine : après quelques jours, `fetchData` échoue sur l'empreinte (comportement voulu). La recette F-Droid ne doit pas en dépendre. Solution recommandée : publier un instantané (≤ 100 Mo, autorisé par CC BY) comme pièce jointe d'une release du dépôt et pointer `sources.json` vers lui. Même précaution pour tout fichier hébergé sur un site non versionné (le zip Lexique a été modifié le 17 novembre 2026).
- **Le corpus Tatoeba n'est pas distribué** dans l'APK : il ne sert qu'à l'analyse du §15.1 et à calculer des fréquences. L'attribution est quand même conservée.
- **CC BY-SA (Lexique)** : le lexique binaire dérivé est une adaptation ; il doit être livré comme fichier de données distinct, sous CC BY-SA 4.0, avec attribution et mention des modifications. **MPL-2.0 (Dicollecte)** : même principe, avec le texte de la licence fourni et les fichiers sources MPL disponibles. Ne pas fusionner ces données dans un fichier unique sous Apache-2.0.
- **SCOWL** : l'avis de copyright doit figurer dans toute copie ; la mention COCA mérite une relecture juridique.
- Aucune permission réseau dans l'APK : le téléchargement n'a lieu que dans le build (`tools/`), jamais sur l'appareil.
- Les fichiers bruts (zip, bz2) restent dans `tools/build/data/`, ignoré par git ; aucun fichier de données brut de plus de 1 Mo n'est versionné.
