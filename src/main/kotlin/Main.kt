import x.XServer
import y.YClient
import java.util.*

fun main(args: Array<String>) {
    val logger = when (System.getenv("LOG")?.lowercase(Locale.getDefault())) {
        "debug" -> Logger.DebugLogger
        else -> Logger.NoneLogger
    }
    when (args.firstOrNull()?.lowercase(Locale.getDefault())) {
        "client" -> YClient(logger).listen()
        "server" -> XServer(logger).listenBlocking()
        else -> println("Invalid arguments\n${args.joinToString()}")
    }
}