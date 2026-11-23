package ch.schreibwerkstatt.mobile.data.net

import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * Abgewiesene Server-Antwort mit gelesenem Fehler-Body. Anders als [HttpException]
 * trägt sie den `error_code` (z.B. `INSUFFICIENT_ROLE`), damit die UI die Ursache
 * nennen kann statt nur den Statuscode.
 */
class ApiException(
    val status: Int,
    val error: ApiErrorDto?,
) : RuntimeException("HTTP $status${error?.code?.let { " $it" } ?: ""}") {
    val code: String? get() = error?.code

    companion object {
        fun from(resp: Response<*>): ApiException = ApiException(resp.code(), parseApiError(resp.errorBody()))
        fun from(e: HttpException): ApiException = ApiException(e.code(), parseApiError(e.response()?.errorBody()))
    }
}

/** Fehler-Body als [ApiErrorDto]; null, wenn leer oder kein JSON. */
fun parseApiError(body: ResponseBody?): ApiErrorDto? =
    runCatching { body?.string() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
        runCatching { ApiJson.decodeFromString(ApiErrorDto.serializer(), it) }.getOrNull()
    }

/** Netzaufruf mit Lese-Antwort: [HttpException] wird zu [ApiException] mit `error_code`. */
suspend fun <T> withApiErrors(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        throw ApiException.from(e)
    }
