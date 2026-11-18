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
        var series = ""; var firstId = ""; var latestId = ""
        var payloads = emptyList<String>()
        val schema = open(v17Schema).use { d ->
            PrototypeDatabase.Schema.migrate(d, 17, 18)
            val db = PrototypeDatabase(d); val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            val second = repo.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 601)
            series = first.seriesUuid; firstId = first.initialRevisionUuid; latestId = second.initialRevisionUuid
            payloads = db.frozenRevisionQueries.selectExactHistory(series).executeAsList().map { it.payload }
            // This is the only status persisted by the original v17 application.
            db.frozenEstimateQueries.updateEstimateWorkflow("APPROVED", 602, series, 1).executeAsOne()
            d.execute(null, "DROP TABLE estimate_revision_choice", 0)
            d.execute(null, "DROP TABLE estimate_revision_workflow", 0)
            verifyFrozenEstimateStructure(d, 17)
            PriceNameMigrationProof.schema(d)
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
        open(production).use { d ->
            val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
            val old = assertNotNull(repo.exactIndividual(series, firstId))
            val latest = assertNotNull(repo.exactIndividual(series, latestId))
            assertNull(old.status); assertEquals(1, old.statusVersion)
            assertEquals(EstimateWorkflowStatus.APPROVED, latest.status)
            assertNull(latest.selectedRevisionUuid)
            assertEquals(payloads, PrototypeDatabase(d).frozenRevisionQueries.selectExactHistory(series).executeAsList().map { it.payload })
            assertTrue(repo.setWorkflowStatus(firstId, 1, EstimateWorkflowStatus.SENT, 603))
            assertTrue(repo.selectRevision(series, firstId, 1, 604))
            assertFalse(repo.selectRevision(series, latestId, 1, 605))
            verifyFrozenEstimateContents(d)
        }
        open(production).use { d ->
            val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
            assertEquals(EstimateWorkflowStatus.SENT, repo.exactIndividual(series, firstId)!!.status)
            assertEquals(EstimateWorkflowStatus.APPROVED, repo.exactIndividual(series, latestId)!!.status)
            assertEquals(firstId, repo.exactIndividual(series, latestId)!!.selectedRevisionUuid)
            verifyFrozenEstimateContents(d)
        }
    }
}
