package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SQLCipher's integrity_check alone cannot detect wrong totals or a
 * cross-document parent: both remain structurally valid SQLite pages.
 *
 * These tests corrupt only an isolated WAL database, never the app singleton.
 * The same production snapshot verifier guards automatic/manual backups
 * and imported encrypted .stpb contents before a destructive DB swap.
 */
@RunWith(AndroidJUnit4::class)
class EstimateRevisionSnapshotAuditTest {
    private fun original(): ImmutableEstimateDraft {
        val lines = listOf(
            ImmutableEstimateLine(
                "canvas.pvc_matte", "Frozen canvas", "м²", 6.0, 10.0, 60.0
            ),
            ImmutableEstimateLine(
                "profile.standard_alu", "Frozen profile", "м.п.", 10.0, 5.0, 50.0
            )
        )
        return ImmutableEstimateDraft(
            sourceMeasurementJson = """{"shape":"rectangle","area":6.0}""",
            detailsJson = """{"measurementPrecisionMeters":0.01}""",
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-v1",
            totalCost = 110.0,
            lines = lines
        )
    }

    @Test fun encryptedBackupRejectsInconsistentTotalsAndCrossSeriesParents() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val filename = "sqldelight_test_immutable_snapshot_audit.db"
        val key = ByteArray(32) { 0x58.toByte() }
        context.deleteDatabase(filename)
        val file = context.getDatabasePath(filename)
        try {
            lateinit var v2: String
            lateinit var parentA: String
            lateinit var parentB: String
            createEncryptedPrototypeDriver(
                context, key, filename, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Snapshot audit", createdAt = 100L
                )
                val history = ImmutableEstimateRepository(db)
                val a = history.createSeries(
                    projectId = projectId, year = 2026, createdAt = 200L,
                    firstRevision = original()
                )
                val b = history.createSeries(
                    projectId = projectId, year = 2026, createdAt = 201L,
                    firstRevision = original()
                )
                parentA = a.initialRevisionUuid
                parentB = b.initialRevisionUuid
                v2 = history.appendRevision(
                    a.seriesUuid, parentA, 300L, original()
                )
            }
            // Baseline: the exact same verifier used by encrypted imports.
            PortableBackupManager.verifyEncryptedSnapshot(context, file, key)

            // Page-level SQLite integrity still passes after this update.
            // The version PDF would show a total different from its rows.
            createEncryptedPrototypeDriver(
                context, key, filename, enableWalForTesting = true
            ).use { driver ->
                driver.execute(null,
                    "UPDATE estimate_revision_lines SET lineTotalSnapshot = " +
                        "lineTotalSnapshot + 7.0 WHERE revisionUuid = '$v2' " +
                        "AND sortIndex = 0", 0
                )
            }
            assertTrue("Backup accepted inconsistent immutable version totals",
                runCatching {
                    PortableBackupManager.verifyEncryptedSnapshot(context, file, key)
                }.isFailure)

            // Restore exact total but corrupt the parent relationship: the
            // parent UUID exists, so SQLite foreign_key_check stays clean,
            // but the parent belongs to ANOTHER estimate series.
            createEncryptedPrototypeDriver(
                context, key, filename, enableWalForTesting = true
            ).use { driver ->
                driver.execute(null,
                    "UPDATE estimate_revision_lines SET lineTotalSnapshot = " +
                        "lineTotalSnapshot - 7.0 WHERE revisionUuid = '$v2' " +
                        "AND sortIndex = 0", 0
                )
                driver.execute(null,
                    "UPDATE estimate_revisions SET parentRevisionUuid = " +
                        "'$parentB' WHERE revisionUuid = '$v2'", 0
                )
            }
            assertTrue("Backup accepted an estimate revision from another series",
                runCatching {
                    PortableBackupManager.verifyEncryptedSnapshot(context, file, key)
                }.isFailure)

            // Correcting the bad link makes the image valid again.
            createEncryptedPrototypeDriver(
                context, key, filename, enableWalForTesting = true
            ).use { driver ->
                driver.execute(null,
                    "UPDATE estimate_revisions SET parentRevisionUuid = " +
                        "'$parentA' WHERE revisionUuid = '$v2'", 0
                )
            }
            PortableBackupManager.verifyEncryptedSnapshot(context, file, key)
        } finally {
            context.deleteDatabase(filename)
            key.fill(0)
        }
    }
}
