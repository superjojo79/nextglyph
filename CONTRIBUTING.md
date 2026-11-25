# Contribuer à NextGlyph

Merci de votre intérêt pour le projet.

## Avant de commencer

- La [spécification](docs/SPEC.md) décrit le comportement attendu. Une modification de comportement commence par une modification de la spec.
- Les failles de sécurité se signalent en privé : voir [SECURITY.md](SECURITY.md).

## Développement

Prérequis : JDK 21 ou plus récent.

```sh
./gradlew build     # compilation, tests, convention de tests
./gradlew pitest    # tests de mutation
```

## Tests

Toute contribution respecte la [convention de tests](docs/TESTING.md) : chaque classe de test couvre le **chemin nominal**, les **cas d'erreur** et les **valeurs limites**. La CI le vérifie et applique des seuils de tests de mutation.

## Pull requests

- Une pull request = un sujet.
- La CI doit être verte : build, tests, PIT, CodeQL, revue des dépendances.
- Aucune dépendance sous licence GPL, LGPL ou AGPL (incompatible avec la distribution sous Apache-2.0).
- Aucune dépendance propriétaire (Google Play Services, Firebase, SDK d'analyse) : condition pour F-Droid.
- Aucune permission réseau.

## Licence

En contribuant, vous acceptez que votre contribution soit publiée sous la licence [Apache-2.0](LICENSE).
