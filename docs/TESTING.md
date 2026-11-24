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

## Bonnes pratiques

- Noms de tests en français, entre backticks, décrivant le comportement attendu.
- Structure Arrange / Act / Assert, séparée par des lignes vides.
- Assertions avec AssertJ ; tests paramétrés (`@ParameterizedTest`) pour les familles de cas.
- Pour les valeurs limites numériques, tester systématiquement `limite − 1`, `limite` et `limite + 1` quand ils ont un sens.
- Un test qui modifie un état global (locale, fuseau horaire) le restaure dans un `finally`.
- Un mutant qui survit dans PIT signale un test manquant : ajouter le test plutôt que baisser le seuil.
