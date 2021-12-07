package x

import Logger
import fileName
import readN
import readUntil
import writeString
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.TimeUnit

class XServer(val log: Logger) {

    enum class State { ALIVE, FAILED, KILLED }

    sealed class ReadingStrategy(val client: XClient, val log: Logger) {

        abstract fun handleLoop(inputStream: InputStream, outputStream: OutputStream): ReadingStrategy

        class UploadReadingStrategy(client: XClient, log: Logger, outFileName: String, val outFileSize: Long): ReadingStrategy(client, log) {

            private val file: File = File(outFileName.fileName())
            private var fileOutput: FileOutputStream? = null
            private var progress: Long = 0

            private val startTime: Long = System.nanoTime()

            init {
                if (file.exists()) file.delete()
                if (file.parentFile?.exists() == false)
                    file.parentFile?.mkdirs()
                if (file.createNewFile() && file.canWrite())
                    fileOutput = FileOutputStream(file)
            }

            override fun handleLoop(inputStream: InputStream, outputStream: OutputStream): ReadingStrategy {
                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= outFileSize) {
                    fileOutput?.close()
                    outputStream.writeString(spd + "\n")
                    return CommandReadingStrategy(client, log)
                }
                val data = inputStream.readN(kotlin.math.min(512L, outFileSize - progress))
                if (data.isEmpty()) {
                    client.state = XClient.State.CLOSED
                    return this
                }

                fileOutput?.write(data, 0, data.size)
                progress += data.size

                return this
            }
        }

        class DownloadReadingStrategy(client: XClient, log: Logger, inFileName: String): ReadingStrategy(client, log) {

            private val file = File(inFileName)
            private var fileSize: Long = 0
            private var fileInput: FileInputStream? = null
            private var progress: Long = 0

            private val startTime: Long = System.nanoTime()

            init {
                if (file.exists() && file.canRead()) {
                    fileSize = file.length()
                    fileInput = FileInputStream(file)
                }
            }

            override fun handleLoop(inputStream: InputStream, outputStream: OutputStream): ReadingStrategy {
                val fileInput = fileInput

                if (fileSize <= 0 || fileInput == null)
                    return CommandReadingStrategy(client, log)

                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= fileSize) {
                    fileInput.close()
                    outputStream.writeString(spd + "\n")
                    return CommandReadingStrategy(client, log)
                }
                if (progress == 0L) {
                    outputStream.writeString(fileSize.toString() + "\n")
                }
                val bytes = fileInput.readN(kotlin.math.min(512L, fileSize - progress))
                outputStream.write(bytes)
                progress += bytes.size
                return this
            }
        }

        class CommandReadingStrategy(client: XClient, log: Logger): ReadingStrategy(client, log) {
            override fun handleLoop(inputStream: InputStream, outputStream: OutputStream): ReadingStrategy {
                val buffer = inputStream.readUntil('\n'.code.toByte())
                if (buffer.isEmpty()) {
                    client.state = XClient.State.CLOSED
                    return this
                }

                val line = String(buffer)
                log.log { "-> line $line" }
                val params = line.split(" ", limit=2)
                if (params.isNotEmpty() && params.first().equals("time", ignoreCase = true)) {
                    outputStream.writeString(Instant.now().toString() + "\n")
                    return this
                } else if (params.isNotEmpty() && params.first().equals("echo", ignoreCase = true)) {
                    outputStream.writeString(params.getOrElse(1) { "" } + "\n")
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
                    return UploadReadingStrategy(client, log, fileName, fileSize)
                } else if (params.isNotEmpty() && params.first().equals("download", ignoreCase = true)) {
                    val fileName = params.getOrNull(1)
                        ?: return this
                    if (fileName.isBlank()) return this
                    return DownloadReadingStrategy(client, log, fileName)
                } else {
                    return this
                }
            }
        }

    }

    var state: State = State.ALIVE

    private val server: ServerSocket = ServerSocket(2000, 50, InetAddress.getByName("0.0.0.0"))

    fun listenBlocking() {
        log.log { "-> listenBlocking $state" }
        while (state == State.ALIVE) {
            val socket = server.accept()
            log.log { "-> accepted ${socket.inetAddress.hostAddress}" }
            val client = XClient()
            loopClient(socket, client)
        }
        log.log { "<- listenBlocking $state" }
    }

    fun loopClient(socket: Socket, client: XClient) {
        val inputStream: InputStream = socket.getInputStream() ?: throw RuntimeException("no input")
        val outputStream: OutputStream = socket.getOutputStream() ?: throw RuntimeException("no output")
        var readingStrategy: ReadingStrategy = ReadingStrategy.CommandReadingStrategy(client, log)
        while (socket.isConnected && client.state == XClient.State.ALIVE) {
            log.log { "-> handleLoop ${readingStrategy::class.java.simpleName} ${socket.isConnected} ${socket.isClosed}" }
            readingStrategy = readingStrategy.handleLoop(inputStream, outputStream)
            log.log { "<- handleLoop ${readingStrategy::class.java.simpleName} ${socket.isConnected} ${socket.isClosed}" }
        }
    }

}