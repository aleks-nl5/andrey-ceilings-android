package com.andreyceilings.app

import android.content.Context
import com.andreyceilings.app.calendar.CalendarDeviceState
import kotlinx.coroutines.runBlocking
import com.andreyceilings.shared.calendar.*
import com.andreyceilings.shared.db.removeEmptyCalendarSchema
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.db.PriceNameUniquenessProof
import com.andreyceilings.shared.db.verifyCalendarContents
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.EstimateV22MigrationProof
import com.andreyceilings.shared.db.FrozenIndividualEstimateProof
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceCatalogSchema
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.removeEmptyConsolidatedSchema
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupEnvelope
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.app.ui.screens.loadImmutableEstimatePdfSnapshot
import com.andreyceilings.app.ui.screens.writeEstimatePdf
import com.andreyceilings.shared.db.PriceGroupRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.localization.AndroidAppLanguageStorage
import com.andreyceilings.shared.localization.AppLanguage
import com.andreyceilings.shared.localization.AppLanguagePreference
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.prices.StarterPriceCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    @Test fun preV22PortableRestorePurgesOnlyEstimatesAndPreservesSourceBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceName = "legacy-estimate-v21-source.db"
        val source = context.getDatabasePath(sourceName)
        val key = ByteArray(32) { 21 }
        val password = "Test pre-v22 estimate backup".toCharArray()
        context.deleteDatabase(sourceName)
        try {
            System.loadLibrary("sqlcipher")
            val fixture = AndroidSqliteDriver(EstimateV22MigrationProof.v21Schema, context, sourceName,
                factory = SupportOpenHelperFactory(key.copyOf())).use { driver ->
                EstimateV22MigrationProof.seed(driver)
            }
            val sourceBytes = source.readBytes()
            val payload = PortableBackupPayload.encode(sourceBytes, key)
            val envelope = try { PortableBackupEnvelope.seal(payload, password) } finally { payload.fill(0) }
            val baselineKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            val baseline = PortableBackupManager.createVerifiedSnapshot(context, CeilingsDatabase.get(context))
            val baselineBytes = baseline.readBytes()
            try {
                val validated = PortableBackupManager.validate(context, envelope, password)
                try {
                    assertTrue(validated.clearsPreV22Estimates)
                    assertArrayEquals(sourceBytes, validated.database)
                    val restored = SqlCipherRestoreManager.restore(context, validated)
                    assertTrue(restored.checkIntegrity())
                    assertTrue(EstimateV22MigrationProof.counts(restored.driver).values.all { it == 0L })
                    assertEquals("in_progress", ProjectRepository(restored.database).find(fixture.projectId)?.status)
                    assertEquals("paid", ProjectRepository(restored.database).find(fixture.projectId)?.paymentStatus)
                    assertEquals(fixture.measurementUuid, MeasurementRepository(restored.database).findByUuid(fixture.measurementUuid)?.uuid)
                    assertArrayEquals(sourceBytes, source.readBytes())
                } finally {
                    validated.database.fill(0)
                    validated.passphrase.fill(0)
                }
            } finally {
                try { SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(baselineBytes, baselineKey)) }
                finally { baseline.delete(); baselineBytes.fill(0); baselineKey.fill(0); sourceBytes.fill(0) }
            }
        } finally {
            password.fill(Char.MIN_VALUE)
            key.fill(0)
            context.deleteDatabase(sourceName)
            }
    }

    @Test fun v24EncryptedBackupPassesCalendarPreflightAndPreservesSourceBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "calendar-v24-restore-source.db"
        val migratedName = "calendar-v24-migrated-copy.db"
        val source = context.getDatabasePath(name)
        val passphrase = ByteArray(32) { 24 }
        context.deleteDatabase(name)
        context.deleteDatabase(migratedName)
        try {
            System.loadLibrary("sqlcipher")
            val (projectId, revisionUuid, expectedDetails) = AndroidSqliteDriver(PriceCatalogSchema, context, name,
                factory = SupportOpenHelperFactory(passphrase.copyOf())).use { driver ->
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val quotes = ImmutableEstimateRepository(db)
                val saved = quotes.saveIndividual(
                    FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                val details = checkNotNull(quotes.revision(saved.initialRevisionUuid)).detailsJson
                removeEmptyCalendarSchema(driver)
                driver.execute(null, "PRAGMA user_version = 24", 0)
                PriceCatalogSchema.verifyStructure(driver, 24)
                Triple(fixture.projectId, saved.initialRevisionUuid, details)
            }
            val before = source.readBytes()
            try {
                PortableBackupManager.verifyEncryptedSnapshot(context, source, passphrase)
                assertArrayEquals(before, source.readBytes())

                // Open a separate encrypted copy through the production Android driver.
                // Keep the original v24 backup immutable, including on migration failure.
                source.copyTo(context.getDatabasePath(migratedName))
                val day = LocalDate(2026, 12, 1)
                val draft = CalendarWorkDraft(day, WorkSchedule(WorkTimeMode.DATE_ONLY, TimeZone.UTC,
                    listOf(WorkDay(0))), "Migrated calendar work", "MEASURE")
                val workKey = createEncryptedPrototypeDriver(context, passphrase, migratedName,
                    enableWalForTesting = true, initializePriceCatalog = true, requireExistingSchema = true).use { driver ->
                    assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                    PriceCatalogSchema.verifyStructure(driver)
                    verifyCalendarContents(driver)
                    val db = PrototypeDatabase(driver)
                    val project = checkNotNull(ProjectRepository(db).find(projectId))
                    assertEquals(expectedDetails, ImmutableEstimateRepository(db).revision(revisionUuid)?.detailsJson)
                    val calendar = CalendarRepository(db)
                    assertEquals(4, calendar.types().size)
                    assertTrue(calendar.period(WorkDateRange(day, day), TimeZone.UTC).isEmpty())
                    val result = calendar.save(CalendarSave(draft.copy(projectUuid = project.uuid)))
                    assertTrue(result is CalendarSaveResult.Saved)
                    (result as CalendarSaveResult.Saved).key
                }
                createEncryptedPrototypeDriver(context, passphrase, migratedName,
                    enableWalForTesting = true, initializePriceCatalog = true, requireExistingSchema = true).use { driver ->
                    val db = PrototypeDatabase(driver)
                    val project = checkNotNull(ProjectRepository(db).find(projectId))
                    assertEquals(expectedDetails, ImmutableEstimateRepository(db).revision(revisionUuid)?.detailsJson)
                    assertEquals(draft.copy(projectUuid = project.uuid), CalendarRepository(db).exact(workKey)?.draft)
                }
                assertArrayEquals(before, source.readBytes())
            } finally { before.fill(0) }
        } finally {
            passphrase.fill(0)
            context.deleteDatabase(migratedName)
            context.deleteDatabase(name)
        }
    }

    @Test fun v23EncryptedBackupPassesPreflightWithoutChangingSourceBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "consolidated-v23-restore-source.db"
        val source = context.getDatabasePath(name)
        val key = ByteArray(32) { 24 }
        context.deleteDatabase(name)
        try {
            System.loadLibrary("sqlcipher")
            AndroidSqliteDriver(PriceCatalogSchema, context, name,
                factory = SupportOpenHelperFactory(key.copyOf())).use { driver ->
                val database = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(database)
                ImmutableEstimateRepository(database).saveIndividual(
                    FrozenIndividualEstimateProof.command(database, fixture), 2026, 600)
                removeEmptyConsolidatedSchema(driver)
                driver.execute(null, "PRAGMA user_version = 23", 0)
                PriceCatalogSchema.verifyStructure(driver, 23)
            }
            val original = source.readBytes()
            try {
                PortableBackupManager.verifyEncryptedSnapshot(context, source, key)
                assertArrayEquals(original, source.readBytes())
            } finally {
                original.fill(0)
            }
        } finally {
            key.fill(0)
            context.deleteDatabase(name)
        }
    }

    @Test fun portableRestorePreservesEveryFrozenVersionAndVersionPdfSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = CeilingsDatabase.get(context)
        val initialKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val baseline = PortableBackupManager.createVerifiedSnapshot(context, original)
        val baselineImage = baseline.readBytes()
        val password = "Test revs v1-v2-v3 backup 2026".toCharArray()
        var validatedContents: PortableBackupPayload.Contents? = null
        val renderedPdfFiles = mutableListOf<File>()
        val preferences = context.getSharedPreferences("revision_restore_language_test", Context.MODE_PRIVATE)
        val language = AppLanguageState(AndroidAppLanguageStorage(preferences))
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
            val expectedLabels = listOf("Frozen version one", "Frozen version two", "Frozen version three")
            StarterPriceCatalog.initialize(original.database)
            val customGroup = PriceGroupRepository(original.database).create("Éclairage • Моя група", 13100L, "шт.")
            val prices = PriceRepository(original.database)
            prices.createInGroup("localization.backup.custom", "Éclairage • Моя позиція", customGroup, 123.45, 13200L)
            val catalogBefore = prices.all()
            val groupsBefore = PriceGroupRepository(original.database).all()
            val revisionsBefore = repo.revisions(first.seriesUuid)

            language.select(AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN))
            assertEquals(catalogBefore, prices.all())
            assertEquals(groupsBefore, PriceGroupRepository(original.database).all())
            assertEquals(revisionsBefore, repo.revisions(first.seriesUuid))
            val work = CalendarRepository(original.database)
            val zone = TimeZone.of("Europe/Kyiv")
            work.saveSettings(CalendarSettings(zone, null, emptySet()))
            val day = LocalDate(2026, 12, 15)
            val customType = CalendarWorkType("portable-test-type", "Portable custom", 0x123456, "other", 45, false)
            work.saveType(customType)
            val workKey = (work.save(CalendarSave(CalendarWorkDraft(day,
                WorkSchedule(WorkTimeMode.DATE_ONLY, zone, listOf(WorkDay(0))), "Portable calendar work", customType.code,
                recurrence = WorkRecurrence(day, WorkFrequency.DAILY, count = 3))))
                as CalendarSaveResult.Saved).key
            val calendarStore = original.objectsClientsStore.calendar
            val integrationSettings = CalendarIntegrationSettings(syncEnabled = true, exportFields = setOf(CalendarExportField.ADDRESS))
            val typeRules = CalendarTypeIntegration(setOf(ReminderRule(ReminderDay.DATE, 0, 9 * 60)), syncEnabled = true)
            runBlocking {
                calendarStore.saveIntegrationSettings(integrationSettings)
                calendarStore.saveTypeIntegration(customType.code, typeRules)
            }
            val changedKey = WorkOccurrenceKey(workKey.rootUuid, LocalDate(2026, 12, 16))
            val changed = checkNotNull(work.exact(changedKey))
            val exceptionRules = CalendarWorkIntegration(ReminderMode.OFF, syncMode = CalendarSyncMode.OFF)
            assertTrue(work.save(CalendarSave(changed.draft.copy(title = "Portable exception"), changedKey, changed.version,
                integration = exceptionRules)) is CalendarSaveResult.Saved)
            work.saveType(customType.copy(archived = true))
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
            val event = checkNotNull(work.exact(workKey))
            assertTrue(work.delete(workKey, event.version, CalendarEditScope.OCCURRENCE))
            work.saveSettings(CalendarSettings(TimeZone.UTC, null, emptySet()))
            runBlocking { calendarStore.saveIntegrationSettings(CalendarIntegrationSettings()) }
            val device = CalendarDeviceState(context)
            val oldGeneration = device.generation
            device.save("snoozes", setOf("disposable-test-marker"))

            val decoded = PortableBackupManager.validate(context, exported, password)
            validatedContents = decoded
            assertFalse(decoded.clearsPreV22Estimates)
            language.select(AppLanguagePreference.System)
            val replaced = SqlCipherRestoreManager.restore(context, decoded)
            assertTrue(device.paused)
            assertTrue(device.snoozes.isEmpty())
            assertFalse(oldGeneration == device.generation)
            assertEquals(AppLanguagePreference.System, AppLanguageState(AndroidAppLanguageStorage(preferences)).preference)
            assertEquals("wal", replaced.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(replaced.checkIntegrity())

            fun verifyAll(db: CeilingsDatabase) {
                val restoredCalendar = CalendarRepository(db.database)
                val integration = runBlocking { db.objectsClientsStore.calendar.integrationSnapshot() }
                assertEquals(integrationSettings, integration.settings)
                assertEquals(typeRules, integration.types[customType.code])
                assertEquals(exceptionRules, integration.overrides(changedKey))
                assertEquals("Portable calendar work", restoredCalendar.exact(workKey)?.draft?.title)
                assertEquals(zone, restoredCalendar.settings().zone)
                assertEquals(emptySet<kotlinx.datetime.DayOfWeek>(), restoredCalendar.settings().workingDays)
                assertEquals("Portable exception", restoredCalendar.exact(changedKey)?.draft?.title)
                assertEquals("Portable calendar work", restoredCalendar.exact(WorkOccurrenceKey(workKey.rootUuid, LocalDate(2026, 12, 17)))?.draft?.title)
                assertEquals(customType.copy(archived = true), restoredCalendar.types().single { it.code == customType.code })
                val history = ImmutableEstimateRepository(db.database)
                val actual = history.revisions(first.seriesUuid)
                assertEquals(revisionsBefore, actual)
                assertEquals(catalogBefore, PriceRepository(db.database).all())
                assertEquals(groupsBefore, PriceGroupRepository(db.database).all())
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
                    assertEquals(
                        expectedLabels[index],
                        JSONObject(pdf.estimate.detailsJson).getJSONArray("lineItems")
                            .getJSONObject(0).getString("title")
                    )
                    assertFalse(pdf.printableNumber.contains(first.seriesUuid))
                    assertFalse(pdf.estimate.detailsJson.contains("999.0"))
                    printable += pdf.printableNumber

                    // Read-only history is not sufficient: the actual PDF
                    // must still render after the encrypted .stpb restore
                    // and again after a WAL cold reopen. Use only the frozen
                    // revision, never today's (post-backup) price list.
                    val rendered = File.createTempFile(
                        "restored_revision_v${index + 1}_", ".pdf", context.cacheDir
                    )
                    renderedPdfFiles += rendered
                    writeEstimatePdf(
                        context, Uri.fromFile(rendered), pdf.estimate,
                        pdf.projectTitle, pdf.versionNumber
                    )
                    assertTrue("Restored PDF v${index + 1} is empty",
                        rendered.length() > 100L)
                    ParcelFileDescriptor.open(
                        rendered, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            assertTrue(
                                "Restored PDF v${index + 1} has no readable pages",
                                renderer.pageCount > 0
                            )
                        }
                    }
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
                renderedPdfFiles.forEach { it.delete() }
                preferences.edit().clear().commit()
                baseline.delete()
                baselineImage.fill(0)
                initialKey.fill(0)
                password.fill(0.toChar())
            }
        }
    }
}
