package be.ugent.topl.mio.woodstate

import kotlin.streams.toList

class HexaEncoder {
    companion object {
        fun serializeBool(b: Boolean): String {
            return serializeUInt(if (b) 1U else 0U, 1, true)
        }

        fun serializeUInt8(n: Int): String {
            return serializeUInt(n.toUInt(), 1, true)
        }

        fun serializeUInt16BE(n: Int): String {
            return serializeUInt(n.toUInt(), 2, true)
        }

        fun serializeUInt32BE(n: Int): String {
            return serializeUInt(n.toUInt(), 4, true)
        }

        fun serializeUInt32BE(n: UInt): String {
            return serializeUInt(n, 4, true)
        }

        fun serializeInt32LE(n: Int): String {
            return serializeUInt(n.toUInt(), 4, false)
        }

        fun serializeUInt32LE(n: Int): String {
            return serializeUInt(n.toUInt(), 4, false)
        }

        fun serializeBigUInt64LE(n: Long): String {
            return serializeBigUInt64(n, false)
        }

        fun serializeBigUInt64BE(n: Long): String {
            return serializeBigUInt64(n, true)
        }

        fun serializeBigUInt64(n: Long, bigendian: Boolean): String {
            val str = String.format("%016x", n)
            return if (bigendian) str else hexStringBEToLE(str)
        }

        fun serializeUInt(n: UInt, amountBytes: Int, bigendian: Boolean): String {
            if (amountBytes < 1 || amountBytes > 4) {
                throw Error("invalid amount of bytes")
            }
            // We have 4 bytes, our number is limited to amountBytes so we throw away the other bytes.
            val shift = (4 - amountBytes) * 8
            val nLimited = ((n shl shift) shr shift)

            val str = String.format("%0${amountBytes*2}x", nLimited.toLong())
            if (!bigendian) {
                return str.chunked(2).reversed().joinToString(separator = "")
            }
            return str
        }

        fun serializeInt32(n: Int, bigendian: Boolean): String {
            val str = String.format("%08x", n)
            return if (bigendian) str else hexStringBEToLE(str)
        }

        fun serializeFloatBE(n: Float): String {
            return serializeFloat(n, true)
        }

        fun serializeFloatLE(n: Float): String {
            return serializeFloat(n, false)
        }

        fun serializeFloat(n: Float, bigendian: Boolean): String {
            val bytes = java.lang.Float.floatToIntBits(n)
            return serializeInt32(bytes, bigendian)
        }

        fun serializeDoubleBE(n: Double): String {
            return serializeDouble(n, true)
        }

        fun serializeDoubleLE(n: Double): String {
            return serializeDouble(n, false)
        }

        fun serializeDouble(n: Double, bigendian: Boolean): String {
            val bytes = java.lang.Double.doubleToLongBits(n)
            return serializeBigUInt64(bytes, bigendian)
        }

        fun serializeString(s: String): String {
            return s.chars().toList().joinToString("") { c: Int -> String.format("%02x", c) }
        }

        fun convertToLEB128(a: Int): String { // TODO can only handle 32 bit
            var a = a or 0
            val result = mutableListOf<String>()
            while (true) {
                val byte_ = a and 0x7f
                a  = a shr 7
                if (
                    (a == 0 && (byte_ and 0x40) == 0) ||
                    (a == -1 && (byte_ and 0x40) != 0)
                ) {
                    result.add(byte_.toString(16).padStart(2, '0'))
                    return result.joinToString("").uppercase()
                }
                result.add((byte_ or 0x80).toString(16).padStart(2, '0'))
            }
        }

        fun hexStringBEToLE(str: String): String = str.chunked(2).reversed().joinToString(separator = "")
    }
}
