package dev.cfbender.folio.epub

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * KOReader's "partial MD5" document fingerprint: the MD5 of twelve 1 KiB samples taken at offsets
 * 0, 1 KiB, 4 KiB, 16 KiB … 1 GiB. Sync servers key reading progress by this digest, so it has to
 * match KOReader's `util.partialMD5` byte for byte.
 */
object PartialMd5 {
    private const val SAMPLE = 1024

    fun of(file: File): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(SAMPLE)
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            for (i in -1..10) {
                // KOReader computes lshift(1024, 2 * i); for i = -1 the shift wraps and yields 0.
                val position = if (i < 0) 0L else 1024L shl (2 * i)
                if (position >= length) break
                raf.seek(position)
                var read = 0
                while (read < SAMPLE) {
                    val n = raf.read(buffer, read, SAMPLE - read)
                    if (n < 0) break
                    read += n
                }
                md5.update(buffer, 0, read)
            }
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }
}
