package nextglyph.tools.lexicons

import nextglyph.tools.ngrams.BuildNgramsCli
import kotlin.system.exitProcess

/** Construit les lexiques (`.nglx`) puis les modèles de mot suivant (`.ngrm`) dans le même dossier de sortie. */
fun main(args: Array<String>) {
    val lexicons = BuildLexiconsCli().run(args, System.out, System.err)
    val ngrams = BuildNgramsCli().run(args, System.out, System.err)
    exitProcess(maxOf(lexicons, ngrams))
}
