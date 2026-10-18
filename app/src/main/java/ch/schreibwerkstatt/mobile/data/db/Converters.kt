package ch.schreibwerkstatt.mobile.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room-Typkonverter: String-Listen als JSON-Array in einer TEXT-Spalte. */
class Converters {
    @TypeConverter
    fun stringListToJson(value: List<String>): String = Json.encodeToString(STRING_LIST, value)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString(STRING_LIST, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        val STRING_LIST = ListSerializer(String.serializer())
    }
}
