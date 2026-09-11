package com.xmrip.app.protocol

/**
 * Trame MDR décodée.
 */
data class MdrFrame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MdrFrame && type == other.type && seq == other.seq && payload.contentEquals(other.payload)

    override fun hashCode(): Int = type * 31 + seq
}

/**
 * Encodage/décodage des trames MDR : START 0x3E, END 0x3C, escape 0x3D, checksum = somme mod 256.
 * Porté depuis Command.cpp / Command.hpp de libmdr.
 */
object MdrFramer {

    fun pack(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(6 + payload.size)
        body[0] = type.toByte()
        body[1] = seq.toByte()

        val size = payload.size.toLong()
        body[2] = ((size shr 24) and 0xFF).toByte()
        body[3] = ((size shr 16) and 0xFF).toByte()
        body[4] = ((size shr 8) and 0xFF).toByte()
        body[5] = (size and 0xFF).toByte()

        payload.copyInto(body, 6)

        var sum = 0
        for (i in 0 until body.size) {
            sum = (sum + (body[i].toInt() and 0xFF)) and 0xFF
        }

        val withChecksum = body + sum.toByte()
        val escaped = escape(withChecksum)

        val out = ByteArray(escaped.size + 2)
        out[0] = MdrConstants.START_MARKER.toByte()
        escaped.copyInto(out, 1)
        out[out.size - 1] = MdrConstants.END_MARKER.toByte()
        return out
    }

    fun unpack(frame: ByteArray): MdrFrame? {
        if (frame.size < 8) return null
        if ((frame[0].toInt() and 0xFF) != MdrConstants.START_MARKER) return null
        if ((frame[frame.size - 1].toInt() and 0xFF) != MdrConstants.END_MARKER) return null

        val body = unescape(frame.copyOfRange(1, frame.size - 1)) ?: return null
        if (body.size < 7) return null

        val type = body[0].toInt() and 0xFF
        val seq = body[1].toInt() and 0xFF
        val size =
            ((body[2].toLong() and 0xFF) shl 24) or
                ((body[3].toLong() and 0xFF) shl 16) or
                ((body[4].toLong() and 0xFF) shl 8) or
                (body[5].toLong() and 0xFF)

        // body = [type, seq, size(4), payload(size), checksum(1)]
        if (body.size.toLong() != 6L + size + 1L) return null

        var sum = 0
        for (i in 0 until body.size - 1) {
            sum = (sum + (body[i].toInt() and 0xFF)) and 0xFF
        }
        val storedChecksum = body[body.size - 1].toInt() and 0xFF
        if (sum != storedChecksum) return null

        return MdrFrame(type, seq, body.copyOfRange(6, 6 + size.toInt()))
    }

    private fun escape(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size * 2)
        for (byte in src) {
            when (byte.toInt() and 0xFF) {
                MdrConstants.END_MARKER -> {
                    out.write(MdrConstants.ESCAPE_BYTE); out.write(MdrConstants.ESCAPED_3C)
                }
                MdrConstants.ESCAPE_BYTE -> {
                    out.write(MdrConstants.ESCAPE_BYTE); out.write(MdrConstants.ESCAPED_3D)
                }
                MdrConstants.START_MARKER -> {
                    out.write(MdrConstants.ESCAPE_BYTE); out.write(MdrConstants.ESCAPED_3E)
                }
                else -> out.write(byte.toInt() and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun unescape(src: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            val byte = src[i].toInt() and 0xFF
            if (byte == MdrConstants.ESCAPE_BYTE) {
                if (i + 1 >= src.size) return null
                val next = src[i + 1].toInt() and 0xFF
                when (next) {
                    MdrConstants.ESCAPED_3C -> out.write(MdrConstants.END_MARKER)
                    MdrConstants.ESCAPED_3D -> out.write(MdrConstants.ESCAPE_BYTE)
                    MdrConstants.ESCAPED_3E -> out.write(MdrConstants.START_MARKER)
                    else -> return null
                }
                i += 2
            } else {
                out.write(byte)
                i += 1
            }
        }
        return out.toByteArray()
    }
}

/**
 * Reconstruction incrémentale des trames depuis un flux octet continu.
 */
class MdrFrameReader {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        buf = buf + chunk
    }

    /** Extrait toutes les trames complètes disponibles ; les trames malformées sont ignorées. */
    fun readAvailable(): List<MdrFrame> {
        val out = ArrayList<MdrFrame>(4)
        while (true) {
            val raw = nextRawFrame() ?: break
            val frame = MdrFramer.unpack(raw) ?: continue
            out.add(frame)
        }
        return out
    }

    private fun nextRawFrame(): ByteArray? {
        // Jette tout ce qui précède un START_MARKER.
        var start = -1
        for (i in buf.indices) {
            if ((buf[i].toInt() and 0xFF) == MdrConstants.START_MARKER) {
                start = i
                break
            }
        }
        if (start == -1) {
            buf = ByteArray(0)
            return null
        }
        if (start > 0) buf = buf.copyOfRange(start, buf.size)

        var end = -1
        for (i in 1 until buf.size) {
            if ((buf[i].toInt() and 0xFF) == MdrConstants.END_MARKER) {
                end = i
                break
            }
        }
        if (end == -1) return null // trame incomplète, on attend encore des octets

        val frame = buf.copyOfRange(0, end + 1)
        buf = buf.copyOfRange(end + 1, buf.size)
        return frame
    }
}