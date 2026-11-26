package me.lvca.alba.engine.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.MediaDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A whole-catalog read spans several 2 MB cursor windows. Read outside a transaction, each window is filled by its own
 * query, so a write that commits between two fills shifts the rows under the cursor: rows repeat or go missing, and a
 * window past the new end fails with "Couldn't read row N, col 0 from CursorWindow", which killed the app at start-up
 * while the first refresh wrote the catalog. Read in one transaction, the read sees one snapshot from start to end.
 */
@RunWith(AndroidJUnit4::class)
class CatalogReadConsistencyTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(NAME)
        // A file database, as on the phone: write-ahead logging gives readers and the writer their own connections.
        database = Room.databaseBuilder(context, AlbaDatabase::class.java, NAME).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun theWholeCatalogReadsAsOneSnapshotWhileTheCatalogIsWritten() = runBlocking {
        val dao = database.catalogDao()
        // About 1.5 KB a row: several windows' worth, as a real library of thousands of photos is.
        val rows = (0 until ROWS).map(::row)
        dao.upsertItems(rows)
        val newest = rows.sortedByDescending { it.capturedAtEpochMillis }.take(CHURN)

        val writing = AtomicBoolean(true)
        val writes = AtomicInteger()
        val writer = async(Dispatchers.IO) {
            // The newest rows sort first, so removing and restoring them shifts every later row by CHURN places.
            while (writing.get() && isActive) {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM catalog_items WHERE capturedAtEpochMillis >= ?", arrayOf(newest.last().capturedAtEpochMillis),
                )
                dao.upsertItems(newest)
                writes.incrementAndGet()
            }
        }
        try {
            repeat(READS) {
                val read = withContext(Dispatchers.IO) { dao.observeAll().first() }
                val ids = read.map { it.mediaId }
                assertEquals("A read repeated rows", ids.size, ids.toSet().size)
                assertTrue("A read saw ${ids.size} rows", ids.size == ROWS || ids.size == ROWS - CHURN)
                val cache = withContext(Dispatchers.IO) { dao.observeCache().first() }
                assertEquals("A cache read repeated rows", cache.size, cache.map { it.mediaId }.toSet().size)
            }
        } finally {
            writing.set(false)
            writer.await()
        }
        assertTrue("The writer committed while the reads ran", writes.get() > 0)
    }

    private fun row(index: Int) = CatalogItemEntity(
        mediaId = "icloud:account:asset-%05d".format(index),
        fileName = "IMG_%05d.HEIC".format(index),
        capturedAtEpochMillis = 1_700_000_000_000L + index * 1_000L,
        width = 4_032, height = 3_024, origin = "ICLOUD", sourceRevision = "master:asset-$index",
        localUri = null, previewCachePath = "/data/cache/previews/asset-$index-preview.jpg", originalCachePath = null,
        accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan",
        masterRecordName = "master-$index", assetRecordName = "asset-$index", resourceFingerprint = "fingerprint-$index",
        details = MediaDetails(caption = "Caption $index ".padEnd(CAPTION_LENGTH, 'x'), latitude = 45.0, longitude = 9.0),
    )

    private companion object {
        const val NAME = "catalog-read-consistency.db"
        const val ROWS = 6_000
        const val CHURN = 500
        const val READS = 12
        const val CAPTION_LENGTH = 1_200
    }
}
