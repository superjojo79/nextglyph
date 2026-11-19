package nextglyph.tools.analysis

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(AnalyzeCorpusCli.run(args, System.out, System.err))
}
