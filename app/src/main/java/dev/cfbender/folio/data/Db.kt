package dev.cfbender.folio.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A book in the library. The primary key is KOReader's partial MD5 of the file, which is also the
 * document id progress is synced under, so the same file is the same book everywhere.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val addedAt: Long,
    /** OPDS entry id the file was downloaded from, so catalogs can show it as owned. */
    val opdsId: String? = null,
    val lastReadAt: Long? = null,
    // Reading position: see render.Locator.
    val spine: Int = 0,
    val block: Int = 0,
    val char: Int = 0,
    /** Fraction of the book read, 0–1. */
    val progress: Float = 0f,
)

@Entity(tableName = "catalogs")
data class CatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, addedAt) DESC")
    fun books(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity)

    @Query("UPDATE books SET spine = :spine, block = :block, char = :char, progress = :progress, lastReadAt = :at WHERE id = :id")
    suspend fun saveProgress(id: String, spine: Int, block: Int, char: Int, progress: Float, at: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalogs ORDER BY title COLLATE NOCASE")
    fun catalogs(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalogs WHERE id = :id")
    suspend fun catalog(id: Long): CatalogEntity?

    @Upsert
    suspend fun save(catalog: CatalogEntity)

    @Delete
    suspend fun delete(catalog: CatalogEntity)
}

@Database(entities = [BookEntity::class, CatalogEntity::class], version = 1, exportSchema = true)
abstract class FolioDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun catalogs(): CatalogDao
}
