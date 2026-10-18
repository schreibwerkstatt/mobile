package ch.schreibwerkstatt.mobile.data.net

import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.net.dto.BookDto
import ch.schreibwerkstatt.mobile.data.net.dto.BundleManifestDto
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.ConfigDto
import ch.schreibwerkstatt.mobile.data.net.dto.CreateChapterRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreatePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreateResearchRequest
import ch.schreibwerkstatt.mobile.data.net.dto.PageConflictDto
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.PageLockedDto
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchUrlDto
import ch.schreibwerkstatt.mobile.data.net.dto.RestoreResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDetailResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionListResponse
import ch.schreibwerkstatt.mobile.data.net.dto.SavePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.SyncResponse
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Client-Vertrag des Servers (`contract/` im Mutterprojekt, vendored per
 * `scripts/sync-contract.sh` nach `src/test/resources/contract/`).
 *
 * Jede Fixture ist eine echte Server-Antwort `{ operation, request, status, response }`.
 * Geprüft wird mit den ECHTEN DTOs und der ECHTEN Json-Konfiguration ([ApiJson], die
 * auch Retrofit benutzt):
 * - jede Antwort, die die App liest, dekodiert;
 * - jeder Feldname, den die App sendet, steht im Request-Schema von `openapi.json`
 *   (Tippfehler = rot);
 * - jede Fixture-Datei ist entweder einem DTO zugeordnet oder bewusst ignoriert — eine
 *   neue Fixture vom Server zwingt zur Entscheidung.
 */
class ContractFixturesTest {

    private val json: Json = ApiJson

    private data class Fixture(
        val name: String,
        val operation: String,
        val status: Int,
        val request: JsonObject?,
        val response: JsonElement,
    ) {
        val requestBody: JsonObject? get() = (request?.get("body") as? JsonObject)
    }

    private val fixtures: Map<String, Fixture> by lazy {
        val dir = resource("contract/fixtures")
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        assertTrue("keine Fixtures unter $dir — scripts/sync-contract.sh laufen lassen", files.isNotEmpty())
        files.associate { f ->
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            val name = f.name.removeSuffix(".json")
            name to Fixture(
                name = name,
                operation = o["operation"]!!.jsonPrimitive.content,
                status = o["status"]!!.jsonPrimitive.int,
                request = o["request"] as? JsonObject,
                response = o["response"] ?: JsonNull,
            )
        }
    }

    private val openapi: JsonObject by lazy {
        Json.parseToJsonElement(resource("contract/openapi.json").readText()).jsonObject
    }

    // ── Zuordnung ───────────────────────────────────────────────────────────

    @Test fun `every fixture is decoded or explicitly ignored`() {
        val known = DECODERS.keys + STATUS_ONLY.keys + IGNORED.keys
        val unknown = fixtures.keys - known
        assertTrue(
            "Neue Fixture(s) vom Server: $unknown — einem DTO in DECODERS zuordnen, als " +
                "STATUS_ONLY führen oder mit Begründung in IGNORED aufnehmen",
            unknown.isEmpty(),
        )
        val stale = known - fixtures.keys
        assertTrue("Zuordnung ohne Fixture (vom Server entfernt?): $stale", stale.isEmpty())
        val twice = DECODERS.keys.intersect(IGNORED.keys) + STATUS_ONLY.keys.intersect(IGNORED.keys)
        assertTrue("gleichzeitig genutzt und ignoriert: $twice", twice.isEmpty())
    }

    @Test fun `contract version is the one this client was written against`() {
        val v = Json.parseToJsonElement(resource("contract/version.json").readText()).jsonObject
        assertTrue("api >= 1", v["api"]!!.jsonPrimitive.int >= 1)
        assertTrue(
            "editorApi ${v["editorApi"]} kann die Schale nicht (BundleManager.SUPPORTED_EDITOR_API)",
            v["editorApi"]!!.jsonPrimitive.int in ch.schreibwerkstatt.mobile.bundle.BundleManager.SUPPORTED_EDITOR_API,
        )
    }

    // ── Antworten ───────────────────────────────────────────────────────────

