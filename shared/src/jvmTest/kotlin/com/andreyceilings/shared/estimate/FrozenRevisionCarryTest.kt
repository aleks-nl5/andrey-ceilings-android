package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.QuantityMode
import kotlin.test.*

class FrozenRevisionCarryTest {
    @Test fun selectiveCarryRequiresExplicitActionsAndFailsClosedForMissingStableIdentity() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val group = PriceGroupRepository(db).create("Optional", 200, "шт.", QuantityMode.MANUAL)
            val prices = PriceRepository(db)
            val price = prices.createInGroup("optional.fixture", "Optional fixture", group, 2.0, 201)
            val elements = MeasurementElementRepository(db)
            val included = elements.createCatalogSelection(fixture.measurementUuid, prices.find(price)!!.uuid,
                ElementQuantity.Exact(2000), 202, businessRole = BusinessRole.ADDITIONAL_WORK)
            val excluded = elements.createCatalogSelection(fixture.measurementUuid, prices.find(price)!!.uuid,
                ElementQuantity.Exact(1000), 202, approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
            val presenter = FrozenRevisionProof.presenter(db, fixture)
            presenter.select("element:${included.uuid}", true)
            presenter.select("element:${excluded.uuid}", false)
            presenter.editDiscount("10"); presenter.confirmDiscount(); presenter.acknowledge(true)
            val history = ImmutableEstimateRepository(db)
            val saved = history.saveIndividual(checkNotNull(presenter.saveRequest(fixture.projectId, "Manual decisions")), 2026, 300)
            val base = checkNotNull(history.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid))
            val fresh = FrozenRevisionProof.presenter(db, fixture, base)
            assertFalse("element:${included.uuid}" in fresh.state.value.selected)
            assertTrue("element:${excluded.uuid}" in fresh.state.value.selected)
            assertEquals("0", fresh.state.value.discountText)
            assertTrue(fresh.carrySelection())
            assertTrue("element:${included.uuid}" in fresh.state.value.selected)
            assertFalse("element:${excluded.uuid}" in fresh.state.value.selected)
            assertFalse(fresh.state.value.acknowledged)
            // One missing decision blocks the entire carry, including the otherwise eligible exclusion.
            elements.delete(included.version, 400)
            val changed = FrozenRevisionProof.presenter(db, fixture, base)
            val before = changed.state.value
            assertFalse(changed.carrySelection())
            assertEquals(before, changed.state.value)
            assertEquals(1, history.revisions(saved.seriesUuid).size)
            assertEquals("DRAFT", history.frozenIndividual(saved.seriesUuid)!!.status)
        }
    }
}
