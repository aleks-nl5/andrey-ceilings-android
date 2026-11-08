package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.*
import kotlin.test.*

object FrozenEstimateMigrationProof {
    val v15Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 15L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            BusinessRoleMigrationProof.v14Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 14, 15)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Frozen v15 fixture")
    }

    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val (originalSchema, originalRows) = open(v15Schema).use { old ->
            PriceCatalogMigrationProof.populateHistory(old)
            old.execute(null, "UPDATE estimate_number_settings SET template = '{uuid}', prefix = 'ІП', sequenceWidth = 7", 0)
            PriceNameMigrationProof.schema(old) to settings(old)
        }
        val interrupted = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                val failing = object : SqlDriver by driver {
                    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                        val result = driver.execute(identifier, sql, parameters, binders)
                        if (sql.startsWith("CREATE TABLE estimate_workflow")) error("Injected v16 migration failure")
                        return result
                    }
                }
                return production.migrate(failing, oldVersion, newVersion, *callbacks)
            }
        }
        assertFails { open(interrupted).close() }
        open(v15Schema).use { old ->
            assertEquals(originalSchema, PriceNameMigrationProof.schema(old))
            assertEquals(originalRows, settings(old))
        }
        var createdId: String? = null
        var payload: String? = null
        var legacyNumber: String? = null
        open(production).use { current ->
            PriceCatalogSchema.verifyStructure(current)
            val db = PrototypeDatabase(current)
            val history = ImmutableEstimateRepository(db)
            assertEquals("ІП", history.numberPolicy().prefix)
            assertEquals(7, history.numberPolicy().sequenceWidth)
            assertTrue(EstimateNumbering.validatePublic(history.numberPolicy()).isEmpty())
            legacyNumber = history.seriesForProject(ProjectRepository(db).all().single().id).single().documentNumber
            val fixture = MeasurementPricingFixture.seed(db)
            val request = FrozenIndividualEstimateProof.command(db, fixture)
            val saved = history.saveIndividual(request, 2026, 600)
            createdId = saved.seriesUuid
            payload = history.frozenIndividual(saved.seriesUuid)!!.payload
        }
        open(production).use { reopened ->
            val history = ImmutableEstimateRepository(PrototypeDatabase(reopened))
            assertEquals(payload, history.frozenIndividual(checkNotNull(createdId))!!.payload)
            assertNotNull(history.seriesByNumber(checkNotNull(legacyNumber)))
            assertEquals(2, history.revisions(checkNotNull(createdId)).single().let { history.series(it.seriesUuid)!!.localSequence })
        }
    }
    private fun settings(driver: SqlDriver) = MeasurementElementMigrationProof.rows(driver,
        "SELECT template,prefix,sequenceWidth,nextSequence FROM estimate_number_settings", 4)
}
