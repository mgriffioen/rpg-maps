package com.rpgmaps.tabletop.ui.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rpgmaps.tabletop.RpgMapsApplication
import com.rpgmaps.tabletop.data.ImageImporter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as RpgMapsApplication
    val scope = rememberCoroutineScope()

    val settings by app.settings.flow.collectAsState(initial = null)
    val statuses by app.displayHub.statuses.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val current = settings ?: return@Scaffold

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            SettingsCard(
                title = "Player view on this network",
                body = "Open this address in a browser on anything plugged into the TV " +
                    "— a laptop, an Android TV, a spare tablet. No setup, no accounts. " +
                    "This is the quickest way to check the app is working.",
            ) {
                statuses.forEach { status ->
                    Text(
                        text = "${status.label}: ${status.detail}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            SettingsCard(
                title = "TV size",
                body = "Used by \"Scale to life\" so one battle square renders at one real " +
                    "inch on the TV, which is what makes a 28 mm miniature fit its square. " +
                    "Measure the screen corner to corner.",
            ) {
                Text(
                    "%.0f inches diagonal".format(current.tvDiagonalInches),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = current.tvDiagonalInches,
                    onValueChange = { scope.launch { app.settings.setTvDiagonalInches(it) } },
                    valueRange = 24f..85f,
                )
            }

            SettingsCard(
                title = "Map quality",
                body = "Maps are downscaled on import to keep the tablet responsive and the " +
                    "transfer to the TV quick. Lower this if an older Chromecast struggles; " +
                    "raise it if you zoom in a lot. Only affects maps imported from now on.",
            ) {
                ChipRow {
                    listOf(1600, ImageImporter.DEFAULT_DISPLAY_MAX_DIM, 3072).forEach { size ->
                        FilterChip(
                            selected = current.displayMaxDim == size,
                            onClick = { scope.launch { app.settings.setDisplayMaxDim(size) } },
                            label = { Text("${size}px") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            SettingsCard(
                title = "Curtain message",
                body = "Shown on the TV when you hide the map — during a break, or while you " +
                    "set up the next encounter.",
            ) {
                var text by remember(current.blankText) { mutableStateOf(current.blankText) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        scope.launch { app.settings.setBlankText(it) }
                    },
                    label = { Text("Message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsCard(
                title = "Server port",
                body = "Change this only if something else on the tablet already uses the port. " +
                    "The server restarts automatically.",
            ) {
                var portText by remember(current.serverPort) { mutableStateOf(current.serverPort.toString()) }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        portText = value.filter { it.isDigit() }.take(5)
                        portText.toIntOrNull()
                            ?.takeIf { it in 1024..65535 }
                            ?.let { port -> scope.launch { app.settings.setServerPort(port) } }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
private fun SettingsCard(
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

