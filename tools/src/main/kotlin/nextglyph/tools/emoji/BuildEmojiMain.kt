package nextglyph.tools.emoji

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(BuildEmojiCli().run(args, System.out, System.err))
}
