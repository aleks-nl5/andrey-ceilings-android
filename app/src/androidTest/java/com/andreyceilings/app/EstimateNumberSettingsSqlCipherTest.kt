package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.security.PortableBackupEnvelope
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.EstimateNumberPolicy
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EstimateNumberSettingsSqlCipherTest {
    private fun estimate(projectId: Long, createdAt: Long) = EstimateEntity(
        projectId = projectId,
        name = "Кімната",
        material = "pvc_matte",
        profileType = "standard_alu",
        complexity = "simple",
        lengthMeters = 2.0,
        widthMeters = 3.0,
        areaSquareMeters = 6.0,
        perimeterMeters = 10.0,
        canvasCost = 100.0,
        profileCost = 40.0,
        complexityCost = 0.0,
        optionsCost = 0.0,
        totalCost = 140.0,
        detailsJson = """{"measurementPrecisionMeters":0.01,"lineItems":[{"code":"canvas.pvc_matte","title":"Полотно","unit":"м²","quantity":1.0,"unitPrice":100.0,"total":100.0},{"code":"profile.standard_alu","title":"Профіль","unit":"м.п.","quantity":1.0,"unitPrice":40.0,"total":40.0}]}""",
        createdAt = createdAt
    )

    @Test
    fun newFormatPersistsThroughReopenAndPortableRestoreAndOldNumbersNeverChange() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbName = "sqldelight_test_number_format_settings.db"
        val restoredName = "sqldelight_test_number_format_restored.db"
        val key = ByteArray(32) { 75 }
        val password = "test-password-only".toCharArray()
        val custom = EstimateNumberPolicy(
            template = "{prefix}_{year}_{sequence}_{uuid}",
            prefix = "STEL",
            sequenceWidth = 3
        )
        context.deleteDatabase(dbName)
        context.deleteDatabase(restoredName)
        var originalNumber = ""
        var changedNumber = ""
        try {
            CeilingsDatabase.openIsolatedForTesting(context, dbName, key).use { db ->
                val projectId = db.projects().insert(ProjectEntity(title = "Квартира", clientId = null))
                val now = 1793300000000L // October 2026, fixed to keep assertions stable.
                val firstId = db.estimates().insert(estimate(projectId, now))
                originalNumber = requireNotNull(
                    db.estimates().observeAll().first().first { it.id == firstId }.documentNumber
                )
                assertTrue(originalNumber.startsWith("AC-2026-00001-"))
                assertEquals("AC-2026-00001",
                    db.estimates().observeAll().first().first { it.id == firstId }.printableNumber)
                assertEquals(EstimateNumberPolicy(), db.estimateNumberSettings().load())

                assertEquals(custom, db.estimateNumberSettings().save(custom))
                assertEquals(custom, db.estimateNumberSettings().load())

                val invalid = custom.copy(template = "{year}") // missing sequence and UUID
                val error = runCatching { db.estimateNumberSettings().save(invalid) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertEquals(custom, db.estimateNumberSettings().load())

                val secondId = db.estimates().insert(estimate(projectId, now + 1000L))
                val estimates = db.estimates().observeAll().first()
                changedNumber = requireNotNull(estimates.first { it.id == secondId }.documentNumber)
                assertTrue(changedNumber.startsWith("STEL_2026_002_"))
                assertEquals("STEL_2026_002",
                    estimates.first { it.id == secondId }.printableNumber)
                assertNotEquals(originalNumber, changedNumber)
                assertEquals(originalNumber,
                    estimates.first { it.id == firstId }.documentNumber)
            }

            CeilingsDatabase.openIsolatedForTesting(context, dbName, key).use { db ->
                assertEquals(custom, db.estimateNumberSettings().load())
                val active = db.estimates().observeAll().first()
                assertEquals(2, active.size)
                assertTrue(active.any { it.documentNumber == originalNumber })
                assertTrue(active.any { it.documentNumber == changedNumber })
                assertTrue(active.all { it.printableNumber?.contains("12345678-") != true })
                assertTrue(active.all { (it.printableNumber?.length ?: 0) < (it.documentNumber?.length ?: 0) })
                val history = ImmutableEstimateRepository(db.database)
                assertEquals("AC", history.seriesByNumber(originalNumber)?.policyPrefix)
                assertEquals("STEL", history.seriesByNumber(changedNumber)?.policyPrefix)
            }

            val payload = PortableBackupPayload.encode(context.getDatabasePath(dbName).readBytes(), key)
            try {
                val envelope = PortableBackupEnvelope.seal(payload, password)
                val restored = PortableBackupManager.validate(context, envelope, password)
                try {
                    val restoredFile = context.getDatabasePath(restoredName)
                    restoredFile.parentFile?.mkdirs()
                    restoredFile.writeBytes(restored.database)
                    createEncryptedPrototypeDriver(context, restored.passphrase, restoredName).use { driver ->
                        val history = ImmutableEstimateRepository(PrototypeDatabase(driver))
                        assertEquals(custom, history.numberPolicy())
                        assertEquals(originalNumber, history.seriesByNumber(originalNumber)?.documentNumber)
                        assertEquals(changedNumber, history.seriesByNumber(changedNumber)?.documentNumber)
                    }
                } finally {
                    restored.database.fill(0)
                    restored.passphrase.fill(0)
                }
            } finally {
                payload.fill(0)
            }
        } finally {
            context.deleteDatabase(dbName)
            context.deleteDatabase(restoredName)
            key.fill(0)
            password.fill('\u0000')
        }
    }
}
