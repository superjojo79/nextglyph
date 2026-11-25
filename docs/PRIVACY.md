# Politique de confidentialité — Privacy policy

*Nextglyph, clavier Android. Dernière mise à jour : 24 novembre 2026.*

[English version below](#english)

## Français

Nextglyph ne collecte, ne transmet et ne partage aucune donnée.

- **Aucun accès réseau dans la version F-Droid.** Elle ne demande pas la permission Internet : rien de ce que vous tapez, ni aucune autre donnée, ne peut quitter votre appareil par son intermédiaire. Elle ne contient ni publicité, ni statistiques d'usage, ni rapport de plantage, ni bibliothèque tierce de ce type.
- **Version Google Play : réseau pour les dictionnaires seulement.** Cette version déclare la permission Internet pour une seule fonction, facultative et désactivée par défaut : « Mises à jour en ligne ». Si vous l'activez, le bouton « Rechercher une mise à jour » télécharge, uniquement quand vous appuyez dessus, deux petits fichiers publics puis, si vous confirmez, un paquet de dictionnaires signé, depuis `github.com` en HTTPS. Rien d'autre n'est envoyé que cette requête (adresse IP et en-tête `User-Agent: Nextglyph`, sans version, identifiant, cookie ni texte saisi) ; GitHub peut journaliser ces requêtes selon sa propre politique. Aucune vérification n'a lieu en arrière-plan. Dans les deux versions, vous pouvez aussi télécharger le paquet dans votre navigateur et l'importer, ou rester sur les dictionnaires embarqués.
- **Texte saisi.** Le texte est traité en mémoire pour proposer des lettres et des mots, puis oublié. Il n'est jamais journalisé. Dans les champs mot de passe et code PIN, rien n'est lu ni appris.
- **Données apprises.** Pour s'adapter à vous, le clavier enregistre sur l'appareil les mots que vous utilisez, ceux que vous refusez et un modèle de vos appuis sur les touches. Ces données restent dans l'espace privé de l'application et sont exclues des sauvegardes Android. Vous pouvez les exporter, les importer ou les effacer en un geste depuis les réglages ; la désinstallation les supprime aussi.
- **Presse-papiers.** Tant que Nextglyph est le clavier actif, il garde en mémoire les 10 derniers textes copiés pour vous permettre de les recoller. Cet historique n'est jamais écrit sur le disque et disparaît à l'arrêt du clavier ; rien n'est retenu en navigation privée, dans un champ mot de passe ni pour un contenu marqué sensible.
- **Mesures de saisie.** Le clavier tient des compteurs locaux (vitesse, corrections, suggestions retenues), jamais de texte, affichés dans les réglages et remis à zéro d'un geste.
- **Contacts (facultatif).** Si vous activez « Noms des contacts » et accordez la permission `READ_CONTACTS`, le clavier lit les noms de vos contacts pour les proposer pendant la saisie. Il ne lit que les noms, les garde en mémoire seulement, et les oublie dès que vous désactivez la fonction. Vous pouvez retirer la permission à tout moment dans les réglages d'Android.
- **Enfants.** L'application ne collecte aucune donnée, y compris auprès d'enfants.

Le code source est public : https://github.com/superjojo79/nextglyph. Pour toute question, ouvrez une issue sur ce dépôt.

## English

Nextglyph does not collect, transmit or share any data.

- **No network access in the F-Droid version.** It does not request the Internet permission: nothing you type, and no other data, can leave your device through it. It contains no ads, no usage analytics, no crash reporting, and no third-party library of that kind.
- **Google Play version: network for dictionaries only.** This version declares the Internet permission for a single optional feature, off by default: "Online updates". If you turn it on, the "Search for an update" button downloads, only when you tap it, two small public files and then, if you confirm, a signed dictionary pack from `github.com` over HTTPS. Nothing is sent beyond that request (IP address and a `User-Agent: Nextglyph` header, with no version, identifier, cookie or typed text); GitHub may log such requests under its own policy. Nothing is checked in the background. In both versions you can also download the pack in your browser and import it, or keep the bundled dictionaries.
- **Typed text.** Text is processed in memory to suggest letters and words, then forgotten. It is never logged. In password and PIN fields, nothing is read or learned.
- **Learned data.** To adapt to you, the keyboard stores on the device the words you use, the words you reject and a model of how you tap the keys. This data stays in the app's private storage and is excluded from Android backups. You can export, import or erase it in one tap from the settings; uninstalling the app deletes it too.
- **Clipboard.** While Nextglyph is the active keyboard, it keeps the last 10 copied texts in memory so you can paste them again. This history is never written to disk and disappears when the keyboard stops; nothing is kept in incognito mode, in password fields, or for content marked as sensitive.
- **Typing statistics.** The keyboard keeps local counters (speed, corrections, accepted suggestions), never text, shown in the settings and reset in one tap.
- **Contacts (optional).** If you turn on "Contact names" and grant the `READ_CONTACTS` permission, the keyboard reads your contacts' names to suggest them while you type. It reads names only, keeps them in memory only, and forgets them as soon as you turn the feature off. You can revoke the permission at any time in Android settings.
- **Children.** The app collects no data, including from children.

The source code is public: https://github.com/superjojo79/nextglyph. For any question, open an issue on this repository.
