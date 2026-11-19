# Champs de test

Panel du §15.3 de la [spécification](SPEC.md) : l'activité « Champs de test » (`nextglyph.ime.debug.FieldTestsActivity`) n'existe que dans la variante **debug** du module `ime` ; `ManifestTest` échoue si elle apparaît dans le manifeste de la release. Elle contient un `EditText` par cas (`FieldTestCases`), suivi d'un écho : texte exact (`⏎` pour un saut de ligne, points de code hors ASCII imprimable en clair) et dernière action d'éditeur reçue (`action=send`, etc.).

## Lancer le panel

```sh
./gradlew :ime:assembleDebug
adb install -r ime/build/outputs/apk/debug/ime-debug.apk
adb shell ime enable io.github.superjojo79.nextglyph/nextglyph.ime.NextglyphInputMethodService
adb shell ime set io.github.superjojo79.nextglyph/nextglyph.ime.NextglyphInputMethodService
adb shell am start -n io.github.superjojo79.nextglyph/nextglyph.ime.debug.FieldTestsActivity
```

L'écho se lit à l'écran ou par `adb shell uiautomator dump` (texte `<clé> = «…»`) ; chaque champ a l'identifiant de ressource `field_<clé>`.

## Résultats sur Android 16

Émulateur Android 16 (API 36, AVD `nextglyph36`), locale `fr-FR`, 1080 × 2340 px, 440 dpi (392 dp de large), disposition AZERTY. Saisie par touches simulées (`input tap`) sur le clavier Nextglyph, lecture de l'écho par `uiautomator dump`. Date : **2026-11-19**.

| # | Champ | `inputType` / `imeOptions` | Disposition attendue | Saisie | Résultat attendu | Observé |
|---|---|---|---|---|---|---|
| 1 | Texte | `text\|capSentences` | Compacte adaptative, barre | `bonjour␣` | `Bonjour ` (majuscule auto, validation) | `Bonjour ` ✔ |
| 2 | Texte multiligne | `text\|capSentences\|multiLine` | Compacte, `↵` = saut de ligne | `salut↵ok` | `Salut⏎Ok` | `Salut⏎Ok` ✔ |
| 3 | Nom de personne | `textPersonName\|capWords` | Compacte | `marie` | `Marie` | `Marie` ✔ |
| 4 | E-mail | `textEmailAddress` | Compacte, barre `@ . .com .fr - _` | `jean` `@` `exemple` `.fr` | `jean@exemple.fr` (ni majuscule ni espace) | `jean@exemple.fr` ✔ |
| 5 | URL | `textUri` | Compacte, barre `/ . .com .fr -` | `site` `.com` | `site.com` | `site.com` ✔ |
| 6 | Mot de passe | `textPassword` | Classique 6 rangées, sans barre | `⇧ a b 1 ! ?123 € ABC ⌫` | `Ab1!` (majuscule simple, `€` effacé par `⌫` sans lecture) | `Ab1!` ✔ |
| 6b | Mot de passe (verrouillage) | `textPassword` | `⇧` surligné, `⇪` | double appui `⇧`, `c d` | `Ab1!CD` | `Ab1!CD` ✔ |
| 7 | Mot de passe visible | `textVisiblePassword` | Classique 6 rangées | `x y 9` | `xy9` | `xy9` ✔ |
| 8 | Mot de passe web | `textWebPassword` | Classique 6 rangées | `pass` | `pass` | `pass` ✔ |
| 9 | Numérique | `number` | Pavé 4 × 4 (44 dp), `−` et séparateur absents | `1 2 3 ⌫ 4`, emplacements vides | `124` (vides inertes) | `124` ✔ |
| 10 | Numérique signé | `numberSigned` | Pavé avec `-` | `- 4 2` | `-42` | `-42` ✔ (voir écart 1) |
| 11 | Numérique décimal | `numberDecimal` | Pavé avec séparateur `,` | `3 , 1 4` | `3.14` (virgule refusée, repli sur le point, touche relibellée `.`) | `3.14` ✔ (voir écart 2) |
| 12 | Numérique signé décimal | `numberSigned\|numberDecimal` | Pavé avec `-` et séparateur | `- 2 , 5` | `-2.5` | `-2.5` ✔ |
| 13 | Numérique décimal, point seul | `numberDecimal` + `DigitsKeyListener("0123456789.")` | Pavé avec séparateur | `3 , 5` | `3.5` (repli) | `3.5` ✔ |
| 14 | Code PIN | `numberPassword` | Pavé sans `-` ni séparateur | `1 2 3 4` | `1234` | `1234` ✔ |
| 15 | Téléphone | `phone` | Pavé 5 colonnes | `+ 3 3 , 1 ; 2 * #` | `+33,1;2*#` | `+33,1;2*#` ✔ |
| 16 | Date | `date` | Pavé avec `/` et `-`, sans `:` | `1 9 / 1 1 - 2 6` | `19/11-26` | `19/11-26` ✔ |
| 17 | Heure | `time` | Pavé avec `:`, emplacements `/` `-` vides | `1 4 : 3 0` puis `/` `-` (vides) | `14:30` | `14:30` ✔ |
| 18 | Date et heure | `datetime` | Pavé avec `/ - :` | `1 / 2 - 3 : 4` | `1/2-3:4` | `1/2-3:4` ✔ |
| 19 | Sans suggestions | `text\|textNoSuggestions` | Compacte, sans validation | `bonjoue␣` | `bonjoue ` (pas de correction) | `bonjoue ` ✔ |
| 20 | Incognito | `text`, `flagNoPersonalizedLearning` | Compacte | `merci␣` | `merci ` | `merci ` ✔ |
| 21 | Action « envoyer » | `text`, `actionSend` | `↵` libellé `➤` | `a ↵` | `a`, `action=send` | ✔ |
| 22 | Action « rechercher » | `text`, `actionSearch` | `↵` libellé `⌕` | `a ↵` | `a`, `action=search` | ✔ |
| 23 | Action « aller » | `text`, `actionGo` | `↵` libellé `➔` | `a ↵` | `a`, `action=go` | ✔ |
| 24 | Action « suivant » | `text`, `actionNext` | `↵` libellé `⇥` | `a ↵` | `a`, `action=next` (focus au champ suivant) | ✔ |
| 25 | Action « terminé » | `text`, `actionDone` | `↵` libellé `✓` | `a ↵` | `a`, `action=done` (clavier masqué) | ✔ |
| 26 | Disposition classique | champ 1 | Appui long `?123` → « Classique », puis « Adaptative » | `⇧ x 1 *` | `Bonjour X1*`, puis retour à la compacte | `Bonjour X1*` ✔, retour vérifié par capture |

