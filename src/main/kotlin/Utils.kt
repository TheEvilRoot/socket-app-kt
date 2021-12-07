import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun InputStream.readUntil(terminator: Byte, including: Boolean = false): ByteArray {
    val buffer = mutableListOf<Byte>()
    while (true) {
        try {
            val data = read()
            if (data < 0 || data > 0xFF)
                break
            val byte = data.toByte()
            if (terminator != byte || including)
                buffer.add(data.toByte())
            if (terminator == byte)
                break
        } catch (e: IOException) {
            break
        } catch (e: EOFException) {
            break
        }
    }
    return buffer.toByteArray()
}

fun InputStream.readN(count: Long): ByteArray {
    val buffer = mutableListOf<Byte>()
    while (true) {
        try {
            if (buffer.size >= count)
                break
            val data = read()
            if (data < 0 || data > 0xFF)
                break
            val byte = data.toByte()
            buffer.add(byte)
        } catch (e: IOException) {
            e.printStackTrace()
            break
        } catch (e: EOFException) {
            e.printStackTrace()
            break
        }
    }
    return buffer.toByteArray()
}

fun OutputStream.writeString(string: String) {
    write(string.toByteArray(), 0, string.length)
}

fun String.fileName(): String {
    if (isBlank())
        return "unnamed_file"
    return split("/").last()
}