    @Test fun `every used response decodes with the app DTO`() {
        val failures = DECODERS.mapNotNull { (name, deserializer) ->
            val fx = fixtures.getValue(name)
            runCatching { json.decodeFromJsonElement(deserializer, fx.response) }
                .exceptionOrNull()?.let { "$name (${fx.operation} → ${fx.status}): ${it.message}" }
        }
        if (failures.isNotEmpty()) fail("Nicht dekodierbar:\n" + failures.joinToString("\n"))
    }

    @Test fun `status-only fixtures carry the status the app branches on`() {
        for ((name, status) in STATUS_ONLY) {
            assertEquals(name, status, fixtures.getValue(name).status)
        }
    }

    @Test fun `error fixtures carry an error code`() {
        for (name in ERROR_FIXTURES) {
            val e = decode<ApiErrorDto>(name, ApiErrorDto.serializer())
            assertNotNull("$name ohne error_code", e.code)
        }
        assertEquals("EDITOR_API_UNSUPPORTED", decode("editor-bundle-unsupported", ApiErrorDto.serializer()).code)
        assertEquals(409, fixtures.getValue("editor-bundle-unsupported").status)
    }

    @Test fun `push-merged returns the merged state`() {
        val fx = fixtures.getValue("push-merged")
        assertEquals(200, fx.status)
        val page = decode("push-merged", PageDto.serializer())
        assertEquals(true, page.merged)
        assertNotNull(page.html)
        assertTrue("gemergter Stand ≠ Gesendetes", page.html != fx.requestBody?.get("html")?.jsonPrimitive?.content)
        // Der gemergte Write trägt keine Save-Kennung: sein Stand enthält fremde Blöcke,
        // die die App erst mit der Antwort sieht — Wiederholung und Nachfolger mergen erneut.
        assertNull(page.last_save_id)
    }

    @Test fun `push-merge-conflict lists the colliding blocks`() {
        assertEquals(409, fixtures.getValue("push-merge-conflict").status)
        val c = decode("push-merge-conflict", PageConflictDto.serializer())
        assertEquals("PAGE_CONFLICT", c.error_code)
        assertFalse(c.merge_conflicts.isNullOrEmpty())
        assertNotNull(c.server_updated_at)
    }

    @Test fun `plain push conflict has no merge_conflicts`() {
        val c = decode("push-conflict", PageConflictDto.serializer())
        assertEquals("PAGE_CONFLICT", c.error_code)
        assertTrue(c.merge_conflicts == null)
    }

    @Test fun `push-replay is recognised as replay`() {
        assertEquals("replay", decode("push-replay", PageDto.serializer()).save_match)
    }

    @Test fun `sync-delta carries deletions and a cursor`() {
        val s = decode("sync-delta", SyncResponse.serializer())
        assertFalse(s.deleted.isEmpty())
        assertFalse(s.pages.isEmpty())
        assertNotNull(s.cursor?.since)
        val full = decode("sync-full", SyncResponse.serializer())
        assertTrue(full.pages.all { it.html != null && it.updated_at != null })
    }

    @Test fun `config announces the client contract`() {
        val cfg = decode("config", ConfigDto.serializer())
        val cc = cfg.clientContract
        assertNotNull(cc)
        assertTrue("clientContract.api >= 1", cc!!.api >= 1)
        assertTrue(cc.editorApi in ch.schreibwerkstatt.mobile.bundle.BundleManager.SUPPORTED_EDITOR_API)
        assertNotNull(cfg.languagetool)
        assertNotNull(cfg.stt)
    }

    @Test fun `bundle manifest carries a supported editorApi`() {
        val m = decode("editor-bundle", BundleManifestDto.serializer())
        val major = m.editorApi
        assertNotNull("bundle-manifest.json ohne editorApi", major)
        assertTrue(major!! in ch.schreibwerkstatt.mobile.bundle.BundleManager.SUPPORTED_EDITOR_API)
        val header = fixtures.getValue("editor-bundle").request?.get("headers")?.jsonObject
            ?.get(ch.schreibwerkstatt.mobile.bundle.BundleManager.EDITOR_API_HEADER)
        assertNotNull("Fixture ohne X-Editor-Api-Header — Header-Name geändert?", header)
    }

