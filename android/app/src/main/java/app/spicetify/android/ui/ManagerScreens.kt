package app.spicetify.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.spicetify.android.BuildConfig
import app.spicetify.android.R
import app.spicetify.android.data.LayoutMode
import app.spicetify.android.data.Snippet
import app.spicetify.android.data.SpicetifyConfig
import app.spicetify.android.diagnostics.DiagnosticsLog
import app.spicetify.android.playback.PlaybackController
import app.spicetify.android.spicengine.Spicengine
import app.spicetify.android.spicetify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

// --- Shared building blocks -----------------------------------------------------

@Composable
private fun rememberConfig(): SpicetifyConfig {
    val repo = LocalContext.current.spicetify.config
    val config by repo.config.collectAsState()
    return config
}

private fun updateConfig(context: android.content.Context, transform: (SpicetifyConfig) -> SpicetifyConfig) =
    context.spicetify.config.update(transform)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** "Add from URL" + "Import file" buttons. */
@Composable
private fun ImportRow(
    urlTitle: String,
    urlHint: String,
    mimeTypes: Array<String>,
    onUrl: suspend (String) -> String,
    onFile: suspend (Uri) -> String,
    snackbar: SnackbarHostState,
    onImported: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val installedMsg = stringResource(R.string.installed)
    val failedMsg = stringResource(R.string.import_failed)
    var showUrl by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { onFile(uri) }
                .onSuccess { onImported(it); snackbar.showSnackbar("$installedMsg: $it") }
                .onFailure { snackbar.showSnackbar("$failedMsg: ${it.message}") }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { showUrl = true }) {
            Icon(Icons.Filled.Link, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_from_url))
        }
        OutlinedButton(onClick = { picker.launch(mimeTypes) }) {
            Icon(Icons.Filled.FileOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.import_file))
        }
    }
    if (showUrl) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrl = false },
            title = { Text(urlTitle) },
            text = {
                Column {
                    Text(urlHint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("https://…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("https://"), onClick = {
                    showUrl = false
                    scope.launch {
                        runCatching { onUrl(url) }
                            .onSuccess { onImported(it); snackbar.showSnackbar("$installedMsg: $it") }
                            .onFailure { snackbar.showSnackbar("$failedMsg: ${it.message}") }
                    }
                }) { Text(stringResource(R.string.install)) }
            },
            dismissButton = { TextButton(onClick = { showUrl = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// --- Extensions -------------------------------------------------------------

@Composable
fun ExtensionsScreen(modifier: Modifier, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val files = context.spicetify.files
    val config = rememberConfig()
    var version by remember { mutableIntStateOf(0) }
    val extensions = remember(version) { files.extensions() }
    val importer = remember { Importer(context) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Hint(stringResource(R.string.extensions_hint))
            ImportRow(
                urlTitle = stringResource(R.string.add_extension),
                urlHint = stringResource(R.string.add_extension_hint),
                mimeTypes = arrayOf("application/javascript", "text/javascript", "application/x-javascript", "*/*"),
                onUrl = importer::extensionFromUrl,
                onFile = importer::extensionFromFile,
                snackbar = snackbar,
                onImported = { name ->
                    version++
                    updateConfig(context) { it.copy(extensions = (it.extensions + name).distinct()) }
                },
            )
            HorizontalDivider()
        }
        items(extensions, key = { it.file }) { ext ->
            val enabled = ext.file in config.extensions
            ListItem(
                headlineContent = { Text(ext.name) },
                supportingContent = {
                    Text(
                        ext.description.ifEmpty { ext.file },
                        maxLines = 3,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!ext.bundled) {
                            IconButton(onClick = {
                                files.deleteUser("Extensions/${ext.file}")
                                updateConfig(context) { it.copy(extensions = it.extensions - ext.file) }
                                version++
                            }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                        }
                        Switch(checked = enabled, onCheckedChange = { on ->
                            updateConfig(context) {
                                it.copy(extensions = if (on) (it.extensions + ext.file).distinct() else it.extensions - ext.file)
                            }
                        })
                    }
                },
            )
        }
    }
}

// --- Themes -----------------------------------------------------------------

@Composable
fun ThemesScreen(modifier: Modifier, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val files = context.spicetify.files
    val config = rememberConfig()
    var version by remember { mutableIntStateOf(0) }
    val themes = remember(version) { files.themes() }
    val importer = remember { Importer(context) }
    val schemes = remember(config.currentTheme, version) {
        val ini = files.readText("Themes/${config.currentTheme}/color.ini") ?: ""
        val arr = JSONArray(Spicengine.colorSchemes(ini))
        (0 until arr.length()).map { arr.getString(it) }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Hint(stringResource(R.string.themes_hint))
            ImportRow(
                urlTitle = stringResource(R.string.add_theme),
                urlHint = stringResource(R.string.add_zip_hint),
                mimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "*/*"),
                onUrl = importer::themeFromUrl,
                onFile = importer::themeFromFile,
                snackbar = snackbar,
                onImported = { name ->
                    version++
                    updateConfig(context) { it.copy(currentTheme = name, colorScheme = "") }
                },
            )
            SectionHeader(stringResource(R.string.theme))
        }
        item {
            ThemeOption(stringResource(R.string.theme_none), config.currentTheme.isEmpty()) {
                updateConfig(context) { it.copy(currentTheme = "", colorScheme = "") }
            }
        }
        items(themes, key = { it }) { theme ->
            val deletable = !files.isBundled("Themes/$theme")
            ThemeOption(
                theme,
                config.currentTheme == theme,
                onDelete = if (deletable) {
                    {
                        files.deleteUser("Themes/$theme")
                        if (config.currentTheme == theme) updateConfig(context) { it.copy(currentTheme = "SpicetifyDefault", colorScheme = "") }
                        version++
                    }
                } else null,
            ) { updateConfig(context) { it.copy(currentTheme = theme, colorScheme = "") } }
        }
        if (schemes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.color_scheme)) }
            items(schemes, key = { "scheme-$it" }) { scheme ->
                val selected = config.colorScheme == scheme || (config.colorScheme.isEmpty() && scheme == schemes.first())
                ThemeOption(scheme, selected) { updateConfig(context) { it.copy(colorScheme = scheme) } }
            }
        }
        item {
            SectionHeader(stringResource(R.string.theme_options))
            SwitchRow(stringResource(R.string.opt_replace_colors), config.replaceColors) { v -> updateConfig(context) { it.copy(replaceColors = v) } }
            SwitchRow(stringResource(R.string.opt_inject_css), config.injectCss) { v -> updateConfig(context) { it.copy(injectCss = v) } }
            SwitchRow(stringResource(R.string.opt_inject_theme_js), config.injectThemeJs) { v -> updateConfig(context) { it.copy(injectThemeJs = v) } }
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onDelete: (() -> Unit)? = null, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(horizontal = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
        }
    }
}

