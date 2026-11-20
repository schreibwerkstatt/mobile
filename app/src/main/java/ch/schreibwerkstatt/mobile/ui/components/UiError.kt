package ch.schreibwerkstatt.mobile.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import ch.schreibwerkstatt.mobile.R
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Nutzertaugliche Fehlerkategorie statt roher Exception-Texte
 * («Unable to resolve host …», «restore HTTP 423») im UI. ViewModels halten ein
 * [UiError] im State, Screens lösen es per [text] / [message] in einen
 * lokalisierten String auf.
 */
sealed interface UiError {
    data object Offline : UiError
    data object Locked : UiError
    data object NotFound : UiError
    data object Unauthorized : UiError
    data object BadResponse : UiError
    data class Server(val code: Int) : UiError
    /** Unbekannter Fehler; [detail] nur als technischer Zusatz (Klassenname, wenn keine Message). */
    data class Unknown(val detail: String) : UiError
}

private val HTTP_IN_MESSAGE = Regex("""\bHTTP (\d{3})\b""")

fun Throwable.toUiError(): UiError = when (this) {
    is HttpException -> httpError(code())
    is IOException -> UiError.Offline
    is SerializationException -> UiError.BadResponse
    else -> HTTP_IN_MESSAGE.find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        ?.let(::httpError)
        ?: UiError.Unknown(message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName)
}

private fun httpError(code: Int): UiError = when (code) {
    401, 403 -> UiError.Unauthorized
    404 -> UiError.NotFound
    423 -> UiError.Locked
    else -> UiError.Server(code)
}

fun UiError.message(res: Resources): String = when (this) {
    UiError.Offline -> res.getString(R.string.error_offline)
    UiError.Locked -> res.getString(R.string.error_locked)
    UiError.NotFound -> res.getString(R.string.error_not_found)
    UiError.Unauthorized -> res.getString(R.string.error_unauthorized)
    UiError.BadResponse -> res.getString(R.string.error_bad_response)
    is UiError.Server -> res.getString(R.string.error_server, code)
    is UiError.Unknown -> res.getString(R.string.error_unknown, detail)
}

@Composable
fun UiError.text(): String = message(LocalContext.current.resources)
