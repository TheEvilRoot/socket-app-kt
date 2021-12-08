package x

import Logger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

class XClient(val channel: SocketChannel, val log: Logger) {

    enum class State { ALIVE, CLOSED }

    var state: State = State.ALIVE

    var strategy: XServer.ReadingStrategy = XServer.ReadingStrategy.CommandReadingStrategy(this, log)

    var buffer: ByteArray = byteArrayOf()

    fun push() {
        val outputStream = ByteArrayOutputStream(1024 * 1024)
        log.log { "-> handleLoop ${strategy::class.java.simpleName}" }
        strategy = strategy.handleLoop(outputStream)
        log.log { "<- handleLoop ${strategy::class.java.simpleName}" }
        channel.write(ByteBuffer.wrap(outputStream.toByteArray()))
    }

}