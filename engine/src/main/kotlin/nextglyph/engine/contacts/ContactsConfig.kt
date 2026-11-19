package nextglyph.engine.contacts

import nextglyph.engine.text.WordPrefix

/**
 * Contexte d'écriture d'où dépend le bonus accordé aux noms de contacts (spec §6.3). L'IME le
 * renseigne à chaque changement de mot ; chaque indice vrai ajoute un niveau de bonus.
 *
 * @property personNameField le champ est de type `TYPE_TEXT_VARIATION_PERSON_NAME`.
 * @property atMessageStart début de message ou de champ (aucun mot avant le curseur).
 * @property previousWord mot qui précède le mot en cours, tel qu'écrit, ou `null` : un mot déclencheur
 *   (« bonjour », « salut », « à », « @ »...) ou un nom de contact en majuscule initiale (prénom → nom).
 * @property typedCapital la première lettre du mot en cours a été tapée en majuscule par l'utilisateur
 *   (et non par la majuscule automatique de début de phrase).
 */
data class ContactContext(
    val personNameField: Boolean = false,
    val atMessageStart: Boolean = false,
    val previousWord: String? = null,
    val typedCapital: Boolean = false,
) {
    companion object {
        /**
         * Contexte lu dans le texte qui précède le curseur ([textBeforeCursor], `null` si le champ ne le
         * fournit pas : seuls [personNameField] et [typedCapital] comptent alors).
         *
         * Le mot en cours (au sens de `WordPrefix.currentWord`, élisions de [elisionLanguage] retirées) est
         * écarté ; s'il ne reste que des blancs, c'est un début de message. Sinon, le mot précédent est la
         * dernière suite de lettres, apostrophes et traits d'union avant les blancs, une virgule éventuelle
         * étant sautée (« Bonjour, Hél » : le mot précédent est « Bonjour ») ; un `@` est lui-même le mot
         * précédent. Tout autre signe (point, chiffre) : pas de mot précédent.
         */
        fun read(
            textBeforeCursor: CharSequence?,
            personNameField: Boolean = false,
            typedCapital: Boolean = false,
            elisionLanguage: String = "",
        ): ContactContext {
            if (textBeforeCursor == null) return ContactContext(personNameField = personNameField, typedCapital = typedCapital)
            val word = WordPrefix.currentWord(textBeforeCursor, 0, elisionLanguage).orEmpty()
            val before = textBeforeCursor.subSequence(0, textBeforeCursor.length - word.length)
            var end = skipBlanks(before, before.length)
            if (end == 0) return ContactContext(personNameField, atMessageStart = true, typedCapital = typedCapital)
            if (before[end - 1] == ',') end = skipBlanks(before, end - 1)
            val previous = when {
                end == 0 -> null
                before[end - 1] == '@' -> "@"
                else -> {
                    var start = end
                    while (start > 0 && isNameChar(before[start - 1])) start--
                    if (start < end && before[start].isLetter()) before.substring(start, end) else null
                }
            }
            return ContactContext(personNameField, atMessageStart = false, previousWord = previous, typedCapital = typedCapital)
        }

        private fun skipBlanks(text: CharSequence, from: Int): Int {
            var end = from
            while (end > 0 && text[end - 1].isWhitespace()) end--
            return end
        }

        private fun isNameChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' || c == '-'
    }
}

/**
 * Constantes des noms de contacts (spec §6.3), immuables.
 *
 * @property baseShare poids de base d'un nom, en part de la masse du lexique de chaque langue
 *   (1e-5 : « un mot de fréquence moyenne », comme [nextglyph.engine.learn.LearningConfig.baseShare]).
 * @property contextBoost `contacts.contextBoost` : facteur multiplicatif par niveau de contexte (4).
 * @property maxBoostLevel nombre maximal d'indices cumulés (2) : poids ×1, ×4, ×16.
 * @property commonWordCap au niveau 0, un nom qui est aussi un mot du lexique pèse au plus cette part
 *   de la fréquence du mot (0,25) : le mot courant l'emporte.
 * @property maxMultiplicity plafond du facteur `1 + ln n` des noms portés par `n` contacts (3).
 * @property maxNames nombre maximal de noms indexés (20 000).
 * @property minNameLength longueur minimale d'un nom (2).
 * @property maxNameLength longueur maximale d'un nom (32).
 * @property includeNicknames indexer les surnoms (§6.2, « en option »).
 * @property triggerWords mots, en minuscules, après lesquels un nom est probable (§6.3), français
 *   et anglais.
 */
data class ContactsConfig(
    val baseShare: Double = 1e-5,
    val contextBoost: Double = 4.0,
    val maxBoostLevel: Int = 2,
    val commonWordCap: Double = 0.25,
    val maxMultiplicity: Double = 3.0,
    val maxNames: Int = 20_000,
    val minNameLength: Int = 2,
    val maxNameLength: Int = 32,
    val includeNicknames: Boolean = true,
    val triggerWords: Set<String> = FRENCH_TRIGGERS + ENGLISH_TRIGGERS + "@",
) {
    init {
        require(baseShare > 0.0 && baseShare <= 1.0) { "baseShare doit être dans ]0, 1] ($baseShare)." }
        require(contextBoost >= 1.0 && contextBoost.isFinite()) { "contextBoost doit valoir au moins 1 ($contextBoost)." }
        require(maxBoostLevel >= 0) { "maxBoostLevel doit être positif ou nul ($maxBoostLevel)." }
        require(commonWordCap > 0.0 && commonWordCap <= 1.0) { "commonWordCap doit être dans ]0, 1] ($commonWordCap)." }
        require(maxMultiplicity >= 1.0 && maxMultiplicity.isFinite()) { "maxMultiplicity doit valoir au moins 1 ($maxMultiplicity)." }
        require(maxNames >= 0) { "maxNames doit être positif ou nul ($maxNames)." }
        require(minNameLength >= 1) { "minNameLength doit valoir au moins 1 ($minNameLength)." }
        require(maxNameLength >= minNameLength) { "maxNameLength ($maxNameLength) doit valoir au moins minNameLength ($minNameLength)." }
    }

    companion object {
        /** « bonjour », « salut », « coucou », « cher », « chère », « avec », « chez », « à », « pour » (§6.3). */
        val FRENCH_TRIGGERS: Set<String> =
            setOf("bonjour", "salut", "coucou", "cher", "chère", "avec", "chez", "à", "pour")

        /** Équivalents anglais. */
        val ENGLISH_TRIGGERS: Set<String> = setOf("hello", "hi", "hey", "dear", "with", "to", "for", "from")
    }
}
