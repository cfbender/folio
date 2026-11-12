package dev.cfbender.folio

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.cfbender.folio.data.FolioDatabase
import dev.cfbender.folio.data.Library
import dev.cfbender.folio.data.Settings
import dev.cfbender.folio.net.LocalNetworkAccess
import dev.cfbender.folio.opds.OpdsClient
import dev.cfbender.folio.sync.KoSyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The app's long-lived objects, built once and handed to view models. */
class AppContainer(context: Context) {
    /** For work that must finish even if the screen that started it goes away. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val localNetwork = LocalNetworkAccess(context)
    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(localNetwork).build()
    val database: FolioDatabase = Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db").build()
    val library = Library(context.filesDir, database.books())
    val settings = Settings(context)
    val opds = OpdsClient(http)
    val koSync = KoSyncClient(http)
}

class FolioApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}

val Context.container: AppContainer get() = (applicationContext as FolioApp).container
