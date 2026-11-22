package com.hereliesaz.stencilartist.app

/**
 * Bundles several files into one ZIP archive, so a stencil of many sheets is one download and not
 * a dozen. Files are STORED, not compressed: the archive exists to keep them together, the formats
 * inside are small or already compressed, and a store-only writer needs nothing but a checksum.
 */
object Zip {

    /** A ZIP archive holding [files] under their own names. Names must be unique. */
    fun store(files: List<ExportFile>): ByteArray {
        require(files.map { it.name }.toSet().size == files.size) { "archive entries need unique names" }
        val out = Bytes()
        val central = Bytes()
        for (file in files) {
            val name = file.name.encodeToByteArray()
            val crc = crc32(file.bytes)
            val offset = out.size
            // Local file header.
            out.int(0x04034b50)
            header(out, crc, file.bytes.size, name.size)
            out.raw(name)
            out.raw(file.bytes)
            // The matching central directory record.
            central.int(0x02014b50)
            central.short(20) // version made by
            header(central, crc, file.bytes.size, name.size)
            central.short(0) // comment length
            central.short(0) // disk number
            central.short(0) // internal attributes
            central.int(0) // external attributes
            central.int(offset)
            central.raw(name)
        }
        val directoryOffset = out.size
        out.raw(central.toByteArray())
        // End of central directory.
        out.int(0x06054b50)
        out.short(0)
        out.short(0)
        out.short(files.size)
        out.short(files.size)
        out.int(central.size)
        out.int(directoryOffset)
        out.short(0)
        return out.toByteArray()
    }

    /** The fields a local header and a central record share, from "version needed" to "extra length". */
    private fun header(to: Bytes, crc: Int, size: Int, nameLength: Int) {
        to.short(20) // version needed: 2.0
        to.short(0x0800) // flags: names are UTF-8
        to.short(0) // method: stored
        to.short(0) // time
        to.short(0x21) // date: 1980-01-01, the format's epoch; the archive carries no clock
        to.int(crc)
        to.int(size) // compressed size
        to.int(size) // uncompressed size
        to.short(nameLength)
        to.short(0) // extra field length
    }

    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /** The CRC-32 every ZIP reader checks an entry against. */
    fun crc32(bytes: ByteArray): Int {
        var c = -1
        for (b in bytes) c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    /** A growing little-endian byte buffer. */
    private class Bytes {
        private var data = ByteArray(1024)
        var size = 0
            private set

        private fun room(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }

        fun short(v: Int) {
            room(2)
            data[size++] = v.toByte()
            data[size++] = (v shr 8).toByte()
        }

        fun int(v: Int) {
            short(v and 0xFFFF)
            short(v ushr 16)
        }

        fun raw(bytes: ByteArray) {
            room(bytes.size)
            bytes.copyInto(data, size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
