package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.TreeCacheEntity
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreePageDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline-Gliederung: jede erfolgreiche `tree()`-Antwort ist danach über `cachedTree()` lesbar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositoryTreeCacheTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeContentApi
    private lateinit var repo: ContentRepository

    private val tree = TreeDto(
        chapters = listOf(
            ChapterNodeDto(
                id = 1, name = "Teil 1", position = 0,
                subchapters = listOf(ChapterNodeDto(id = 2, name = "Kap 1", parent_chapter_id = 1,
                    pages = listOf(TreePageDto(id = 20, name = "A", chapter_id = 2)))),
            )
        ),
        topPages = listOf(TreePageDto(id = 10, name = "Vorwort")),
    )

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        api = FakeContentApi()
        val settings = SettingsStore(ctx).apply { setServerBaseUrl("http://test") }
        repo = ContentRepository(db, FakeNetworkClient(ctx, api), settings)
    }

    @After fun tearDown() = db.close()

    @Test fun `successful tree is cached and survives going offline`() = runTest {
        assertNull(repo.cachedTree(7))
        api.treeResponder = { tree }

        assertEquals(tree, repo.tree(7).getOrThrow())
        assertEquals(tree, repo.cachedTree(7))

        api.treeResponder = { throw java.io.IOException("offline") }
        assertTrue(repo.tree(7).isFailure)
        assertEquals(tree, repo.cachedTree(7))
        assertNull(repo.cachedTree(8))
    }

    @Test fun `unreadable cache row yields null`() = runTest {
        db.treeCacheDao().upsert(TreeCacheEntity(bookId = 7, json = "{kaputt", fetchedAt = 1))
        assertNull(repo.cachedTree(7))
    }

    @Test fun `clearing local data drops cached trees`() = runTest {
        api.treeResponder = { tree }
        repo.tree(7).getOrThrow()
        repo.clearLocalData()
        assertNull(repo.cachedTree(7))
    }
}
