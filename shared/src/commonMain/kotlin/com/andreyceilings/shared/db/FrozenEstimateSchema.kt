package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Strict physical admission for encrypted reopen/import; never repair a partial v16 in place. */
fun verifyFrozenEstimateStructure(driver: SqlDriver, version: Long = PriceCatalogSchema.version) {
    val expected = mutableMapOf(
        "estimate_v1_frozen" to """CREATE TABLE estimate_v1_frozen (
    revisionUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    payload TEXT NOT NULL,
    subtotalCents INTEGER NOT NULL CHECK (typeof(subtotalCents) = 'integer' AND subtotalCents >= 0),
    discountBasisPoints INTEGER NOT NULL CHECK (typeof(discountBasisPoints) = 'integer' AND discountBasisPoints BETWEEN 0 AND 10000),
    discountCents INTEGER NOT NULL CHECK (typeof(discountCents) = 'integer' AND discountCents >= 0 AND discountCents <= subtotalCents),
    totalCents INTEGER NOT NULL CHECK (typeof(totalCents) = 'integer' AND totalCents = subtotalCents - discountCents)
);""",
        "estimate_workflow" to """CREATE TABLE estimate_workflow (
    seriesUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_series(seriesUuid) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','SENT','APPROVED','REJECTED')),
    version INTEGER NOT NULL CHECK (typeof(version) = 'integer' AND version > 0),
    updatedAt INTEGER NOT NULL CHECK (typeof(updatedAt) = 'integer' AND updatedAt > 0)
);""",
        "estimate_save_receipts" to """CREATE TABLE estimate_save_receipts (
    operationUuid TEXT NOT NULL PRIMARY KEY,
    command TEXT NOT NULL,
    seriesUuid TEXT NOT NULL UNIQUE REFERENCES estimate_series(seriesUuid) ON DELETE RESTRICT,
    revisionUuid TEXT NOT NULL UNIQUE REFERENCES estimate_v1_frozen(revisionUuid) ON DELETE RESTRICT
);""",
        "estimate_number_settings" to """CREATE TABLE estimate_number_settings (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    template TEXT NOT NULL,
    prefix TEXT NOT NULL,
    sequenceWidth INTEGER NOT NULL CHECK (sequenceWidth BETWEEN 1 AND 12),
    nextSequence INTEGER NOT NULL DEFAULT 1 CHECK (nextSequence > 0),
    suffixLabel TEXT NOT NULL DEFAULT ''
);""",
        "estimate_series" to """CREATE TABLE estimate_series (
    seriesUuid TEXT NOT NULL PRIMARY KEY,
    documentNumber TEXT NOT NULL UNIQUE,
    projectId INTEGER NOT NULL REFERENCES projects(id),
    measurementUuid TEXT,
    policyTemplate TEXT NOT NULL,
    policyPrefix TEXT NOT NULL,
    policySequenceWidth INTEGER NOT NULL,
    localSequence INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    deletedAt INTEGER,
    publicNumber TEXT
);""",
        "index_estimate_series_public_number" to "CREATE UNIQUE INDEX index_estimate_series_public_number ON estimate_series(publicNumber) WHERE publicNumber IS NOT NULL;"
    )
    if (version >= 17) {
        expected["estimate_revision_frozen"] = """CREATE TABLE estimate_revision_frozen (
    revisionUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    payload TEXT NOT NULL,
    subtotalCents INTEGER NOT NULL CHECK (typeof(subtotalCents) = 'integer' AND subtotalCents >= 0),
    discountBasisPoints INTEGER NOT NULL CHECK (typeof(discountBasisPoints) = 'integer' AND discountBasisPoints BETWEEN 0 AND 10000),
    discountCents INTEGER NOT NULL CHECK (typeof(discountCents) = 'integer' AND discountCents >= 0 AND discountCents <= subtotalCents),
    totalCents INTEGER NOT NULL CHECK (typeof(totalCents) = 'integer' AND totalCents = subtotalCents - discountCents)
);"""
        expected["estimate_revision_receipts"] = """CREATE TABLE estimate_revision_receipts (
    operationUuid TEXT NOT NULL PRIMARY KEY,
    command TEXT NOT NULL,
    seriesUuid TEXT NOT NULL REFERENCES estimate_series(seriesUuid) ON DELETE RESTRICT,
    revisionUuid TEXT NOT NULL UNIQUE REFERENCES estimate_revision_frozen(revisionUuid) ON DELETE RESTRICT
);"""
    }
    fun normalize(sql: String): String = Regex("'(?:''|[^'])*'|[^']+").findAll(sql.trim().trimEnd(';')).joinToString("") { token ->
        if (token.value.startsWith("'")) token.value else token.value.replace("\"", "")
            .replace(Regex("\\s+"), " ").replace(Regex("\\s*([,()])\\s*"), "$1").lowercase()
    }
    if (version >= 18) {
        expected["estimate_revision_workflow"] = """CREATE TABLE estimate_revision_workflow (
    revisionUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    status TEXT CHECK (status IN ('DRAFT','SENT','APPROVED','REJECTED')),
    version INTEGER NOT NULL CHECK (typeof(version) = 'integer' AND version > 0),
    updatedAt INTEGER CHECK ((status IS NULL AND updatedAt IS NULL) OR (status IS NOT NULL AND typeof(updatedAt) = 'integer' AND updatedAt > 0))
);"""
        expected["estimate_revision_choice"] = """CREATE TABLE estimate_revision_choice (
    seriesUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_series(seriesUuid) ON DELETE RESTRICT,
    selectedRevisionUuid TEXT REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    version INTEGER NOT NULL CHECK (typeof(version) = 'integer' AND version > 0),
    updatedAt INTEGER CHECK ((selectedRevisionUuid IS NULL AND updatedAt IS NULL) OR (selectedRevisionUuid IS NOT NULL AND typeof(updatedAt) = 'integer' AND updatedAt > 0))
);"""
    }
    if (version >= 21) expected["estimate_revision_tombstones"] = """CREATE TABLE estimate_revision_tombstones (
    revisionUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    deletedAt INTEGER NOT NULL CHECK (typeof(deletedAt) = 'integer' AND deletedAt > 0)
);"""
    expected.forEach { (name, definition) ->
        val actual = driver.executeQuery(null, "SELECT sql FROM sqlite_master WHERE name = ?", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 1) { bindString(0, name) }.value
        check(actual != null && normalize(actual) == normalize(definition)) { "Unsupported frozen estimate structure: $name" }
    }
}
