package dev.cfbender.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cfbender.folio.container
import dev.cfbender.folio.sync.KoSyncAccount
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    // A sentinel distinguishes "not loaded yet" from "no account".
    val loaded by remember { container.settings.koSyncAccount.map(::Loaded) }.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Progress sync", style = MaterialTheme.typography.titleLarge)
            Text(
                "Keeps your place in step with KOReader and other readers through a KOReader sync server. " +
                    "With Grimmory, use your server’s address and the KOReader username and password from its settings; " +
                    "those are separate from your Grimmory login.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val state = loaded
            if (state != null) {
                val account = state.account
                if (account != null) {
                    Text("Signed in as ${account.username}", style = MaterialTheme.typography.titleMedium)
                    Text(account.serverUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { scope.launch { container.settings.setKoSyncAccount(null) } }) { Text("Sign out") }
                } else {
                    var address by rememberSaveable { mutableStateOf("") }
                    var username by rememberSaveable { mutableStateOf("") }
                    var password by rememberSaveable { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    var error by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        address, { address = it },
                        label = { Text("Server address") },
                        placeholder = { Text("https://books.example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Row {
                        Button(
                            enabled = !busy && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        container.settings.setKoSyncAccount(container.koSync.signIn(address, username, password))
                                    } catch (e: Exception) {
                                        error = e.message ?: "Could not sign in"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) { Text(if (busy) "Signing in…" else "Sign in") }
                    }
                }
            }
        }
    }
}

private class Loaded(val account: KoSyncAccount?)
