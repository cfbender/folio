package dev.cfbender.folio

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.cfbender.folio.net.LocalNetworkAccess
import dev.cfbender.folio.reader.ReaderScreen
import dev.cfbender.folio.ui.CatalogsScreen
import dev.cfbender.folio.ui.FeedScreen
import dev.cfbender.folio.ui.FolioTheme
import dev.cfbender.folio.ui.LibraryScreen
import dev.cfbender.folio.ui.SettingsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data object CatalogsRoute

@Serializable
data object SettingsRoute

@Serializable
data class FeedRoute(val catalogId: Long, val url: String, val title: String)

@Serializable
data class ReaderRoute(val bookId: String)

class MainActivity : ComponentActivity() {
    /** A book opened from another app, waiting for the UI to show it. */
    private val opened = MutableStateFlow<String?>(null)

    /** Completed when the user answers the local network prompt that is on screen. */
    private var localNetworkAnswer: CompletableDeferred<Unit>? = null
    private val localNetworkPrompt = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        localNetworkAnswer?.complete(Unit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            FolioTheme {
                val openedBook by opened.collectAsState()
                FolioNavigation(openedBook) { opened.value = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.localNetwork.prompt = {
            withContext(Dispatchers.Main) {
                val answer = CompletableDeferred<Unit>().also { localNetworkAnswer = it }
                localNetworkPrompt.launch(LocalNetworkAccess.PERMISSION)
                answer.await()
            }
        }
    }

    override fun onStop() {
        container.localNetwork.prompt = null
        super.onStop()
    }

    override fun onDestroy() {
        // Requests waiting on a prompt this activity can no longer report on go ahead without it.
        localNetworkAnswer?.complete(Unit)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Imports the EPUB another app asked us to view, then opens it. */
    private fun openFrom(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        lifecycleScope.launch {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Unreadable")
                opened.value = input.use { container.library.import(it) }.id
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "That file could not be read as an EPUB", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun FolioNavigation(openedBook: String?, onOpenedShown: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openedBook) {
        if (openedBook != null) {
            nav.navigate(ReaderRoute(openedBook))
            onOpenedShown()
        }
    }
    NavHost(nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onOpen = { nav.navigate(ReaderRoute(it)) },
                onCatalogs = { nav.navigate(CatalogsRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<CatalogsRoute> {
            CatalogsScreen(onBack = nav::popBackStack) { nav.navigate(FeedRoute(it.id, it.url, it.title)) }
        }
        composable<FeedRoute> { entry ->
            val route = entry.toRoute<FeedRoute>()
            FeedScreen(
                route.catalogId, route.url, route.title,
                onBack = nav::popBackStack,
                onOpenFeed = { url, title -> nav.navigate(FeedRoute(route.catalogId, url, title)) },
                onRead = { nav.navigate(ReaderRoute(it)) },
            )
        }
        composable<SettingsRoute> { SettingsScreen(onBack = nav::popBackStack) }
        composable<ReaderRoute> { entry -> ReaderScreen(entry.toRoute<ReaderRoute>().bookId, onBack = nav::popBackStack) }
    }
}
