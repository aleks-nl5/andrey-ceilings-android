package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Fail closed on a partial v7 Windows schema, including missing CHECKs/FKs/index/view.
 * Canonical SQL mirrors 6.sqm; the shared migration contract verifies both paths.
 */
internal fun verifyMeasurementElementStructure(driver: SqlDriver, version: Long = PriceCatalogSchema.version) {
    // Ignore formatting/SQL keyword case; persisted enum literals remain case-sensitive.
    fun normalize(sql: String) = Regex("'(?:''|[^'])*'|[^']+")
        .findAll(sql.trim().trimEnd(';')).joinToString("") { token ->
            if (token.value.startsWith("'")) token.value else token.value
                .replace(Regex("\\s+"), " ").replace(Regex("\\s*([,()])\\s*"), "$1").lowercase()
        }
    fun definition(name: String): String = driver.executeQuery(null,
        "SELECT sql FROM sqlite_master WHERE name = '$name'", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 0).value ?: error("Incomplete measurement element schema")
    check(normalize(definition("measurement_elements")) == normalize(if (version >= 15) elementTableSql.replace("    CHECK(unit IS NULL OR length(trim(unit)) > 0),", "    $businessRoleColumn,\n    CHECK(unit IS NULL OR length(trim(unit)) > 0),") else elementTableSql)) {
        "Unsupported measurement element table structure"
    }
    check(normalize(definition("index_measurement_elements_parent_active_order")) == normalize(
        "CREATE INDEX index_measurement_elements_parent_active_order ON measurement_elements(measurementUuid, deletedAt, createdAt, uuid)")) {
        "Unsupported measurement element index"
    }
    if (version >= 15) {
        for ((name, role) in listOf("canvas" to "CANVAS", "profile" to "PRIMARY_PROFILE")) {
            check(normalize(definition("index_measurement_elements_$name")) == normalize(
                "CREATE UNIQUE INDEX index_measurement_elements_$name ON measurement_elements(measurementUuid) WHERE deletedAt IS NULL AND businessRole = '$role'")) {
                "Unsupported business role index"
            }
        }
    }
    check(normalize(definition("measurement_element_records")) == normalize(elementViewSql)) {
        "Unsupported measurement element projection"
    }
}

private val elementTableSql = """
CREATE TABLE measurement_elements (
    uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid)) > 0),
    measurementUuid TEXT NOT NULL REFERENCES room_measurements(uuid),
    kind TEXT NOT NULL CHECK(kind IN ('wish','catalog_item')),
    priceItemUuid TEXT REFERENCES price_items(uuid),
    label TEXT NOT NULL CHECK(length(trim(label)) > 0),
    note TEXT NOT NULL DEFAULT '',
    priceItemCode TEXT,
    unit TEXT,
    quantityMode TEXT,
    placementKind TEXT,
    approvalStatus TEXT NOT NULL DEFAULT 'discussing' CHECK(approvalStatus IN ('discussing','approved')),
    quantityKind TEXT NOT NULL CHECK(quantityKind IN ('unknown','exact','range','ceiling_area','ceiling_perimeter')),
    quantityMilli INTEGER,
    quantityMinMilli INTEGER,
    quantityMaxMilli INTEGER,
    placementStatus TEXT NOT NULL DEFAULT 'unplaced' CHECK(placementStatus IN ('unplaced','approximate','exact')),
    placedQuantityMilli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(placedQuantityMilli) = 'integer' AND placedQuantityMilli BETWEEN 0 AND 9000000000000000),
    createdAt INTEGER NOT NULL CHECK(typeof(createdAt) = 'integer' AND createdAt >= 0),
    updatedAt INTEGER NOT NULL CHECK(typeof(updatedAt) = 'integer' AND updatedAt >= createdAt),
    deletedAt INTEGER CHECK(deletedAt IS NULL OR (typeof(deletedAt) = 'integer' AND deletedAt = updatedAt)),
    CHECK(unit IS NULL OR length(trim(unit)) > 0),
    CHECK((kind = 'wish' AND priceItemUuid IS NULL AND priceItemCode IS NULL AND quantityMode IS NULL AND placementKind IS NULL
            AND quantityKind IN ('unknown','exact','range')) OR
        (kind = 'catalog_item' AND priceItemUuid IS NOT NULL AND length(trim(priceItemUuid)) > 0
            AND priceItemCode IS NOT NULL AND length(trim(priceItemCode)) > 0 AND unit IS NOT NULL
            AND quantityMode IS NOT NULL AND placementKind IS NOT NULL
            AND placementKind IN ('none','point','line','area')
            AND ((quantityMode IN ('manual','entered_length') AND quantityKind IN ('unknown','exact','range'))
                OR (quantityMode = 'ceiling_area' AND quantityKind = 'ceiling_area' AND unit = 'м²')
                OR (quantityMode = 'ceiling_perimeter' AND quantityKind = 'ceiling_perimeter' AND unit = 'м.п.'))
            AND (quantityMode != 'entered_length' OR unit = 'м.п.'))),
    CHECK((quantityKind IN ('unknown','ceiling_area','ceiling_perimeter') AND quantityMilli IS NULL AND quantityMinMilli IS NULL AND quantityMaxMilli IS NULL) OR
        (quantityKind = 'exact' AND unit IS NOT NULL AND quantityMilli IS NOT NULL
            AND typeof(quantityMilli) = 'integer' AND quantityMilli BETWEEN 1 AND 9000000000000000
            AND quantityMinMilli IS NULL AND quantityMaxMilli IS NULL) OR
        (quantityKind = 'range' AND unit IS NOT NULL AND quantityMilli IS NULL
            AND quantityMinMilli IS NOT NULL AND quantityMaxMilli IS NOT NULL
            AND typeof(quantityMinMilli) = 'integer' AND typeof(quantityMaxMilli) = 'integer'
            AND quantityMinMilli BETWEEN 1 AND 9000000000000000 AND quantityMaxMilli BETWEEN 1 AND 9000000000000000
            AND quantityMinMilli < quantityMaxMilli)),
    CHECK(unit IS NULL OR unit != 'шт.' OR
        ((quantityMilli IS NULL OR quantityMilli % 1000 = 0)
            AND (quantityMinMilli IS NULL OR quantityMinMilli % 1000 = 0)
            AND (quantityMaxMilli IS NULL OR quantityMaxMilli % 1000 = 0))),
    CHECK(placementStatus != 'unplaced' OR placedQuantityMilli = 0)
)
""".trimIndent()
private val elementViewSql = """
CREATE VIEW measurement_element_records AS
SELECT e.*, CASE WHEN e.kind = 'wish' THEN 'not_applicable'
    WHEN i.uuid IS NOT NULL AND i.deletedAt IS NULL AND g.uuid IS NOT NULL AND g.deletedAt IS NULL THEN 'active'
    ELSE 'deleted_or_missing' END AS catalogAvailability
FROM measurement_elements e
LEFT JOIN price_items i ON i.uuid = e.priceItemUuid
LEFT JOIN price_groups g ON g.uuid = i.groupUuid
""".trimIndent()

internal val businessRoleColumn = """businessRole TEXT CHECK(businessRole IS NULL OR (kind = 'catalog_item' AND businessRole IN ('CANVAS','PRIMARY_PROFILE','ADDITIONAL_WORK')
        AND (businessRole != 'CANVAS' OR (quantityMode = 'ceiling_area' AND unit = 'м²'))
        AND (businessRole != 'PRIMARY_PROFILE' OR (quantityMode = 'ceiling_perimeter' AND unit = 'м.п.'))))"""
