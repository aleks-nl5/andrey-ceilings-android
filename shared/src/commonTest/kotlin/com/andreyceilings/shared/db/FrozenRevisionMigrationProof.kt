package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.*
import kotlin.test.*

object FrozenRevisionMigrationProof {
    val v16Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 16L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            FrozenEstimateMigrationProof.v15Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 15, 16)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("v16 fixture")
    }
    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val (beforeSchema, beforePayload) = open(v16Schema).use { old ->
            val db = PrototypeDatabase(old)
            // The current v1 writer also checks the cross-version operation namespace.
            // Create its empty lookup tables only while producing the historical v1 fixture,
            // then remove them. The on-disk database below is exactly v16 with real v1 data.
            PrototypeDatabase.Schema.migrate(old, 16, 17)
            val fixture = MeasurementPricingFixture.seed(db)
            val history = ImmutableEstimateRepository(db)
            val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            history.setWorkflowStatus(first.seriesUuid, 1, EstimateWorkflowStatus.APPROVED, 601)
            val payload = db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload
            old.execute(null, "DROP TABLE estimate_revision_receipts", 0)
            old.execute(null, "DROP TABLE estimate_revision_frozen", 0)
            verifyFrozenEstimateStructure(old, 16)
            PriceNameMigrationProof.schema(old) to payload
        }
        val interrupted = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                val failing = object : SqlDriver by driver {
                    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                        val result = driver.execute(identifier, sql, parameters, binders)
                        if (sql.startsWith("CREATE TABLE estimate_revision_receipts")) error("Injected v17 migration failure")
                        return result
                    }
                }
                return production.migrate(failing, oldVersion, newVersion, *callbacks)
            }
        }
        assertFails { open(interrupted).close() }
        open(v16Schema).use { old ->
            assertEquals(beforeSchema, PriceNameMigrationProof.schema(old))
            assertEquals(beforePayload, PrototypeDatabase(old).frozenEstimateQueries.selectFrozenV1List().executeAsList().single().payload)
        }
        var saved: List<Pair<String, String>> = emptyList()
        open(production).use { current ->
            PriceCatalogSchema.verifyStructure(current)
            verifyFrozenEstimateContents(current)
            assertEquals(beforePayload, PrototypeDatabase(current).frozenEstimateQueries.selectFrozenV1List().executeAsList().single().payload)
            FrozenRevisionProof.run(current)
            saved = PrototypeDatabase(current).frozenRevisionQueries.selectExactLatestList().executeAsList().map { it.revisionUuid to it.payload }
        }
        open(production).use { current ->
            PriceCatalogSchema.verifyStructure(current)
            verifyFrozenEstimateContents(current)
            assertEquals(saved, PrototypeDatabase(current).frozenRevisionQueries.selectExactLatestList().executeAsList().map { it.revisionUuid to it.payload })
            assertTrue(PrototypeDatabase(current).frozenEstimateQueries.selectFrozenV1List().executeAsList().any { it.payload == beforePayload })
        }
    }
}
