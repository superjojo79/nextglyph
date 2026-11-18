package nextglyph.engine.lexicon

/**
 * Une forme d'affichage du lexique.
 *
 * @property form forme d'affichage, telle qu'insérée à la validation (`école`).
 * @property key clé normalisée, partagée par toutes les variantes (`ecole`).
 * @property frequency fréquence de cette forme, dans `[1, Long.MAX_VALUE]`.
 */
data class LexiconEntry(val form: String, val key: String, val frequency: Long)
