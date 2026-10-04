package ch.schreibwerkstatt.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.schreibwerkstatt.mobile.data.prefs.ThemeMode
import ch.schreibwerkstatt.mobile.ui.AppNav
import ch.schreibwerkstatt.mobile.ui.research.SharedResearch
import ch.schreibwerkstatt.mobile.ui.research.parseShareIntent
import ch.schreibwerkstatt.mobile.ui.theme.SchreibwerkstattTheme

class MainActivity : ComponentActivity() {
    // Über einen ACTION_SEND-Intent geteilter Inhalt (Recherche-Erfassung). Als
    // Compose-State gehalten, damit AppNav bei neuem Share (onNewIntent, singleTop)
    // reagieren kann. Wird vom Capture-Screen nach Speichern/Abbruch geleert.
    private var sharedResearch by mutableStateOf<SharedResearch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim echten Start den Intent auswerten. Bei einem Neuaufbau (Rotation,
        // Dark-Mode-/Sprachwechsel) trägt `intent` noch den alten Share — der würde das
        // Capture-Formular nach dem Speichern erneut öffnen. Ein noch nicht erledigter
        // Share überlebt stattdessen über den Instance-State.
        sharedResearch = if (savedInstanceState == null) {
            parseShareIntent(intent)
        } else {
            savedInstanceState.getStringArray(STATE_SHARED)?.let { (url, title, note) ->
                SharedResearch(url = url, title = title, note = note)
            }
        }
        val serviceLocator = locator
        setContent {
            val themeMode by serviceLocator.settings.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SchreibwerkstattTheme(darkTheme = darkTheme) {
                AppNav(
                    locator = serviceLocator,
                    sharedResearch = sharedResearch,
                    onSharedConsumed = { sharedResearch = null },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sharedResearch?.let {
            outState.putStringArray(STATE_SHARED, arrayOf(it.url, it.title, it.note))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseShareIntent(intent)?.let { sharedResearch = it }
    }

    private companion object {
        const val STATE_SHARED = "shared_research"
    }
}
