package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Strict v8/v9/v10/v11 fingerprints, including already distributed P3C3 v10 files. */
internal fun verifyPointPlacementStructure(driver: SqlDriver, version: Long = PriceCatalogSchema.version) {
    fun normalize(sql: String) = sql.trim().trimEnd(';').replace(Regex("\\s+"), " ").replace(Regex("\\s*([,()])\\s*"), "$1").lowercase()
    val names = driver.executeQuery(null,
        "SELECT name FROM sqlite_master WHERE name LIKE 'point_%' OR name LIKE 'index_point_%' OR name LIKE 'ceiling_%' OR name LIKE 'index_ceiling_%'", { cursor ->
            val found = mutableSetOf<String>()
            while (cursor.next().value) found.add(checkNotNull(cursor.getString(0)))
            QueryResult.Value(found)
        }, 0).value
    check(names == (when(version) { 8L -> pointV8SchemaDefinitions; 9L -> pointV9SchemaDefinitions; 10L -> pointV10SchemaDefinitions; else -> pointSchemaDefinitions }).keys) { "Partial or unknown point placement objects" }
    val definitions = when(version) { 8L -> pointV8SchemaDefinitions; 9L -> pointV9SchemaDefinitions; 10L -> pointV10SchemaDefinitions; else -> pointSchemaDefinitions }
    definitions.forEach { (name, expected) ->
        val actual = driver.executeQuery(null, "SELECT sql FROM sqlite_master WHERE name = '$name'", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 0).value
        check(actual != null && normalize(actual) == normalize(expected)) { "Incomplete or unknown point placement schema: $name" }
    }
}

