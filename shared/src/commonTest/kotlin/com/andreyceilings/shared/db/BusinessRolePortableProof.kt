package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.MeasurementElement
import kotlin.test.*

/** Encrypted physical DB snapshot portability, independent of a future native host backup UI. */
object BusinessRolePortableProof {
    data class Evidence(val projectId: Long, val measurementUuid: String,
        val elements: List<MeasurementElement>, val preview: LiveEstimatePreview)

    suspend fun snapshot(driver: SqlDriver, destination: String): Evidence {
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val reader = LivePricingReader(db)
        val source = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        assertTrue(source.buildPreview().canFinalizeFull)
        val evidence = Evidence(fixture.projectId, fixture.measurementUuid, source.source.elements, source.buildPreview())
        reader.closeReads()
        // SQLite's snapshot operation includes committed WAL pages, never a raw live-file copy.
        driver.execute(null, "VACUUM INTO '${destination.replace("'", "''")}'", 0)
        return evidence
    }

    suspend fun verify(restored: SqlDriver, expected: Evidence) {
        PriceCatalogSchema.verifyStructure(restored)
        val reader = LivePricingReader(PrototypeDatabase(restored))
        val source = assertIs<PricingReadResult.Loaded>(reader.read(expected.projectId, expected.measurementUuid))
        assertEquals(expected.elements, source.source.elements)
        assertEquals(expected.preview, source.buildPreview())
        reader.closeReads()
    }
}
