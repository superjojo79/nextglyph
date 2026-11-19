package nextglyph.engine.session

import nextglyph.engine.contacts.ContactContext
import nextglyph.engine.contacts.ContactDirectory
import nextglyph.engine.contacts.ContactName
import nextglyph.engine.contacts.ContactsConfig
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.learn.PersonalDictionary
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.RejectionStore

/**
 * Prédicteurs de la session (spec §6.3, §10.5) : un par niveau de contexte des contacts, construits
 * sur les mêmes lexiques (principaux, augmentés du dictionnaire personnel), la même disposition et la
 * même mémoire des refus. Sans contact, un seul prédicteur.
 *
 * Immuable. La construction ([build]) peut prendre quelques dizaines de millisecondes (lexiques des
 * mots appris et des noms) : l'IME la fait en arrière-plan, puis installe le résultat dans la session.
 *
 * @property directory noms des contacts, ou `null` sans contact.
 */
class PredictionStack(private val levels: List<Predictor>, val directory: ContactDirectory? = null) {

    init {
        require(levels.isNotEmpty()) { "Au moins un prédicteur est requis." }
        val expected = if (directory == null) 1 else directory.config.maxBoostLevel + 1
        require(levels.size == expected) { "$expected prédicteurs attendus, ${levels.size} reçus." }
        require(levels.all { it.languages == levels[0].languages }) { "Les prédicteurs doivent porter sur les mêmes langues." }
    }

    /** Prédicteur sans bonus de contexte (niveau 0) : distributions de l'adaptation, annulations. */
    val base: Predictor get() = levels[0]

    /** Codes des langues actives. */
    val languages: List<String> get() = base.languages

    /** Prédicteur à utiliser dans le contexte [context] (niveau des contacts, §6.3). */
    fun forContext(context: ContactContext): Predictor {
        val directory = directory ?: return base
        return levels[directory.levelOf(context)]
    }

    companion object {
        /** Un seul prédicteur, sans contacts. */
        fun of(predictor: Predictor): PredictionStack = PredictionStack(listOf(predictor))

        /**
         * Construit les prédicteurs : lexiques [base] (par langue, dans l'ordre des langues), augmentés des
         * mots promus de [personal] (§10.5), puis des noms de [contacts] (§6.3) avec leurs niveaux.
         * [personal] doit être une copie propre à l'appelant si la construction a lieu sur un autre fil
         * (`PersonalDictionary.copy`).
         */
        fun build(
            base: Map<String, LexiconSet>,
            layout: KeyboardLayout,
            personal: PersonalDictionary? = null,
            contacts: List<ContactName> = emptyList(),
            config: PredictionConfig = PredictionConfig(),
            rejections: RejectionStore = InMemoryRejectionStore(),
            contactsConfig: ContactsConfig = ContactsConfig(),
        ): PredictionStack {
            val sets = personal?.sets(base) ?: base
            val directory = ContactDirectory(contacts, sets, contactsConfig)
            if (directory.isEmpty) return of(Predictor(sets, layout, config, rejections))
            val levels = (0..contactsConfig.maxBoostLevel).map { level ->
                Predictor(directory.sets(level), layout, config, rejections, blockedAutocommit = directory.autocommitGuard(level))
            }
            return PredictionStack(levels, directory)
        }
    }
}