    @Test fun `tree keeps nested chapters and top pages`() {
        val t = decode("book-tree", TreeDto.serializer())
        assertFalse(t.chapters.isEmpty())
        assertTrue(t.chapters.any { it.subchapters.isNotEmpty() })
        assertTrue(t.chapters.any { it.pages.isNotEmpty() })
    }

    // ── Requests ────────────────────────────────────────────────────────────

    @Test fun `save request fields are known to the server`() {
        val req = SavePageRequest(
            html = "<p>x</p>", device_id = "d", source = "main", expected_updated_at = "t",
            save_id = "s", own_save_ids = listOf("o"), base_html = "<p>b</p>",
        )
        val body = encode(SavePageRequest.serializer(), req)
        assertKnown(body, "PageSaveRequest", fixtures.getValue("push-merged").requestBody)
        assertTrue("base_html wird gesendet", "base_html" in body)
    }

    @Test fun `create requests fields are known to the server`() {
        assertKnown(
            encode(CreatePageRequest.serializer(), CreatePageRequest(book_id = 1, chapter_id = 2, name = "n", html = "<p></p>")),
            "CreatePageRequest", fixtures.getValue("page-create").requestBody,
        )
        assertKnown(
            encode(CreateChapterRequest.serializer(), CreateChapterRequest(book_id = 1, name = "n", position = 1, parent_chapter_id = 2)),
            "CreateChapterRequest", fixtures.getValue("chapter-create").requestBody,
        )
        assertKnown(
            encode(DevicePingRequest.serializer(), DevicePingRequest(device_id = "d", page_id = 1)),
            "DevicePingRequest", fixtures.getValue("device-ping").requestBody,
        )
        assertKnown(
            encode(WritingTimeRequest.serializer(), WritingTimeRequest(book_id = 1, seconds = 30)),
            "WritingTimeRequest", fixtures.getValue("writing-time").requestBody,
        )
        assertKnown(
            encode(DictionaryAddRequest.serializer(), DictionaryAddRequest(word = "w", bookId = 3, lang = "de")),
            "DictionaryAddRequest", fixtures.getValue("dictionary-add").requestBody,
        )
        assertKnown(
            encode(LtCheckRequest.serializer(), LtCheckRequest(text = "t", language = "de", bookId = 1, pageId = 2)),
            "LtCheckRequest", null,
        )
    }

    @Test fun `research request is sent as a link with known fields`() {
        // So, wie ResearchRepository.createLink es baut.
        val req = CreateResearchRequest(
            book_id = 1, kind = "link", title = "T", body = "B", source = "S",
            urls = listOf(ResearchUrlDto(url = "https://example.org", label = "L")), tags = listOf("x"),
        )
        val body = encode(CreateResearchRequest.serializer(), req)
        assertKnown(body, "ResearchCreateRequest", fixtures.getValue("research-create").requestBody)
        // Ohne `kind` legt der Server still eine Notiz an statt eines Links.
        assertEquals("link", body["kind"]?.jsonPrimitive?.content)
    }

    // ── Helfer ──────────────────────────────────────────────────────────────

    private fun <T> decode(name: String, deserializer: DeserializationStrategy<T>): T =
        json.decodeFromJsonElement(deserializer, fixtures.getValue(name).response)

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    /**
     * Jeder gesendete Feldname steht im Request-Schema [schema] (rekursiv über `$ref` und
     * Arrays) oder im Body des Fixture-Requests [fixtureBody].
     */
    private fun assertKnown(body: JsonObject, schema: String, fixtureBody: JsonObject?) {
        val unknown = unknownFields(body, schemaObject(schema), fixtureBody, schema)
        assertTrue("$schema: Feld(er) kennt der Server nicht: $unknown", unknown.isEmpty())
    }