// --- Custom apps --------------------------------------------------------------

@Composable
fun CustomAppsScreen(modifier: Modifier, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val files = context.spicetify.files
    val config = rememberConfig()
    var version by remember { mutableIntStateOf(0) }
    val apps = remember(version) { files.customApps() }
    val importer = remember { Importer(context) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Hint(stringResource(R.string.apps_hint))
            ImportRow(
                urlTitle = stringResource(R.string.add_app),
                urlHint = stringResource(R.string.add_zip_hint),
                mimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "*/*"),
                onUrl = importer::customAppFromUrl,
                onFile = importer::customAppFromFile,
                snackbar = snackbar,
                onImported = { name ->
                    version++
                    updateConfig(context) { it.copy(customApps = (it.customApps + name).distinct()) }
                },
            )
            HorizontalDivider()
        }
        items(apps, key = { it }) { app ->
            ListItem(
                headlineContent = { Text(app) },
                supportingContent = { Text("/$app", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!files.isBundled("CustomApps/$app")) {
                            IconButton(onClick = {
                                files.deleteUser("CustomApps/$app")
                                updateConfig(context) { it.copy(customApps = it.customApps - app) }
                                version++
                            }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                        }
                        Switch(checked = app in config.customApps, onCheckedChange = { on ->
                            updateConfig(context) {
                                it.copy(customApps = if (on) (it.customApps + app).distinct() else it.customApps - app)
                            }
                        })
                    }
                },
            )
        }
    }
}

// --- Snippets -----------------------------------------------------------------

