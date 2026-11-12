package dev.cfbender.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.cfbender.folio.container
import dev.cfbender.folio.data.BookEntity
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onCatalogs: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val books by container.library.books.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }

    fun import(uris: List<Uri>) {
        // On the app's scope, so that an import in flight survives leaving this screen.
        container.scope.launch {
            var failed = 0
            for (uri in uris) {
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Unreadable")
                    input.use { container.library.import(it) }
                } catch (_: Exception) {
                    failed++
                }
            }
            if (failed > 0) {
                snackbar.showSnackbar(if (failed == 1) "A file could not be read as an EPUB" else "$failed files could not be read as EPUBs")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }
    // Some file providers do not know the EPUB type and report plain binary data.
    val pick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = onCatalogs) { Icon(Icons.Outlined.CloudDownload, contentDescription = "Catalogs") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!books.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pick,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Add books") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = books
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyLibrary(Modifier.padding(padding), onCatalogs, pick)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(list, key = { it.id }) { book ->
                    BookTile(
                        book,
                        cover = container.library.cover(book.id),
                        onOpen = { onOpen(book.id) },
                        onRemove = { container.scope.launch { container.library.delete(book.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onCatalogs: () -> Unit, onPick: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.height(56.dp).aspectRatio(1f),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("No books yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Download from an OPDS catalog, or add EPUB files from this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCatalogs) { Text("Browse catalogs") }
            OutlinedButton(onClick = onPick) { Text("Add files") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookEntity, cover: File, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Book options"),
    ) {
        Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            BookCover(book.title, book.author, model = cover.takeIf { remember(cover) { cover.exists() } })
        }
        if (book.progress > 0f) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {},
            )
        }
        Text(
            book.title,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(book.author, if (book.progress > 0f) "${(book.progress * 100).roundToInt()}%" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Remove from library") },
                onClick = {
                    menu = false
                    confirm = true
                },
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove this book?") },
            text = { Text("“${book.title}” and its reading position will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private val CoverTints = listOf(0xFF7A3E2B, 0xFF2F4B5C, 0xFF4E5A32, 0xFF5B3A5E, 0xFF7B5A1E, 0xFF30504A)

/** A book's cover image, over a typeset stand-in that shows when there is none or it fails to load. */
@Composable
fun BookCover(title: String, author: String?, model: Any?, modifier: Modifier = Modifier) {
    val tint = Color(CoverTints[title.hashCode().absoluteValue % CoverTints.size])
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, tint.copy(red = tint.red * 0.7f, green = tint.green * 0.7f, blue = tint.blue * 0.7f)))),
    ) {
        // A thumbnail in a list has room for the title in small type and nothing else.
        val thumbnail = maxWidth < 96.dp
        Column(Modifier.fillMaxSize().padding(if (thumbnail) 6.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                title,
                color = Color(0xFFF6EBDD),
                style = (if (thumbnail) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium)
                    .copy(fontFamily = Literata, hyphens = Hyphens.Auto),
                fontWeight = FontWeight.Bold,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (!thumbnail) {
                Text(
                    author.orEmpty(),
                    color = Color(0xCCF6EBDD),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
