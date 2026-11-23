package ch.schreibwerkstatt.mobile.ideen

import android.content.Intent

/**
 * Systemweiter Einstieg ins Schnell-Erfassen einer Idee: App-Shortcut, Schnell-
 * einstellungs-Kachel oder „Teilen → Als Idee". [text] ist die Vorbelegung (nur beim
 * Teilen nicht leer). Das Buch wählt der Capture-Screen selbst (zuletzt benutztes).
 */
data class IdeaLaunch(val text: String)

const val ACTION_NEW_IDEA = "ch.schreibwerkstatt.mobile.action.NEW_IDEA"
private const val SHARE_ALIAS_SUFFIX = ".ShareAsIdea"

/** true, wenn der Intent über den Teilen-Alias „Als Idee" kam. */
fun isIdeaShare(intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_SEND &&
        intent.component?.className?.endsWith(SHARE_ALIAS_SUFFIX) == true

/**
 * Liefert den Ideen-Einstieg aus einem Intent oder null. Beim Teilen werden Betreff
 * und Text zusammengeführt (Browser teilen oft „Titel" + URL getrennt).
 */
fun parseIdeaLaunch(intent: Intent?): IdeaLaunch? {
    if (intent == null) return null
    if (intent.action == ACTION_NEW_IDEA) return IdeaLaunch("")
    if (!isIdeaShare(intent)) return null
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())?.trim().orEmpty()
    val combined = when {
        subject.isEmpty() -> text
        text.isEmpty() || text.contains(subject) -> text.ifEmpty { subject }
        else -> "$subject\n$text"
    }
    return IdeaLaunch(combined)
}