Les champs sans `imeOptions` explicite reçoivent du système `actionNext` (ou `actionDone` pour le dernier) : leur `↵` affiche `⇥`.

### Écarts constatés et corrigés

1. **Signe moins** : la touche du §8.1 insérait `−` (U+2212), refusé par les champs signés d'Android (`-42` donnait `42`). Elle insère désormais `-` (U+002D).
2. **Séparateur décimal** : en `fr-FR`, un `EditText` `numberDecimal` d'Android 16 refuse la virgule. `DecimalSeparatorPolicy` insère alors le point (vérification par le texte avant le curseur) et le mémorise pour le champ ; la touche affiche `.` dès le premier repli.

## Test instrumenté (§15.2)

`ime/src/androidTest/kotlin/nextglyph/ime/PasswordStorageTest.kt` (AndroidX Test et UI Automator, Apache-2.0) : active Nextglyph, ouvre le panel, tape dans le champ mot de passe sur les touches du clavier (géométrie calculée par le moteur) et vérifie que rien n'est ajouté ni modifié dans le stockage de l'application (`dataDir`, hors `code_cache`). Quatre cas : saisie, effacement, champ vide puis `↵`, et un contrôle que le détecteur repère un fichier écrit.

Lancement local, émulateur ou appareil connecté (le clavier courant est rétabli à la fin) :

```sh
./gradlew :ime:connectedDebugAndroidTest
```

Résultat sur l'émulateur Android 16 le 2026-11-19 : 4 tests, 0 échec (environ 1 min, compilation comprise). Rapport : `ime/build/reports/androidTests/connected/debug/index.html`.

Ce test n'est pas dans la CI : un job GitHub avec émulateur doit démarrer l'émulateur (plusieurs minutes) en plus de la compilation de l'IME et des lexiques, au-delà du budget de 10 minutes. Note : Gradle désinstalle l'application à la fin du test ; réinstaller l'APK de debug et refaire `ime enable` / `ime set` ensuite.
