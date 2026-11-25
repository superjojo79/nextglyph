package nextglyph.tools.pack

import kotlin.system.exitProcess

/** Point d'entrée sans logique : voir [BuildDictionaryPackCli]. */
fun main(args: Array<String>) {
    exitProcess(BuildDictionaryPackCli().run(args, System.out, System.err))
}
