package nextglyph.tools.wordlists

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(BuildWordListsCli.run(args, System.out, System.err))
}
