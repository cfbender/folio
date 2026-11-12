package dev.cfbender.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.cfbender.folio.AppContainer
import dev.cfbender.folio.container
import dev.cfbender.folio.opds.OpdsCredentials
import dev.cfbender.folio.opds.OpdsEntry
import dev.cfbender.folio.opds.OpdsSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What is known about fetching one catalog entry's book. */
sealed interface Download {
    /** [fraction] is null until the server has said how big the file is. */
    data class Running(val fraction: Float?) : Download
    data class Failed(val message: String) : Download
}

/** One OPDS feed: its entries, further pages, search, and downloads into the library. */
class FeedViewModel(private val catalogId: Long, private val url: String, private val app: AppContainer) : ViewModel() {
    var title by mutableStateOf("")
        private set
    val entries = mutableStateListOf<OpdsEntry>()
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var search by mutableStateOf<OpdsSearch?>(null)
        private set
    var credentials: OpdsCredentials? = null
        private set
    val downloads = mutableStateMapOf<String, Download>()

    /** Library book id for each catalog entry already downloaded. */
    val owned: StateFlow<Map<String, String>> = app.library.books
        .map { books -> books.mapNotNull { book -> book.opdsId?.let { it to book.id } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var nextUrl: String? = null

    init {
        load()
    }

    /** Key under which a downloaded entry is remembered; entry ids are only unique per catalog. */
    fun key(entry: OpdsEntry): String = "$catalogId|${entry.id.ifEmpty { entry.epub?.url.orEmpty() }}"

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                credentials = app.database.catalogs().catalog(catalogId)?.credentials
                val feed = app.opds.feed(url, credentials)
                title = feed.title
                search = feed.search
                nextUrl = feed.nextUrl
                entries.clear()
                entries += feed.entries
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not load the catalog"
            } finally {
                loading = false
            }
        }
    }

    /** Fetches the next page, if the feed has one and it is not already on its way. */
    fun loadMore() {
        val more = nextUrl ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val feed = app.opds.feed(more, credentials)
                nextUrl = feed.nextUrl
                entries += feed.entries
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                nextUrl = null // Stop asking; what loaded so far stays usable.
            } finally {
                loadingMore = false
            }
        }
    }

    suspend fun searchUrl(query: String): String? {
        val description = search ?: return null
        return try {
            app.opds.searchUrl(description, query, credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
            null
        }
    }

    fun download(entry: OpdsEntry) {
        val file = entry.epub ?: return
        val key = key(entry)
        if (downloads[key] is Download.Running) return
        downloads[key] = Download.Running(null)
        // On the app's scope: a download keeps going when the reader leaves this feed.
        app.scope.launch {
            val temp = app.library.newTempFile()
            try {
                var shown = 0f
                app.opds.download(file.url, credentials, temp) { fraction ->
                    // Called from the download thread for every chunk; report whole percents only.
                    if (fraction != null && fraction - shown >= 0.01f) {
                        shown = fraction
                        app.scope.launch { if (downloads[key] is Download.Running) downloads[key] = Download.Running(fraction) }
                    }
                }
                app.library.import(temp, opdsId = key)
                downloads.remove(key)
            } catch (e: Exception) {
                temp.delete()
                downloads[key] = Download.Failed(e.message ?: "Download failed")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    catalogId: Long,
    url: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onOpenFeed: (url: String, title: String) -> Unit,
    onRead: (bookId: String) -> Unit,
) {
    val container = LocalContext.current.container
    val vm: FeedViewModel = viewModel(key = "feed:$catalogId:$url", factory = viewModelFactory { initializer { FeedViewModel(catalogId, url, container) } })
    val owned by vm.owned.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    fun submitSearch() {
        val text = query.trim()
        if (text.isEmpty()) return
        scope.launch {
            vm.searchUrl(text)?.let {
                searching = false
                onOpenFeed(it, "“$text”")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            query, { query = it },
                            placeholder = { Text("Search this catalog") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(fallbackTitle.ifEmpty { vm.title }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false }) { Icon(Icons.Outlined.Close, contentDescription = "Close search") }
                    } else if (vm.search != null) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val error = vm.error
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && vm.entries.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = vm::load) { Text("Try again") }
                }
                vm.entries.isEmpty() -> Text(
                    "Nothing here",
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // One column on a phone; book rows flow into two or more on an unfolded screen.
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(340.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(vm.entries, key = { index, entry -> "$index:${entry.id}" }) { index, entry ->
                        if (index >= vm.entries.size - 5) LaunchedEffect(vm.entries.size) { vm.loadMore() }
                        val navigation = entry.navigationUrl
                        if (navigation != null) {
                            ListItem(
                                modifier = Modifier.clickable { onOpenFeed(navigation, entry.title) },
                                headlineContent = { Text(entry.title) },
                                supportingContent = entry.summary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                            )
                        } else {
                            val key = vm.key(entry)
                            BookEntry(
                                entry,
                                credentials = vm.credentials,
                                download = vm.downloads[key],
                                ownedBookId = owned[key],
                                onDownload = { vm.download(entry) },
                                onRead = onRead,
                            )
                        }
                    }
                    if (vm.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookEntry(
    entry: OpdsEntry,
    credentials: OpdsCredentials?,
    download: Download?,
    ownedBookId: String?,
    onDownload: () -> Unit,
    onRead: (String) -> Unit,
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 2.dp, modifier = Modifier.width(64.dp).height(96.dp)) {
            val cover = remember(entry.coverUrl, credentials) {
                entry.coverUrl?.let { url ->
                    ImageRequest.Builder(context)
                        .data(url)
                        .apply { credentials?.let { httpHeaders(NetworkHeaders.Builder().set("Authorization", it.header).build()) } }
                        .build()
                }
            }
            BookCover(entry.title, entry.author, cover)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    ownedBookId != null -> Button(onClick = { onRead(ownedBookId) }) { Text("Read") }
                    download is Download.Running -> {
                        val fraction = download.fraction
                        if (fraction == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    }
                    entry.epub == null -> Text(
                        "No EPUB available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    download is Download.Failed -> {
                        TextButton(onClick = onDownload) { Text("Retry") }
                        Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                    else -> FilledTonalButton(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}
