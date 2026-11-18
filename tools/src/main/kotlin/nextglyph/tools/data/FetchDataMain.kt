package nextglyph.tools.data

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(FetchDataCli.run(args, HttpDownloader(), System.out, System.err))
}