internal val pointV8SchemaDefinitions = mapOf(
    "point_placements" to """
CREATE TABLE point_placements (
    uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid)) > 0),
    elementUuid TEXT NOT NULL REFERENCES measurement_elements(uuid),
    xMm INTEGER NOT NULL CHECK(typeof(xMm) = 'integer' AND xMm BETWEEN -1000000 AND 1000000),
    yMm INTEGER NOT NULL CHECK(typeof(yMm) = 'integer' AND yMm BETWEEN -1000000 AND 1000000),
    contourUpdatedAt INTEGER NOT NULL CHECK(typeof(contourUpdatedAt) = 'integer' AND contourUpdatedAt >= 0),
    createdAt INTEGER NOT NULL CHECK(typeof(createdAt) = 'integer' AND createdAt >= 0),
    updatedAt INTEGER NOT NULL CHECK(typeof(updatedAt) = 'integer' AND updatedAt >= createdAt),
    deletedAt INTEGER CHECK(deletedAt IS NULL OR (typeof(deletedAt) = 'integer' AND deletedAt = updatedAt))
);
""".trimIndent(),
    "index_point_placements_element_active_order" to """
CREATE INDEX index_point_placements_element_active_order ON point_placements(elementUuid, deletedAt, createdAt, uuid);
""".trimIndent(),
    "point_insert_guard" to """
CREATE TRIGGER point_insert_guard BEFORE INSERT ON point_placements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NOT NULL OR NOT EXISTS (
        SELECT 1 FROM measurement_elements e JOIN room_measurements m ON m.uuid = e.measurementUuid
        JOIN projects p ON p.id = m.projectId
        WHERE e.uuid = new.elementUuid AND e.deletedAt IS NULL AND m.deletedAt IS NULL AND p.deletedAt IS NULL
        AND e.kind = 'catalog_item' AND e.placementKind = 'point' AND e.unit = 'шт.'
        AND e.quantityKind = 'exact' AND e.quantityMilli % 1000 = 0
        AND e.updatedAt < 9223372036854775807 AND m.updatedAt = new.contourUpdatedAt
        AND (SELECT count(*) FROM point_placements q WHERE q.elementUuid = e.uuid AND q.deletedAt IS NULL) < e.quantityMilli / 1000
    ) THEN RAISE(ABORT, 'point_parent_capacity_or_contour') END;
END;
""".trimIndent(),
    "point_identity_guard" to """
CREATE TRIGGER point_identity_guard BEFORE UPDATE ON point_placements
WHEN new.uuid IS NOT old.uuid OR new.elementUuid IS NOT old.elementUuid
    OR new.xMm IS NOT old.xMm OR new.yMm IS NOT old.yMm
    OR new.contourUpdatedAt IS NOT old.contourUpdatedAt OR new.createdAt IS NOT old.createdAt
    OR old.deletedAt IS NOT NULL OR new.deletedAt IS NULL OR new.updatedAt <= old.updatedAt
BEGIN
    SELECT RAISE(ABORT, 'point_immutable_or_stale');
END;
""".trimIndent(),
    "point_delete_guard" to """
CREATE TRIGGER point_delete_guard BEFORE DELETE ON point_placements
BEGIN
    SELECT RAISE(ABORT, 'point_requires_tombstone');
END;
""".trimIndent(),
    "point_insert_summary" to """
CREATE TRIGGER point_insert_summary AFTER INSERT ON point_placements
BEGIN
    UPDATE measurement_elements SET
        placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL),
        placementStatus = CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL) THEN 'approximate' ELSE 'unplaced' END,
        updatedAt = CASE WHEN deletedAt IS NULL THEN max(updatedAt + 1, new.updatedAt) ELSE updatedAt END
    WHERE uuid = new.elementUuid;
END;
""".trimIndent(),
    "point_remove_summary" to """
CREATE TRIGGER point_remove_summary AFTER UPDATE OF deletedAt ON point_placements
BEGIN
    UPDATE measurement_elements SET
        placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL),
        placementStatus = CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL) THEN 'approximate' ELSE 'unplaced' END,
        updatedAt = CASE WHEN deletedAt IS NULL THEN max(updatedAt + 1, new.updatedAt) ELSE updatedAt END
    WHERE uuid = new.elementUuid;
END;
""".trimIndent(),
    "point_element_insert_guard" to """
CREATE TRIGGER point_element_insert_guard BEFORE INSERT ON measurement_elements
WHEN new.placedQuantityMilli != 0 OR new.placementStatus != 'unplaced'
BEGIN
    SELECT RAISE(ABORT, 'point_summary_requires_records');
END;
""".trimIndent(),
    "point_element_update_guard" to """
CREATE TRIGGER point_element_update_guard BEFORE UPDATE ON measurement_elements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NULL AND (
        new.placedQuantityMilli != (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL)
        OR new.placementStatus != CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL) THEN 'approximate' ELSE 'unplaced' END
    ) THEN RAISE(ABORT, 'point_summary_requires_records') END;
    SELECT CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL)
        AND new.deletedAt IS NULL AND (new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid
            OR new.kind != 'catalog_item' OR new.placementKind IS NOT 'point' OR new.unit IS NOT 'шт.'
            OR new.quantityKind != 'exact' OR new.quantityMilli IS NULL
            OR new.quantityMilli < (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL))
        THEN RAISE(ABORT, 'point_quantity_in_use') END;
END;
""".trimIndent(),
    "point_element_tombstone" to """
CREATE TRIGGER point_element_tombstone AFTER UPDATE OF deletedAt ON measurement_elements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE point_placements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE elementUuid = old.uuid AND deletedAt IS NULL;
    UPDATE measurement_elements SET placedQuantityMilli = 0, placementStatus = 'unplaced' WHERE uuid = new.uuid;
END;
""".trimIndent(),
    "point_measurement_tombstone" to """
CREATE TRIGGER point_measurement_tombstone AFTER UPDATE OF deletedAt ON room_measurements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE measurement_elements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE measurementUuid = old.uuid AND deletedAt IS NULL;
END;
""".trimIndent(),
    "point_project_tombstone" to """
CREATE TRIGGER point_project_tombstone AFTER UPDATE OF deletedAt ON projects
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE measurement_elements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE measurementUuid IN (SELECT uuid FROM room_measurements WHERE projectId = old.id) AND deletedAt IS NULL;
END;
""".trimIndent(),
    "point_contour_guard" to """
CREATE TRIGGER point_contour_guard BEFORE UPDATE ON room_measurements
WHEN (new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape
    OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters
    OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters
    OR new.uuid IS NOT old.uuid OR new.projectId IS NOT old.projectId)
    AND EXISTS (SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid = q.elementUuid
        WHERE e.measurementUuid = old.uuid AND q.deletedAt IS NULL)
BEGIN
    SELECT RAISE(ABORT, 'measurement_contour_has_points');
END;
""".trimIndent()
)

