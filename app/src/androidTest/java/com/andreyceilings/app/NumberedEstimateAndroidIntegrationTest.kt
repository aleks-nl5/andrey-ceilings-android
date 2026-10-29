package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.PortableBackupEnvelope
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NumberedEstimateAndroidIntegrationTest {
    @Test
    fun newAndDuplicatedQuotesAreNumberedFrozenAndSoftDeletedTogether() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbName = "sqldelight_test_numbered_android_flow.db"
        val key = ByteArray(32) { 94 }
        context.deleteDatabase(dbName)
        val savedDetails = JSONObject()
            .put("measurementPrecisionMeters", 0.01)
            .put("lineItems", JSONArray()
                .put(JSONObject().put("code", "canvas.pvc_matte").put("title", "Матеріал")
                    .put("quantity", 1.0).put("unit", "м²")
                    .put("unitPrice", 51.0).put("total", 51.0))
                .put(JSONObject().put("code", "profile.standard_alu").put("title", "Профіль")
                    .put("quantity", 1.0).put("unit", "м.п.")
                    .put("unitPrice", 50.0).put("total", 50.0))
                .put(JSONObject().put("code", "system.rounding").put("title", "Коригування округлення")
                    .put("quantity", 1.0).put("unit", "посл.")
                    .put("unitPrice", -1.0).put("total", -1.0))
            ).toString()
        val timestamp = 1793300000000L
        val firstDraft = EstimateEntity(projectId = 1L, name = "Кухня", material = "pvc_matte",
            profileType = "standard_alu", complexity = "simple",
            lengthMeters = 1.0, widthMeters = 1.0, areaSquareMeters = 1.0,
            perimeterMeters = 4.0, canvasCost = 51.0, profileCost = 50.0,
            complexityCost = 0.0, optionsCost = 0.0, totalCost = 100.0,
            detailsJson = savedDetails, createdAt = timestamp
        )
        var firstNumber = ""
        var firstSeries = ""
        var firstId = -1L
        try {
            CeilingsDatabase.openIsolatedForTesting(context, dbName, key).use { db ->
                val projectId = db.projects().insert(ProjectEntity(title = "Квартира", clientId = null))
                val original = firstDraft.copy(projectId = projectId)
                firstId = db.estimates().insert(original)
                val first = db.estimates().observeAll().first().single()
                firstNumber = requireNotNull(first.documentNumber)
                assertTrue(firstNumber.startsWith("AC-"))
                assertEquals("AC-2026-00001", first.printableNumber)
                assertTrue(first.printableNumber!!.length < firstNumber.length)
                val history = ImmutableEstimateRepository(db.database)
                firstSeries = requireNotNull(db.database.businessSchemaQueries
                    .selectEstimateById(firstId).executeAsOne().seriesUuid)
                val firstRevision = history.revisions(firstSeries).single()
                assertEquals(1L, firstRevision.versionNumber)
                assertEquals(100.0, firstRevision.totalCost, 0.0)
                assertEquals(-1.0, history.lines(firstRevision.revisionUuid).last().lineTotalSnapshot, 0.0)

                val duplicateId = db.estimates().insert(original.copy(
                    id = 0L, name = "Кухня (копія)", createdAt = timestamp + 500L
                ))
                assertNotEquals(firstId, duplicateId)
                val copy = db.estimates().observeAll().first().first { it.id == duplicateId }
                assertNotEquals(firstNumber, copy.documentNumber)
                assertNotEquals(first.printableNumber, copy.printableNumber)
                assertEquals(savedDetails, copy.detailsJson)
                assertEquals(100.0, copy.totalCost, 0.0)

                db.estimates().update(first.copy(name = "Нова назва"))
                val afterRename = db.estimates().observeAll().first().first { it.id == firstId }
                assertEquals(firstNumber, afterRename.documentNumber)
                assertEquals("Нова назва", afterRename.name)
                assertEquals("Кухня", afterRename.originalDocumentName) // PDF must keep v1 title
                assertEquals(savedDetails, afterRename.detailsJson)
                var blocked = false
                try { db.estimates().update(afterRename.copy(totalCost = 500.0)) }
                catch (_: IllegalStateException) { blocked = true }
                assertTrue("Changing frozen totals must fail", blocked)

                db.estimates().deleteById(firstId)
                assertNull(db.database.businessSchemaQueries.selectEstimateById(firstId).executeAsOneOrNull())
                assertNotNull(history.seriesByNumber(firstNumber))
                assertNotNull(history.series(firstSeries)?.deletedAt)
                assertEquals(1, history.revisions(firstSeries).size)
            }
            // Reopen the encrypted SQLDelight file: archived values and numbered
            // duplicate still exist, never reset due to the new schema version.
            CeilingsDatabase.openIsolatedForTesting(context, dbName, key).use { db ->
                val history = ImmutableEstimateRepository(db.database)
                assertNotNull(history.seriesByNumber(firstNumber))
                assertEquals(1, history.revisions(firstSeries).size)
                val active = db.estimates().observeAll().first()
                assertEquals(1, active.size)
                assertNotEquals(firstNumber, active.single().documentNumber)
                assertEquals(100.0, active.single().totalCost, 0.0)
            }

            // Portable .stpb payload must preserve all immutable history and the
            // original encrypted DB passphrase, not just the five legacy tables.
            val recoveryPassword = "test-password-do-not-use".toCharArray()
            val payload = PortableBackupPayload.encode(context.getDatabasePath(dbName).readBytes(), key)
            try {
                val archive = PortableBackupEnvelope.seal(payload, recoveryPassword)
                val recovered = PortableBackupManager.validate(context, archive, recoveryPassword)
                val restoredName = "sqldelight_test_numbered_restore.db"
                try {
                    context.deleteDatabase(restoredName)
                    val target = context.getDatabasePath(restoredName)
                    target.parentFile?.mkdirs()
                    target.writeBytes(recovered.database)
                    createEncryptedPrototypeDriver(context, recovered.passphrase, restoredName).use { driver ->
                        val history = ImmutableEstimateRepository(PrototypeDatabase(driver))
                        assertNotNull(history.seriesByNumber(firstNumber))
                        assertEquals(1, history.revisions(firstSeries).size)
                        assertEquals(1L, history.revisions(firstSeries).first().versionNumber)
                    }
                } finally {
                    context.deleteDatabase(restoredName)
                    recovered.database.fill(0)
                    recovered.passphrase.fill(0)
                }
            } finally {
                payload.fill(0)
                recoveryPassword.fill('\u0000')
            }
        } finally {
            context.deleteDatabase(dbName)
            key.fill(0)
        }
    }
}
