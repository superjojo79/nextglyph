package nextglyph.tools.lexicons

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(BuildLexiconsCli().run(args, System.out, System.err))
}
