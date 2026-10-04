package ch.schreibwerkstatt.mobile.ui.editor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import ch.schreibwerkstatt.mobile.ui.theme.LocalAppDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewAssetLoader
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.editor.EditorBridge
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.SkeletonParagraphs
import ch.schreibwerkstatt.mobile.ui.components.SyncStatusBar
import ch.schreibwerkstatt.mobile.ui.components.pageFlipGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val APP_ORIGIN = "https://appassets.androidplatform.net"
private const val APP_ORIGIN_HOST = "appassets.androidplatform.net"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    bookId: Long,
    pageId: Long,
    pageTitle: String,
    onBack: () -> Unit,
    onOpenHistory: (pageId: Long, pageName: String) -> Unit,
    onNavigateToPage: (pageId: Long, pageName: String) -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = LocalAppDarkTheme.current
    val vm: EditorViewModel = viewModel(factory = EditorViewModel.factory(context.locator, bookId, pageId))
    val state by vm.state.collectAsStateWithLifecycle()
    val coordinator = remember(context) { context.locator.syncCoordinator }
    val online by coordinator.online.collectAsStateWithLifecycle()
    val pendingCount by coordinator.pendingCount.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // evaluateJavascript-Helfer (UI-Thread).
    val evalJs: (String) -> Unit = { js -> webViewRef.value?.post { webViewRef.value?.evaluateJavascript(js, null) } }

    val spellcheckStrings = rememberSpellcheckStrings()

    // Schreiblinie („Typewriter") des Editors. Der Anker im Bundle ist ein Anteil
    // der WebView-Höhe; über der WebView liegen aber Statusleiste, TopAppBar und
    // ggf. der Sync-Streifen, unter ihr bei offener Tastatur nichts. Ein fixes 0.5
    // setzt die Schreiblinie darum unter die Bildschirmmitte, dicht an die
    // Tastatur. Diese Geometrie kennt nur die native Seite — sie rechnet daraus
    // den Anteil, der die Linie auf die Mitte des SICHTBAREN Bildschirms legt,
    // und schickt ihn an host.html (`window.__sw.setTypewriterAnchor`).
    val density = LocalDensity.current
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)
    var typewriterAnchor by remember { mutableStateOf(0.5f) }
    LaunchedEffect(state.editorReady, typewriterAnchor) {
        if (!state.editorReady) return@LaunchedEffect
        evalJs("window.__sw && window.__sw.setTypewriterAnchor($typewriterAnchor);")
    }

    val micDeniedMsg = stringResource(R.string.editor_mic_denied)
    val activity = context as? Activity
    // Dialoge der Mikrofon-Berechtigung (Diktat): Begründung vor erneuter Anfrage,
    // bzw. Hinweis auf die App-Einstellungen bei dauerhafter Ablehnung.
    var showMicRationale by remember { mutableStateOf(false) }
    var showMicBlocked by remember { mutableStateOf(false) }

    val startDictation = { vm.toggleDictation() }

    // Diktat-Text geht an DIESE WebView; nach Rotation registriert die neue Komposition
    // ihr eigenes Ziel (das VM puffert dazwischen, siehe EditorViewModel.attachTextSink).
    DisposableEffect(vm) {
        val sink: (String) -> Unit = { text -> insertText(evalJs, text) }
        vm.attachTextSink(sink)
        onDispose { vm.detachTextSink(sink) }
    }

    // Presence-/Schreibzeit-Heartbeat nur, solange diese Seite sichtbar ist: der
    // LifecycleOwner ist hier der Backstack-Eintrag — verdeckt (Wisch-Navigation,
    // Verlauf) oder im Hintergrund fällt er unter RESUMED und der Block wird gecancelt.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.runWhileVisible() }
    }

    // App geht in den Hintergrund: Save anstossen. Der Schliess-Save in onDispose greift
    // nur beim Verlassen der Komposition — beim Wegwischen aus den Recents oder einem
    // Prozess-Tod im Hintergrund gäbe es sonst keinen, und was im Autosave-Debounce
    // des Editors liegt, wäre verloren. Persistenz läuft über die Bridge im App-Scope.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        evalJs("window.__sw && window.__sw.save();")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> startDictation()
            // Nach der Anfrage keine Begründung mehr erlaubt → dauerhaft abgelehnt
            // („Nicht mehr fragen"): zu den App-Einstellungen leiten.
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.RECORD_AUDIO
            ) -> showMicBlocked = true
            else -> vm.notify(micDeniedMsg)
        }
    }
    // Berechtigungs-Gate für den Diktat-Start: erteilt → direkt; vorher abgelehnt
    // (Begründung erlaubt) → Begründung zeigen; sonst direkt anfragen.
    val requestDictation = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> startDictation()
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.RECORD_AUDIO
            ) -> showMicRationale = true
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Rechtschreibprüfung im WebView an-/abschalten. Erst wenn der Editor gemountet
    // ist (`onReady`), existiert das contenteditable, an das der Controller andockt —
    // vorher liefe der Aufruf ins Leere.
    LaunchedEffect(state.editorReady, state.spellcheckEnabled, state.spellcheckDebounceMs) {
        if (!state.editorReady) return@LaunchedEffect
        evalJs(
            "window.__sw && window.__sw.setSpellcheck(" +
                "${state.spellcheckEnabled}, ${state.spellcheckDebounceMs});"
        )
    }

    // Snackbar-Events: lokalisierbaren Message-Typ hier (Composable-Scope) auflösen.
    val messageText = state.message?.let { resolveEditorMsg(context, it) }
    LaunchedEffect(state.message) {
        messageText?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Speichern übernimmt zentral der onDispose-Barrier (siehe unten),
                        // sobald dieser Screen die Komposition verlässt.
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Dauerhafter Hinweis bei ungelöstem Konflikt: öffnet den Dialog erneut,
                    // auch wenn er einmal weggetippt wurde (sonst klebt der lokale Stand).
                    if (state.hasOpenConflict) {
                        IconButton(onClick = vm::reopenConflict) {
                            Icon(
                                Icons.Filled.SyncProblem,
                                contentDescription = stringResource(R.string.editor_conflict_indicator),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    // Versionsverlauf der Seite.
                    IconButton(onClick = {
                        onOpenHistory(pageId, pageTitle)
                    }) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = stringResource(R.string.history_title),
                        )
                    }
                    // Block-Formatierung in den eingebetteten Focus-Editor (host.html).
                    IconButton(onClick = { evalJs("window.__sw && window.__sw.insertHr();") }) {
                        Icon(
                            Icons.Filled.HorizontalRule,
                            contentDescription = stringResource(R.string.editor_insert_divider),
                        )
                    }
                    IconButton(onClick = { evalJs("window.__sw && window.__sw.insertChecklist();") }) {
                        Icon(
                            Icons.Filled.CheckBox,
                            contentDescription = stringResource(R.string.editor_insert_checklist),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (state.sttEnabled) {
                FloatingActionButton(onClick = {
                    if (state.transcribing) return@FloatingActionButton
                    // Taktiles Feedback beim Diktat-Start/Stop, da der Schnitt sonst stumm bleibt.
                    vibrateTick(context)
                    // Berechtigung prüfen → ggf. Begründung/Anfrage; bei Erfolg toggelt der
                    // Callback bzw. direkt start/stop des Diktats.
                    requestDictation()
                }) {
                    when {
                        state.transcribing -> CircularProgressIndicator(strokeWidth = 2.dp)
                        state.recording -> Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.editor_stop))
                        else -> Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.editor_dictate))
                    }
                }
            }
        },
    ) { padding ->
      // IME-Padding ist Voraussetzung für den Typewriter-Scroll der WebView:
      // Die Activity läuft edge-to-edge (`enableEdgeToEdge()` in MainActivity),
      // damit ist `windowSoftInputMode="adjustResize"` wirkungslos — das Fenster
      // wird von der Tastatur NICHT mehr verkleinert, sie liefert nur noch Insets.
      // Ohne dieses Padding behält die WebView volle Höhe, `visualViewport`/`100vh`
      // im Editor-Bundle melden weiter den ganzen Bildschirm, und die
      // Schreiblinie landet hinter der Tastatur → beim Tippen scrollt sichtbar
      // nichts mehr nach. Mit dem Padding schrumpft die WebView real und rechnet
      // mit dem tatsächlich sichtbaren Ausschnitt.
      // **`imeAnimationTarget` statt `ime`** (also nicht `imePadding()`): das
      // laufende Inset animiert die Tastaturhöhe über ~20 Frames hoch. Jeder
      // Frame ist eine echte WebView-Grössenänderung → Chromium legt das Dokument
      // neu aus, die vh-abhängigen Puffer der Schreibfläche verschieben den Text
      // gegenüber der Scroll-Position, und der Editor rechnet seinen Recenter
      // (100 ms debounced) gegen eine Höhe, die schon wieder veraltet ist —
      // sichtbar als Flackern beim Öffnen/Schliessen der Tastatur. Das
      // Animations-ZIEL springt einmal auf den Endwert: genau eine Grössenänderung.
      // `consumeWindowInsets(padding)` verhindert doppeltes Padding: die
      // Scaffold-Insets (Navigationsleiste) stecken bereits im IME-Inset.
      Column(
          Modifier
              .fillMaxSize()
              .padding(padding)
              .consumeWindowInsets(padding)
              .windowInsetsPadding(WindowInsets.imeAnimationTarget)
      ) {
        // Persistenter Offline-/Pending-Streifen — gerade im Editor wichtig, wo sonst
        // nur eine einmalige „Offline gespeichert"-Snackbar den Zustand andeutet.
        SyncStatusBar(online = online, pendingCount = pendingCount)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // Lage der WebView im Fenster → Anker der Schreiblinie (s.o.).
                // Alles über der WebView (Statusleiste, TopAppBar, Sync-Streifen)
                // und der sichtbare Rest darunter (Navigationsleiste, ohne die
                // Tastatur) gehen in die Rechnung ein.
                .onGloballyPositioned { coords ->
                    val windowHeight = coords.findRootCoordinates().size.height
                    val topGap = coords.positionInRoot().y.roundToInt()
                    val height = coords.size.height
                    val bottomGap = (windowHeight - topGap - height - imeTargetPx).coerceAtLeast(0)
                    val next = typewriterAnchorRatio(topGap, height, bottomGap)
                    if (abs(next - typewriterAnchor) > 0.005f) typewriterAnchor = next
                }
                .pageFlipGestures(
                    // Speichern erledigt der onDispose-Barrier beim Verlassen der Komposition.
                    onPrev = state.prevPage?.let { p ->
                        { onNavigateToPage(p.id, p.name) }
                    },
                    onNext = state.nextPage?.let { n ->
                        { onNavigateToPage(n.id, n.name) }
                    },
                )
        ) {
            when (val b = state.bundle) {
                is BundleState.Loading -> SkeletonParagraphs(Modifier.fillMaxSize())
                is BundleState.Error -> {
                    val msg = when (b.reason) {
                        BundleError.NO_SERVER_URL -> stringResource(R.string.editor_bundle_no_server)
                        BundleError.UNAVAILABLE -> stringResource(R.string.editor_bundle_unavailable)
                        BundleError.FAILED -> stringResource(
                            R.string.editor_bundle_error,
                            b.detail ?: stringResource(R.string.editor_bundle_error_fallback),
                        )
                    }
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.reloadBundle() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                is BundleState.Ready -> {
                    val bridge = remember(b.dir, darkTheme, spellcheckStrings) {
                        vm.newBridge(evalJs, darkTheme, spellcheckStrings)
                    }
                    EditorWebView(
                        bundleDir = b.dir,
                        bridge = bridge,
                        darkTheme = darkTheme,
                        onWebViewCreated = {
                            webViewRef.value = it
                            vm.onWebViewCreated()
                        },
                    )
                }
            }

            // Native Diktat-Statusleiste: pulsende Pegel-Anzeige + Label, animiert
            // ein-/ausgeblendet. Antippen beendet die Aufnahme (wie der FAB).
            DictationStatusBar(
                recording = state.recording,
                transcribing = state.transcribing,
                level = state.level,
                onStop = {
                    vibrateTick(context)
                    vm.toggleDictation()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
            )
        }
      }
    }

    // Konflikt-Dialog (409): beide Fassungen vergleichen, dann bewusst wählen,
    // welche bestehen bleibt (Server übernehmen ODER eigene Fassung durchsetzen).
    state.conflict?.let { c ->
        val editorName = c.serverEditorName ?: stringResource(R.string.editor_conflict_someone)
        val updatedAt = c.serverUpdatedAt ?: stringResource(R.string.editor_conflict_unknown_time)
        val emptyLabel = stringResource(R.string.editor_conflict_empty)
        AlertDialog(
            onDismissRequest = vm::dismissConflict,
            title = { Text(stringResource(R.string.editor_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.editor_conflict_message, editorName, updatedAt))
                    Spacer(Modifier.height(16.dp))
                    if (state.conflictLoading && state.conflictServerText == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.editor_conflict_loading))
                        }
                    } else {
                        ConflictVersionBlock(
                            label = stringResource(R.string.editor_conflict_your_version),
                            text = state.conflictLocalText?.ifBlank { emptyLabel } ?: emptyLabel,
                        )
                        Spacer(Modifier.height(12.dp))
                        ConflictVersionBlock(
                            label = stringResource(R.string.editor_conflict_server_version),
                            text = state.conflictServerText?.ifBlank { emptyLabel } ?: emptyLabel,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.resolveConflictWithServer { html ->
                        // Server-Stand still in den Editor spielen (kein erneuter Save).
                        val payload = JSONObject().put("id", pageId).put("html", html).toString()
                        evalJs("window.__sw && window.__sw.setPage(${jsString(payload)});")
                    }
                }) { Text(stringResource(R.string.editor_conflict_load_server)) }
            },
            dismissButton = {
                // Eigene Fassung durchsetzen (überschreibt den Server). Der Editor zeigt
                // sie bereits an, daher kein setPage nötig.
                TextButton(onClick = vm::resolveConflictWithLocal) {
                    Text(stringResource(R.string.editor_conflict_keep_local))
                }
            },
        )
    }

    // Mikrofon-Begründung: vor erneuter Anfrage erklären, wozu das Diktat das Mikrofon braucht.
    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text(stringResource(R.string.editor_mic_rationale_title)) },
            text = { Text(stringResource(R.string.editor_mic_rationale_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.editor_mic_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Dauerhaft abgelehnt: in die App-Einstellungen leiten (dort lässt sich das Recht aktivieren).
    if (showMicBlocked) {
        AlertDialog(
            onDismissRequest = { showMicBlocked = false },
            title = { Text(stringResource(R.string.editor_mic_rationale_title)) },
            text = { Text(stringResource(R.string.editor_mic_blocked)) },
            confirmButton = {
                TextButton(onClick = {
                    showMicBlocked = false
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }) { Text(stringResource(R.string.editor_mic_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showMicBlocked = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Beim Verlassen (Back, History, Wisch-Navigation): einziger, verlässlicher
    // Schliess-Save. Wir stossen den finalen Save an und warten kurz, bis der Editor
    // ihn an die Bridge übergeben hat — erst DANN wird die WebView abgeräumt, damit
    // die (asynchrone) JS-Save-Kette die Übergabe sicher erreicht. Persist/Flush selbst
    // läuft im applicationScope weiter und überlebt das destroy(). Der ganze Ablauf
    // hängt am applicationScope, nicht am (gleich gecancelten) viewModelScope.
    val appScope = context.locator.applicationScope
    DisposableEffect(Unit) {
        onDispose {
            // Seite wirklich verlassen (nicht bloss Rotation/Theme-Wechsel): laufende
            // Aufnahme beenden — das VM bleibt bei Wisch-Navigation im Backstack.
            if (activity?.isChangingConfigurations != true) vm.stopDictationOnLeave()
            val wv = webViewRef.value
            webViewRef.value = null
            if (wv != null) {
                appScope.launch(Dispatchers.Main) {
                    vm.saveBeforeClose { js -> wv.evaluateJavascript(js, null) }
                    wv.destroy()
                }
            }
        }
    }
}

/**
 * i18n der Spellcheck-UI, die im WebView gerendert wird (Badge/Popover des
 * Editor-Bundles). Hier aufgelöst, weil nur der Composable-Scope Zugriff auf die
 * String-Ressourcen hat; die Bridge reicht die Map als JSON nach JS durch. Die
 * Schlüssel sind die i18n-Keys des Spellcheck-Controllers. Stabil gehalten
 * (`remember`), damit die WebView-Bridge nicht bei jeder Recomposition neu entsteht.
 */
@Composable
private fun rememberSpellcheckStrings(): Map<String, String> {
    val statusActive = stringResource(R.string.spellcheck_status_active)
    val statusDisabled = stringResource(R.string.spellcheck_status_disabled)
    val statusError = stringResource(R.string.spellcheck_status_error)
    val statusMatches = stringResource(R.string.spellcheck_status_matches)
    val statusNoMatches = stringResource(R.string.spellcheck_status_no_matches)
    val popoverClose = stringResource(R.string.spellcheck_popover_close)
    val popoverNoSuggestions = stringResource(R.string.spellcheck_popover_no_suggestions)
    val popoverIgnore = stringResource(R.string.spellcheck_popover_ignore)
    val popoverAddToDict = stringResource(R.string.spellcheck_popover_add_to_dict)
    val popoverRuleInfo = stringResource(R.string.spellcheck_popover_rule_info)
    return remember(
        statusActive, statusDisabled, statusError, statusMatches, statusNoMatches,
        popoverClose, popoverNoSuggestions, popoverIgnore, popoverAddToDict, popoverRuleInfo,
    ) {
        mapOf(
            "spellcheck.status.active" to statusActive,
            "spellcheck.status.disabled" to statusDisabled,
            "spellcheck.status.error" to statusError,
            "spellcheck.status.matches" to statusMatches,
            "spellcheck.status.no_matches" to statusNoMatches,
            "spellcheck.popover.close" to popoverClose,
            "spellcheck.popover.no_suggestions" to popoverNoSuggestions,
            "spellcheck.popover.ignore" to popoverIgnore,
            "spellcheck.popover.add_to_dict" to popoverAddToDict,
            "spellcheck.popover.rule_info" to popoverRuleInfo,
        )
    }
}

/**
 * Anker der Schreiblinie als Anteil der WebView-Höhe (0–1), so dass die Linie auf
 * der Mitte des sichtbaren Bildschirms liegt.
 *
 * [topGap]/[bottomGap] sind die sichtbaren Flächen über/unter der WebView (oben
 * Statusleiste + TopAppBar + Sync-Streifen, unten Navigationsleiste; die Tastatur
 * zählt NICHT mit, sie ist nicht sichtbarer Bildschirm). Einheit ist beliebig,
 * solange alle drei Werte dieselbe benutzen — das Ergebnis ist ein Verhältnis.
 *
 * Mitte des sichtbaren Bereichs = `(topGap + height + bottomGap) / 2`, gemessen ab
 * Bildschirmoberkante; minus [topGap] ergibt den Abstand ab WebView-Oberkante.
 * Geklemmt, damit extreme Geometrien (sehr flache WebView neben hoher Chrome) die
 * Linie nicht an den Rand der Schreibfläche drücken.
 */
internal fun typewriterAnchorRatio(topGap: Int, height: Int, bottomGap: Int): Float {
    if (height <= 0) return 0.5f
    val visible = topGap + height + bottomGap
    if (visible <= 0) return 0.5f
    return ((visible / 2f - topGap) / height).coerceIn(0.25f, 0.6f)
}

/** Fügt Diktat-Text am Cursor der aktiven Seite ein (host.html: window.__sw.insertText). */
private fun insertText(evalJs: (String) -> Unit, text: String) {
    evalJs("window.__sw && window.__sw.insertText(${jsString(text)});")
}

/** Sicheres JS-/JSON-String-Literal. */
private fun jsString(value: String): String = JSONObject.quote(value)

/** Löst den lokalisierbaren [EditorMsg]-Typ in den anzuzeigenden Snackbar-Text auf. */
private fun resolveEditorMsg(context: Context, msg: EditorMsg): String = when (msg) {
    EditorMsg.SavedOffline -> context.getString(R.string.editor_msg_saved_offline)
    is EditorMsg.Conflict -> context.getString(
        R.string.editor_msg_conflict,
        msg.editorName ?: context.getString(R.string.editor_conflict_someone),
    )
    is EditorMsg.Locked -> context.getString(
        R.string.editor_msg_locked,
        msg.lockedBy ?: context.getString(R.string.editor_lock_default),
    )
    is EditorMsg.EditorError -> context.getString(R.string.editor_msg_error, msg.detail)
    is EditorMsg.RecordFailed -> context.getString(R.string.editor_msg_record_failed, msg.detail)
    EditorMsg.ServerResolved -> context.getString(R.string.editor_msg_server_resolved)
    EditorMsg.LocalResolved -> context.getString(R.string.editor_msg_local_resolved)
    is EditorMsg.LoadFailed -> context.getString(R.string.editor_msg_load_failed, msg.detail)
    is EditorMsg.Raw -> msg.text
}

/**
 * Eine beschriftete, scrollbare Fassung im Konflikt-Vergleich. Höhe gedeckelt,
 * damit der Dialog auch bei langem Text handhabbar bleibt.
 */
@Composable
private fun ConflictVersionBlock(label: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(8.dp),
                )
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Kurzer, spürbarer Vibrations-Tick beim Diktat-Start/-Stop. Respektiert fehlende
 * Hardware (kein Motor → no-op); die System-Haptik-Einstellung greift weiterhin.
 */
private fun vibrateTick(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
}

/**
 * Bodennahe Statusleiste während des Diktats. Beim Aufnehmen zeigt sie eine
 * lebendige Pegel-Anzeige (reagiert auf [level]) und „Höre zu …"; während der
 * Transkription einen Spinner. Tippen beendet die Aufnahme. Gleitet sanft ein
 * und aus, sobald sich der Zustand ändert.
 */
@Composable
private fun DictationStatusBar(
    recording: Boolean,
    transcribing: Boolean,
    level: Float,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = recording || transcribing,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            // Als Live-Region zusammengefasst: TalkBack sagt „Höre zu …" bzw.
            // „Transkribiere …" an, sobald der Streifen erscheint. Beim Aufnehmen
            // ist er antippbar zum Beenden (wie der FAB) – mit Aktionslabel & Rolle.
            modifier = Modifier
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .then(
                    if (recording) {
                        Modifier.clickable(
                            onClickLabel = stringResource(R.string.editor_stop),
                            role = Role.Button,
                            onClick = onStop,
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (transcribing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.editor_transcribing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LevelEqualizer(
                        level = level,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column {
                        Text(
                            stringResource(R.string.editor_listening),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.editor_listening_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fünf-Balken-Equalizer, der auf den Mikrofon-Pegel reagiert. Eine endlose
 * Wellen-Phase moduliert die Balken zeitversetzt, damit die Anzeige auch bei
 * konstantem Pegel „atmet"; ein Pegel-Boden hält sie bei Stille lebendig.
 */
@Composable
private fun LevelEqualizer(level: Float, color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val bars = 5
    val maxHeight = 22.dp
    val barWidth = 4.dp
    val amplitude = max(level, 0.10f) // Boden, damit es bei Stille leicht pulst.
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(maxHeight),
    ) {
        repeat(bars) { i ->
            val wave = (sin(phase + i * 0.9f) * 0.5f + 0.5f) // 0..1
            val fraction = (0.18f + 0.82f * amplitude * wave).coerceIn(0.12f, 1f)
            Box(
                Modifier
                    .width(barWidth)
                    .height(maxHeight * fraction)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EditorWebView(
    bundleDir: java.io.File,
    // Konkreter Typ (nicht Any): nur so erkennt Lint die @JavascriptInterface-
    // Methoden der Bridge (sonst JavascriptInterface-Error beim addJavascriptInterface).
    bridge: EditorBridge,
    darkTheme: Boolean,
    onWebViewCreated: (WebView) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            // Asset-Loader: Bundle-Verzeichnis an Origin-Root mappen, damit relative
            // Imports (./js/…) und /icons.svg same-origin auflösen.
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(context, bundleDir))
                .build()

            WebView(context).apply {
                // WebView-Hintergrund passend zum Theme, damit beim Laden kein
                // weisser Blitz vor dem CSS-Inject aufscheint (Navy = Dark-BG).
                setBackgroundColor(if (darkTheme) 0xFF1A1F3A.toInt() else 0xFFFAF7F2.toInt())
                // Gescrollt wird ausschliesslich der Editor-Container im Dokument
                // (`.focus-editor__content`); das Dokument selbst ist auf
                // Viewport-Höhe fixiert. Der Overscroll-Effekt der WebView kann
                // hier also nur noch als Glow/Stretch am Rand aufblitzen, wenn der
                // Typewriter-Scroll gegen den Anschlag fährt — abschalten.
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    // Navigation ausserhalb des App-Origins unterbinden. Die SWHost-Bridge
                    // (loadPage/savePage) bleibt sonst für jede Seite verfügbar, zu der die
                    // WebView navigiert — nur der eigene appassets-Origin (Bundle/host.html)
                    // darf sie steuern. Echte http(s)-Links im Inhalt gehen in den
                    // System-Browser statt in der App-WebView zu öffnen.
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        if (url.scheme == "https" && url.host == APP_ORIGIN_HOST) return false
                        if (url.scheme == "https" || url.scheme == "http") {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        return true // alles Übrige blockieren
                    }
                }
                // Bridge injizieren (Name muss zu host.html passen: SWHost).
                addJavascriptInterface(bridge, "SWHost")
                onWebViewCreated(this)
                loadUrl("$APP_ORIGIN/host.html")
            }
        },
    )
}
