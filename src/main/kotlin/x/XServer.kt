package x

import Logger
import fileName
import readN
import readUntil
import sendBuffer
import sendString
import writeString
import java.io.*
import java.net.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.min

class XServer(val log: Logger) {

    enum class State { ALIVE, FAILED, KILLED }

    sealed class ReadingStrategy(val client: XClient, val log: Logger) {

        abstract fun handleLoop(socket: DatagramSocket): ReadingStrategy

        class UploadReadingStrategy(client: XClient, log: Logger, outFileName: String, val outFileSize: Long, val packet: DatagramPacket): ReadingStrategy(client, log) {

            private val file: File = File(outFileName.fileName())
            private var fileOutput: FileOutputStream? = null
            private var progress: Long = 0

            private val startTime: Long = System.nanoTime()

            private val isa: InetSocketAddress = InetSocketAddress(packet.address, packet.port)

            init {
                if (file.exists()) file.delete()
                if (file.parentFile?.exists() == false)
                    file.parentFile?.mkdirs()
                if (file.createNewFile() && file.canWrite())
                    fileOutput = FileOutputStream(file)
            }

            override fun handleLoop(socket: DatagramSocket): ReadingStrategy {
                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= outFileSize) {
                    fileOutput?.close()
                    socket.sendString(spd + "\n", isa)
                    return CommandReadingStrategy(client, log)
                }
                val data = socket.readN(kotlin.math.min(512L, outFileSize - progress), isa)
                if (data.isEmpty()) {
                    client.state = XClient.State.CLOSED
                    return this
                }

                fileOutput?.write(data, 0, data.size)
                progress += data.size

                return this
            }
        }

        class DownloadReadingStrategy(client: XClient, log: Logger, inFileName: String, packet: DatagramPacket): ReadingStrategy(client, log) {

            private val file = File(inFileName)
            private var fileSize: Long = 0
            private var fileInput: FileInputStream? = null
            private var progress: Long = 0

            private val startTime: Long = System.nanoTime()

            private val isa: InetSocketAddress = InetSocketAddress(packet.address, packet.port)

            init {
                if (file.exists() && file.canRead()) {
                    fileSize = file.length()
                    fileInput = FileInputStream(file)
                }
            }

            override fun handleLoop(socket: DatagramSocket): ReadingStrategy {
                val fileInput = fileInput

                if (fileSize <= 0 || fileInput == null) {
                    socket.sendString("0\n", isa)
                    return CommandReadingStrategy(client, log)
                }

                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= fileSize) {
                    fileInput.close()
                    socket.sendString(spd + "\n", isa)
                    return CommandReadingStrategy(client, log)
                }
                if (progress == 0L) {
                    socket.sendString(fileSize.toString() + "\n", isa)
                }
                val bytes = fileInput.readN(min(512L, fileSize - progress))
                socket.sendBuffer(bytes, isa)
                progress += bytes.size
                return this
            }
        }

        class CommandReadingStrategy(client: XClient, log: Logger): ReadingStrategy(client, log) {
            override fun handleLoop(socket: DatagramSocket): ReadingStrategy {
                log.log { "-> read" }
                val (packet, buffer) = socket.readUntil('\n'.code.toByte(), null)
                log.log { "<- read ${packet.length}" }
                val isa = InetSocketAddress(packet.address, packet.port)
                if (buffer.isEmpty()) {
                    client.state = XClient.State.CLOSED
                    return this
                }

                val line = String(buffer)
                log.log { "-> line $line" }
                val params = line.split(" ", limit=2)
                if (params.isNotEmpty() && params.first().equals("time", ignoreCase = true)) {
                    socket.sendString(Instant.now().toString() + "\n", isa)
                    return this
                } else if (params.isNotEmpty() && params.first().equals("echo", ignoreCase = true)) {
                    socket.sendString(params.getOrElse(1) { "" } + "\n", isa)
                    return this
                } else if (params.isNotEmpty() && params.first().equals("close", ignoreCase = true)) {
                    client.state = XClient.State.CLOSED
                    return this
                } else if (params.isNotEmpty() && params.first().equals("upload", ignoreCase = true)) {
                    val args = params.getOrNull(1)
                        ?.split(" ")
                        ?: return this
                    if (args.size != 2) return this
                    val fileName = args[0]
                    val fileSize = args[1].toLongOrNull() ?: return this
                    if (fileName.isBlank() || fileSize <= 0) return this
                    return UploadReadingStrategy(client, log, fileName, fileSize, packet)
                } else if (params.isNotEmpty() && params.first().equals("download", ignoreCase = true)) {
                    val fileName = params.getOrNull(1)
                        ?: return this
                    if (fileName.isBlank()) return this
                    return DownloadReadingStrategy(client, log, fileName, packet)
                } else {
                    return this
                }
            }
        }

    }

    var state: State = State.ALIVE

    fun listenBlocking() {
        log.log { "-> listenBlocking $state" }
        val socket = DatagramSocket(2002, InetAddress.getByName("localhost"))
        while (state == State.ALIVE) {
            val client = XClient()
            log.log { "-> loopClient ${socket.port}" }
            loopClient(socket, client)
            log.log { "<- loopClient ${socket.port}" }
            Thread.sleep(10_000)
        }
        log.log { "<- listenBlocking $state" }
    }

    fun loopClient(socket: DatagramSocket, client: XClient) {
        var readingStrategy: ReadingStrategy = ReadingStrategy.CommandReadingStrategy(client, log)
        while (client.state == XClient.State.ALIVE) {
            log.log { "-> handleLoop ${readingStrategy::class.java.simpleName} ${socket.isConnected} ${socket.isClosed}" }
            readingStrategy = readingStrategy.handleLoop(socket)
            log.log { "<- handleLoop ${readingStrategy::class.java.simpleName} ${socket.isConnected} ${socket.isClosed}" }
        }
    }

}