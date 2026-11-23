package ch.schreibwerkstatt.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.schreibwerkstatt.mobile.data.prefs.ThemeMode
import ch.schreibwerkstatt.mobile.ideen.IdeaLaunch
import ch.schreibwerkstatt.mobile.ideen.isIdeaShare
import ch.schreibwerkstatt.mobile.ideen.parseIdeaLaunch
import ch.schreibwerkstatt.mobile.ui.AppNav
import ch.schreibwerkstatt.mobile.ui.research.SharedResearch
import ch.schreibwerkstatt.mobile.ui.research.parseShareIntent
import ch.schreibwerkstatt.mobile.ui.theme.SchreibwerkstattTheme

class MainActivity : ComponentActivity() {
    // Über einen ACTION_SEND-Intent geteilter Inhalt (Recherche-Erfassung). Als
    // Compose-State gehalten, damit AppNav bei neuem Share (onNewIntent, singleTop)
    // reagieren kann. Wird vom Capture-Screen nach Speichern/Abbruch geleert.
    private var sharedResearch by mutableStateOf<SharedResearch?>(null)

    // Einstieg ins Schnell-Erfassen einer Idee (Shortcut, Kachel, „Teilen → Als Idee").
    // Gleiches Muster wie [sharedResearch]: Compose-State, vom Capture-Screen geleert.
    private var ideaLaunch by mutableStateOf<IdeaLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim echten Start den Intent auswerten. Bei einem Neuaufbau (Rotation,
        // Dark-Mode-/Sprachwechsel) trägt `intent` noch den alten Share — der würde das
        // Capture-Formular nach dem Speichern erneut öffnen. Ein noch nicht erledigter
        // Share überlebt stattdessen über den Instance-State.
        sharedResearch = if (savedInstanceState == null) {
            researchShare(intent)
        } else {
            SharedResearch.fromArray(savedInstanceState.getStringArray(STATE_SHARED))
        }
        ideaLaunch = if (savedInstanceState == null) {
            parseIdeaLaunch(intent)
        } else {
            savedInstanceState.getString(STATE_IDEA)?.let { IdeaLaunch(it) }
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
            // enableEdgeToEdge() oben folgt dem System-Nachtmodus; die App folgt aber
            // `themeMode`. Ohne Nachziehen stünden bei erzwungenem Hell/Dunkel helle
            // Statusleisten-Icons auf hellem Grund (bzw. umgekehrt).
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            SchreibwerkstattTheme(darkTheme = darkTheme) {
                AppNav(
                    locator = serviceLocator,
                    sharedResearch = sharedResearch,
                    // Nur den eigenen Share leeren — ein inzwischen neuer bleibt stehen.
                    onSharedConsumed = { id -> if (sharedResearch?.id == id) sharedResearch = null },
                    ideaLaunch = ideaLaunch,
                    onIdeaLaunchConsumed = { ideaLaunch = null },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sharedResearch?.let {
            outState.putStringArray(STATE_SHARED, it.toArray())
        }
        ideaLaunch?.let { outState.putString(STATE_IDEA, it.text) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        researchShare(intent)?.let { sharedResearch = it }
        parseIdeaLaunch(intent)?.let { ideaLaunch = it }
    }

    /** Share als Recherche — ausser er kam über den Alias „Als Idee". */
    private fun researchShare(intent: Intent?): SharedResearch? =
        if (isIdeaShare(intent)) null else parseShareIntent(intent)

    private companion object {
        const val STATE_SHARED = "shared_research_v2"
        const val STATE_IDEA = "idea_launch"
        // Navigationsleisten-Scrims wie die Defaults von enableEdgeToEdge().
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
