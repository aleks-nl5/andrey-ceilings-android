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
            PrototypeDatabase.Schema.migrate(old, 16, 18)
            val fixture = MeasurementPricingFixture.seed(db)
            val history = ImmutableEstimateRepository(db)
            val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            db.frozenEstimateQueries.updateEstimateWorkflow(EstimateWorkflowStatus.APPROVED.name, 601, first.seriesUuid, 1).executeAsOne()
            val payload = readPreV21FrozenV1Payload(old, first.seriesUuid)
            old.execute(null, "DROP TABLE estimate_revision_choice", 0)
            old.execute(null, "DROP TABLE estimate_revision_workflow", 0)
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
            assertEquals(beforePayload, readPreV21FrozenV1Payload(old))
        }
        var saved: List<Pair<String, String>> = emptyList()
        open(production).use { current ->
            PriceCatalogSchema.verifyStructure(current)
            verifyFrozenEstimateContents(current)
            assertEquals(beforePayload, PrototypeDatabase(current).frozenEstimateQueries.selectFrozenV1List().executeAsList().single().payload)
            FrozenRevisionProof.run(current)
            saved = PrototypeDatabase(current).frozenRevisionQueries.selectExactList().executeAsList().map { it.revisionUuid to it.payload }
        }
        open(production).use { current ->
            PriceCatalogSchema.verifyStructure(current)
            verifyFrozenEstimateContents(current)
            assertEquals(saved, PrototypeDatabase(current).frozenRevisionQueries.selectExactList().executeAsList().map { it.revisionUuid to it.payload })
            assertTrue(PrototypeDatabase(current).frozenEstimateQueries.selectFrozenV1List().executeAsList().any { it.payload == beforePayload })
        }
    }
}

/**
 * Test-only historical read: fixtures intentionally reopen the exact pre-v21 schema.
 * Retain the old v1 active-series/workflow joins without referring to the v21
 * tombstone table. Production reads after migration must still use current queries.
 */
internal fun readPreV21FrozenV1Payload(driver: SqlDriver, seriesUuid: String? = null): String =
    driver.executeQuery(null, """
        SELECT f.payload
        FROM estimate_v1_frozen f JOIN estimate_revisions r USING(revisionUuid)
        JOIN estimate_series s USING(seriesUuid) JOIN estimate_workflow w USING(seriesUuid)
        WHERE s.deletedAt IS NULL AND r.versionNumber = 1
        AND (? IS NULL OR s.seriesUuid = ?)
    """.trimIndent(), { cursor ->
        check(cursor.next().value) { "Missing historical v1 payload" }
        val payload = checkNotNull(cursor.getString(0))
        check(!cursor.next().value) { "Ambiguous historical v1 payload" }
        QueryResult.Value(payload)
    }, 2) {
        bindString(0, seriesUuid)
        bindString(1, seriesUuid)
    }.value
