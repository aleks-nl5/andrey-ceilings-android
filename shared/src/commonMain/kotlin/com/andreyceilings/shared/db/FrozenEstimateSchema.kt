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
    if (version >= 22) {
        expected["estimate_issuance_policy"] = """CREATE TABLE estimate_issuance_policy (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    policy TEXT NOT NULL
);"""
        expected["estimate_number_reservations"] = """CREATE TABLE estimate_number_reservations (
    seriesUuid TEXT NOT NULL PRIMARY KEY,
    publicNumber TEXT NOT NULL UNIQUE,
    estimateType TEXT NOT NULL CHECK (estimateType IN ('INDIVIDUAL', 'CONSOLIDATED')),
    issueDate TEXT NOT NULL,
    issueYear INTEGER NOT NULL CHECK (typeof(issueYear) = 'integer' AND issueYear BETWEEN 1000 AND 9999),
    sequence INTEGER NOT NULL CHECK (typeof(sequence) = 'integer' AND sequence > 0),
    policy TEXT NOT NULL,
    counterMode TEXT NOT NULL CHECK (counterMode IN ('INDEPENDENT', 'COMMON')),
    operationUuid TEXT UNIQUE,
    command TEXT CHECK ((operationUuid IS NULL) = (command IS NULL)),
    UNIQUE (issueYear, estimateType, sequence)
);"""
    } else {
        val hasIssuanceSchema = driver.executeQuery(null,
            "SELECT count(*) FROM sqlite_master WHERE name IN ('estimate_issuance_policy', 'estimate_number_reservations')",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) != 0L) }, 0).value
        check(!hasIssuanceSchema) { "Numbering schema requires version 22" }
    }
    if (version >= 24L) {
        expected["consolidated_series"] = """CREATE TABLE consolidated_series (
    seriesUuid TEXT NOT NULL PRIMARY KEY REFERENCES estimate_number_reservations(seriesUuid) ON DELETE RESTRICT,
    projectId INTEGER NOT NULL UNIQUE REFERENCES projects(id) ON DELETE RESTRICT,
    UNIQUE (seriesUuid, projectId)
);"""
        expected["consolidated_revisions"] = """CREATE TABLE consolidated_revisions (
    revisionUuid TEXT NOT NULL PRIMARY KEY,
    seriesUuid TEXT NOT NULL,
    projectId INTEGER NOT NULL,
    versionNumber INTEGER NOT NULL CHECK (typeof(versionNumber) = 'integer' AND versionNumber > 0),
    parentRevisionUuid TEXT,
    createdAt INTEGER NOT NULL CHECK (typeof(createdAt) = 'integer' AND createdAt > 0),
    title TEXT NOT NULL,
    finalCents INTEGER NOT NULL CHECK (typeof(finalCents) = 'integer' AND finalCents >= 0),
    discountMode TEXT NOT NULL CHECK (discountMode IN ('PERCENT','FIXED')),
    discountValue INTEGER NOT NULL CHECK (typeof(discountValue) = 'integer' AND discountValue >= 0
        AND (discountMode = 'FIXED' OR discountValue <= 10000)),
    payload TEXT NOT NULL,
    proof TEXT NOT NULL,
    draftProof TEXT NOT NULL,
    operationUuid TEXT NOT NULL UNIQUE,
    command TEXT NOT NULL,
    UNIQUE (seriesUuid, versionNumber),
    UNIQUE (seriesUuid, draftProof),
    UNIQUE (revisionUuid, seriesUuid),
    UNIQUE (revisionUuid, projectId),
    FOREIGN KEY (seriesUuid, projectId) REFERENCES consolidated_series(seriesUuid, projectId) ON DELETE RESTRICT,
    FOREIGN KEY (parentRevisionUuid, seriesUuid) REFERENCES consolidated_revisions(revisionUuid, seriesUuid) ON DELETE RESTRICT,
    CHECK ((versionNumber = 1) = (parentRevisionUuid IS NULL))
);"""
        expected["consolidated_members"] = """CREATE TABLE consolidated_members (
    consolidatedRevisionUuid TEXT NOT NULL REFERENCES consolidated_revisions(revisionUuid) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (typeof(position) = 'integer' AND position >= 0),
    measurementUuid TEXT NOT NULL REFERENCES room_measurements(uuid) ON DELETE RESTRICT,
    individualRevisionUuid TEXT NOT NULL REFERENCES estimate_revisions(revisionUuid) ON DELETE RESTRICT,
    PRIMARY KEY (consolidatedRevisionUuid, position),
    UNIQUE (consolidatedRevisionUuid, measurementUuid),
    UNIQUE (consolidatedRevisionUuid, individualRevisionUuid)
);"""
        expected["consolidated_workflow"] = """CREATE TABLE consolidated_workflow (
    revisionUuid TEXT NOT NULL PRIMARY KEY,
    projectId INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','SENT','APPROVED','REJECTED')),
    version INTEGER NOT NULL CHECK (typeof(version) = 'integer' AND version > 0),
    updatedAt INTEGER NOT NULL CHECK (typeof(updatedAt) = 'integer' AND updatedAt > 0),
    deletedAt INTEGER CHECK (deletedAt IS NULL OR (typeof(deletedAt) = 'integer' AND deletedAt > 0 AND status != 'APPROVED')),
    FOREIGN KEY (revisionUuid, projectId) REFERENCES consolidated_revisions(revisionUuid, projectId) ON DELETE RESTRICT
);"""
        expected["consolidated_approved_project"] = """CREATE UNIQUE INDEX consolidated_approved_project ON consolidated_workflow(projectId) WHERE status = 'APPROVED';"""
        expected["consolidated_project_delete_guard"] = """CREATE TRIGGER consolidated_project_delete_guard BEFORE UPDATE OF deletedAt ON projects
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
AND EXISTS (SELECT 1 FROM consolidated_workflow WHERE projectId = old.id AND status = 'APPROVED')
BEGIN SELECT RAISE(ABORT, 'consolidated_approval_requires_active_project'); END;"""
        expected["consolidated_ceiling_delete_guard"] = """CREATE TRIGGER consolidated_ceiling_delete_guard BEFORE UPDATE OF deletedAt ON room_measurements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
AND EXISTS (SELECT 1 FROM consolidated_workflow w JOIN consolidated_members m ON m.consolidatedRevisionUuid = w.revisionUuid
WHERE w.projectId = old.projectId AND w.status = 'APPROVED' AND m.measurementUuid = old.uuid)
BEGIN SELECT RAISE(ABORT, 'consolidated_approval_requires_active_ceiling'); END;"""
    } else {
        val exists = driver.executeQuery(null,
            "SELECT count(*) FROM sqlite_master WHERE name GLOB 'consolidated_*'",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) != 0L) }, 0).value
        check(!exists) { "Consolidated estimate schema requires version 24" }
    }
    expected.forEach { (name, definition) ->
        val actual = driver.executeQuery(null, "SELECT sql FROM sqlite_master WHERE name = ?", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 1) { bindString(0, name) }.value
        check(actual != null && normalize(actual) == normalize(definition)) { "Unsupported frozen estimate structure: $name" }
    }
}
