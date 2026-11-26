package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.*
import kotlin.test.*

/** Distributed v17 offers have only one known series status. Never fabricate earlier history. */
object RevisionWorkflowMigrationProof {
    val v17Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 17L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            FrozenRevisionMigrationProof.v16Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 16, 17)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("v17 fixture")
    }
    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val (historical, schema) = open(v17Schema).use { d ->
            val fixture = MeasurementPricingFixture.seed(PrototypeDatabase(d))
            seedHistoricalEstimate(d, fixture.projectId, fixture.measurementUuid, 17) to PriceNameMigrationProof.schema(d)
        }
        val failing = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                val observed = object : SqlDriver by driver {
                    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                        val result = driver.execute(identifier, sql, parameters, binders)
                        if (sql.startsWith("INSERT INTO estimate_revision_choice")) error("Injected metadata migration failure")
                        return result
                    }
                }
                return production.migrate(observed, oldVersion, newVersion, *callbacks)
            }
        }
        assertFails { open(failing).close() }
        open(v17Schema).use { assertEquals(schema, PriceNameMigrationProof.schema(it)) }
        val inspecting = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
                production.migrate(driver, oldVersion, newVersion, *callbacks, AfterVersion(17) { migrated ->
                    val statuses = MeasurementElementMigrationProof.rows(migrated,
                        "SELECT revisionUuid,status,version FROM estimate_revision_workflow", 3).associateBy { it.first() }
                    assertEquals(listOf(historical.first, null, "1"), statuses[historical.first])
                    assertEquals(listOf(historical.latest, "APPROVED", "1"), statuses[historical.latest])
                    val choice = MeasurementElementMigrationProof.rows(migrated,
                        "SELECT seriesUuid,selectedRevisionUuid FROM estimate_revision_choice", 2)
                    assertEquals(listOf(listOf(historical.series, null)), choice)
                })
        }
        repeat(2) { attempt ->
            open(if (attempt == 0) inspecting else production).use { d ->
                val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
                assertNull(repo.series(historical.series))
                verifyFrozenEstimateContents(d)
            }
        }
    }
}
