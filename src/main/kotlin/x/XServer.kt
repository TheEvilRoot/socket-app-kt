package x

import Logger
import fileName
import putString
import readN
import writeString
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.time.Instant

class XServer(val log: Logger) {

    enum class State { ALIVE, FAILED, KILLED }

    sealed class ReadingStrategy(val client: XClient, val log: Logger) {

        abstract fun handleLoop(outputStream: ByteBuffer): ReadingStrategy

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

            override fun handleLoop(outputStream: ByteBuffer): ReadingStrategy {
                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= outFileSize) {
                    fileOutput?.close()
                    outputStream.putString(spd + "\n")
                    return CommandReadingStrategy(client, log)
                }
                val required = kotlin.math.min(512L, outFileSize - progress).toInt()
                if (client.buffer.size < required)
                    return this
                val data = client.buffer.copyOfRange(0, required)
                client.buffer = client.buffer.drop(required).toByteArray()
                if (data.isEmpty()) {
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

            override fun handleLoop(outputStream: ByteBuffer): ReadingStrategy {
                val fileInput = fileInput

                if (fileSize <= 0 || fileInput == null)
                    return CommandReadingStrategy(client, log)

                val delta = System.nanoTime() - startTime
                val bps = if (delta > 0) progress.toDouble() / (delta.toDouble() / 1_000_000_000) else 0.0
                val spd =  String.format("%.2f", bps)
                log.log { "Average transmission speed: $spd" }

                if (progress >= fileSize) {
                    fileInput.close()
                    outputStream.putString(spd + "\n")
                    return CommandReadingStrategy(client, log)
                }
                if (progress == 0L) {
                    outputStream.putString(fileSize.toString() + "\n")
                }
                val bytes = fileInput.readN(kotlin.math.min(512L, fileSize - progress))
                outputStream.put(bytes)
                progress += bytes.size
                return this
            }
        }

        class CommandReadingStrategy(client: XClient, log: Logger): ReadingStrategy(client, log) {
            override fun handleLoop(outputStream: ByteBuffer): ReadingStrategy {
                if (!client.buffer.contains('\n'.code.toByte()))
                    return this
                val buffer = client.buffer.copyOfRange(0, client.buffer.indexOf('\n'.code.toByte()))
                client.buffer = client.buffer.drop(buffer.size + 1).toByteArray()
                if (buffer.isEmpty()) {
                    return this
                }

                val line = String(buffer)
                log.log { "-> line $line" }
                val params = line.split(" ", limit=2)
                if (params.isNotEmpty() && params.first().equals("time", ignoreCase = true)) {
                    outputStream.putString(Instant.now().toString() + "\n")
                    return this
                } else if (params.isNotEmpty() && params.first().equals("echo", ignoreCase = true)) {
                    outputStream.putString(params.getOrElse(1) { "" } + "\n")
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

    private val socketChannel = ServerSocketChannel.open()

    fun listenBlocking() {
        socketChannel.configureBlocking(false)
        val server = socketChannel.socket()

        server.bind(InetSocketAddress(2003))
        val selector = Selector.open()
        socketChannel.register(selector, SelectionKey.OP_ACCEPT)
        log.log { "-> listenBlocking $state" }

        val clients = mutableMapOf<SocketAddress, XClient>()
        while (state == State.ALIVE) {
            val x = selector.select(20)
            if (x == 0) {
                clients.forEach { (_, u) ->
                    when (u.strategy) {
                        is ReadingStrategy.DownloadReadingStrategy,
                        is ReadingStrategy.UploadReadingStrategy -> u.push()
                    }
                }
                continue
            }
            val keys = selector.selectedKeys()
            for (key in keys.iterator()) {
                if ((key.readyOps() and SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT) {
                    val socket = server.accept()
                    log.log { "-> accepted ${socket.inetAddress.hostAddress}" }
                    val channel = socket.channel
                    channel.configureBlocking(false)
                    channel.register(selector, SelectionKey.OP_READ)
                    clients[channel.remoteAddress] = XClient(channel, log)
                } else if ((key.readyOps() and SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                    val clientChannel = key.channel() as SocketChannel
                    val buffer = ByteBuffer.allocate(512)
                    val count = clientChannel.read(buffer)
                    if (count > 0) {
                        val client = clients[clientChannel.remoteAddress]
                        if (client != null) {
                            client.buffer += buffer.array().copyOfRange(0, count)
                            client.push()
                        }
                    }
                }
            }
            keys.removeAll(keys)
        }
        log.log { "<- listenBlocking $state" }
    }

}