internal val pointV9SchemaDefinitions = mapOf(
    "point_placements" to """
CREATE TABLE point_placements (
    uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid)) > 0),
    elementUuid TEXT NOT NULL REFERENCES measurement_elements(uuid),
    xMm INTEGER NOT NULL CHECK(typeof(xMm) = 'integer' AND xMm BETWEEN -1000000 AND 1000000),
    yMm INTEGER NOT NULL CHECK(typeof(yMm) = 'integer' AND yMm BETWEEN -1000000 AND 1000000),
    contourUpdatedAt INTEGER NOT NULL CHECK(typeof(contourUpdatedAt) = 'integer' AND contourUpdatedAt >= 0),
    createdAt INTEGER NOT NULL CHECK(typeof(createdAt) = 'integer' AND createdAt >= 0),
    updatedAt INTEGER NOT NULL CHECK(typeof(updatedAt) = 'integer' AND updatedAt >= createdAt),
    deletedAt INTEGER CHECK(deletedAt IS NULL OR (typeof(deletedAt) = 'integer' AND deletedAt = updatedAt)),
    precision TEXT NOT NULL DEFAULT 'approximate' CHECK(precision IN ('approximate','exact')),
    lastMoveUuid TEXT REFERENCES point_position_operations(uuid)
);
""".trimIndent(),
    "index_point_placements_element_active_order" to """
CREATE INDEX index_point_placements_element_active_order ON point_placements(elementUuid, deletedAt, createdAt, uuid);
""".trimIndent(),
    "point_insert_guard" to """
CREATE TRIGGER point_insert_guard BEFORE INSERT ON point_placements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NOT NULL OR new.precision != 'approximate' OR new.lastMoveUuid IS NOT NULL OR NOT EXISTS (
        SELECT 1 FROM measurement_elements e JOIN room_measurements m ON m.uuid = e.measurementUuid
        JOIN projects p ON p.id = m.projectId
        WHERE e.uuid = new.elementUuid AND e.deletedAt IS NULL AND m.deletedAt IS NULL AND p.deletedAt IS NULL
        AND e.kind = 'catalog_item' AND e.placementKind = 'point' AND e.unit = 'шт.'
        AND e.quantityKind = 'exact' AND e.quantityMilli % 1000 = 0
        AND e.updatedAt < 9223372036854775807 AND m.updatedAt = new.contourUpdatedAt
        AND (SELECT count(*) FROM point_placements q WHERE q.elementUuid = e.uuid AND q.deletedAt IS NULL) < e.quantityMilli / 1000
    ) THEN RAISE(ABORT, 'point_parent_capacity_or_contour') END;
END;
""".trimIndent(),
    "point_identity_guard" to """
CREATE TRIGGER point_identity_guard BEFORE UPDATE ON point_placements
BEGIN
    SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.elementUuid IS NOT old.elementUuid
        OR new.contourUpdatedAt IS NOT old.contourUpdatedAt OR new.createdAt IS NOT old.createdAt
        OR old.deletedAt IS NOT NULL OR new.updatedAt <= old.updatedAt
        THEN RAISE(ABORT, 'point_immutable_or_stale') END;
    SELECT CASE WHEN new.deletedAt IS NOT NULL AND (new.xMm != old.xMm OR new.yMm != old.yMm
        OR new.precision != old.precision OR new.lastMoveUuid IS NOT old.lastMoveUuid)
        THEN RAISE(ABORT, 'point_delete_cannot_move') END;
    SELECT CASE WHEN new.deletedAt IS NULL AND (new.lastMoveUuid IS old.lastMoveUuid OR NOT EXISTS (
        SELECT 1 FROM point_position_operations o JOIN measurement_elements e ON e.uuid = old.elementUuid
        JOIN room_measurements m ON m.uuid = e.measurementUuid JOIN projects p ON p.id = m.projectId
        WHERE o.uuid = new.lastMoveUuid AND o.pointUuid = old.uuid AND o.expectedPointUpdatedAt = old.updatedAt
        AND o.expectedElementUpdatedAt = e.updatedAt AND o.contourUpdatedAt = m.updatedAt
        AND o.xMm = new.xMm AND o.yMm = new.yMm AND o.precision = new.precision
        AND new.updatedAt = max(old.updatedAt + 1, o.createdAt)
        AND e.deletedAt IS NULL AND m.deletedAt IS NULL AND p.deletedAt IS NULL
        AND e.updatedAt < 9223372036854775807 AND e.kind = 'catalog_item'
        AND e.placementKind = 'point' AND e.unit = 'шт.' AND e.quantityKind = 'exact'
        AND e.placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements q WHERE q.elementUuid = e.uuid AND q.deletedAt IS NULL)
        AND e.placedQuantityMilli <= e.quantityMilli
    )) THEN RAISE(ABORT, 'point_move_requires_cas_receipt') END;
END;
""".trimIndent(),
    "point_delete_guard" to """
CREATE TRIGGER point_delete_guard BEFORE DELETE ON point_placements
BEGIN
    SELECT RAISE(ABORT, 'point_requires_tombstone');
END;
""".trimIndent(),
    "point_insert_summary" to """
CREATE TRIGGER point_insert_summary AFTER INSERT ON point_placements
BEGIN
    UPDATE measurement_elements SET
        placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL),
        placementStatus = CASE WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL) THEN 'unplaced' WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL AND precision != 'exact') THEN 'exact' ELSE 'approximate' END,
        updatedAt = CASE WHEN deletedAt IS NULL THEN max(updatedAt + 1, new.updatedAt) ELSE updatedAt END
    WHERE uuid = new.elementUuid;
END;
""".trimIndent(),
    "point_remove_summary" to """
CREATE TRIGGER point_remove_summary AFTER UPDATE OF deletedAt ON point_placements
BEGIN
    UPDATE measurement_elements SET
        placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL),
        placementStatus = CASE WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL) THEN 'unplaced' WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL AND precision != 'exact') THEN 'exact' ELSE 'approximate' END,
        updatedAt = CASE WHEN deletedAt IS NULL THEN max(updatedAt + 1, new.updatedAt) ELSE updatedAt END
    WHERE uuid = new.elementUuid;
END;
""".trimIndent(),
    "point_element_insert_guard" to """
CREATE TRIGGER point_element_insert_guard BEFORE INSERT ON measurement_elements
WHEN new.placedQuantityMilli != 0 OR new.placementStatus != 'unplaced'
BEGIN
    SELECT RAISE(ABORT, 'point_summary_requires_records');
END;
""".trimIndent(),
    "point_element_update_guard" to """
CREATE TRIGGER point_element_update_guard BEFORE UPDATE ON measurement_elements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NULL AND (
        new.placedQuantityMilli != (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL)
        OR new.placementStatus != CASE WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL) THEN 'unplaced' WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL AND precision != 'exact') THEN 'exact' ELSE 'approximate' END
    ) THEN RAISE(ABORT, 'point_summary_requires_records') END;
    SELECT CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL)
        AND new.deletedAt IS NULL AND (new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid
            OR new.kind != 'catalog_item' OR new.placementKind IS NOT 'point' OR new.unit IS NOT 'шт.'
            OR new.quantityKind != 'exact' OR new.quantityMilli IS NULL
            OR new.quantityMilli < (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL))
        THEN RAISE(ABORT, 'point_quantity_in_use') END;
END;
""".trimIndent(),
    "point_element_tombstone" to """
CREATE TRIGGER point_element_tombstone AFTER UPDATE OF deletedAt ON measurement_elements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE point_placements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE elementUuid = old.uuid AND deletedAt IS NULL;
    UPDATE measurement_elements SET placedQuantityMilli = 0, placementStatus = 'unplaced' WHERE uuid = new.uuid;
END;
""".trimIndent(),
    "point_measurement_tombstone" to """
CREATE TRIGGER point_measurement_tombstone AFTER UPDATE OF deletedAt ON room_measurements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE measurement_elements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE measurementUuid = old.uuid AND deletedAt IS NULL;
END;
""".trimIndent(),
    "point_project_tombstone" to """
CREATE TRIGGER point_project_tombstone AFTER UPDATE OF deletedAt ON projects
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL
BEGIN
    UPDATE measurement_elements SET updatedAt = max(updatedAt + 1, new.updatedAt), deletedAt = max(updatedAt + 1, new.updatedAt)
    WHERE measurementUuid IN (SELECT uuid FROM room_measurements WHERE projectId = old.id) AND deletedAt IS NULL;
END;
""".trimIndent(),
    "point_contour_guard" to """
CREATE TRIGGER point_contour_guard BEFORE UPDATE ON room_measurements
WHEN (new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape
    OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters
    OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters
    OR new.uuid IS NOT old.uuid OR new.projectId IS NOT old.projectId)
    AND EXISTS (SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid = q.elementUuid
        WHERE e.measurementUuid = old.uuid AND q.deletedAt IS NULL)
BEGIN
    SELECT RAISE(ABORT, 'measurement_contour_has_points');
END;
""".trimIndent(),
    "point_position_operations" to """
CREATE TABLE point_position_operations (
    uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid)) > 0),
    pointUuid TEXT NOT NULL REFERENCES point_placements(uuid),
    expectedPointUpdatedAt INTEGER NOT NULL CHECK(typeof(expectedPointUpdatedAt) = 'integer' AND expectedPointUpdatedAt >= 0),
    expectedElementUpdatedAt INTEGER NOT NULL CHECK(typeof(expectedElementUpdatedAt) = 'integer' AND expectedElementUpdatedAt >= 0),
    contourUpdatedAt INTEGER NOT NULL CHECK(typeof(contourUpdatedAt) = 'integer' AND contourUpdatedAt >= 0),
    xMm INTEGER NOT NULL CHECK(typeof(xMm) = 'integer' AND xMm BETWEEN -1000000 AND 1000000),
    yMm INTEGER NOT NULL CHECK(typeof(yMm) = 'integer' AND yMm BETWEEN -1000000 AND 1000000),
    precision TEXT NOT NULL CHECK(precision IN ('approximate','exact')),
    createdAt INTEGER NOT NULL CHECK(typeof(createdAt) = 'integer' AND createdAt >= 0 AND createdAt < 9223372036854775807)
);
""".trimIndent(),
    "index_point_position_operations_point" to """
CREATE INDEX index_point_position_operations_point ON point_position_operations(pointUuid, createdAt, uuid);
""".trimIndent(),
    "point_move_summary" to """
CREATE TRIGGER point_move_summary AFTER UPDATE OF xMm, yMm, precision, lastMoveUuid ON point_placements
BEGIN
    UPDATE measurement_elements SET
        placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL),
        placementStatus = CASE WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL) THEN 'unplaced' WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = new.elementUuid AND deletedAt IS NULL AND precision != 'exact') THEN 'exact' ELSE 'approximate' END,
        updatedAt = CASE WHEN deletedAt IS NULL THEN max(updatedAt + 1, new.updatedAt) ELSE updatedAt END
    WHERE uuid = new.elementUuid;
END;
""".trimIndent(),
    "point_operation_update_guard" to """
CREATE TRIGGER point_operation_update_guard BEFORE UPDATE ON point_position_operations
BEGIN
    SELECT RAISE(ABORT, 'point_operation_immutable');
END;
""".trimIndent(),
    "point_operation_delete_guard" to """
CREATE TRIGGER point_operation_delete_guard BEFORE DELETE ON point_position_operations
BEGIN
    SELECT RAISE(ABORT, 'point_operation_immutable');
END;
""".trimIndent(),
    "point_operation_insert_guard" to """
CREATE TRIGGER point_operation_insert_guard BEFORE INSERT ON point_position_operations
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid = q.elementUuid
        JOIN room_measurements m ON m.uuid = e.measurementUuid JOIN projects p ON p.id = m.projectId
        WHERE q.uuid = new.pointUuid AND q.updatedAt = new.expectedPointUpdatedAt
        AND q.deletedAt IS NULL AND q.updatedAt < 9223372036854775807
        AND e.updatedAt = new.expectedElementUpdatedAt AND e.updatedAt < 9223372036854775807
        AND m.updatedAt = new.contourUpdatedAt
        AND e.deletedAt IS NULL AND m.deletedAt IS NULL AND p.deletedAt IS NULL
        AND e.kind = 'catalog_item' AND e.placementKind = 'point' AND e.unit = 'шт.' AND e.quantityKind = 'exact'
        AND e.placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements a WHERE a.elementUuid = e.uuid AND a.deletedAt IS NULL)
        AND e.placedQuantityMilli <= e.quantityMilli AND (
            WITH RECURSIVE edge_index(i) AS (
                SELECT 0 UNION ALL SELECT i + 1 FROM edge_index
                WHERE i + 1 < json_array_length(m.drawingJson, '$.vertices') AND i < 127
            ), edges AS (
                SELECT json_extract(m.drawingJson, '$.vertices[' || i || '].xMm') AS ax,
                    json_extract(m.drawingJson, '$.vertices[' || i || '].yMm') AS ay,
                    json_extract(m.drawingJson, '$.vertices[' || ((i + 1) % json_array_length(m.drawingJson, '$.vertices')) || '].xMm') AS bx,
                    json_extract(m.drawingJson, '$.vertices[' || ((i + 1) % json_array_length(m.drawingJson, '$.vertices')) || '].yMm') AS by
                FROM edge_index
            )
            SELECT CASE WHEN count(*) BETWEEN 3 AND 128
                AND json_array_length(m.drawingJson, '$.vertices') = count(*)
                AND sum(CASE WHEN typeof(ax) = 'integer' AND typeof(ay) = 'integer'
                    AND ax BETWEEN -1000000 AND 1000000 AND ay BETWEEN -1000000 AND 1000000 THEN 0 ELSE 1 END) = 0
                AND sum(CASE WHEN ((bx - ax) * (new.yMm - ay) - (by - ay) * (new.xMm - ax)) = 0 AND new.xMm BETWEEN min(ax,bx) AND max(ax,bx)
                    AND new.yMm BETWEEN min(ay,by) AND max(ay,by) THEN 1 ELSE 0 END) = 0
                AND sum(CASE WHEN (ay > new.yMm) != (by > new.yMm)
                    AND (((bx - ax) * (new.yMm - ay) - (by - ay) * (new.xMm - ax)) > 0) = (by > ay) THEN 1 ELSE 0 END) % 2 = 1
                THEN 1 ELSE 0 END FROM edges
        ) = 1
    ) THEN RAISE(ABORT, 'point_move_invalid_parent_version_or_interior') END;
END;
""".trimIndent()
)