@Composable
fun SnippetsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val config = rememberConfig()
    var editing by remember { mutableStateOf<Snippet?>(null) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Hint(stringResource(R.string.snippets_hint))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilledTonalButton(onClick = { editing = Snippet(UUID.randomUUID().toString(), "", "") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_snippet))
                }
            }
            HorizontalDivider()
        }
        items(config.snippets, key = { it.id }) { snippet ->
            ListItem(
                modifier = Modifier.clickable { editing = snippet },
                headlineContent = { Text(snippet.name.ifEmpty { stringResource(R.string.untitled) }) },
                supportingContent = {
                    Text(snippet.css.lineSequence().take(2).joinToString("\n"), fontFamily = FontFamily.Monospace, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { editing = snippet }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit)) }
                        Switch(checked = snippet.enabled, onCheckedChange = { on ->
                            updateConfig(context) { c -> c.copy(snippets = c.snippets.map { if (it.id == snippet.id) it.copy(enabled = on) else it }) }
                        })
                    }
                },
            )
        }
    }

    editing?.let { snippet ->
        var name by remember(snippet.id) { mutableStateOf(snippet.name) }
        var css by remember(snippet.id) { mutableStateOf(snippet.css) }
        val exists = config.snippets.any { it.id == snippet.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (exists) R.string.edit_snippet else R.string.add_snippet)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = css,
                        onValueChange = { css = it },
                        label = { Text("CSS") },
                        minLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = css.isNotBlank(), onClick = {
                    val updated = snippet.copy(name = name.trim(), css = css)
                    updateConfig(context) { c ->
                        c.copy(snippets = if (exists) c.snippets.map { if (it.id == snippet.id) updated else it } else c.snippets + updated)
                    }
                    editing = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    if (exists) {
                        TextButton(onClick = {
                            updateConfig(context) { c -> c.copy(snippets = c.snippets.filter { it.id != snippet.id }) }
                            editing = null
                        }) { Text(stringResource(R.string.delete)) }
                    }
                    TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

// --- Settings -----------------------------------------------------------------

@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
fun SettingsScreen(modifier: Modifier, snackbar: SnackbarHostState, openDiagnostics: Boolean = false) {
    val context = LocalContext.current
    val app = context.spicetify
    val config = rememberConfig()
    val scope = rememberCoroutineScope()
    var showDiagnostics by rememberSaveable { mutableStateOf(openDiagnostics) }
    if (showDiagnostics) DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    var cacheSize by remember { mutableStateOf(app.patchCache.sizeBytes()) }
    val clearedMsg = stringResource(R.string.cache_cleared)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.layout))
        LayoutMode.entries.forEach { mode ->
            val label = stringResource(
                when (mode) {
                    LayoutMode.AUTO -> R.string.layout_auto
                    LayoutMode.MOBILE -> R.string.layout_mobile
                    LayoutMode.DESKTOP -> R.string.layout_desktop
                },
            )
            ThemeOption(label, config.layoutMode == mode) { updateConfig(context) { it.copy(layoutMode = mode) } }
        }

        SectionHeader(stringResource(R.string.features))
        SwitchRow(stringResource(R.string.opt_expose_apis), config.exposeApis, stringResource(R.string.opt_expose_apis_desc)) { v -> updateConfig(context) { it.copy(exposeApis = v) } }
        SwitchRow(stringResource(R.string.opt_sidebar_config), config.sidebarConfig) { v -> updateConfig(context) { it.copy(sidebarConfig = v) } }
        SwitchRow(stringResource(R.string.opt_home_config), config.homeConfig) { v -> updateConfig(context) { it.copy(homeConfig = v) } }
        SwitchRow(stringResource(R.string.opt_exp_features), config.expFeatures) { v -> updateConfig(context) { it.copy(expFeatures = v) } }

        SectionHeader(stringResource(R.string.privacy))
        SwitchRow(stringResource(R.string.opt_disable_sentry), config.disableSentry) { v -> updateConfig(context) { it.copy(disableSentry = v) } }
        SwitchRow(stringResource(R.string.opt_disable_logging), config.disableLogging) { v -> updateConfig(context) { it.copy(disableLogging = v) } }
        SwitchRow(stringResource(R.string.opt_remove_rtl), config.removeRtl) { v -> updateConfig(context) { it.copy(removeRtl = v) } }
        SwitchRow(stringResource(R.string.opt_external_links), config.externalLinksInBrowser) { v -> updateConfig(context) { it.copy(externalLinksInBrowser = v) } }

        SectionHeader(stringResource(R.string.maintenance))
        ListItem(
            modifier = Modifier.clickable { showDiagnostics = true },
            headlineContent = { Text(stringResource(R.string.diagnostics)) },
            supportingContent = { Text(stringResource(R.string.diagnostics_desc), style = MaterialTheme.typography.bodySmall) },
        )
        SwitchRow(stringResource(R.string.opt_safe_mode), config.safeMode, stringResource(R.string.opt_safe_mode_desc)) { v ->
            updateConfig(context) { it.copy(safeMode = v) }
        }
        SwitchRow(stringResource(R.string.opt_webview_debugging), config.webViewDebugging, stringResource(R.string.opt_webview_debugging_desc)) { v ->
            updateConfig(context) { it.copy(webViewDebugging = v) }
        }
        ListItem(
            modifier = Modifier.clickable {
                scope.launch {
                    withContext(Dispatchers.IO) { app.patchCache.clear() }
                    cacheSize = 0
                    snackbar.showSnackbar(clearedMsg)
                }
            },
            headlineContent = { Text(stringResource(R.string.clear_cache)) },
            supportingContent = { Text(stringResource(R.string.clear_cache_desc, cacheSize / (1024 * 1024))) },
        )

        SectionHeader(stringResource(R.string.about))
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Spicetify for Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("Engine ${Spicengine.version()}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// --- Diagnostics --------------------------------------------------------------

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(DiagnosticsLog.report(context)) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // Calls the web player's PlayerAPI directly and logs the
                // result, requests and state (android/web/diagnostics.js).
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        val sink = PlaybackController.sink
                        if (sink == null) {
                            DiagnosticsLog.log("player", "test: player not running")
                            report = DiagnosticsLog.report(context)
                            return@OutlinedButton
                        }
                        DiagnosticsLog.log("player", "test: started")
                        sink.send("testPlayback", null)
                        testing = true
                        scope.launch {
                            repeat(3) {
                                delay(4_000)
                                report = DiagnosticsLog.report(context)
                            }
                            testing = false
                        }
                    },
                ) { Text(stringResource(if (testing) R.string.test_playback_running else R.string.test_playback)) }
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                context.startActivity(Intent.createChooser(send, null))
            }) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Spicetify diagnostics", report))
                }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = {
                    DiagnosticsLog.clear()
                    report = DiagnosticsLog.report(context)
                }) { Text(stringResource(R.string.clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
