package nextglyph.ime.lexicon

import nextglyph.engine.lexicon.Lexicon
import java.util.concurrent.Executor

/**
 * Charge le lexique d'une langue en arrière-plan et remet le résultat sur le fil principal, pour que
 * l'ouverture du clavier ne soit jamais bloquée (le clavier reste neutre tant que le lexique n'est
 * pas prêt, spec §11.3).
 *
 * M1 : une seule langue active à la fois. Un résultat périmé (langue changée entre-temps) est
 * ignoré ; un échec de chargement est livré comme `null` (clavier neutre) et ne fait jamais planter
 * le service. À utiliser depuis le fil principal uniquement.
 *
 * @param open ouvre le lexique de la langue demandée (peut lever n'importe quelle exception).
 * @param background exécute le chargement.
 * @param main exécute les rappels (fil principal).
 */
class LexiconLoader(
    private val open: (String) -> Lexicon,
    private val background: Executor,
    private val main: Executor,
) {

    private var generation = 0
    private var requested: String? = null

    /**
     * Demande le lexique de [language] ; [onLoaded] reçoit la langue et le lexique (ou `null` en cas
     * d'échec) sur le fil principal. Sans effet si cette langue est déjà demandée.
     */
    fun load(language: String, onLoaded: (String, Lexicon?) -> Unit) {
        if (language == requested) return
        requested = language
        val mine = ++generation
        background.execute {
            val lexicon = try {
                open(language)
            } catch (_: Exception) {
                null
            }
            main.execute { if (mine == generation) onLoaded(language, lexicon) }
        }
    }

    /** Oublie la demande en cours : son résultat ne sera pas livré, et la même langue peut être redemandée. */
    fun cancel() {
        generation++
        requested = null
    }
}
