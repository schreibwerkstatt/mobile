package ch.schreibwerkstatt.mobile.bundle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Cache zuerst: Mit lauffähigem Bundle darf das Öffnen des Editors nie auf den Server
 * warten; ein neues Bundle wird im Hintergrund vorbereitet und erst beim nächsten
 * [BundleManager.ensureBundle] eingesetzt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BundleManagerCacheFirstTest {

    private lateinit var server: MockWebServer
    private lateinit var ctx: Context
    private lateinit var manager: BundleManager

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        ctx = ApplicationProvider.getApplicationContext()
        manager = BundleManager(ctx, token = { "swd_test" }, onUnauthorized = {})
    }

    @After fun tearDown() = server.shutdown()

    private val base get() = server.url("/").toString()

    private fun zip(marker: String): Buffer {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("js/editor/focus/standalone.js"))
            z.write("// $marker".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("bundle-manifest.json"))
            z.write("""{"editorApi":1}""".toByteArray())
            z.closeEntry()
        }
        return Buffer().write(out.toByteArray())
    }

    private fun bundleResponse(marker: String, etag: String) =
        MockResponse().setBody(zip(marker)).setHeader("ETag", etag)

    private fun installedMarker(): String =
        File(manager.bundleDir, "js/editor/focus/standalone.js").readText()

    private fun awaitRequests(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (server.requestCount < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(n, server.requestCount)
    }

    /** Wartet, bis die Hintergrund-Prüfung ein Bundle vorbereitet hat. */
    private fun awaitPending() {
        val pending = File(ctx.filesDir, "editor-bundle.pending")
        val deadline = System.currentTimeMillis() + 5_000
        while (!File(pending, "js/editor/focus/standalone.js").exists() &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
        // Rename und ETag liegen gemeinsam unter dem Lock — kurz nachlaufen lassen.
        Thread.sleep(100)
    }

    @Test fun `without cache the download is awaited and installed`() = runBlocking {
        server.enqueue(bundleResponse("v1", "\"e1\""))

        assertTrue(manager.ensureBundle(base).getOrThrow())
        assertEquals("// v1", installedMarker())
        assertTrue(File(manager.bundleDir, "host.html").exists())
    }

    @Test fun `with cache a hanging server does not delay opening`() = runBlocking {
        server.enqueue(bundleResponse("v1", "\"e1\""))
        manager.ensureBundle(base).getOrThrow()

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertTrue(manager.ensureBundle(base).getOrThrow())
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("ensureBundle wartete $tookMs ms", tookMs < 1_000)
        assertEquals("// v1", installedMarker())
    }

    @Test fun `new bundle is staged in the background and installed on the next open`() = runBlocking {
        server.enqueue(bundleResponse("v1", "\"e1\""))
        manager.ensureBundle(base).getOrThrow()

        server.enqueue(bundleResponse("v2", "\"e2\""))
        manager.ensureBundle(base).getOrThrow()
        // Die laufende WebView behält ihr Bundle.
        assertEquals("// v1", installedMarker())
        awaitRequests(2)
        server.takeRequest() // Erst-Download
        assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match"))
        awaitPending()

        server.enqueue(MockResponse().setResponseCode(304))
        manager.ensureBundle(base).getOrThrow()
        assertEquals("// v2", installedMarker())
        // Die nächste Prüfung fragt mit dem ETag des eingesetzten Bundles.
        awaitRequests(3)
        assertEquals("\"e2\"", server.takeRequest().getHeader("If-None-Match"))
    }
}