    private fun unknownFields(body: JsonObject, schema: JsonObject, fixtureBody: JsonObject?, path: String): List<String> {
        val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        return body.flatMap { (key, value) ->
            val prop = props[key]?.jsonObject
            if (prop == null && fixtureBody?.containsKey(key) != true) return@flatMap listOf("$path.$key")
            val itemSchema = prop?.let { p ->
                val items = p["items"]?.jsonObject
                (items ?: p)["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/')
            }
            when {
                itemSchema == null -> emptyList()
                value is JsonArray -> value.filterIsInstance<JsonObject>().flatMap {
                    unknownFields(it, schemaObject(itemSchema), null, "$path.$key[]")
                }
                value is JsonObject -> unknownFields(value, schemaObject(itemSchema), null, "$path.$key")
                else -> emptyList()
            }
        }
    }

    private fun schemaObject(name: String): JsonObject =
        openapi["components"]?.jsonObject?.get("schemas")?.jsonObject?.get(name)?.jsonObject
            ?: error("Schema $name fehlt in openapi.json")

    private fun resource(path: String): File {
        val url = javaClass.classLoader?.getResource(path)
            ?: error("$path fehlt im Test-Classpath — scripts/sync-contract.sh laufen lassen")
        return File(url.toURI())
    }

    private companion object {
        /** Fixture → DTO, mit dem die App diese Antwort liest. */
        val DECODERS: Map<String, DeserializationStrategy<Any>> = mapOf(
            "books" to ListSerializer(BookDto.serializer()),
            "book-tree" to TreeDto.serializer(),
            "sync-full" to SyncResponse.serializer(),
            "sync-delta" to SyncResponse.serializer(),
            "page" to PageDto.serializer(),
            "push" to PageDto.serializer(),
            "push-replay" to PageDto.serializer(),
            "push-merged" to PageDto.serializer(),
            "rename" to PageDto.serializer(),
            "page-create" to PageDto.serializer(),
            "push-conflict" to PageConflictDto.serializer(),
            "push-merge-conflict" to PageConflictDto.serializer(),
            "push-locked" to PageLockedDto.serializer(),
            "revisions" to RevisionListResponse.serializer(),
            "revision" to RevisionDetailResponse.serializer(),
            "revision-restore" to RestoreResponse.serializer(),
            "chapter-create" to ChapterNodeDto.serializer(),
            "research-create" to ResearchItemDto.serializer(),
            "research-list" to ListSerializer(ResearchItemDto.serializer()),
            "config" to ConfigDto.serializer(),
            "editor-bundle" to BundleManifestDto.serializer(),
            "auth-invalid-token" to ApiErrorDto.serializer(),
            "scope-forbidden" to ApiErrorDto.serializer(),
            "push-empty" to ApiErrorDto.serializer(),
            "page-not-found" to ApiErrorDto.serializer(),
            "editor-bundle-unsupported" to ApiErrorDto.serializer(),
        )

        val ERROR_FIXTURES = listOf(
            "auth-invalid-token", "scope-forbidden", "push-empty", "page-not-found", "editor-bundle-unsupported",
        )

        /**
         * Die App ruft die Operation auf, liest aber keinen Body (`Response<Unit>` bzw.
         * 304): geprüft wird nur der Status, auf den sie verzweigt.
         */
        val STATUS_ONLY: Map<String, Int> = mapOf(
            "device-ping" to 200,
            "writing-time" to 200,
            "dictionary-add" to 200,
            "editor-bundle-unchanged" to 304,
        )

        /** Fixtures, die diese App nicht nutzt — mit Grund. */
        val IGNORED: Map<String, String> = mapOf(
            "account-delete-unconfirmed" to "Konto-Selbstlöschung gibt es nur im Mac-Client",
            "book-settings" to "Buch-Einstellungen liest nur der Mac-Client",
            "device-tokens" to "Geräteliste nur im Mac-Client; Android koppelt per manuell eingegebenem Token",
            "export-md" to "Markdown-Export nur im Mac-Client",
            "job-check-create" to "KI-Jobs (Lektorat) nur im Mac-Client",
            "job-check-done" to "KI-Jobs (Lektorat) nur im Mac-Client",
            "job-synonym-create" to "KI-Jobs (Synonyme) nur im Mac-Client",
            "job-synonym-done" to "KI-Jobs (Synonyme) nur im Mac-Client",
            "page-delete" to "Die App löscht keine Abschnitte",
        )
    }
}
