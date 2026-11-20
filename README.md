# Nextglyph

Clavier Android adaptatif et open source. Il garde une disposition AZERTY ou QWERTY compacte et facile à reconnaître, et met en avant, à chaque lettre tapée, les lettres qui peuvent encore former un mot valide.

> Statut : conception. Aucune version n'est encore publiée.

- Objectif : gagner en vitesse et en place à l'écran, sans perte de confort.
- Langues de la V1 : français et anglais.
- Respect de la vie privée : aucune permission réseau, tout reste sur l'appareil.
- Distribution prévue : F-Droid et Google Play.

## Documentation

- [Spécification](docs/SPEC.md)
- [Convention de tests](docs/TESTING.md)
- [Contribuer](CONTRIBUTING.md)
- [Signaler une faille de sécurité](SECURITY.md)
- [Publication](docs/PUBLISHING.md) et [politique de confidentialité](docs/PRIVACY.md)

## Développement

Prérequis : JDK 21 ou plus récent.

```sh
./gradlew build     # compilation, tests et contrôle de la convention de tests
./gradlew pitest    # tests de mutation (rapport dans */build/reports/pitest)
```

## Licence

Apache License 2.0 : voir [LICENSE](LICENSE) et [NOTICE](NOTICE).
