package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.app.ui.screens.loadImmutableEstimatePdfSnapshot
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Complete production portable-backup round trip of immutable v1/v2/v3:
 * WAL -> verified encrypted snapshot -> password-protected .stpb -> validate
 * -> staged SQLCipher restore -> history/PDF read -> cold reopen.
 *
 * Runs in the instrumentation APK sandbox. The exact baseline encrypted DB
 * and Keystore-backed passphrase are restored in finally, including on test
 * assertion failures. It never intentionally overwrites device user data.
 */
@RunWith(AndroidJUnit4::class)
class EstimateRevisionPortableRestoreTest {
    private fun sourceJson() = JSONObject()
        .put("name", "Kitchen").put("material", "pvc_matte")
        .put("profileType", "standard_alu").put("complexity", "straight")
        .put("lengthMeters", 2.0).put("widthMeters", 3.0)
        .put("areaSquareMeters", 6.0).put("perimeterMeters", 10.0)
        .toString()

    private fun draft(canvasPrice: Double, profilePrice: Double, label: String): ImmutableEstimateDraft {
        val lines = listOf(
            ImmutableEstimateLine(
                "canvas.pvc_matte", label, "м²", 6.0, canvasPrice, 6.0 * canvasPrice
            ),
            ImmutableEstimateLine(
                "profile.standard_alu", "Frozen aluminium profile", "м.п.",
                10.0, profilePrice, 10.0 * profilePrice
            )
        )
        val snapshots = JSONArray()
        lines.forEach { line ->
            snapshots.put(JSONObject()
                .put("code", line.stableCode)
                .put("title", line.title)
                .put("unit", line.unit)
                .put("quantity", line.quantity)
                .put("unitPrice", line.unitPrice)
                .put("total", line.lineTotal))
        }
        return ImmutableEstimateDraft(
            sourceMeasurementJson = sourceJson(),
            detailsJson = JSONObject().put("measurementPrecisionMeters", 0.01)
                .put("lineItems", snapshots).toString(),
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-v1",
            totalCost = lines.sumOf { it.lineTotal },
            lines = lines
        )
    }

    @Test fun portableRestorePreservesEveryFrozenVersionAndVersionPdfSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = CeilingsDatabase.get(context)
        val initialKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val baseline = PortableBackupManager.createVerifiedSnapshot(context, original)
        val baselineImage = baseline.readBytes()
        val password = "Test revs v1-v2-v3 backup 2026".toCharArray()
        var validatedContents: PortableBackupPayload.Contents? = null
        try {
            assertEquals("wal", original.queryText("PRAGMA journal_mode")?.lowercase())
            val project = ProjectRepository(original.database).createReturningId(
                clientId = null, title = "Portable history project", createdAt = 10000L
            )
            val repo = ImmutableEstimateRepository(original.database)
            val first = repo.createSeries(
                projectId = project, year = 2026, createdAt = 11000L,
                firstRevision = draft(10.0, 5.0, "Frozen version one")
            )
            val second = repo.appendRevision(
                first.seriesUuid, first.initialRevisionUuid, 12000L,
                draft(20.0, 6.0, "Frozen version two")
            )
            val third = repo.appendRevision(
                first.seriesUuid, second, 13000L,
                draft(25.0, 7.0, "Frozen version three")
            )
            val exactRevisions = listOf(first.initialRevisionUuid, second, third)
            val originalNumber = repo.series(first.seriesUuid)!!.documentNumber
            val expectedTotals = listOf(110.0, 180.0, 220.0)
            val exported = PortableBackupManager.create(context, original, password)

            // A bad password must reject before changing the live database.
            val wrong = runCatching {
                PortableBackupManager.validate(context, exported, "wrong password".toCharArray())
            }
            assertTrue("Incorrect portable password unexpectedly accepted", wrong.isFailure)
            assertEquals(3, repo.revisions(first.seriesUuid).size)

            // A post-backup price edit and v4 must disappear upon restore:
            // this proves that the portable image reflects a committed point
            // in time rather than the current live file or price catalog.
            PriceRepository(original.database).upsertReturningId(
                code = "canvas.pvc_matte", title = "Current mutable price",
                category = "canvas", unit = "м²", price = 999.0, updatedAt = 14000L
            )
            repo.appendRevision(
                first.seriesUuid, third, 15000L,
                draft(99.0, 9.0, "Should not survive portable restore")
            )
            assertEquals(4, repo.revisions(first.seriesUuid).size)

            val decoded = PortableBackupManager.validate(context, exported, password)
            validatedContents = decoded
            val replaced = SqlCipherRestoreManager.restore(context, decoded)
            assertEquals("wal", replaced.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(replaced.checkIntegrity())

            fun verifyAll(db: CeilingsDatabase) {
                val history = ImmutableEstimateRepository(db.database)
                val actual = history.revisions(first.seriesUuid)
                assertEquals(listOf(1L, 2L, 3L), actual.map { it.versionNumber })
                assertEquals(exactRevisions, actual.map { it.revisionUuid })
                assertEquals(expectedTotals, actual.map { it.totalCost })
                assertEquals(originalNumber, history.series(first.seriesUuid)?.documentNumber)
                assertFalse(PriceRepository(db.database).all()
                    .any { it.title == "Current mutable price" })
                val printable = mutableSetOf<String>()
                exactRevisions.forEachIndexed { index, id ->
                    val pdf = loadImmutableEstimatePdfSnapshot(
                        db.database, first.seriesUuid, id
                    )
                    assertEquals((index + 1).toLong(), pdf.versionNumber)
                    assertEquals(expectedTotals[index], pdf.estimate.totalCost, 0.0)
                    assertEquals("Portable history project", pdf.projectTitle)
                    assertEquals(
                        listOf(10.0, 20.0, 25.0)[index],
                        JSONObject(pdf.estimate.detailsJson)
                            .getJSONArray("lineItems").getJSONObject(0)
                            .getDouble("unitPrice"),
                        0.0
                    )
                    assertFalse(pdf.printableNumber.contains(first.seriesUuid))
                    assertFalse(pdf.estimate.detailsJson.contains("999.0"))
                    printable += pdf.printableNumber
                }
                assertEquals(1, printable.size)
            }

            verifyAll(replaced)
            CeilingsDatabase.closeAndReset()
            val cold = CeilingsDatabase.get(context)
            assertEquals("wal", cold.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(cold.checkIntegrity())
            verifyAll(cold)
            val restoredKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals(initialKey, restoredKey)
            } finally {
                restoredKey.fill(0)
            }
        } finally {
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(
                    context, PortableBackupPayload.Contents(baselineImage, initialKey)
                )
            } finally {
                validatedContents?.database?.fill(0)
                validatedContents?.passphrase?.fill(0)
                baseline.delete()
                baselineImage.fill(0)
                initialKey.fill(0)
                password.fill('\\u0000')
            }
        }
    }
}
