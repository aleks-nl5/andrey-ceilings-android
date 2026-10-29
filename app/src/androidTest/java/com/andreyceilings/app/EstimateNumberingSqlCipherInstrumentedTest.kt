package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.EstimateNumberPolicy
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens a real encrypted Android SQLDelight database and verifies immutable history across reopen. */
@RunWith(AndroidJUnit4::class)
class EstimateNumberingSqlCipherInstrumentedTest {
    @Test
    fun documentNumberAndRevisionsPersistWithoutPlaintextOrRepricing() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_numbered_history.db"
        val key = ByteArray(32) { 83 }
        context.deleteDatabase(name)
        val firstUuid = "12345678-1234-4abc-8def-000000000001"
        val firstRevisionUuid = "12345678-1234-4abc-8def-000000000002"
        val secondRevisionUuid = "12345678-1234-4abc-8def-000000000003"
        val snapshot = ImmutableEstimateDraft(
            sourceMeasurementJson = """{"length":4,"width":3}""",
            detailsJson = """{"measurementPrecisionMeters":0.01}""",
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-1",
            totalCost = 500.0,
            lines = listOf(ImmutableEstimateLine("material.pvc", "Полотно", "м²", 10.0, 50.0, 500.0))
        )
        try {
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(null, "Квартира", createdAt = 100L)
                val history = ImmutableEstimateRepository(db)
                val root = history.createSeries(
                    projectId, 2026, 101L, snapshot,
                    newSeriesUuid = { firstUuid }, newRevisionUuid = { firstRevisionUuid }
                )
                history.configureNumberPolicy(EstimateNumberPolicy("{year}-{prefix}-{uuid}", "CEIL"))
                history.appendRevision(
                    root.seriesUuid, root.initialRevisionUuid, 102L,
                    snapshot.copy(totalCost = 700.0, lines = listOf(
                        ImmutableEstimateLine("material.pvc", "Нове полотно", "м²", 10.0, 70.0, 700.0)
                    )),
                    newRevisionUuid = { secondRevisionUuid }
                )
            }
            val dbFile = context.getDatabasePath(name)
            assertTrue(dbFile.exists())
            val fileHeader = File(dbFile.absolutePath).inputStream().use { input ->
                ByteArray(16).also { input.read(it) }
            }
            assertTrue(!fileHeader.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8)))
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val db = PrototypeDatabase(driver)
                val history = ImmutableEstimateRepository(db)
                val root = requireNotNull(history.series(firstUuid))
                assertEquals("AC-2026-00001-$firstUuid", root.documentNumber)
                assertEquals("{prefix}-{year}-{sequence}-{uuid}", root.policyTemplate)
                assertEquals(listOf(1L, 2L), history.revisions(firstUuid).map { it.versionNumber })
                assertEquals(500.0, history.revision(firstRevisionUuid)?.totalCost ?: -1.0, 0.0)
                assertEquals(700.0, history.revision(secondRevisionUuid)?.totalCost ?: -1.0, 0.0)
                assertEquals("Полотно", history.lines(firstRevisionUuid).single().titleSnapshot)
                assertNotEquals(
                    history.lines(firstRevisionUuid).single().titleSnapshot,
                    history.lines(secondRevisionUuid).single().titleSnapshot
                )
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
