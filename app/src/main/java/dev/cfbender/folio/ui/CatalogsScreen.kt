package dev.cfbender.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cfbender.folio.container
import dev.cfbender.folio.data.CatalogEntity
import dev.cfbender.folio.opds.OpdsCredentials
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

val CatalogEntity.credentials: OpdsCredentials?
    get() = if (username.isNullOrEmpty()) null else OpdsCredentials(username, password.orEmpty())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogsScreen(onBack: () -> Unit, onOpen: (CatalogEntity) -> Unit) {
    val container = LocalContext.current.container
    val dao = container.database.catalogs()
    val catalogs by dao.catalogs().collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CatalogEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogs", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = CatalogEntity(title = "", url = "") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Add catalog") },
            )
        },
    ) { padding ->
        val list = catalogs
        if (list != null && list.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No catalogs", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Add the OPDS address of a book server. For Grimmory that is your server’s address followed by /api/v1/opds, " +
                        "with an OPDS user from its settings.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(list.orEmpty(), key = { it.id }) { catalog ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { onOpen(catalog) },
                    leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    headlineContent = { Text(catalog.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(catalog.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Catalog options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                menu = false
                                editing = catalog
                            })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                                menu = false
                                scope.launch { dao.delete(catalog) }
                            })
                        }
                    },
                )
            }
        }
    }

    editing?.let { catalog ->
        CatalogDialog(catalog, onDismiss = { editing = null }) { editing = null }
    }
}

/** Adds or edits a catalog. It is saved only once its feed has actually been fetched. */
@Composable
private fun CatalogDialog(initial: CatalogEntity, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var username by rememberSaveable { mutableStateOf(initial.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val address = url.trim().let { if ("://" in it) it else "https://$it" }
        if (address.toHttpUrlOrNull() == null) {
            error = "That is not a web address"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val catalog = initial.copy(url = address, username = username.trim().ifEmpty { null }, password = password.ifEmpty { null })
                val feed = container.opds.feed(address, catalog.credentials)
                container.database.catalogs().save(catalog.copy(title = title.trim().ifEmpty { feed.title.ifEmpty { address } }))
                onSaved()
            } catch (e: Exception) {
                error = e.message ?: "Could not reach the catalog"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Add catalog" else "Edit catalog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("OPDS address") },
                    placeholder = { Text("https://books.example.com/api/v1/opds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(title, { title = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = !busy && url.isNotBlank()) { Text(if (busy) "Connecting…" else "Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
