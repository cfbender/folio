package dev.cfbender.folio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.cfbender.folio.epub.EpubBook
import dev.cfbender.folio.epub.PartialMd5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The books on this device: their files, covers, and database rows. */
class Library(private val root: File, private val dao: BookDao) {
    private val booksDir = File(root, "books").apply { mkdirs() }
    private val coversDir = File(root, "covers").apply { mkdirs() }

    val books: Flow<List<BookEntity>> = dao.books()

    suspend fun book(id: String): BookEntity? = dao.book(id)

    fun file(id: String): File = File(booksDir, "$id.epub")

    /** The cover thumbnail; the file is absent when the book has no cover. */
    fun cover(id: String): File = File(coversDir, "$id.jpg")

    /** A fresh file to download or copy a book into before handing it to [import]. */
    fun newTempFile(): File = File.createTempFile("incoming", ".epub", root)

    suspend fun import(input: InputStream, opdsId: String? = null): BookEntity {
        val temp = newTempFile()
        withContext(Dispatchers.IO) { temp.outputStream().use { input.copyTo(it) } }
        return import(temp, opdsId)
    }

    /**
     * Adds [temp] to the library, taking ownership of the file. The bytes are stored untouched,
     * because KOReader sync identifies a book by a digest of its file. Importing a file that is
     * already in the library returns the existing book.
     */
    suspend fun import(temp: File, opdsId: String? = null): BookEntity = withContext(Dispatchers.IO) {
        try {
            val id = PartialMd5.of(temp)
            dao.book(id)?.let { return@withContext it }
            val entity = EpubBook.open(temp).use { epub ->
                epub.coverPath?.let(epub::read)?.let { saveCover(id, it) }
                BookEntity(
                    id = id,
                    title = epub.metadata.title,
                    author = epub.metadata.author,
                    addedAt = System.currentTimeMillis(),
                    opdsId = opdsId,
                )
            }
            val destination = file(id)
            if (!temp.renameTo(destination)) temp.copyTo(destination, overwrite = true)
            dao.insert(entity)
            entity
        } finally {
            temp.delete()
        }
    }

    suspend fun saveProgress(id: String, spine: Int, block: Int, char: Int, progress: Float) =
        dao.saveProgress(id, spine, block, char, progress, System.currentTimeMillis())

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        file(id).delete()
        cover(id).delete()
    }

    private fun saveCover(id: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((bounds.outWidth / COVER_WIDTH).coerceAtLeast(1))
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
        cover(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
    }

    private companion object {
        const val COVER_WIDTH = 480
    }
}
