package nextglyph.engine.edit

import nextglyph.engine.edit.EditConfig.Companion.APOSTROPHE
import nextglyph.engine.edit.EditConfig.Companion.NARROW_NBSP
import nextglyph.engine.edit.EditConfig.Companion.NBSP
import nextglyph.engine.input.EditOperation

/**
 * Édition de texte autour du curseur (§5.6, §5.7, §9.1) : logique pure, sans Android.
 *
 * Chaque méthode reçoit la situation de l'éditeur ([EditContext]) et renvoie les opérations à
 * appliquer dans un seul lot d'édition, le nouvel [EditState] et l'état de la touche majuscule.
 * Le moteur ne se fie jamais à l'état seul : l'espace à remplacer doit être présent dans
 * `textBefore`, sinon rien n'est supprimé (le champ a pu changer).
 *
 * Règles communes :
 * - l'espace automatique n'est ajouté qu'en fin de texte (`textAfter` vide) et jamais dans les profils
 *   sans typographie (e-mail, URL, mot de passe) ;
 * - la typographie française (insécables) ne s'applique qu'avec le profil adéquat, le réglage actif
 *   et la langue française ; jamais d'espace en double ni d'insécable en début de champ ou de ligne ;
 * - une espace ordinaire tapée par l'utilisateur est respectée et jamais transformée.
 */
class TextEditor(private val config: EditConfig = EditConfig()) {

    /**
     * Un mot vient d'être validé (déjà inséré : [EditContext.textBefore] se termine par [word]).
     * Ajoute l'espace automatique, sauf après une élision (apostrophe) ou un trait d'union final,
     * si le texte avant le curseur ne se termine pas par [word] (le champ a changé), ou hors
     * fin de texte.
     */
    fun onWordCommitted(ctx: EditContext, word: String): EditResult {
        val text = ctx.textBefore
        val allowed = ctx.profile.automaticTypography &&
            word.isNotEmpty() &&
            ctx.textAfter.isEmpty() &&
            text.endsWith(word) &&
            word.last() !in JOINERS
        val ops = if (allowed) listOf(EditOperation.InsertText(" ")) else emptyList()
        return result(ctx, ops, if (allowed) SpaceOrigin.AUTO else SpaceOrigin.NONE)
    }

    /**
     * Un signe de la barre de ponctuation (ou `;`, `«`, `»`) est tapé. Lève une
     * [IllegalArgumentException] pour tout autre caractère.
     *
     * Après un mot validé (état [SpaceOrigin.AUTO] et espace bien présente), l'espace automatique
     * est remplacée par le signe, puis une nouvelle espace automatique est ajoutée, sauf après `'`
     * et `«`. `«` conserve l'espace qui le précède (`dit «`).
     */
    fun onPunctuation(ctx: EditContext, sign: Char): EditResult {
        require(sign in EditConfig.SUPPORTED_SIGNS) { "Signe de ponctuation non pris en charge : '$sign'" }
        if (!ctx.profile.automaticTypography) {
            return result(ctx, listOf(EditOperation.InsertText(sign.toString())), SpaceOrigin.NONE)
        }
        val ops = mutableListOf<EditOperation>()
        var before = ctx.textBefore
        val replaceSpace = sign != '«' && ctx.state.trailingSpace == SpaceOrigin.AUTO && before.endsWith(' ')
        if (replaceSpace) {
            ops += EditOperation.DeleteBeforeCursor(1)
            before = before.subSequence(0, before.length - 1)
        }
        val french = config.frenchTypography && ctx.language == SentenceLanguage.FRENCH
        val inserted = StringBuilder()
        if (french) frenchSpaceBefore(sign, before)?.let(inserted::append)
        inserted.append(sign)
        if (french && sign == '«') inserted.append(NARROW_NBSP)
        val autoSpace = sign != APOSTROPHE && sign != '«' && ctx.textAfter.isEmpty()
        if (autoSpace) inserted.append(' ')
        ops += EditOperation.InsertText(inserted.toString())
        return result(ctx, ops, if (autoSpace) SpaceOrigin.AUTO else SpaceOrigin.NONE)
    }

    /**
     * Touche espace tapée sans mot à valider. Double espace (§5.6) : si le texte se termine par une
     * seule espace ordinaire (automatique ou tapée) précédée d'une lettre, d'un chiffre ou d'un
     * guillemet ou parenthèse fermant, elle devient `. `. Espace absorbée : juste après l'espace
     * automatique qui suit un signe de ponctuation (`va ? `), l'espace tapée par habitude ne
     * double pas l'espace ; elle est absorbée une fois (l'état passe à [SpaceOrigin.TYPED]).
     * Sinon, une espace est insérée.
     */
    fun onSpace(ctx: EditContext): EditResult {
        if (isAbsorbed(ctx)) return result(ctx, emptyList(), SpaceOrigin.TYPED)
        if (isDoubleSpace(ctx)) {
            val ops = listOf(EditOperation.DeleteBeforeCursor(1), EditOperation.InsertText(". "))
            return result(ctx, ops, SpaceOrigin.TYPED)
        }
        return result(ctx, listOf(EditOperation.InsertText(" ")), SpaceOrigin.TYPED)
    }

    private fun isAbsorbed(ctx: EditContext): Boolean {
        val text = ctx.textBefore
        if (ctx.state.trailingSpace != SpaceOrigin.AUTO || text.length < 2 || text[text.length - 1] != ' ') return false
        return text[text.length - 2] in SIGNS_WITHOUT_SPACE_AFTER
    }

    private fun isDoubleSpace(ctx: EditContext): Boolean {
        val text = ctx.textBefore
        if (!config.doubleSpacePeriod || !ctx.profile.automaticTypography) return false
        if (ctx.state.trailingSpace == SpaceOrigin.NONE || !text.endsWith(' ') || text.length < 2) return false
        val previous = Character.codePointBefore(text, text.length - 1)
        return Character.isLetterOrDigit(previous) || (Character.isBmpCodePoint(previous) && previous.toChar() in AutoCapitalization.CLOSERS)
    }

    /** Espace insécable à insérer avant [sign] en typographie française, ou `null`. */
    private fun frenchSpaceBefore(sign: Char, before: CharSequence): Char? {
        val space = when (sign) {
            ';', '!', '?', '»' -> NARROW_NBSP
            ':' -> NBSP
            else -> return null
        }
        if (before.isEmpty() || AutoCapitalization.isSpace(before.last())) return null
        if (sign != '»' && before.last() in SIGNS_WITHOUT_SPACE_AFTER) return null
        return space
    }

    private fun result(ctx: EditContext, ops: List<EditOperation>, origin: SpaceOrigin): EditResult {
        val after = apply(ctx.textBefore, ops)
        val shift = AutoCapitalization.shiftFor(after, ctx.profile, ctx.capsMode, ctx.shift, config)
        return EditResult(ops, EditState(origin), shift)
    }

    private fun apply(text: CharSequence, ops: List<EditOperation>): CharSequence {
        val sb = StringBuilder(text)
        for (op in ops) when (op) {
            is EditOperation.DeleteBeforeCursor -> sb.setLength(maxOf(0, sb.length - op.count))
            is EditOperation.InsertText -> sb.append(op.text)
        }
        return sb
    }

    private companion object {
        const val JOINERS = "'’ʼ-‑"
        const val SIGNS_WITHOUT_SPACE_AFTER = ".,;:!?…"
    }
}
