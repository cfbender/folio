package dev.cfbender.folio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cfbender.folio.sync.KoSyncAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ReaderTheme { AUTO, PAPER, SEPIA, DARK }

data class ReaderPrefs(
    val fontSizeSp: Float = 17f,
    /** Distance between baselines as a multiple of the font size. */
    val lineSpacing: Float = 1.5f,
    val theme: ReaderTheme = ReaderTheme.AUTO,
) {
    companion object {
        val FONT_SIZES = 12f..30f
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class Settings(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    val readerPrefs: Flow<ReaderPrefs> = store.data.map { p ->
        ReaderPrefs(
            fontSizeSp = p[FONT_SIZE] ?: ReaderPrefs().fontSizeSp,
            lineSpacing = p[LINE_SPACING] ?: ReaderPrefs().lineSpacing,
            theme = p[THEME]?.let { name -> ReaderTheme.entries.firstOrNull { it.name == name } } ?: ReaderTheme.AUTO,
        )
    }.distinctUntilChanged()

    val koSyncAccount: Flow<KoSyncAccount?> = store.data.map { p ->
        val url = p[SYNC_URL]
        val user = p[SYNC_USER]
        val key = p[SYNC_KEY]
        if (url.isNullOrBlank() || user.isNullOrBlank() || key.isNullOrBlank()) null else KoSyncAccount(url, user, key)
    }.distinctUntilChanged()

    suspend fun setReaderPrefs(prefs: ReaderPrefs) {
        store.edit {
            it[FONT_SIZE] = prefs.fontSizeSp.coerceIn(ReaderPrefs.FONT_SIZES)
            it[LINE_SPACING] = prefs.lineSpacing
            it[THEME] = prefs.theme.name
        }
    }

    suspend fun setKoSyncAccount(account: KoSyncAccount?) {
        store.edit {
            if (account == null) {
                it.remove(SYNC_URL)
                it.remove(SYNC_USER)
                it.remove(SYNC_KEY)
            } else {
                it[SYNC_URL] = account.serverUrl
                it[SYNC_USER] = account.username
                it[SYNC_KEY] = account.userKey
            }
        }
    }

    /** A stable id for this install, sent with synced progress so we can tell our own pushes apart. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").uppercase()
        store.edit { if (it[DEVICE_ID] == null) it[DEVICE_ID] = id }
        return store.data.first()[DEVICE_ID] ?: id
    }

    private companion object {
        val FONT_SIZE = floatPreferencesKey("reader.fontSize")
        val LINE_SPACING = floatPreferencesKey("reader.lineSpacing")
        val THEME = stringPreferencesKey("reader.theme")
        val SYNC_URL = stringPreferencesKey("kosync.url")
        val SYNC_USER = stringPreferencesKey("kosync.user")
        val SYNC_KEY = stringPreferencesKey("kosync.key")
        val DEVICE_ID = stringPreferencesKey("device.id")
    }
}
