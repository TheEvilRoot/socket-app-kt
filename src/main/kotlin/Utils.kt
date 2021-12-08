import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

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
    var failure = 0
    while (true) {
        try {
            if (buffer.size >= count)
                break
            val data = read()
            if (data < 0 || data > 0xFF)
                break
            val byte = data.toByte()
            buffer.add(byte)
        } catch (e: SocketTimeoutException) {
            Thread.sleep(1000)
            if (failure++ < 10)
                continue
            break
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

fun DatagramSocket.sendString(string: String, isa: InetSocketAddress?) {
    if (string.length > 255) {
        return string.chunked(255).forEach { sendString(it, isa) }
    }
    var failures = 0
    while (failures < 10) {
        try {
            val sizeBuffer = byteArrayOf(string.length.toByte())
            val sizePacket = if (isa == null)
                DatagramPacket(sizeBuffer, 0, sizeBuffer.size)
            else DatagramPacket(sizeBuffer, 0, sizeBuffer.size, isa.address, isa.port)
            send(sizePacket)
    //    var ack = ByteArray(1)
    //    var ackPacket = DatagramPacket(ack, 0, ack.size)
    //    receive(ackPacket)
            val packet = if (isa == null)
                DatagramPacket(string.toByteArray(), 0, string.length)
            else DatagramPacket(string.toByteArray(), 0, string.length, isa.address, isa.port)
            send(packet)
            return
        } catch (e: IOException) {
            Thread.sleep(1000)
            if (failures++ < 10)
                continue
            throw e
        }
    }
//    ack = ByteArray(1)
//    ackPacket = DatagramPacket(ack, 0, ack.size)
//    receive(ackPacket)
}

fun DatagramSocket.sendBuffer(buffer: ByteArray, isa: InetSocketAddress?) {
    if (buffer.size > 255) {
        return buffer.toList().chunked(255).forEach { sendBuffer(it.toByteArray(), isa) }
    }
    var failures = 0
    while (failures < 10) {
        try {
            val sizeBuffer = byteArrayOf(buffer.size.toByte())
            val sizePacket = if (isa == null)
                DatagramPacket(sizeBuffer, 0, sizeBuffer.size)
            else DatagramPacket(sizeBuffer, 0, sizeBuffer.size, isa.address, isa.port)
            send(sizePacket)
//    var ack = ByteArray(1)
//    var ackPacket = DatagramPacket(ack, 0, ack.size)
//    receive(ackPacket)
            val packet = if (isa == null)
                DatagramPacket(buffer, 0, buffer.size)
            else DatagramPacket(buffer, 0, buffer.size, isa.address, isa.port)
            send(packet)
            return
        } catch (e: IOException) {
            Thread.sleep(1000)
            if (failures++ < 10)
                continue
            throw e
        }
    }
//    ack = ByteArray(1)
//    ackPacket = DatagramPacket(ack, 0, ack.size)
//    receive(ackPacket)
}


fun DatagramSocket.readN(count: Long, isa: InetSocketAddress?): ByteArray {
    var ret = byteArrayOf()
    val sizeBuffer = ByteArray(1)
    while (ret.size < count) {
        val sizePacket = if (isa == null)
            DatagramPacket(sizeBuffer, 0, sizeBuffer.size)
        else DatagramPacket(sizeBuffer, 0, sizeBuffer.size, isa.address, isa.port)
        receive(sizePacket)
//        val ack = byteArrayOf(0)
//        val ackPacket = DatagramPacket(ack, 0, ack.size, sizePacket.address, sizePacket.port)
//        send(ackPacket)
        val size = sizePacket.data.firstOrNull()?.toUByte()?.toInt() ?: 0
        if (size > 0) {
            val buffer = ByteArray(size)
            val packet = if (isa == null)
                DatagramPacket(buffer, 0, size)
            else DatagramPacket(buffer, 0, size, isa.address, isa.port)
            receive(packet)
            ret += packet.data ?: byteArrayOf()
//            println("waiting ack")
//            val ack = byteArrayOf(size.toByte())
//            val ackPacket = DatagramPacket(ack, 0, ack.size, packet.address, packet.port)
//            send(ackPacket)
//            println("ack")
        }
    }
    return ret
}

fun DatagramSocket.readUntil(terminator: Byte, isa: InetSocketAddress?): Pair<DatagramPacket, ByteArray> {
    var ret = byteArrayOf()
    var packet = DatagramPacket(ByteArray(1), 0, 1)
    val sizeBuffer = ByteArray(1)
    while (ret.lastOrNull() != terminator) {
        packet = if (isa == null)
            DatagramPacket(sizeBuffer, 0, sizeBuffer.size)
        else DatagramPacket(sizeBuffer, 0, sizeBuffer.size, isa.address, isa.port)
        receive(packet)
        val size = packet.data.firstOrNull()?.toUByte()?.toInt() ?: 0
        if (size > 0) {
            val buffer = ByteArray(size)
            packet = if (isa == null)
                DatagramPacket(buffer, 0, buffer.size)
            else DatagramPacket(buffer, 0, buffer.size, isa.address, isa.port)
            receive(packet)
            ret += packet.data.copyOfRange(0, packet.length)
        }
    }
    return packet to ret.dropLast(1).toByteArray()
}