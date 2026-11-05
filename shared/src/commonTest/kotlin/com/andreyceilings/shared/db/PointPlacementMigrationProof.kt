package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

object PointPlacementMigrationProof {
    val v7Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 7L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            MeasurementElementMigrationProof.v6Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 6, 7)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Fixture cannot migrate")
    }
    fun snapshot(driver: SqlDriver) = MeasurementElementMigrationProof.snapshot(driver) +
        MeasurementElementMigrationProof.rows(driver,
            "SELECT " + listOf("uuid", "measurementUuid", "kind", "priceItemUuid", "label", "note", "priceItemCode", "unit", "quantityMode", "placementKind",
                "approvalStatus", "quantityKind", "quantityMilli", "quantityMinMilli", "quantityMaxMilli", "placementStatus", "placedQuantityMilli", "createdAt", "updatedAt", "deletedAt")
                .joinToString { "quote($it)" } + " FROM measurement_elements ORDER BY uuid", 20)

    fun failing(driver: SqlDriver): SqlDriver = object : SqlDriver by driver {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.trimStart().startsWith("CREATE TRIGGER point_insert_guard")) error("Injected v8 migration interruption")
            return driver.execute(identifier, sql, parameters, binders)
        }
    }

    /** SQLiter/Android callbacks own real encrypted transactions; no nested native BEGIN. */
    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, populated: Boolean,
        open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val old = open(v7Schema)
        val before: List<List<String?>>; val schemaBefore: List<List<String?>>
        try {
            // SQLiter owns the schema callback transaction. Seed only after open,
            // when the normal driver's SQLDelight transaction can own BEGIN.
            com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(PrototypeDatabase(old))
            if (populated) {
                MeasurementElementMigrationProof.populate(old)
                val db = PrototypeDatabase(old)
                val parent = MeasurementRepository(db).all().single().uuid
                val spot = PriceRepository(db).all().single { it.code == "light.spot" }
                // Historical snapshot remains NONE after upgrade, even when fresh defaults are POINT.
                old.execute(null, "UPDATE price_items SET placementKind='none' WHERE uuid='${spot.uuid}'", 0)
                MeasurementElementRepository(db).createCatalogSelection(parent, spot.uuid, ElementQuantity.Exact(6000), 400, "old-none-snapshot")
            }
            before = snapshot(old); schemaBefore = PriceNameMigrationProof.schema(old)
        } finally { old.close() }
        val failSchema = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
                production.migrate(failing(driver), oldVersion, newVersion, *callbacks)
        }
        assertFails { open(failSchema).close() }
        val rolledBack = open(v7Schema)
        try {
            assertEquals(7L, PriceNameUniquenessProof.scalar(rolledBack, "PRAGMA user_version"))
            assertEquals(before, snapshot(rolledBack)); assertEquals(schemaBefore, PriceNameMigrationProof.schema(rolledBack))
        } finally { rolledBack.close() }
        val upgraded = open(production)
        val fixture: PointPlacementProof.Fixture; val point: PointPlacement
        try {
            assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(upgraded, "PRAGMA user_version"))
            assertEquals(before, snapshot(upgraded))
            val db = PrototypeDatabase(upgraded)
            if (populated) {
                val oldElement = MeasurementElementRepository(db).find("old-none-snapshot")!!
                assertNull(oldElement.pointCapacity())
                assertEquals("none", PriceRepository(db).all().single { it.code == "light.spot" }.placementKind)
            }
            fixture = PointPlacementProof.fixture(db, "Reopen")
            point = PointPlacementRepository(db).addPoint(fixture.element.version, fixture.contourVersion, 1234, 2345, 500, "persistent-point")
        } finally { upgraded.close() }
        val reopened = open(production)
        try {
            val db = PrototypeDatabase(reopened)
            assertEquals(point, PointPlacementRepository(db).find(point.uuid))
            assertEquals(listOf(point), PointPlacementRepository(db).forMeasurement(fixture.element.measurementUuid))
            assertEquals(1000L, MeasurementElementRepository(db).find(fixture.element.uuid)!!.placedQuantityMilli)
            assertEquals(ElementPlacement.APPROXIMATE, MeasurementElementRepository(db).find(fixture.element.uuid)!!.placement)
            assertTrue(MeasurementElementMigrationProof.rows(reopened, "PRAGMA foreign_key_check", 4).isEmpty())
        } finally { reopened.close() }
    }
}
