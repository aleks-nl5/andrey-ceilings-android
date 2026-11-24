package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import org.junit.Test
import kotlin.test.*

class CurrentEstimatesRepositoryTest {

    @Test fun legacyRowsNeverBecomeCurrentFrozenDocuments() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "current-v1-list.db"
        val key = ByteArray(32) { 48 }
        context.deleteDatabase(name)
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val presenter = TransientQuotePresenter(fixture.measurementUuid)
        presenter.acceptRead(presenter.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        val saved = history.saveIndividual(assertNotNull(presenter.saveRequest(fixture.projectId, "Current proposal")), 2026, 1000)
        try {
            val onlyV1 = history.frozenIndividuals().also { rows ->
                assertEquals(saved.seriesUuid, rows.single().seriesUuid)
            }
            val frozen = assertNotNull(history.frozenIndividual(saved.seriesUuid)).payload
            val legacy = EstimateRepository(db)
            fun insertLegacy(title: String) = legacy.createReturningId(fixture.projectId, title, "PVC", "standard", "simple",
                4.0, 3.0, 12.0, 14.0, 9999.0, 0.0, 0.0, 0.0, 9999.0, createdAt = 1001)
            val linked = insertLegacy("Legacy linked test")
            db.businessSchemaQueries.linkLegacyEstimateToSeries(saved.seriesUuid, linked)
            insertLegacy("Legacy independent test")
            assertEquals(onlyV1, history.frozenIndividuals())
            assertEquals(frozen, history.frozenIndividual(saved.seriesUuid)?.payload)
            history.softDeleteSeries(saved.seriesUuid, 1200)
            assertTrue(history.frozenIndividuals().isEmpty())
            assertEquals(2, legacy.count())
            assertEquals(1, history.revisions(saved.seriesUuid).size)
        } finally {
            driver.close(); key.fill(0); context.deleteDatabase(name)
        }
    }
}
