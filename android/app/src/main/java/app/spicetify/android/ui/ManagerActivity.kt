package app.spicetify.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.spicetify.android.R

/** Native Spicetify manager: extensions, themes, custom apps, snippets, settings. */
class ManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SpicetifyTheme {
                ManagerScreen(onClose = { finish() })
            }
        }
    }
}

private val SpotifyGreen = Color(0xFF1ED760)

@Composable
fun SpicetifyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = SpotifyGreen, onPrimary = Color.Black, background = Color(0xFF121212), surface = Color(0xFF181818))
    } else {
        lightColorScheme(primary = Color(0xFF1DB954), onPrimary = Color.Black)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val label: Int, val icon: ImageVector) {
    EXTENSIONS(R.string.tab_extensions, Icons.Filled.Extension),
    THEMES(R.string.tab_themes, Icons.Filled.Palette),
    APPS(R.string.tab_apps, Icons.Filled.Apps),
    SNIPPETS(R.string.tab_snippets, Icons.Filled.Code),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerScreen(onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.EXTENSIONS -> ExtensionsScreen(modifier, snackbar)
            Tab.THEMES -> ThemesScreen(modifier, snackbar)
            Tab.APPS -> CustomAppsScreen(modifier, snackbar)
            Tab.SNIPPETS -> SnippetsScreen(modifier)
            Tab.SETTINGS -> SettingsScreen(modifier, snackbar)
        }
    }
}
