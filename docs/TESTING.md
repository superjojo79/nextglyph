# Convention de tests

## La règle

**Chaque classe de test couvre trois catégories de cas, regroupées dans trois classes `@Nested` :**

| Groupe | Contenu |
|---|---|
| `HappyPath` | Les usages nominaux : entrées valides et typiques, résultat attendu. |
| `ErrorCases` | Les entrées invalides ou les états erronés : exception levée, message, absence d'effet de bord. |
| `BoundaryValues` | Les valeurs limites : vide, un seul élément, maximum exact, maximum + 1, Unicode inhabituel, locale exotique, etc. |

Chaque groupe contient au moins un test (`@Test`, `@ParameterizedTest` ou tout autre test JUnit).

```kotlin
class TextNormalizerTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `retire les accents`() { … }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un mot vide`() { … }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte un mot de exactement MAX_INPUT_LENGTH caractères`() { … }

        @Test
        fun `rejette un mot de MAX_INPUT_LENGTH plus un caractères`() { … }
    }
}
```

Si une unité n'a vraiment aucun cas d'erreur possible (fonction totale), le groupe `ErrorCases` le démontre : il teste par exemple qu'une entrée « bizarre » est acceptée sans exception. Le groupe n'est jamais omis.

## Contrôles automatiques

1. **`TestConventionTest`** (un par module, exécuté par `./gradlew build` ; le contrôle `TestConvention` est partagé via les test fixtures d'`engine`) : échoue si une classe de test `*Test` n'a pas ses trois groupes `@Nested` non vides.
2. **PIT** (`./gradlew pitest`, exécuté en CI) : tests de mutation. Le build échoue si :
   - moins de **85 %** des mutants sont tués ;
   - moins de **90 %** des lignes sont couvertes.

   Le rapport HTML est dans `engine/build/reports/pitest/index.html`, et en CI dans les artefacts `rapport-pitest-engine` et `rapport-pitest-tools` (un job par module).

## Tests de fuzzing

Les décodeurs qui lisent des octets ou des chaînes potentiellement corrompus (lexiques `.nglx`, modèle de mot suivant, fichier d'état appris, primitives de `ByteCodec`, carte de chaleur et compteurs de saisie) sont éprouvés par du **fuzzing** avec [Jazzer](https://github.com/CodeIntelligence/jazzer) (Apache-2.0). Un fuzzer génère des entrées en boucle, guidé par la couverture du code, et signale celles qui violent une propriété : seule l'exception documentée de l'API est permise (`LexiconFormatException`, `NgramFormatException`, `IllegalArgumentException`) ; la lecture tolérante de `LearnedDataFile` et des états texte ne doit jamais lever d'exception ; jamais de `OutOfMemoryError`, de boucle infinie ni d'accès hors bornes non maîtrisé.

- Les tests sont en Java (`engine/src/test/java/nextglyph/engine/fuzz/`, `*FuzzTest`), car Jazzer s'appuie sur `FuzzedDataProvider`. Ils suivent la convention : `HappyPath`, `ErrorCases` (qui porte les `@FuzzTest`) et `BoundaryValues` contiennent aussi des `@Test` ordinaires.
- Les entrées sont soit des octets bruts, soit un fichier valide altéré (`FuzzInputs`) : les contrôles profonds sont ainsi atteints vite.
- Dans `./gradlew build` (mode régression), Jazzer ne rejoue que les entrées de départ et celles sauvegardées dans les dossiers `…Inputs` des ressources de test : c'est rapide et déterministe. PIT ignore ces classes.
- Pour chercher vraiment, `scripts/fuzz.sh` lance chaque cible dans sa propre JVM (Jazzer n'en exécute qu'une à la fois) pendant le `maxDuration` de son `@FuzzTest` (30 à 60 s). Un fragment suffit : `scripts/fuzz.sh Lexicon`. Équivalent manuel : `JAZZER_FUZZ=1 ./gradlew :engine:test --tests 'nextglyph.engine.fuzz.LexiconFuzzTest*.arbitraryBytesOnlyRaiseFormatException'`.
- La CI exécute le même script dans le job « Fuzzing ». Une entrée fautive est écrite dans un fichier `crash-…` : la corriger dans le code, puis copier l'entrée dans `src/test/resources/nextglyph/engine/fuzz/<Classe>$<Groupe>Inputs/<méthode>/` et ajouter un test unitaire de régression.

## Bonnes pratiques

- Noms de tests en français, entre backticks, décrivant le comportement attendu.
- Structure Arrange / Act / Assert, séparée par des lignes vides.
- Assertions avec AssertJ ; tests paramétrés (`@ParameterizedTest`) pour les familles de cas.
- Pour les valeurs limites numériques, tester systématiquement `limite − 1`, `limite` et `limite + 1` quand ils ont un sens.
- Un test qui modifie un état global (locale, fuseau horaire) le restaure dans un `finally`.
- Un mutant qui survit dans PIT signale un test manquant : ajouter le test plutôt que baisser le seuil.
