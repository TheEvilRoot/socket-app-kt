package y

import Logger
import fileName
import readN
import readUntil
import writeString
import y.YClient.ReadingStrategy.SocketDownloadStrategy.Companion.inputBuffer
import java.io.*
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.*
import kotlin.math.min

class YClient(val log: Logger) {

    sealed class ReadingStrategy(val client: YClient, val log: Logger) {

        abstract fun handleLoop(outputStream: OutputStream, inputStream: InputStream): ReadingStrategy

        class SocketLineReadingStrategy(client: YClient, log: Logger, val handler: (YClient, ByteArray) -> ReadingStrategy): ReadingStrategy(client, log) {
            override fun handleLoop(outputStream: OutputStream, inputStream: InputStream): ReadingStrategy {
                val data = inputStream.readUntil('\n'.code.toByte())
                if (data.isEmpty()) {
                    client.socket.close()
                    return StdInReadingStrategy(client, log)
                }
                return handler(client, data)
            }
        }

        class SocketUploadStrategy(client: YClient, log: Logger, file: File, val size: Long): ReadingStrategy(client, log) {

            private val fileInput: FileInputStream = file.inputStream()
            private var progress: Long = 0

            override fun handleLoop(outputStream: OutputStream, inputStream: InputStream): ReadingStrategy {
                if (progress >= size) {
                    fileInput.close()
                    return SocketLineReadingStrategy(client, log) { client, data ->
                        val string  = String(data)
                        println("Upload speed: $string bytes per second")
                        StdInReadingStrategy(client, log)
                    }
                }
                val bytes = fileInput.readN(min(512, size - progress))
                outputStream.write(bytes, 0, bytes.size)
                progress += bytes.size
                return this
            }
        }

        class SocketDownloadStrategy(client: YClient, log: Logger, fileName: String, val fileSize: Long): ReadingStrategy(client, log) {

            companion object {
                val inputBuffer: Stack<String> = Stack()
            }

            private val file = File(fileName.fileName())
            private var fileOutput: FileOutputStream? = null
            private var progress: Long = 0

            init {
                if (file.exists())
                    file.delete()
                if (file.parentFile?.exists() == false)
                    file.parentFile?.mkdirs()
                if (file.createNewFile() && file.canWrite())
                    fileOutput = file.outputStream()
            }

            override fun handleLoop(outputStream: OutputStream, inputStream: InputStream): ReadingStrategy {
                if (fileOutput == null)
                    return StdInReadingStrategy(client, log)
                if (progress >= fileSize) {
                    fileOutput?.close()
                    return SocketLineReadingStrategy(client, log) { client, data ->
                        val string  = String(data)
                        println("Download speed: $string bytes per second")
                        StdInReadingStrategy(client, log)
                    }
                }
                val buffer = inputStream.readN(min(512, fileSize - progress))
                if (buffer.isEmpty()) {
                    client.socket.close()
                    return StdInReadingStrategy(client, log)
                }
                fileOutput?.write(buffer, 0, buffer.size)
                progress += buffer.size
                return this
            }
        }

        class StdInReadingStrategy(client: YClient, log: Logger): ReadingStrategy(client, log) {

            fun handleStdIn(line: String, outputStream: OutputStream, inputStream: InputStream): ReadingStrategy {
                val params = line.split(" ", limit = 2)
                if (params.isEmpty()) return this
                if (params.first().equals("time", ignoreCase = true)) {
                    outputStream.writeString("time\n")
                    return SocketLineReadingStrategy(client, log) { _, data ->
                        val string = String(data)
                        println("Time: $string")
                        this@StdInReadingStrategy
                    }
                } else if (params.first().equals("echo", ignoreCase = true)) {
                    val message = params.getOrNull(1)
                        ?.replace("\n", " ")
                        ?: return this
                    outputStream.writeString("echo $message\n")
                    return SocketLineReadingStrategy(client, log) { _, data ->
                        val string = String(data)
                        println("Echo: $string")
                        this@StdInReadingStrategy
                    }
                } else if (params.first().equals("close", ignoreCase = true)) {
                    outputStream.writeString("close\n")
                    return this
                } else if (params.first().equals("upload", ignoreCase = true)) {
                    val filePath = params.getOrNull(1)
                        ?: return this
                    val file = File(filePath)
                    if (!file.exists() || !file.canRead())
                        return this
                    val fileSize = file.length()
                    val fileName = filePath.replace(" ", "_")
                    outputStream.writeString("upload $fileName $fileSize\n")
                    return SocketUploadStrategy(client, log, file, fileSize)
                } else if (params.first().equals("download", ignoreCase = true)) {
                    val fileName = params.getOrNull(1)
                        ?: return this
                    outputStream.writeString("download $fileName\n")
                    return SocketLineReadingStrategy(client, log) f@{ client, data ->
                        val fileSize = String(data).toLongOrNull()
                            ?: return@f this@StdInReadingStrategy
                        if (fileSize <= 0)
                            return@f this@StdInReadingStrategy
                        return@f SocketDownloadStrategy(client, log, fileName, fileSize)
                    }
                } else {
                    return this
                }
            }

            override fun handleLoop(outputStream: OutputStream, inputStream: InputStream): ReadingStrategy {
                if (inputBuffer.isNotEmpty())
                    return handleStdIn(inputBuffer.pop(), outputStream, inputStream)
                print("> ")
                val line = readLine() ?: return this
                if (line.isBlank()) return this
                try {
                    return handleStdIn(line, outputStream, inputStream)
                } catch (e: Exception) {
                    inputBuffer.push(line)
                    throw e
                }
            }
        }
    }

    private var socket: Socket = Socket()
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    fun createSocket(): Boolean {
        if (!socket.isClosed)
            socket.close()
        socket = Socket()
        log.log { "-> connect" }
        socket.soTimeout = 10_000

        try {
            socket.connect(InetSocketAddress("127.0.0.1", 2003))
            log.log { " <- connect" }

            outputStream = socket.getOutputStream()
            inputStream = socket.getInputStream()
        } catch (e: ConnectException) {
            println("Server is inaccessible")
            return false
        }
        return true
    }

    fun listen() {
        if (!createSocket()) return

        var strategy: ReadingStrategy = ReadingStrategy.StdInReadingStrategy(this, log)
        var reconnection = 0
        while (socket.isConnected) {
            val inputStream = inputStream
            val outputStream = outputStream
            if (inputStream == null || outputStream == null)
                break
            log.log { "-> handleLoop ${strategy::class.java.simpleName}" }
            try {
                strategy = strategy.handleLoop(outputStream, inputStream)
            } catch (e: SocketException) {
                log.log { "<- socket broken. reconnecting $reconnection..." }
                if (++reconnection < 10)
                    if (createSocket()) continue
                break
            }
            log.log { "<- handleLoop ${strategy::class.java.simpleName}" }
        }
    }

}