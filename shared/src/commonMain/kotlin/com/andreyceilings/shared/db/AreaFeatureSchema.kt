package com.andreyceilings.shared.db

/** Complete v13 fingerprints. Historical definitions are retained verbatim. */
internal val pointV13SchemaDefinitions = pointV12SchemaDefinitions + mapOf(
    "area_features" to """
CREATE TABLE area_features (
 uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid))>0),
 measurementUuid TEXT NOT NULL REFERENCES room_measurements(uuid),
 elementUuid TEXT REFERENCES measurement_elements(uuid),
 role TEXT NOT NULL CHECK(role IN ('GENERAL_AREA','OBSTACLE','VOID_MARKER')),
 shapeKind TEXT NOT NULL CHECK(shapeKind IN ('POLYGON','CIRCLE')),
 positionsJson TEXT NOT NULL CHECK(json_valid(positionsJson) AND json_type(positionsJson)='array'),
 radiusMm INTEGER CHECK(radiusMm IS NULL OR (typeof(radiusMm)='integer' AND radiusMm BETWEEN 1 AND 1000000)),
 twiceAreaMm2 INTEGER NOT NULL CHECK(typeof(twiceAreaMm2)='integer' AND twiceAreaMm2 BETWEEN 1 AND 8000000000000),
 mountingsJson TEXT NOT NULL CHECK(json_valid(mountingsJson) AND json_type(mountingsJson)='array'),
 radiusMeasured INTEGER NOT NULL CHECK(radiusMeasured IN (0,1)),
 needsReview INTEGER NOT NULL CHECK(needsReview IN (0,1)),
 unresolved INTEGER NOT NULL CHECK(unresolved IN (0,1) AND (unresolved=0 OR needsReview=1)),
 note TEXT NOT NULL,
 contourUpdatedAt INTEGER NOT NULL CHECK(typeof(contourUpdatedAt)='integer' AND contourUpdatedAt>=0),
 createdAt INTEGER NOT NULL CHECK(typeof(createdAt)='integer' AND createdAt>=0),
 updatedAt INTEGER NOT NULL CHECK(typeof(updatedAt)='integer' AND updatedAt>=createdAt),
 deletedAt INTEGER CHECK(deletedAt IS NULL OR (typeof(deletedAt)='integer' AND deletedAt=updatedAt)),
 lastCeilingEditUuid TEXT NOT NULL REFERENCES ceiling_operations(uuid)
);
""".trimIndent(),
    "index_area_features_measurement" to """
CREATE INDEX index_area_features_measurement ON area_features(measurementUuid,deletedAt,createdAt,uuid);
""".trimIndent(),
    "index_area_features_element" to """
CREATE INDEX index_area_features_element ON area_features(elementUuid,deletedAt);
""".trimIndent(),
    "ceiling_operation_areas" to """
CREATE VIEW ceiling_operation_areas AS SELECT o.uuid AS operationUuid,json_extract(p.value,'$.uuid') AS areaUuid,
json_extract(p.value,'$.elementUuid') AS elementUuid,
json_extract(p.value,'$.role') AS role,
json_extract(p.value,'$.shapeKind') AS shapeKind,
json_extract(p.value,'$.positionsJson') AS positionsJson,
json_extract(p.value,'$.radiusMm') AS radiusMm,
json_extract(p.value,'$.twiceAreaMm2') AS twiceAreaMm2,
json_extract(p.value,'$.mountingsJson') AS mountingsJson,
json_extract(p.value,'$.radiusMeasured') AS radiusMeasured,
json_extract(p.value,'$.needsReview') AS needsReview,
json_extract(p.value,'$.unresolved') AS unresolved,
json_extract(p.value,'$.note') AS note,
json_extract(p.value,'$.expectedUpdatedAt') AS expectedUpdatedAt,
json_extract(p.value,'$.expectedElementUpdatedAt') AS expectedElementUpdatedAt,
json_extract(p.value,'$.remeasured') AS remeasured,
json_extract(p.value,'$.remeasuredRadius') AS remeasuredRadius,
json_extract(p.value,'$.write') AS shouldWrite,json_extract(p.value,'$.deleted') AS deleted,json_extract(p.value,'$.confirm') AS confirmed FROM ceiling_operations o,json_each(o.payload,'$.areas') p;
""".trimIndent(),
    "ceiling_area_positions" to """
CREATE VIEW ceiling_area_positions AS SELECT a.operationUuid,a.areaUuid,a.areaUuid || ':' || v.key AS pointUuid,
 CAST(v.key AS INTEGER) AS ordinal,json_extract(v.value,'$.xMm') AS xMm,json_extract(v.value,'$.yMm') AS yMm,
 json_extract(a.mountingsJson,'$[' || v.key || ']') AS mountingJson,
 a.unresolved,a.needsReview,a.shouldWrite,a.deleted,json_type(a.mountingsJson,'$[' || v.key || ']') AS mountingType,
 json_extract(q.mountingsJson,'$[' || v.key || ']') AS oldMountingJson
 FROM ceiling_operation_areas a,json_each(a.positionsJson) v LEFT JOIN area_features q ON q.uuid=a.areaUuid;
""".trimIndent(),
    "ceiling_area_edges" to """
CREATE VIEW ceiling_area_edges AS SELECT p.operationUuid,p.areaUuid,p.ordinal,p.xMm AS ax,p.yMm AS ay,n.xMm AS bx,n.yMm AS by
 FROM ceiling_area_positions p JOIN ceiling_operation_areas a ON a.operationUuid=p.operationUuid AND a.areaUuid=p.areaUuid
 JOIN ceiling_area_positions n ON n.operationUuid=p.operationUuid AND n.areaUuid=p.areaUuid AND n.ordinal=(p.ordinal+1)%json_array_length(a.positionsJson)
 WHERE a.shapeKind='POLYGON';
""".trimIndent(),
    "ceiling_area_feet" to """
CREATE VIEW ceiling_area_feet AS
 SELECT p.operationUuid,p.pointUuid,w.ordinal,p.xMm AS px,p.yMm AS py,
 w.ax+(w.bx-w.ax)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fx,
 w.ay+(w.by-w.ay)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fy
 FROM ceiling_area_positions p JOIN ceiling_operation_edges w ON w.operationUuid=p.operationUuid
 AND (w.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') OR w.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall'));
""".trimIndent(),
    "ceiling_area_rays" to """
CREATE VIEW ceiling_area_rays AS
 SELECT f.operationUuid,f.pointUuid,(f.fx-f.px) AS rx,(f.fy-f.py) AS ry,
 (f.fx-f.px)*(f.fx-f.px)+(f.fy-f.py)*(f.fy-f.py) AS r2,
 (f.fx-f.px)*(w.by-w.ay)-(f.fy-f.py)*(w.bx-w.ax) AS den,
 (w.ax-f.px)*(w.by-w.ay)-(w.ay-f.py)*(w.bx-w.ax) AS t,
 (w.ax-f.px)*(f.fy-f.py)-(w.ay-f.py)*(f.fx-f.px) AS u,
 (w.ax-f.px)*(f.fx-f.px)+(w.ay-f.py)*(f.fy-f.py) AS da,
 (w.bx-f.px)*(f.fx-f.px)+(w.by-f.py)*(f.fy-f.py) AS db
 FROM ceiling_area_feet f JOIN ceiling_operation_edges w ON w.operationUuid=f.operationUuid AND w.ordinal!=f.ordinal;
""".trimIndent(),
    "ceiling_area_hidden_offsets" to """
CREATE VIEW ceiling_area_hidden_offsets AS SELECT DISTINCT operationUuid,pointUuid FROM ceiling_area_rays
 WHERE (abs(den)>0.0000001 AND t/den>0.00000001 AND t/den<0.99999999 AND u/den>=-0.00000001 AND u/den<=1.00000001)
 OR (abs(den)<=0.0000001 AND abs(u)<=0.0000001 AND max(0.0,min(da,db))<min(r2,max(da,db))-0.0000001);
""".trimIndent(),
    "ceiling_area_mounting_fields" to """
CREATE VIEW ceiling_area_mounting_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_area_positions p,json_each(p.mountingJson) k;
""".trimIndent(),
    "ceiling_area_anchor_fields" to """
CREATE VIEW ceiling_area_anchor_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_area_positions p,json_each(p.mountingJson,'$.anchor') k;
""".trimIndent(),
    "ceiling_declared_areas" to """
CREATE VIEW ceiling_declared_areas AS SELECT o.uuid AS operationUuid,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType FROM ceiling_operations o,json_each(o.payload,'$.declaredAreas') k;
""".trimIndent(),
    "ceiling_area_position_fields" to """
CREATE VIEW ceiling_area_position_fields AS SELECT a.operationUuid,a.areaUuid,CAST(v.key AS INTEGER) AS ordinal,k.key AS fieldName,k.type AS fieldType FROM ceiling_operation_areas a,json_each(a.positionsJson) v,json_each(v.value) k;
""".trimIndent(),
    "ceiling_area_remeasurements" to """
CREATE VIEW ceiling_area_remeasurements AS SELECT a.operationUuid,a.areaUuid,r.value AS ordinal,r.type AS valueType FROM ceiling_operation_areas a,json_each(a.remeasured) r;
""".trimIndent(),
    "area_feature_mountings" to """
CREATE VIEW area_feature_mountings AS SELECT a.uuid AS featureUuid,m.value AS mountingJson FROM area_features a,json_each(a.mountingsJson) m;
""".trimIndent(),
    "ceiling_area_invalid_positions" to """
CREATE VIEW ceiling_area_invalid_positions AS SELECT a.operationUuid,a.areaUuid FROM ceiling_operation_areas a,json_each(a.positionsJson) v WHERE v.type!='object' OR json_type(v.value,'$.xMm') IS NOT 'integer' OR json_type(v.value,'$.yMm') IS NOT 'integer' UNION SELECT operationUuid,areaUuid FROM ceiling_area_position_fields GROUP BY operationUuid,areaUuid,ordinal HAVING count(*)!=2 OR count(DISTINCT fieldName)!=2 OR sum(CASE WHEN fieldName NOT IN ('xMm','yMm') THEN 1 ELSE 0 END)>0;
""".trimIndent(),
    "ceiling_area_totals" to """
CREATE VIEW ceiling_area_totals AS SELECT a.operationUuid,a.elementUuid,
 CASE WHEN e.unit='шт.' THEN sum(CASE WHEN a.deleted=0 AND a.unresolved=0 THEN 1000 ELSE 0 END)
 ELSE (sum(CASE WHEN a.deleted=0 AND a.unresolved=0 THEN a.twiceAreaMm2 ELSE 0 END)+1000)/2000 END AS validMilli,
 CASE WHEN e.unit='шт.' THEN sum(CASE WHEN a.deleted=0 THEN 1000 ELSE 0 END)
 ELSE (sum(CASE WHEN a.deleted=0 THEN a.twiceAreaMm2 ELSE 0 END)+1000)/2000 END AS reservedMilli
 FROM ceiling_operation_areas a JOIN measurement_elements e ON e.uuid=a.elementUuid GROUP BY a.operationUuid,a.elementUuid;
""".trimIndent(),
    "area_element_totals" to """
CREATE VIEW area_element_totals AS SELECT e.uuid AS elementUuid,
 CASE WHEN e.unit='шт.' THEN (SELECT count(*)*1000 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0)
 ELSE ((SELECT coalesce(sum(twiceAreaMm2),0) FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0)+1000)/2000 END AS validMilli,
 CASE WHEN e.unit='шт.' THEN (SELECT count(*)*1000 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL)
 ELSE ((SELECT coalesce(sum(twiceAreaMm2),0) FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL)+1000)/2000 END AS reservedMilli,
 CASE WHEN NOT EXISTS(SELECT 1 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0) THEN 'unplaced'
 WHEN EXISTS(SELECT 1 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0 AND
 ((a.shapeKind='CIRCLE' AND a.radiusMeasured=0) OR EXISTS(SELECT 1 FROM area_feature_mountings m WHERE m.featureUuid=a.uuid AND json_extract(m.mountingJson,'$.anchor') IS NULL))) THEN 'approximate' ELSE 'exact' END AS placementStatus
 FROM measurement_elements e WHERE e.placementKind='area';
""".trimIndent(),
    "area_insert_guard" to """
CREATE TRIGGER area_insert_guard BEFORE INSERT ON area_features BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_areas a ON a.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid JOIN projects pr ON pr.id=m.projectId
 WHERE a.areaUuid=new.uuid AND o.uuid=new.lastCeilingEditUuid AND new.measurementUuid=o.measurementUuid
 AND m.deletedAt IS NULL AND pr.deletedAt IS NULL AND new.deletedAt IS NULL AND a.deleted=0 AND a.shouldWrite=1 AND a.expectedUpdatedAt IS NULL
 AND a.elementUuid IS new.elementUuid AND a.role IS new.role AND a.shapeKind IS new.shapeKind AND a.positionsJson IS new.positionsJson AND a.radiusMm IS new.radiusMm AND a.twiceAreaMm2 IS new.twiceAreaMm2 AND a.mountingsJson IS new.mountingsJson AND a.radiusMeasured IS new.radiusMeasured AND a.needsReview IS new.needsReview AND a.unresolved IS new.unresolved AND a.note IS new.note AND new.createdAt=o.createdAt AND new.updatedAt=o.createdAt AND new.contourUpdatedAt=m.updatedAt
 AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid))
 THEN RAISE(ABORT,'area_requires_atomic_operation') END;
END;
""".trimIndent(),
    "area_update_guard" to """
CREATE TRIGGER area_update_guard BEFORE UPDATE ON area_features BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR new.elementUuid IS NOT old.elementUuid
 OR new.createdAt IS NOT old.createdAt OR old.deletedAt IS NOT NULL OR new.updatedAt<=old.updatedAt THEN RAISE(ABORT,'area_identity_or_stale') END;
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_areas a ON a.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid WHERE o.uuid=new.lastCeilingEditUuid AND a.areaUuid=old.uuid AND o.measurementUuid=old.measurementUuid
 AND a.expectedUpdatedAt=old.updatedAt AND a.shouldWrite=1 AND a.elementUuid IS new.elementUuid AND a.role IS new.role AND a.shapeKind IS new.shapeKind AND a.positionsJson IS new.positionsJson AND a.radiusMm IS new.radiusMm AND a.twiceAreaMm2 IS new.twiceAreaMm2 AND a.mountingsJson IS new.mountingsJson AND a.radiusMeasured IS new.radiusMeasured AND a.needsReview IS new.needsReview AND a.unresolved IS new.unresolved AND a.note IS new.note
 AND new.deletedAt IS CASE WHEN a.deleted=1 THEN new.updatedAt ELSE NULL END
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.contourUpdatedAt=m.updatedAt AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid))
 AND ((EXISTS(SELECT 1 FROM room_measurements WHERE uuid=old.measurementUuid AND deletedAt IS NOT NULL AND new.updatedAt=max(old.updatedAt+1,updatedAt))
 OR EXISTS(SELECT 1 FROM measurement_elements WHERE uuid=old.elementUuid AND deletedAt IS NOT NULL AND new.updatedAt=max(old.updatedAt+1,updatedAt)))
 AND new.deletedAt=new.updatedAt AND new.contourUpdatedAt=old.contourUpdatedAt AND new.lastCeilingEditUuid=old.lastCeilingEditUuid AND new.elementUuid IS old.elementUuid AND new.role IS old.role AND new.shapeKind IS old.shapeKind AND new.positionsJson IS old.positionsJson AND new.radiusMm IS old.radiusMm AND new.twiceAreaMm2 IS old.twiceAreaMm2 AND new.mountingsJson IS old.mountingsJson AND new.radiusMeasured IS old.radiusMeasured AND new.needsReview IS old.needsReview AND new.unresolved IS old.unresolved AND new.note IS old.note)=0
 THEN RAISE(ABORT,'area_requires_atomic_operation') END;
END;
""".trimIndent(),
    "area_delete_guard" to """
CREATE TRIGGER area_delete_guard BEFORE DELETE ON area_features BEGIN SELECT RAISE(ABORT,'area_requires_tombstone'); END;
""".trimIndent(),
    "area_element_guard" to """
CREATE TRIGGER area_element_guard BEFORE UPDATE ON measurement_elements BEGIN
 SELECT CASE WHEN new.deletedAt IS NULL AND new.placementKind='area' AND (old.placementKind='area' OR new.placedQuantityMilli!=0 OR new.placementStatus!='unplaced') AND NOT EXISTS(SELECT 1 FROM area_element_totals t
 WHERE t.elementUuid=old.uuid AND t.validMilli=new.placedQuantityMilli AND t.placementStatus=new.placementStatus)
 THEN RAISE(ABORT,'area_summary_requires_records') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM area_features WHERE elementUuid=old.uuid AND deletedAt IS NULL) AND new.deletedAt IS NULL AND
 (new.kind IS NOT 'catalog_item' OR new.placementKind IS NOT 'area' OR new.quantityMode IS NOT 'manual' OR new.unit NOT IN ('м²','шт.')
 OR new.quantityKind NOT IN ('unknown','exact','range') OR (new.quantityKind IN ('exact','range') AND
 CASE WHEN new.quantityKind='exact' THEN new.quantityMilli ELSE new.quantityMaxMilli END < (SELECT reservedMilli FROM area_element_totals WHERE elementUuid=old.uuid)))
 THEN RAISE(ABORT,'area_quantity_in_use') END;
END;
""".trimIndent(),
    "area_measurement_elements_delete_guard" to """
CREATE TRIGGER area_measurement_elements_delete_guard BEFORE DELETE ON measurement_elements WHEN EXISTS(SELECT 1 FROM area_features WHERE elementUuid=old.uuid) BEGIN SELECT RAISE(ABORT,'area_parent_requires_tombstone'); END;
""".trimIndent(),
    "area_measurement_elements_identity" to """
CREATE TRIGGER area_measurement_elements_identity BEFORE UPDATE ON measurement_elements WHEN (new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR new.kind IS NOT old.kind OR new.priceItemUuid IS NOT old.priceItemUuid OR new.priceItemCode IS NOT old.priceItemCode OR new.label IS NOT old.label OR new.unit IS NOT old.unit OR new.quantityMode IS NOT old.quantityMode OR new.placementKind IS NOT old.placementKind OR new.createdAt IS NOT old.createdAt) AND EXISTS(SELECT 1 FROM area_features WHERE elementUuid=old.uuid) BEGIN SELECT RAISE(ABORT,'area_parent_identity_immutable'); END;
""".trimIndent(),
    "area_room_measurements_delete_guard" to """
CREATE TRIGGER area_room_measurements_delete_guard BEFORE DELETE ON room_measurements WHEN EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid) BEGIN SELECT RAISE(ABORT,'area_parent_requires_tombstone'); END;
""".trimIndent(),
    "area_room_measurements_identity" to """
CREATE TRIGGER area_room_measurements_identity BEFORE UPDATE ON room_measurements WHEN (new.uuid IS NOT old.uuid OR new.projectId IS NOT old.projectId OR new.id IS NOT old.id) AND EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid) BEGIN SELECT RAISE(ABORT,'area_parent_identity_immutable'); END;
""".trimIndent(),
    "area_projects_delete_guard" to """
CREATE TRIGGER area_projects_delete_guard BEFORE DELETE ON projects WHEN EXISTS(SELECT 1 FROM area_features WHERE measurementUuid IN (SELECT uuid FROM room_measurements WHERE projectId=old.id)) BEGIN SELECT RAISE(ABORT,'area_parent_requires_tombstone'); END;
""".trimIndent(),
    "area_projects_identity" to """
CREATE TRIGGER area_projects_identity BEFORE UPDATE ON projects WHEN (new.id IS NOT old.id) AND EXISTS(SELECT 1 FROM area_features WHERE measurementUuid IN (SELECT uuid FROM room_measurements WHERE projectId=old.id)) BEGIN SELECT RAISE(ABORT,'area_parent_identity_immutable'); END;
""".trimIndent(),
    "area_measurement_elements_tombstone" to """
CREATE TRIGGER area_measurement_elements_tombstone AFTER UPDATE OF deletedAt ON measurement_elements WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL BEGIN
 UPDATE area_features SET updatedAt=max(updatedAt+1,new.updatedAt),deletedAt=max(updatedAt+1,new.updatedAt) WHERE elementUuid=new.uuid AND deletedAt IS NULL;
END;
""".trimIndent(),
    "area_room_measurements_tombstone" to """
CREATE TRIGGER area_room_measurements_tombstone AFTER UPDATE OF deletedAt ON room_measurements WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL BEGIN
 UPDATE area_features SET updatedAt=max(updatedAt+1,new.updatedAt),deletedAt=max(updatedAt+1,new.updatedAt) WHERE measurementUuid=new.uuid AND deletedAt IS NULL;
END;
""".trimIndent(),
    "point_element_update_guard" to """
CREATE TRIGGER point_element_update_guard BEFORE UPDATE ON measurement_elements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NULL AND coalesce(new.placementKind,'none') NOT IN ('line','area') AND (
        new.placedQuantityMilli != (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0)
        OR new.placementStatus != CASE WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0) THEN 'unplaced' WHEN NOT EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0 AND precision != 'exact') THEN 'exact' ELSE 'approximate' END
    ) THEN RAISE(ABORT, 'point_summary_requires_records') END;
    SELECT CASE WHEN EXISTS (SELECT 1 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL)
        AND new.deletedAt IS NULL AND (new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid
            OR new.kind != 'catalog_item' OR new.placementKind IS NOT 'point' OR new.unit IS NOT 'шт.'
            OR new.quantityKind != 'exact' OR new.quantityMilli IS NULL
            OR new.quantityMilli < (SELECT count(*) * 1000 FROM point_placements WHERE elementUuid = old.uuid AND deletedAt IS NULL))
        THEN RAISE(ABORT, 'point_quantity_in_use') END;
END;
""".trimIndent(),
    "point_contour_guard" to """
CREATE TRIGGER point_contour_guard BEFORE UPDATE ON room_measurements
WHEN ((new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters
 OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters)
 AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL)))

BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=old.uuid AND o.expectedUpdatedAt=old.updatedAt AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson')
 AND new.shape=json_extract(o.payload,'$.shape') AND new.lengthMeters=json_extract(o.payload,'$.length')
 AND new.widthMeters=json_extract(o.payload,'$.width') AND new.areaSquareMeters=json_extract(o.payload,'$.area')
 AND new.perimeterMeters=json_extract(o.payload,'$.perimeter') AND new.name=json_extract(o.payload,'$.name') AND new.notes=json_extract(o.payload,'$.notes'))
 THEN RAISE(ABORT,'ceiling_requires_atomic_operation') END;
END;
""".trimIndent(),
    "ceiling_wall_update_guard" to """
CREATE TRIGGER ceiling_wall_update_guard BEFORE UPDATE ON ceiling_walls
BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR (NOT EXISTS (
 SELECT 1 FROM room_measurements m JOIN ceiling_operations o ON o.measurementUuid=m.uuid
 WHERE m.uuid=old.measurementUuid AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid) AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND ((new.active=0 AND old.active=1 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND old.geometryVersion<m.updatedAt AND json_extract(o.payload,'$.drawingJson')!=json_extract(o.payload,'$.oldDrawingJson')) OR EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=o.uuid AND w.wallUuid=new.uuid
 AND new.ordinal=w.ordinal AND new.ax=w.ax AND new.ay=w.ay AND new.bx=w.bx AND new.by=w.by AND new.geometryVersion=m.updatedAt)))
 AND (new.active=0 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.measurementUuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.measurementUuid AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.measurementUuid AND q.deletedAt IS NULL))=0)=0)
 THEN RAISE(ABORT,'ceiling_wall_requires_operation') END;
END;
""".trimIndent(),
    "ceiling_unplaced_geometry_walls" to """
CREATE TRIGGER ceiling_unplaced_geometry_walls AFTER UPDATE OF drawingJson ON room_measurements
WHEN new.drawingJson IS NOT old.drawingJson AND NOT EXISTS(SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=new.uuid AND new.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson'))
 AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=new.uuid AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.uuid AND q.deletedAt IS NULL))=0
BEGIN
 UPDATE ceiling_walls SET active=0 WHERE measurementUuid=new.uuid AND active=1;
INSERT INTO ceiling_walls(uuid,measurementUuid,ordinal,ax,ay,bx,by,geometryVersion,active)
 SELECT lower(hex(randomblob(16))),m.uuid,CAST(v.key AS INTEGER),
 json_extract(v.value,'$.xMm'),json_extract(v.value,'$.yMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].xMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].yMm'),m.updatedAt,1
 FROM room_measurements m, json_each(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') v
 WHERE m.uuid=new.uuid AND json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.precisionStepMeters') IN (0.1,0.01,0.005,0.001)
 AND json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') BETWEEN 3 AND 128
 AND json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.geometrySchemaVersion') IN (2,3)
 AND json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.shape')=m.shape AND m.shape IN ('rectangle','l_shaped','polygon')
 AND typeof(m.updatedAt)='integer' AND m.updatedAt>=0
 AND json_type(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')='array'
 AND NOT EXISTS(SELECT 1 FROM room_measurements seed_source, json_each(CASE WHEN json_valid(seed_source.drawingJson) THEN seed_source.drawingJson ELSE '{}' END,'$.vertices') checked
 WHERE seed_source.uuid=m.uuid AND (checked.type!='object'
 OR json_type(CASE WHEN checked.type='object' THEN checked.value ELSE '{}' END,'$.xMm') IS NOT 'integer'
 OR json_type(CASE WHEN checked.type='object' THEN checked.value ELSE '{}' END,'$.yMm') IS NOT 'integer'
 OR json_extract(CASE WHEN checked.type='object' THEN checked.value ELSE '{}' END,'$.xMm') NOT BETWEEN -1000000 AND 1000000
 OR json_extract(CASE WHEN checked.type='object' THEN checked.value ELSE '{}' END,'$.yMm') NOT BETWEEN -1000000 AND 1000000));
END;
""".trimIndent(),
    "ceiling_measurement_version_guard" to """
CREATE TRIGGER ceiling_measurement_version_guard BEFORE UPDATE ON room_measurements
WHEN (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL))
 AND (typeof(new.updatedAt)!='integer' OR new.updatedAt<old.updatedAt OR ((new.name IS NOT old.name OR new.notes IS NOT old.notes OR new.deletedAt IS NOT old.deletedAt
 OR new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters) AND new.updatedAt<=old.updatedAt))
BEGIN SELECT RAISE(ABORT,'ceiling_version_must_increase'); END;
""".trimIndent(),
    "area_measurement_write_guard" to """
CREATE TRIGGER area_measurement_write_guard BEFORE UPDATE ON room_measurements
WHEN new.deletedAt IS NULL AND EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid AND deletedAt IS NULL)
 AND (new.updatedAt IS NOT old.updatedAt OR new.name IS NOT old.name OR new.notes IS NOT old.notes)
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=old.uuid AND o.expectedUpdatedAt=old.updatedAt
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.name=json_extract(o.payload,'$.name') AND new.notes=json_extract(o.payload,'$.notes')
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)) THEN RAISE(ABORT,'area_requires_complete_ceiling_operation') END;
END;
""".trimIndent(),
    "ceiling_operation_results" to """
CREATE TABLE ceiling_operation_results (
 operationUuid TEXT NOT NULL PRIMARY KEY REFERENCES ceiling_operations(uuid),
 measurementVersion INTEGER NOT NULL CHECK(typeof(measurementVersion)='integer' AND measurementVersion>=0),
 pointVersions TEXT NOT NULL CHECK(json_valid(pointVersions) AND json_type(pointVersions)='object'),
 elementVersions TEXT NOT NULL CHECK(json_valid(elementVersions) AND json_type(elementVersions)='object'),
 lineVersions TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(lineVersions) AND json_type(lineVersions)='object'),
 areaVersions TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(areaVersions) AND json_type(areaVersions)='object')
);
""".trimIndent(),
    "ceiling_area_circle_distances" to """
CREATE VIEW ceiling_area_circle_distances AS SELECT a.operationUuid,a.areaUuid,a.radiusMm,
 (w.bx-w.ax)*(w.bx-w.ax)+(w.by-w.ay)*(w.by-w.ay) AS length2,
 (p.xMm-w.ax)*(w.bx-w.ax)+(p.yMm-w.ay)*(w.by-w.ay) AS dot,
 (w.bx-w.ax)*(p.yMm-w.ay)-(w.by-w.ay)*(p.xMm-w.ax) AS cross,
 (p.xMm-w.ax)*(p.xMm-w.ax)+(p.yMm-w.ay)*(p.yMm-w.ay) AS da,
 (p.xMm-w.bx)*(p.xMm-w.bx)+(p.yMm-w.by)*(p.yMm-w.by) AS db
 FROM ceiling_operation_areas a JOIN ceiling_area_positions p ON p.operationUuid=a.operationUuid AND p.areaUuid=a.areaUuid
 JOIN ceiling_operation_edges w ON w.operationUuid=a.operationUuid WHERE a.shapeKind='CIRCLE' AND a.deleted=0 AND a.unresolved=0;
""".trimIndent(),
    "ceiling_operation_validate" to """
CREATE TRIGGER ceiling_operation_validate AFTER INSERT ON ceiling_operations
BEGIN
 SELECT CASE WHEN NOT EXISTS (
 SELECT 1 FROM room_measurements m JOIN projects pr ON pr.id=m.projectId
 WHERE m.uuid=new.measurementUuid AND m.updatedAt=new.expectedUpdatedAt AND m.updatedAt<9223372036854775807
 AND m.deletedAt IS NULL AND pr.deletedAt IS NULL AND json_extract(new.payload,'$.oldDrawingJson')=m.drawingJson
 AND json_type(new.payload,'$.points')='array' AND json_type(new.payload,'$.walls')='array'
 AND json_valid(json_extract(new.payload,'$.drawingJson'))
 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.precisionStepMeters')=json_extract(m.drawingJson,'$.precisionStepMeters')
 AND json_extract(new.payload,'$.shape')=json_extract(json_extract(new.payload,'$.drawingJson'),'$.shape')
 AND json_extract(new.payload,'$.area')=json_extract(json_extract(new.payload,'$.drawingJson'),'$.areaSquareMeters')
 AND json_extract(new.payload,'$.perimeter')=json_extract(json_extract(new.payload,'$.drawingJson'),'$.perimeterMeters')
 AND length(trim(json_extract(new.payload,'$.name')))>0
 AND json_array_length(new.payload,'$.walls')=json_array_length(json_extract(new.payload,'$.drawingJson'),'$.vertices')
 AND json_array_length(new.payload,'$.walls') BETWEEN 3 AND 128
 AND (SELECT count(*) FROM ceiling_operation_points WHERE operationUuid=new.uuid)=
 (SELECT count(*) FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=m.uuid AND q.deletedAt IS NULL)
 AND (SELECT count(DISTINCT pointUuid) FROM ceiling_operation_points WHERE operationUuid=new.uuid)=json_array_length(new.payload,'$.points')
 AND (SELECT count(DISTINCT wallUuid) FROM ceiling_operation_edges WHERE operationUuid=new.uuid)=json_array_length(new.payload,'$.walls')
 ) THEN RAISE(ABORT,'ceiling_parent_geometry_or_set_changed') END;
 SELECT CASE WHEN EXISTS (
 SELECT 1 FROM ceiling_operation_edges w JOIN ceiling_operations o ON o.uuid=w.operationUuid JOIN room_measurements m ON m.uuid=o.measurementUuid
 LEFT JOIN ceiling_walls old ON old.uuid=w.wallUuid WHERE w.operationUuid=new.uuid AND (
 typeof(w.ax)!='integer' OR typeof(w.ay)!='integer' OR w.ax NOT BETWEEN -1000000 AND 1000000 OR w.ay NOT BETWEEN -1000000 AND 1000000
 OR (w.ax=w.bx AND w.ay=w.by) OR length(trim(w.wallUuid))=0
 OR (old.uuid IS NULL AND json_extract(o.payload,'$.drawingJson')=m.drawingJson)
 OR (old.uuid IS NOT NULL AND (old.measurementUuid!=m.uuid OR old.active!=1 OR old.ordinal!=w.ordinal
 OR json_extract(o.payload,'$.shape')!=m.shape OR json_array_length(o.payload,'$.walls')!=(SELECT count(*) FROM ceiling_walls WHERE measurementUuid=m.uuid AND active=1)))
 )) THEN RAISE(ABORT,'ceiling_wall_identity_or_bounds') END;
 SELECT CASE WHEN (SELECT abs(sum(ax*by-bx*ay)) FROM ceiling_operation_edges WHERE operationUuid=new.uuid)=0
 THEN RAISE(ABORT,'ceiling_degenerate_geometry') END;

 SELECT CASE WHEN EXISTS (
 SELECT 1 FROM ceiling_operation_edges a JOIN ceiling_operation_edges b ON a.operationUuid=b.operationUuid
 WHERE a.operationUuid=new.uuid AND b.ordinal>a.ordinal AND b.ordinal!=a.ordinal+1
 AND (a.ordinal=0 AND b.ordinal=json_array_length(new.payload,'$.walls')-1)=0
 AND max(min(a.ax,a.bx),min(b.ax,b.bx))<=min(max(a.ax,a.bx),max(b.ax,b.bx))
 AND max(min(a.ay,a.by),min(b.ay,b.by))<=min(max(a.ay,a.by),max(b.ay,b.by))
 AND (((a.bx-a.ax)*(b.ay-a.ay)-(a.by-a.ay)*(b.ax-a.ax))=0 OR ((a.bx-a.ax)*(b.by-a.ay)-(a.by-a.ay)*(b.bx-a.ax))=0 OR (((a.bx-a.ax)*(b.ay-a.ay)-(a.by-a.ay)*(b.ax-a.ax))>0)!=(((a.bx-a.ax)*(b.by-a.ay)-(a.by-a.ay)*(b.bx-a.ax))>0)) AND (((b.bx-b.ax)*(a.ay-b.ay)-(b.by-b.ay)*(a.ax-b.ax))=0 OR ((b.bx-b.ax)*(a.by-b.ay)-(b.by-b.ay)*(a.bx-b.ax))=0 OR (((b.bx-b.ax)*(a.ay-b.ay)-(b.by-b.ay)*(a.ax-b.ax))>0)!=(((b.bx-b.ax)*(a.by-b.ay)-(b.by-b.ay)*(a.bx-b.ax))>0)))
 THEN RAISE(ABORT,'ceiling_self_intersection') END;
 SELECT CASE WHEN EXISTS (SELECT 1 FROM ceiling_operation_edges a JOIN ceiling_operation_edges b ON b.operationUuid=a.operationUuid
 AND b.ordinal=(a.ordinal+1)%json_array_length(new.payload,'$.walls') WHERE a.operationUuid=new.uuid
 AND (a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)=0
 AND (a.bx-a.ax)*(b.bx-b.ax)+(a.by-a.ay)*(b.by-b.ay)<=0)
 THEN RAISE(ABORT,'ceiling_backtracking') END;
 SELECT CASE WHEN NOT EXISTS (
 SELECT 1 FROM ceiling_operations o WHERE o.uuid=new.uuid
 AND json_extract(o.payload,'$.shape') IN ('rectangle','l_shaped','polygon')
 AND json_extract(json_extract(o.payload,'$.drawingJson'),'$.geometrySchemaVersion') IN (2,3)
 AND abs(json_extract(o.payload,'$.length')-(SELECT (max(ax)-min(ax))/1000.0 FROM ceiling_operation_edges WHERE operationUuid=o.uuid))<0.00000001
 AND abs(json_extract(o.payload,'$.width')-(SELECT (max(ay)-min(ay))/1000.0 FROM ceiling_operation_edges WHERE operationUuid=o.uuid))<0.00000001
 AND json_extract(o.payload,'$.area')>0 AND json_extract(o.payload,'$.perimeter')>0
 AND abs(json_extract(o.payload,'$.area')-(SELECT abs(sum(ax*by-bx*ay))/2000000.0 FROM ceiling_operation_edges WHERE operationUuid=o.uuid))
 <=0.500001*json_extract(json_extract(o.payload,'$.drawingJson'),'$.precisionStepMeters')*json_extract(json_extract(o.payload,'$.drawingJson'),'$.precisionStepMeters')
 AND abs(json_extract(o.payload,'$.perimeter')-(SELECT perimeter FROM ceiling_operation_lengths WHERE operationUuid=o.uuid))
 <=0.500001*json_extract(json_extract(o.payload,'$.drawingJson'),'$.precisionStepMeters')
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=o.uuid AND
 (w.ax%CAST(json_extract(json_extract(o.payload,'$.drawingJson'),'$.precisionStepMeters')*1000 AS INTEGER)!=0
 OR w.ay%CAST(json_extract(json_extract(o.payload,'$.drawingJson'),'$.precisionStepMeters')*1000 AS INTEGER)!=0))
 ) THEN RAISE(ABORT,'ceiling_invalid_geometry_totals') END;

 SELECT CASE WHEN NOT EXISTS(
 SELECT 1 FROM ceiling_operations o WHERE o.uuid=new.uuid
 AND json_type(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm')='object'
 AND (SELECT count(*) FROM ceiling_geometry_fields WHERE operationUuid=new.uuid)=CASE WHEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.geometrySchemaVersion')=3 THEN 8 ELSE 7 END
 AND (SELECT count(DISTINCT fieldName) FROM ceiling_geometry_fields WHERE operationUuid=new.uuid)=(SELECT count(*) FROM ceiling_geometry_fields WHERE operationUuid=new.uuid)
 AND NOT EXISTS(SELECT 1 FROM ceiling_geometry_fields k WHERE k.operationUuid=new.uuid AND k.fieldName NOT IN ('shape','geometrySchemaVersion','vertices','measurementsMm','areaSquareMeters','perimeterMeters','precisionStepMeters','sides'))
 AND NOT EXISTS(SELECT 1 FROM ceiling_geometry_vertex_fields v WHERE v.operationUuid=new.uuid AND
 ((SELECT count(*) FROM ceiling_geometry_vertex_fields k WHERE k.operationUuid=v.operationUuid AND k.ordinal=v.ordinal)!=2 OR v.fieldName NOT IN ('xMm','yMm') OR v.fieldType!='integer' OR (SELECT count(DISTINCT fieldName) FROM ceiling_geometry_vertex_fields k WHERE k.operationUuid=v.operationUuid AND k.ordinal=v.ordinal)!=2))
 AND (SELECT count(DISTINCT fieldName) FROM ceiling_geometry_measurements WHERE operationUuid=new.uuid)=(SELECT count(*) FROM ceiling_geometry_measurements WHERE operationUuid=new.uuid)
 AND NOT EXISTS(SELECT 1 FROM ceiling_geometry_measurements v WHERE v.operationUuid=new.uuid AND (v.fieldType!='integer' OR v.fieldValue NOT BETWEEN 1 AND 1000000
 OR v.fieldValue%CAST(json_extract(json_extract(new.payload,'$.drawingJson'),'$.precisionStepMeters')*1000 AS INTEGER)!=0))
 AND CASE json_extract(new.payload,'$.shape')
 WHEN 'rectangle' THEN json_array_length(new.payload,'$.walls')=4 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.geometrySchemaVersion')=2
 AND (SELECT count(*) FROM ceiling_geometry_measurements WHERE operationUuid=new.uuid)=2 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.length')>0 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.width')>0
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=new.uuid AND
 (w.ax IS NOT CASE w.ordinal WHEN 0 THEN 0 WHEN 1 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.length') WHEN 2 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.length') ELSE 0 END
 OR w.ay IS NOT CASE w.ordinal WHEN 0 THEN 0 WHEN 1 THEN 0 ELSE json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.width') END))
 WHEN 'l_shaped' THEN json_array_length(new.payload,'$.walls')=6 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.geometrySchemaVersion')=2
 AND (SELECT count(*) FROM ceiling_geometry_measurements WHERE operationUuid=new.uuid)=4 AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.A')>json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.C') AND json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.B')>json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.D')
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=new.uuid AND
 (w.ax IS NOT CASE w.ordinal WHEN 0 THEN 0 WHEN 1 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.A') WHEN 2 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.A') WHEN 3 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.C') WHEN 4 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.C') ELSE 0 END
 OR w.ay IS NOT CASE w.ordinal WHEN 0 THEN 0 WHEN 1 THEN 0 WHEN 2 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.D') WHEN 3 THEN json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.D') ELSE json_extract(json_extract(new.payload,'$.drawingJson'),'$.measurementsMm.B') END))
 WHEN 'polygon' THEN (SELECT count(*) FROM ceiling_geometry_measurements WHERE operationUuid=new.uuid)=0 AND
 (json_extract(json_extract(new.payload,'$.drawingJson'),'$.geometrySchemaVersion')=2 OR (
 json_array_length(json_extract(new.payload,'$.drawingJson'),'$.sides')=json_array_length(new.payload,'$.walls')
 AND EXISTS(SELECT 1 FROM ceiling_operation_edges WHERE operationUuid=new.uuid AND ordinal=0 AND ax=0 AND ay=0 AND by=0 AND bx>0)
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_edges w JOIN ceiling_operation_edges next ON next.operationUuid=w.operationUuid
 AND next.ordinal=(w.ordinal+1)%json_array_length(new.payload,'$.walls') WHERE w.operationUuid=new.uuid AND (
 (w.ax!=w.bx AND w.ay!=w.by) OR (w.bx-w.ax)*(next.bx-next.ax)+(w.by-w.ay)*(next.by-next.ay)!=0
 OR json_extract(json_extract(new.payload,'$.drawingJson'),'$.sides['||w.ordinal||'].lengthMm') IS NOT abs(w.bx-w.ax)+abs(w.by-w.ay)
 OR json_extract(json_extract(new.payload,'$.drawingJson'),'$.sides['||w.ordinal||'].turnAfter') IS NOT CASE WHEN (w.bx-w.ax)*(next.by-next.ay)-(w.by-w.ay)*(next.bx-next.ax)>0 THEN 'RIGHT' ELSE 'LEFT' END
 OR (SELECT count(*) FROM ceiling_geometry_side_fields WHERE operationUuid=new.uuid AND ordinal=w.ordinal)!=2 OR (SELECT count(DISTINCT fieldName) FROM ceiling_geometry_side_fields WHERE operationUuid=new.uuid AND ordinal=w.ordinal)!=2 OR EXISTS(SELECT 1 FROM ceiling_geometry_side_fields WHERE operationUuid=new.uuid AND ordinal=w.ordinal AND fieldName NOT IN ('lengthMm','turnAfter'))))))
 ELSE 0 END
 ) THEN RAISE(ABORT,'ceiling_source_geometry_mismatch') END;

 SELECT CASE WHEN EXISTS (
 SELECT 1 FROM ceiling_operation_points p LEFT JOIN point_placements q ON q.uuid=p.pointUuid
 LEFT JOIN measurement_elements e ON e.uuid=q.elementUuid
 WHERE p.operationUuid=new.uuid AND (q.uuid IS NULL OR q.deletedAt IS NOT NULL OR p.elementUuid IS NOT q.elementUuid
 OR e.measurementUuid IS NOT new.measurementUuid OR e.deletedAt IS NOT NULL OR e.updatedAt IS NOT p.expectedElementUpdatedAt
 OR q.updatedAt IS NOT p.expectedUpdatedAt OR q.updatedAt=9223372036854775807
 OR e.kind!='catalog_item' OR e.placementKind!='point' OR e.unit!='шт.' OR e.quantityKind!='exact'
 OR e.quantityMilli<(SELECT count(*)*1000 FROM point_placements reserve WHERE reserve.elementUuid=e.uuid AND reserve.deletedAt IS NULL)
 OR e.placedQuantityMilli!=(SELECT count(*)*1000 FROM point_placements placed WHERE placed.elementUuid=e.uuid AND placed.deletedAt IS NULL AND coalesce(json_extract(placed.mountingJson,'$.unresolved'),0)=0)
 OR typeof(p.shouldWrite)!='integer' OR p.shouldWrite NOT IN (0,1) OR p.unresolved NOT IN (0,1) OR p.needsReview NOT IN (0,1)
 OR NOT json_valid(p.mountingJson) OR typeof(p.xMm)!='integer' OR typeof(p.yMm)!='integer'
 OR p.xMm NOT BETWEEN -1000000 AND 1000000 OR p.yMm NOT BETWEEN -1000000 AND 1000000
 OR p.precision NOT IN ('approximate','exact')
 OR (p.shouldWrite=0 AND (p.xMm!=q.xMm OR p.yMm!=q.yMm OR p.precision!=q.precision OR p.mountingJson!=q.mountingJson))
 OR (p.shouldWrite=1 AND ((SELECT count(*) FROM ceiling_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR (SELECT count(DISTINCT fieldName) FROM ceiling_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR EXISTS(SELECT 1 FROM ceiling_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('anchor','needsReview','unresolved','modifiedVersion','confirmedAt','deferredAt','anchorGeometryJson','lastValidGeometryJson'))
 OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND ((SELECT count(*) FROM ceiling_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR (SELECT count(DISTINCT fieldName) FROM ceiling_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR EXISTS(SELECT 1 FROM ceiling_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('kind','firstWall','secondWall','firstMm','secondMm'))))
 OR (p.unresolved=1 AND json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(q.mountingJson,'$.anchorGeometryJson'))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson') AND (json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(q.mountingJson,'$.anchor') OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(q.mountingJson,'$.anchorGeometryJson')))
 OR coalesce(json_extract(p.mountingJson,'$.modifiedVersion'),0)!=max(q.updatedAt+1,new.createdAt)
 OR coalesce(json_type(p.mountingJson,'$.needsReview'),'missing') NOT IN ('true','false')
 OR coalesce(json_type(p.mountingJson,'$.unresolved'),'missing') NOT IN ('true','false')
 OR coalesce(json_type(p.mountingJson,'$.confirmedAt'),'null') NOT IN ('null','integer')
 OR coalesce(json_type(p.mountingJson,'$.deferredAt'),'null') NOT IN ('null','integer')
 OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND (p.precision!='exact' OR coalesce(json_valid(json_extract(p.mountingJson,'$.anchorGeometryJson')),0)=0))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson'))
 OR (p.unresolved=1 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT coalesce(json_extract(q.mountingJson,'$.lastValidGeometryJson'),json_extract(new.payload,'$.oldDrawingJson')))))
 OR (p.unresolved=1 AND (p.needsReview!=1 OR p.xMm!=q.xMm OR p.yMm!=q.yMm OR p.precision!=q.precision
 OR json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(q.mountingJson,'$.anchor')))
 OR (coalesce(json_extract(q.mountingJson,'$.unresolved'),0)=1 AND p.unresolved=0 AND (json_extract(p.mountingJson,'$.anchor') IS NULL OR p.precision!='exact' OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson')))
 OR (p.unresolved=0 AND (EXISTS(SELECT 1 FROM ceiling_hidden_offsets WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid) OR coalesce(((SELECT CASE WHEN sum(CASE WHEN (bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax)=0
 AND p.xMm BETWEEN min(ax,bx) AND max(ax,bx) AND p.yMm BETWEEN min(ay,by) AND max(ay,by) THEN 1 ELSE 0 END)=0
 AND sum(CASE WHEN (ay>p.yMm)!=(by>p.yMm) AND (((bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax))>0)=(by>ay) THEN 1 ELSE 0 END)%2=1 THEN 1 ELSE 0 END
 FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)=1),0)!=1 OR coalesce(((json_type(p.mountingJson,'$.anchor') IS NULL OR json_type(p.mountingJson,'$.anchor')='null' OR EXISTS (
 SELECT 1 FROM ceiling_operation_edges a LEFT JOIN ceiling_operation_edges b ON b.operationUuid=a.operationUuid AND b.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall')
 WHERE a.operationUuid=p.operationUuid AND a.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') AND (
 (json_extract(p.mountingJson,'$.anchor.kind')='ALONG_INWARD' AND json_extract(p.mountingJson,'$.anchor.secondWall') IS NULL AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 0 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))>=0
 AND (1.0*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))))
 OR (json_extract(p.mountingJson,'$.anchor.kind')='TWO_WALLS' AND a.wallUuid!=b.wallUuid
 AND 1.0*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax))*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)) >=
 0.000001*((a.bx-a.ax)*1.0*(a.bx-a.ax)+(a.by-a.ay)*1.0*(a.by-a.ay))*((b.bx-b.ax)*1.0*(b.bx-b.ax)+(b.by-b.ay)*1.0*(b.by-b.ay))
 AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-b.ax)*(b.bx-b.ax)+(p.yMm-b.ay)*(b.by-b.ay)) BETWEEN 0 AND ((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay)) AND ((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))))))))),0)!=1))
 OR (p.needsReview=1 AND json_extract(p.mountingJson,'$.confirmedAt') IS NOT NULL)
 OR (json_extract(new.payload,'$.drawingJson')!=(SELECT drawingJson FROM room_measurements WHERE uuid=new.measurementUuid) AND (p.needsReview!=1 OR p.shouldWrite!=1))
 )) THEN RAISE(ABORT,'ceiling_point_cas_anchor_or_resolution') END;

 SELECT CASE WHEN coalesce(json_type(new.payload,'$.lines'),'array')!='array'
 OR coalesce(json_type(new.payload,'$.declaredLengths'),'object')!='object'
 OR EXISTS(SELECT 1 FROM ceiling_declared_lengths d LEFT JOIN measurement_elements e ON e.uuid=d.fieldName
 WHERE d.operationUuid=new.uuid AND (e.uuid IS NULL OR e.measurementUuid IS NOT new.measurementUuid OR e.deletedAt IS NOT NULL OR e.placementKind IS NOT 'line' OR e.unit IS NOT 'м.п.'
 OR d.fieldType!='integer' OR d.fieldValue NOT BETWEEN 1 AND 9000000000000000
 OR d.fieldValue!=(SELECT coalesce(sum(lengthMm),0) FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND elementUuid=e.uuid AND deleted=0 AND unresolved=0)))
 OR (SELECT count(DISTINCT lineUuid) FROM ceiling_operation_lines WHERE operationUuid=new.uuid)!=coalesce(json_array_length(new.payload,'$.lines'),0)
 OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.measurementUuid AND q.deletedAt IS NULL
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_lines l WHERE l.operationUuid=new.uuid AND l.lineUuid=q.uuid))
 THEN RAISE(ABORT,'line_incomplete_set') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_operation_lines l LEFT JOIN line_placements q ON q.uuid=l.lineUuid
 LEFT JOIN measurement_elements e ON e.uuid=l.elementUuid WHERE l.operationUuid=new.uuid AND (
 e.uuid IS NULL OR e.deletedAt IS NOT NULL OR e.measurementUuid IS NOT new.measurementUuid OR e.updatedAt IS NOT l.expectedElementUpdatedAt OR e.updatedAt=9223372036854775807
 OR e.kind!='catalog_item' OR e.placementKind IS NOT 'line' OR e.unit IS NOT 'м.п.' OR e.quantityMode NOT IN ('manual','entered_length') OR e.quantityKind NOT IN ('unknown','exact','range')
 OR (q.uuid IS NULL AND (l.expectedUpdatedAt IS NOT NULL OR l.deleted!=0 OR l.unresolved!=0))
 OR (q.uuid IS NOT NULL AND (q.deletedAt IS NOT NULL OR q.elementUuid IS NOT l.elementUuid OR q.updatedAt IS NOT l.expectedUpdatedAt OR q.updatedAt=9223372036854775807))
 OR typeof(l.shouldWrite)!='integer' OR l.shouldWrite NOT IN (0,1) OR typeof(l.deleted)!='integer' OR l.deleted NOT IN (0,1)
 OR typeof(l.unresolved)!='integer' OR l.unresolved NOT IN (0,1) OR typeof(l.needsReview)!='integer' OR l.needsReview NOT IN (0,1)
 OR typeof(l.confirmed)!='integer' OR l.confirmed NOT IN (0,1)
 OR typeof(l.startX)!='integer' OR typeof(l.startY)!='integer' OR typeof(l.endX)!='integer' OR typeof(l.endY)!='integer'
 OR l.startX NOT BETWEEN -1000000 AND 1000000 OR l.startY NOT BETWEEN -1000000 AND 1000000 OR l.endX NOT BETWEEN -1000000 AND 1000000 OR l.endY NOT BETWEEN -1000000 AND 1000000
 OR typeof(l.lengthMm)!='integer' OR l.lengthMm NOT BETWEEN 1 AND 2828427
 OR 4*((l.endX-l.startX)*(l.endX-l.startX)+(l.endY-l.startY)*(l.endY-l.startY)) < (2*l.lengthMm-1)*(2*l.lengthMm-1)
 OR 4*((l.endX-l.startX)*(l.endX-l.startX)+(l.endY-l.startY)*(l.endY-l.startY)) >= (2*l.lengthMm+1)*(2*l.lengthMm+1)
 OR (l.widthMm IS NOT NULL AND (typeof(l.widthMm)!='integer' OR l.widthMm NOT BETWEEN 1 AND 1000000)) OR typeof(l.note)!='text'
 OR (l.shouldWrite=0 AND (q.uuid IS NULL OR l.startX IS NOT q.startX OR l.startY IS NOT q.startY OR l.endX IS NOT q.endX OR l.endY IS NOT q.endY OR l.startMounting IS NOT q.startMounting OR l.endMounting IS NOT q.endMounting OR l.needsReview IS NOT q.needsReview OR l.unresolved IS NOT q.unresolved OR l.widthMm IS NOT q.widthMm OR l.note IS NOT q.note OR l.deleted!=0))
 OR (l.unresolved=1 AND (q.uuid IS NULL OR l.needsReview!=1 OR l.startX!=q.startX OR l.startY!=q.startY OR l.endX!=q.endX OR l.endY!=q.endY OR json_extract(l.startMounting,'$.anchor') IS NOT json_extract(q.startMounting,'$.anchor') OR json_extract(l.endMounting,'$.anchor') IS NOT json_extract(q.endMounting,'$.anchor') OR json_extract(l.startMounting,'$.anchorGeometryJson') IS NOT json_extract(q.startMounting,'$.anchorGeometryJson') OR json_extract(l.endMounting,'$.anchorGeometryJson') IS NOT json_extract(q.endMounting,'$.anchorGeometryJson')))
 OR (q.unresolved=1 AND l.unresolved=0 AND (l.remeasuredStart IS NOT 1 OR l.remeasuredEnd IS NOT 1 OR l.confirmed!=1 OR json_extract(l.startMounting,'$.anchor') IS NULL OR json_extract(l.endMounting,'$.anchor') IS NULL OR json_extract(l.startMounting,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson') OR json_extract(l.endMounting,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson')))
 OR (l.deleted=0 AND json_extract(new.payload,'$.drawingJson')!=json_extract(new.payload,'$.oldDrawingJson') AND (l.shouldWrite!=1 OR l.needsReview!=1))
 )) THEN RAISE(ABORT,'line_cas_or_metadata') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_operation_lines l JOIN measurement_elements e ON e.uuid=l.elementUuid
 WHERE l.operationUuid=new.uuid AND (e.quantityKind IN ('exact','range') OR EXISTS(SELECT 1 FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=e.uuid)) AND
 (SELECT coalesce(sum(lengthMm),0) FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND elementUuid=e.uuid AND deleted=0)>
 coalesce((SELECT fieldValue FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=e.uuid),CASE WHEN e.quantityKind='exact' THEN e.quantityMilli ELSE e.quantityMaxMilli END))
 THEN RAISE(ABORT,'line_capacity') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_line_endpoints p WHERE p.operationUuid=new.uuid AND p.deleted=0 AND (
 NOT json_valid(p.mountingJson) OR json_type(p.mountingJson)!='object'
 OR (SELECT count(*) FROM ceiling_line_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR (SELECT count(DISTINCT fieldName) FROM ceiling_line_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR EXISTS(SELECT 1 FROM ceiling_line_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('anchor','needsReview','unresolved','modifiedVersion','confirmedAt','deferredAt','anchorGeometryJson','lastValidGeometryJson'))
 OR coalesce(json_type(p.mountingJson,'$.needsReview'),'missing') NOT IN ('true','false') OR coalesce(json_type(p.mountingJson,'$.unresolved'),'missing') NOT IN ('true','false')
 OR coalesce(json_type(p.mountingJson,'$.modifiedVersion'),'missing')!='integer'
 OR coalesce(json_type(p.mountingJson,'$.confirmedAt'),'null') NOT IN ('null','integer') OR coalesce(json_type(p.mountingJson,'$.deferredAt'),'null') NOT IN ('null','integer')
 OR (p.shouldWrite=1 AND json_extract(p.mountingJson,'$.modifiedVersion')!=max(coalesce((SELECT expectedUpdatedAt FROM ceiling_operation_lines WHERE operationUuid=p.operationUuid AND lineUuid=p.lineUuid),-1)+1,new.createdAt))
 OR (p.needsReview=1 AND json_extract(p.mountingJson,'$.confirmedAt') IS NOT NULL)
 OR (p.shouldWrite=1 AND p.unresolved=0 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson'))
 OR (p.unresolved=1 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT coalesce(json_extract(p.oldMountingJson,'$.lastValidGeometryJson'),json_extract(new.payload,'$.oldDrawingJson')))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.anchor') IS NOT NULL
 AND json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson')
 AND (json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(p.oldMountingJson,'$.anchor') OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(p.oldMountingJson,'$.anchorGeometryJson')))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.unresolved')!=0)
 OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND ((SELECT count(*) FROM ceiling_line_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR (SELECT count(DISTINCT fieldName) FROM ceiling_line_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR EXISTS(SELECT 1 FROM ceiling_line_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('kind','firstWall','secondWall','firstMm','secondMm')) OR coalesce(json_valid(json_extract(p.mountingJson,'$.anchorGeometryJson')),0)=0))
 OR (p.unresolved=0 AND (EXISTS(SELECT 1 FROM ceiling_line_hidden_offsets WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid) OR coalesce(((SELECT CASE WHEN sum(CASE WHEN (bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax)=0
 AND p.xMm BETWEEN min(ax,bx) AND max(ax,bx) AND p.yMm BETWEEN min(ay,by) AND max(ay,by) THEN 1 ELSE 0 END)=0
 AND sum(CASE WHEN (ay>p.yMm)!=(by>p.yMm) AND (((bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax))>0)=(by>ay) THEN 1 ELSE 0 END)%2=1 THEN 1 ELSE 0 END
 FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)=1),0)!=1 OR coalesce(((json_type(p.mountingJson,'$.anchor') IS NULL OR json_type(p.mountingJson,'$.anchor')='null' OR EXISTS (
 SELECT 1 FROM ceiling_operation_edges a LEFT JOIN ceiling_operation_edges b ON b.operationUuid=a.operationUuid AND b.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall')
 WHERE a.operationUuid=p.operationUuid AND a.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') AND (
 (json_extract(p.mountingJson,'$.anchor.kind')='ALONG_INWARD' AND json_extract(p.mountingJson,'$.anchor.secondWall') IS NULL AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 0 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))>=0
 AND (1.0*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))))
 OR (json_extract(p.mountingJson,'$.anchor.kind')='TWO_WALLS' AND a.wallUuid!=b.wallUuid
 AND 1.0*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax))*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)) >=
 0.000001*((a.bx-a.ax)*1.0*(a.bx-a.ax)+(a.by-a.ay)*1.0*(a.by-a.ay))*((b.bx-b.ax)*1.0*(b.bx-b.ax)+(b.by-b.ay)*1.0*(b.by-b.ay))
 AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-b.ax)*(b.bx-b.ax)+(p.yMm-b.ay)*(b.by-b.ay)) BETWEEN 0 AND ((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay)) AND ((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))))))))),0)!=1))
 )) THEN RAISE(ABORT,'line_endpoint_anchor_or_interior') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_operation_lines l JOIN ceiling_operation_edges w ON w.operationUuid=l.operationUuid
 WHERE l.operationUuid=new.uuid AND l.deleted=0 AND l.unresolved=0
 AND max(min(l.startX,l.endX),min(w.ax,w.bx))<=min(max(l.startX,l.endX),max(w.ax,w.bx))
 AND max(min(l.startY,l.endY),min(w.ay,w.by))<=min(max(l.startY,l.endY),max(w.ay,w.by))
 AND (((l.endX-l.startX)*(w.ay-l.startY)-(l.endY-l.startY)*(w.ax-l.startX))=0 OR ((l.endX-l.startX)*(w.by-l.startY)-(l.endY-l.startY)*(w.bx-l.startX))=0 OR (((l.endX-l.startX)*(w.ay-l.startY)-(l.endY-l.startY)*(w.ax-l.startX))>0)!=(((l.endX-l.startX)*(w.by-l.startY)-(l.endY-l.startY)*(w.bx-l.startX))>0))
 AND (((w.bx-w.ax)*(l.startY-w.ay)-(w.by-w.ay)*(l.startX-w.ax))=0 OR ((w.bx-w.ax)*(l.endY-w.ay)-(w.by-w.ay)*(l.endX-w.ax))=0 OR (((w.bx-w.ax)*(l.startY-w.ay)-(w.by-w.ay)*(l.startX-w.ax))>0)!=(((w.bx-w.ax)*(l.endY-w.ay)-(w.by-w.ay)*(l.endX-w.ax))>0)))
 THEN RAISE(ABORT,'line_crosses_boundary') END;

 SELECT CASE WHEN coalesce(json_type(new.payload,'$.areas'),'array')!='array' OR coalesce(json_type(new.payload,'$.declaredAreas'),'object')!='object'
 OR (SELECT count(*) FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND deleted=0)>1000
 OR (SELECT count(*) FROM ceiling_operation_areas WHERE operationUuid=new.uuid)!=(SELECT count(DISTINCT areaUuid) FROM ceiling_operation_areas WHERE operationUuid=new.uuid)
 OR EXISTS(SELECT 1 FROM area_features q WHERE q.measurementUuid=new.measurementUuid AND q.deletedAt IS NULL AND NOT EXISTS(SELECT 1 FROM ceiling_operation_areas a WHERE a.operationUuid=new.uuid AND a.areaUuid=q.uuid))
 THEN RAISE(ABORT,'area_incomplete_set') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_operation_areas a LEFT JOIN area_features q ON q.uuid=a.areaUuid
 LEFT JOIN measurement_elements e ON e.uuid=a.elementUuid WHERE a.operationUuid=new.uuid AND (
 typeof(a.areaUuid)!='text' OR length(trim(a.areaUuid))=0 OR a.role NOT IN ('GENERAL_AREA','OBSTACLE','VOID_MARKER') OR a.role IS NULL
 OR (a.elementUuid IS NOT NULL AND (e.uuid IS NULL OR e.deletedAt IS NOT NULL OR e.measurementUuid IS NOT new.measurementUuid OR e.updatedAt IS NOT a.expectedElementUpdatedAt OR e.updatedAt=9223372036854775807 OR e.kind IS NOT 'catalog_item' OR e.placementKind IS NOT 'area' OR e.quantityMode IS NOT 'manual' OR e.unit NOT IN ('м²','шт.') OR e.quantityKind NOT IN ('unknown','exact','range')))
 OR (a.elementUuid IS NULL AND a.expectedElementUpdatedAt IS NOT NULL)
 OR (q.uuid IS NULL AND (a.expectedUpdatedAt IS NOT NULL OR a.deleted IS NOT 0 OR a.unresolved IS NOT 0 OR a.shouldWrite IS NOT 1))
 OR (q.uuid IS NOT NULL AND (q.measurementUuid IS NOT new.measurementUuid OR q.elementUuid IS NOT a.elementUuid OR q.deletedAt IS NOT NULL OR q.updatedAt IS NOT a.expectedUpdatedAt OR q.updatedAt=9223372036854775807))
 OR a.shouldWrite IS NULL OR a.shouldWrite NOT IN (0,1) OR a.deleted IS NULL OR a.deleted NOT IN (0,1)
 OR a.confirmed IS NULL OR a.confirmed NOT IN (0,1) OR a.radiusMeasured IS NULL OR a.radiusMeasured NOT IN (0,1)
 OR a.needsReview IS NULL OR a.needsReview NOT IN (0,1) OR a.unresolved IS NULL OR a.unresolved NOT IN (0,1)
 OR (a.unresolved=1 AND a.needsReview!=1) OR typeof(a.note)!='text'
 OR (q.needsReview=1 AND a.needsReview=0 AND a.deleted=0 AND a.confirmed IS NOT 1)
 OR (a.deleted=0 AND json_extract(new.payload,'$.drawingJson')!=json_extract(new.payload,'$.oldDrawingJson') AND (a.shouldWrite!=1 OR a.needsReview!=1))
 OR (a.shouldWrite=0 AND (q.uuid IS NULL OR a.deleted!=0 OR a.elementUuid IS NOT q.elementUuid OR a.role IS NOT q.role OR a.shapeKind IS NOT q.shapeKind OR a.positionsJson IS NOT q.positionsJson OR a.radiusMm IS NOT q.radiusMm OR a.twiceAreaMm2 IS NOT q.twiceAreaMm2 OR a.mountingsJson IS NOT q.mountingsJson OR a.radiusMeasured IS NOT q.radiusMeasured OR a.needsReview IS NOT q.needsReview OR a.unresolved IS NOT q.unresolved OR a.note IS NOT q.note))
 OR (a.unresolved=1 AND (q.uuid IS NULL OR a.positionsJson IS NOT q.positionsJson OR a.radiusMm IS NOT q.radiusMm OR a.shapeKind IS NOT q.shapeKind OR a.twiceAreaMm2 IS NOT q.twiceAreaMm2 OR a.radiusMeasured IS NOT q.radiusMeasured))
 OR (q.unresolved=1 AND a.unresolved=0 AND a.deleted=0 AND (a.confirmed IS NOT 1 OR coalesce(json_array_length(a.remeasured),0)!=json_array_length(a.positionsJson) OR (a.shapeKind='CIRCLE' AND a.remeasuredRadius IS NOT 1)
 OR EXISTS(SELECT 1 FROM ceiling_area_positions p WHERE p.operationUuid=a.operationUuid AND p.areaUuid=a.areaUuid AND (json_extract(p.mountingJson,'$.anchor') IS NULL OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson') OR NOT EXISTS(SELECT 1 FROM ceiling_area_remeasurements r WHERE r.operationUuid=p.operationUuid AND r.areaUuid=p.areaUuid AND r.valueType='integer' AND r.ordinal=p.ordinal)))))
 )) THEN RAISE(ABORT,'area_cas_or_metadata') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_operation_areas a WHERE a.operationUuid=new.uuid AND (
 NOT json_valid(a.positionsJson) OR json_type(a.positionsJson)!='array' OR NOT json_valid(a.mountingsJson) OR json_type(a.mountingsJson)!='array'
 OR json_array_length(a.positionsJson)!=json_array_length(a.mountingsJson)
 OR a.shapeKind IS NULL OR a.shapeKind NOT IN ('POLYGON','CIRCLE')
 OR (a.shapeKind='POLYGON' AND (json_array_length(a.positionsJson) NOT BETWEEN 3 AND 16 OR a.radiusMm IS NOT NULL OR a.radiusMeasured!=0))
 OR (a.shapeKind='CIRCLE' AND (json_array_length(a.positionsJson)!=1 OR typeof(a.radiusMm)!='integer' OR a.radiusMm NOT BETWEEN 1 AND 1000000))
 OR typeof(a.twiceAreaMm2)!='integer' OR a.twiceAreaMm2 NOT BETWEEN 1 AND 8000000000000
 OR (a.shapeKind='CIRCLE' AND a.twiceAreaMm2!=2*((a.radiusMm*a.radiusMm*3141593+500000)/1000000))
 OR (a.shapeKind='POLYGON' AND a.twiceAreaMm2 IS NOT (SELECT abs(sum(ax*by-bx*ay)) FROM ceiling_area_edges WHERE operationUuid=a.operationUuid AND areaUuid=a.areaUuid))
 OR EXISTS(SELECT 1 FROM ceiling_area_invalid_positions v WHERE v.operationUuid=a.operationUuid AND v.areaUuid=a.areaUuid)
 OR EXISTS(SELECT 1 FROM ceiling_area_positions p WHERE p.operationUuid=a.operationUuid AND p.areaUuid=a.areaUuid AND (p.mountingType IS NOT 'text' OR p.xMm NOT BETWEEN -1000000 AND 1000000 OR p.yMm NOT BETWEEN -1000000 AND 1000000))
 )) THEN RAISE(ABORT,'area_shape_or_canonical_area') END;
SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_edges a JOIN ceiling_area_edges b ON b.operationUuid=a.operationUuid AND b.areaUuid=a.areaUuid
 JOIN ceiling_operation_areas f ON f.operationUuid=a.operationUuid AND f.areaUuid=a.areaUuid
 WHERE a.operationUuid=new.uuid AND (a.ax=a.bx AND a.ay=a.by OR
 (b.ordinal>a.ordinal AND b.ordinal!=a.ordinal+1 AND (a.ordinal=0 AND b.ordinal=json_array_length(f.positionsJson)-1)=0 AND max(min(a.ax,a.bx),min(b.ax,b.bx))<=min(max(a.ax,a.bx),max(b.ax,b.bx)) AND max(min(a.ay,a.by),min(b.ay,b.by))<=min(max(a.ay,a.by),max(b.ay,b.by)) AND (((a.bx-a.ax)*(b.ay-a.ay)-(a.by-a.ay)*(b.ax-a.ax))=0 OR ((a.bx-a.ax)*(b.by-a.ay)-(a.by-a.ay)*(b.bx-a.ax))=0 OR (((a.bx-a.ax)*(b.ay-a.ay)-(a.by-a.ay)*(b.ax-a.ax))>0)!=(((a.bx-a.ax)*(b.by-a.ay)-(a.by-a.ay)*(b.bx-a.ax))>0)) AND (((b.bx-b.ax)*(a.ay-b.ay)-(b.by-b.ay)*(a.ax-b.ax))=0 OR ((b.bx-b.ax)*(a.by-b.ay)-(b.by-b.ay)*(a.bx-b.ax))=0 OR (((b.bx-b.ax)*(a.ay-b.ay)-(b.by-b.ay)*(a.ax-b.ax))>0)!=(((b.bx-b.ax)*(a.by-b.ay)-(b.by-b.ay)*(a.bx-b.ax))>0))) OR
 (b.ordinal=(a.ordinal+1)%json_array_length(f.positionsJson) AND (a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)=0 AND (a.bx-a.ax)*(b.bx-b.ax)+(a.by-a.ay)*(b.by-b.ay)<=0)))
 THEN RAISE(ABORT,'area_self_intersection_or_degenerate') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_edges a JOIN ceiling_operation_edges w ON w.operationUuid=a.operationUuid
 JOIN ceiling_operation_areas f ON f.operationUuid=a.operationUuid AND f.areaUuid=a.areaUuid
 WHERE a.operationUuid=new.uuid AND f.deleted=0 AND f.unresolved=0 AND max(min(a.ax,a.bx),min(w.ax,w.bx))<=min(max(a.ax,a.bx),max(w.ax,w.bx)) AND max(min(a.ay,a.by),min(w.ay,w.by))<=min(max(a.ay,a.by),max(w.ay,w.by)) AND (((a.bx-a.ax)*(w.ay-a.ay)-(a.by-a.ay)*(w.ax-a.ax))=0 OR ((a.bx-a.ax)*(w.by-a.ay)-(a.by-a.ay)*(w.bx-a.ax))=0 OR (((a.bx-a.ax)*(w.ay-a.ay)-(a.by-a.ay)*(w.ax-a.ax))>0)!=(((a.bx-a.ax)*(w.by-a.ay)-(a.by-a.ay)*(w.bx-a.ax))>0)) AND (((w.bx-w.ax)*(a.ay-w.ay)-(w.by-w.ay)*(a.ax-w.ax))=0 OR ((w.bx-w.ax)*(a.by-w.ay)-(w.by-w.ay)*(a.bx-w.ax))=0 OR (((w.bx-w.ax)*(a.ay-w.ay)-(w.by-w.ay)*(a.ax-w.ax))>0)!=(((w.bx-w.ax)*(a.by-w.ay)-(w.by-w.ay)*(a.bx-w.ax))>0))) THEN RAISE(ABORT,'area_crosses_boundary') END;
SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_circle_distances WHERE operationUuid=new.uuid AND
 CASE WHEN dot<=0 THEN da WHEN dot>=length2 THEN db ELSE cross*1.0*cross/length2 END<=radiusMm*radiusMm+0.002*radiusMm+0.000001)
 THEN RAISE(ABORT,'circle_crosses_boundary') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas d LEFT JOIN measurement_elements e ON e.uuid=d.fieldName LEFT JOIN ceiling_area_totals t ON t.operationUuid=d.operationUuid AND t.elementUuid=d.fieldName
 WHERE d.operationUuid=new.uuid AND (e.uuid IS NULL OR e.measurementUuid IS NOT new.measurementUuid OR e.deletedAt IS NOT NULL OR e.placementKind IS NOT 'area' OR e.quantityMode IS NOT 'manual' OR e.unit NOT IN ('м²','шт.') OR d.fieldType!='integer' OR d.fieldValue NOT BETWEEN 1 AND 9000000000000000 OR d.fieldValue IS NOT t.validMilli))
 OR (SELECT count(*) FROM ceiling_declared_areas WHERE operationUuid=new.uuid)!=(SELECT count(DISTINCT fieldName) FROM ceiling_declared_areas WHERE operationUuid=new.uuid)
 OR EXISTS(SELECT 1 FROM ceiling_area_totals t JOIN measurement_elements e ON e.uuid=t.elementUuid WHERE t.operationUuid=new.uuid AND t.reservedMilli>coalesce((SELECT fieldValue FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=e.uuid),CASE WHEN e.quantityKind='exact' THEN e.quantityMilli WHEN e.quantityKind='range' THEN e.quantityMaxMilli ELSE NULL END))
 THEN RAISE(ABORT,'area_capacity_or_declared_quantity') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_positions p WHERE p.operationUuid=new.uuid AND p.deleted=0 AND (
 (p.unresolved=1 AND (json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(p.oldMountingJson,'$.anchor') OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(p.oldMountingJson,'$.anchorGeometryJson'))) OR json_extract(p.mountingJson,'$.unresolved') IS NOT p.unresolved OR json_extract(p.mountingJson,'$.needsReview') IS NOT p.needsReview OR (json_extract(p.mountingJson,'$.confirmedAt') IS NOT NULL AND json_extract(p.mountingJson,'$.confirmedAt')<0) OR (json_extract(p.mountingJson,'$.deferredAt') IS NOT NULL AND json_extract(p.mountingJson,'$.deferredAt')<0) OR coalesce(json_type(p.mountingJson,'$.anchorGeometryJson'),'missing') NOT IN ('text','null') OR coalesce(json_type(p.mountingJson,'$.lastValidGeometryJson'),'missing') NOT IN ('text','null') OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND (json_type(p.mountingJson,'$.anchor.kind') IS NOT 'text' OR json_type(p.mountingJson,'$.anchor.firstWall') IS NOT 'text' OR coalesce(json_type(p.mountingJson,'$.anchor.secondWall'),'missing') NOT IN ('text','null') OR json_type(p.mountingJson,'$.anchor.firstMm') IS NOT 'integer' OR json_type(p.mountingJson,'$.anchor.secondMm') IS NOT 'integer')) OR NOT json_valid(p.mountingJson) OR json_type(p.mountingJson)!='object'
 OR (SELECT count(*) FROM ceiling_area_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR (SELECT count(DISTINCT fieldName) FROM ceiling_area_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=8
 OR EXISTS(SELECT 1 FROM ceiling_area_mounting_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('anchor','needsReview','unresolved','modifiedVersion','confirmedAt','deferredAt','anchorGeometryJson','lastValidGeometryJson'))
 OR coalesce(json_type(p.mountingJson,'$.needsReview'),'missing') NOT IN ('true','false') OR coalesce(json_type(p.mountingJson,'$.unresolved'),'missing') NOT IN ('true','false')
 OR coalesce(json_type(p.mountingJson,'$.modifiedVersion'),'missing')!='integer'
 OR coalesce(json_type(p.mountingJson,'$.confirmedAt'),'null') NOT IN ('null','integer') OR coalesce(json_type(p.mountingJson,'$.deferredAt'),'null') NOT IN ('null','integer')
 OR (p.shouldWrite=1 AND json_extract(p.mountingJson,'$.modifiedVersion')!=max(coalesce((SELECT expectedUpdatedAt FROM ceiling_operation_areas WHERE operationUuid=p.operationUuid AND areaUuid=p.areaUuid),-1)+1,new.createdAt))
 OR (p.needsReview=1 AND json_extract(p.mountingJson,'$.confirmedAt') IS NOT NULL)
 OR (p.shouldWrite=1 AND p.unresolved=0 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson'))
 OR (p.unresolved=1 AND json_extract(p.mountingJson,'$.lastValidGeometryJson') IS NOT coalesce(json_extract(p.oldMountingJson,'$.lastValidGeometryJson'),json_extract(new.payload,'$.oldDrawingJson')))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.anchor') IS NOT NULL
 AND json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(new.payload,'$.drawingJson')
 AND (json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(p.oldMountingJson,'$.anchor') OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(p.oldMountingJson,'$.anchorGeometryJson')))
 OR (p.unresolved=0 AND json_extract(p.mountingJson,'$.unresolved')!=0)
 OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND ((SELECT count(*) FROM ceiling_area_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR (SELECT count(DISTINCT fieldName) FROM ceiling_area_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid)!=5 OR EXISTS(SELECT 1 FROM ceiling_area_anchor_fields WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid AND fieldName NOT IN ('kind','firstWall','secondWall','firstMm','secondMm')) OR coalesce(json_valid(json_extract(p.mountingJson,'$.anchorGeometryJson')),0)=0))
 OR (p.unresolved=0 AND (EXISTS(SELECT 1 FROM ceiling_area_hidden_offsets WHERE operationUuid=p.operationUuid AND pointUuid=p.pointUuid) OR coalesce(((SELECT CASE WHEN sum(CASE WHEN (bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax)=0
 AND p.xMm BETWEEN min(ax,bx) AND max(ax,bx) AND p.yMm BETWEEN min(ay,by) AND max(ay,by) THEN 1 ELSE 0 END)=0
 AND sum(CASE WHEN (ay>p.yMm)!=(by>p.yMm) AND (((bx-ax)*(p.yMm-ay)-(by-ay)*(p.xMm-ax))>0)=(by>ay) THEN 1 ELSE 0 END)%2=1 THEN 1 ELSE 0 END
 FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)=1),0)!=1 OR coalesce(((json_type(p.mountingJson,'$.anchor') IS NULL OR json_type(p.mountingJson,'$.anchor')='null' OR EXISTS (
 SELECT 1 FROM ceiling_operation_edges a LEFT JOIN ceiling_operation_edges b ON b.operationUuid=a.operationUuid AND b.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall')
 WHERE a.operationUuid=p.operationUuid AND a.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') AND (
 (json_extract(p.mountingJson,'$.anchor.kind')='ALONG_INWARD' AND json_extract(p.mountingJson,'$.anchor.secondWall') IS NULL AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 0 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))>=0
 AND (1.0*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))*((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))))
 OR (json_extract(p.mountingJson,'$.anchor.kind')='TWO_WALLS' AND a.wallUuid!=b.wallUuid
 AND 1.0*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax))*((a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)) >=
 0.000001*((a.bx-a.ax)*1.0*(a.bx-a.ax)+(a.by-a.ay)*1.0*(a.by-a.ay))*((b.bx-b.ax)*1.0*(b.bx-b.ax)+(b.by-b.ay)*1.0*(b.by-b.ay))
 AND (typeof(json_extract(p.mountingJson,'$.anchor.firstMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.firstMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-a.ax)*(a.bx-a.ax)+(p.yMm-a.ay)*(a.by-a.ay)) BETWEEN 0 AND ((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)) AND ((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))*((a.bx-a.ax)*(p.yMm-a.ay)-(a.by-a.ay)*(p.xMm-a.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.firstMm')-1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay))) AND ((json_extract(p.mountingJson,'$.anchor.firstMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.firstMm')+1)*((a.bx-a.ax)*(a.bx-a.ax)+(a.by-a.ay)*(a.by-a.ay)))) AND (typeof(json_extract(p.mountingJson,'$.anchor.secondMm'))='integer' AND json_extract(p.mountingJson,'$.anchor.secondMm') BETWEEN 1 AND 3000000
 AND ((p.xMm-b.ax)*(b.bx-b.ax)+(p.yMm-b.ay)*(b.by-b.ay)) BETWEEN 0 AND ((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay)) AND ((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*(SELECT CASE WHEN sum(ax*by-bx*ay)>0 THEN 1 ELSE -1 END FROM ceiling_operation_edges WHERE operationUuid=p.operationUuid)>0
 AND (1.0*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))*((b.bx-b.ax)*(p.yMm-b.ay)-(b.by-b.ay)*(p.xMm-b.ax))) BETWEEN (max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*1.0*max(0,json_extract(p.mountingJson,'$.anchor.secondMm')-1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))) AND ((json_extract(p.mountingJson,'$.anchor.secondMm')+1)*1.0*(json_extract(p.mountingJson,'$.anchor.secondMm')+1)*((b.bx-b.ax)*(b.bx-b.ax)+(b.by-b.ay)*(b.by-b.ay))))))))),0)!=1))
 )) THEN RAISE(ABORT,'area_position_anchor_or_interior') END;
 UPDATE room_measurements SET name=json_extract(new.payload,'$.name'), notes=json_extract(new.payload,'$.notes'),
 shape=json_extract(new.payload,'$.shape'),drawingJson=json_extract(new.payload,'$.drawingJson'),
 lengthMeters=CASE WHEN json_extract(new.payload,'$.drawingJson')=json_extract(new.payload,'$.oldDrawingJson') THEN lengthMeters ELSE json_extract(new.payload,'$.length') END,widthMeters=CASE WHEN json_extract(new.payload,'$.drawingJson')=json_extract(new.payload,'$.oldDrawingJson') THEN widthMeters ELSE json_extract(new.payload,'$.width') END,
 areaSquareMeters=CASE WHEN json_extract(new.payload,'$.drawingJson')=json_extract(new.payload,'$.oldDrawingJson') THEN areaSquareMeters ELSE json_extract(new.payload,'$.area') END,perimeterMeters=CASE WHEN json_extract(new.payload,'$.drawingJson')=json_extract(new.payload,'$.oldDrawingJson') THEN perimeterMeters ELSE json_extract(new.payload,'$.perimeter') END,
 updatedAt=max(updatedAt+1,new.createdAt) WHERE uuid=new.measurementUuid;
 UPDATE ceiling_walls SET active=0 WHERE measurementUuid=new.measurementUuid AND
 json_extract(new.payload,'$.drawingJson') IS NOT json_extract(new.payload,'$.oldDrawingJson');
 INSERT INTO ceiling_walls(uuid,measurementUuid,ordinal,ax,ay,bx,by,geometryVersion,active)
 SELECT wallUuid,new.measurementUuid,ordinal,ax,ay,bx,by,max(new.expectedUpdatedAt+1,new.createdAt),1 FROM ceiling_operation_edges
 WHERE operationUuid=new.uuid AND json_extract(new.payload,'$.drawingJson') IS NOT json_extract(new.payload,'$.oldDrawingJson')
 ON CONFLICT(uuid) DO UPDATE SET ordinal=excluded.ordinal,ax=excluded.ax,ay=excluded.ay,bx=excluded.bx,by=excluded.by,geometryVersion=excluded.geometryVersion,active=1;
 UPDATE point_placements SET
 xMm=(SELECT xMm FROM ceiling_operation_points WHERE operationUuid=new.uuid AND pointUuid=point_placements.uuid),
 yMm=(SELECT yMm FROM ceiling_operation_points WHERE operationUuid=new.uuid AND pointUuid=point_placements.uuid),
 precision=(SELECT precision FROM ceiling_operation_points WHERE operationUuid=new.uuid AND pointUuid=point_placements.uuid),
 mountingJson=(SELECT mountingJson FROM ceiling_operation_points WHERE operationUuid=new.uuid AND pointUuid=point_placements.uuid),
 updatedAt=max(updatedAt+1,new.createdAt),lastCeilingEditUuid=new.uuid
 WHERE uuid IN (SELECT pointUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid AND shouldWrite=1);
 UPDATE measurement_elements SET
 placedQuantityMilli=(SELECT count(*)*1000 FROM point_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0),
 placementStatus=CASE WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0) THEN 'unplaced'
 WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0 AND precision!='exact') THEN 'exact' ELSE 'approximate' END
 WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid AND shouldWrite=1);
 UPDATE line_placements SET
 startX=(SELECT startX FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 startY=(SELECT startY FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 endX=(SELECT endX FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 endY=(SELECT endY FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 lengthMm=(SELECT lengthMm FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 widthMm=(SELECT widthMm FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 note=(SELECT note FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 startMounting=(SELECT startMounting FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 endMounting=(SELECT endMounting FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 needsReview=(SELECT needsReview FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 unresolved=(SELECT unresolved FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid),
 updatedAt=max(updatedAt+1,new.createdAt),deletedAt=CASE WHEN (SELECT deleted FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND lineUuid=line_placements.uuid)=1 THEN max(updatedAt+1,new.createdAt) ELSE NULL END,
 contourUpdatedAt=max(new.expectedUpdatedAt+1,new.createdAt),lastCeilingEditUuid=new.uuid
 WHERE uuid IN (SELECT lineUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND shouldWrite=1);
 INSERT INTO line_placements(uuid,elementUuid,startX,startY,endX,endY,lengthMm,widthMm,note,startMounting,endMounting,needsReview,unresolved,contourUpdatedAt,createdAt,updatedAt,deletedAt,lastCeilingEditUuid)
 SELECT lineUuid,elementUuid,startX,startY,endX,endY,lengthMm,widthMm,note,startMounting,endMounting,needsReview,unresolved,max(new.expectedUpdatedAt+1,new.createdAt),new.createdAt,new.createdAt,NULL,new.uuid FROM ceiling_operation_lines
 WHERE operationUuid=new.uuid AND expectedUpdatedAt IS NULL;
 UPDATE measurement_elements SET
 quantityKind=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN 'exact' ELSE quantityKind END,
 quantityMilli=coalesce((SELECT fieldValue FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid),quantityMilli),
 quantityMinMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMinMilli END,
 quantityMaxMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_lengths WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMaxMilli END,
 placedQuantityMilli=(SELECT coalesce(sum(lengthMm),0) FROM line_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND unresolved=0),
 placementStatus=CASE WHEN NOT EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND unresolved=0) THEN 'unplaced'
 WHEN EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=measurement_elements.uuid AND deletedAt IS NULL AND unresolved=0 AND (json_extract(startMounting,'$.anchor') IS NULL OR json_extract(endMounting,'$.anchor') IS NULL)) THEN 'approximate' ELSE 'exact' END,
 updatedAt=max(updatedAt+1,new.createdAt)
 WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid AND shouldWrite=1 UNION SELECT fieldName FROM ceiling_declared_lengths WHERE operationUuid=new.uuid);

 UPDATE area_features SET
elementUuid=(SELECT elementUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
role=(SELECT role FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
shapeKind=(SELECT shapeKind FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
positionsJson=(SELECT positionsJson FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
radiusMm=(SELECT radiusMm FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
twiceAreaMm2=(SELECT twiceAreaMm2 FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
mountingsJson=(SELECT mountingsJson FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
radiusMeasured=(SELECT radiusMeasured FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
needsReview=(SELECT needsReview FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
unresolved=(SELECT unresolved FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
note=(SELECT note FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid),
 updatedAt=max(updatedAt+1,new.createdAt),deletedAt=CASE WHEN (SELECT deleted FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid)=1 THEN max(updatedAt+1,new.createdAt) ELSE NULL END,
 contourUpdatedAt=max(new.expectedUpdatedAt+1,new.createdAt),lastCeilingEditUuid=new.uuid
 WHERE uuid IN (SELECT areaUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND shouldWrite=1);
 INSERT INTO area_features(uuid,measurementUuid,elementUuid,role,shapeKind,positionsJson,radiusMm,twiceAreaMm2,mountingsJson,radiusMeasured,needsReview,unresolved,note,contourUpdatedAt,createdAt,updatedAt,deletedAt,lastCeilingEditUuid)
 SELECT areaUuid,new.measurementUuid,elementUuid,role,shapeKind,positionsJson,radiusMm,twiceAreaMm2,mountingsJson,radiusMeasured,needsReview,unresolved,note,max(new.expectedUpdatedAt+1,new.createdAt),new.createdAt,new.createdAt,NULL,new.uuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND expectedUpdatedAt IS NULL;
 UPDATE measurement_elements SET
 quantityKind=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN 'exact' ELSE quantityKind END,
 quantityMilli=coalesce((SELECT fieldValue FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid),quantityMilli),
 quantityMinMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMinMilli END,
 quantityMaxMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMaxMilli END,
 placedQuantityMilli=(SELECT validMilli FROM area_element_totals WHERE elementUuid=measurement_elements.uuid),
 placementStatus=(SELECT placementStatus FROM area_element_totals WHERE elementUuid=measurement_elements.uuid),updatedAt=max(updatedAt+1,new.createdAt)
 WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND shouldWrite=1 UNION SELECT fieldName FROM ceiling_declared_areas WHERE operationUuid=new.uuid);

 INSERT INTO ceiling_operation_results(operationUuid,measurementVersion,pointVersions,elementVersions,lineVersions,areaVersions)
 SELECT new.uuid,updatedAt,
 (SELECT json_group_object(uuid,updatedAt) FROM point_placements WHERE uuid IN (SELECT pointUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid)),
 (SELECT json_group_object(uuid,updatedAt) FROM measurement_elements WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid UNION SELECT elementUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid UNION SELECT elementUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid)),
 (SELECT json_group_object(uuid,updatedAt) FROM line_placements WHERE elementUuid IN (SELECT uuid FROM measurement_elements WHERE measurementUuid=new.measurementUuid) AND deletedAt IS NULL),
 (SELECT json_group_object(uuid,updatedAt) FROM area_features WHERE measurementUuid=new.measurementUuid AND deletedAt IS NULL)
 FROM room_measurements WHERE uuid=new.measurementUuid;
END;
""".trimIndent()
)
