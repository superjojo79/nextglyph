package nextglyph.tools.lexicons

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(ExportPrototypeDataCli.run(args, System.out, System.err))
}
