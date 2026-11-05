package com.andreyceilings.shared.db

/** Exact v12 fingerprint. Historical lineages remain immutable. */
internal val pointV12SchemaDefinitions = pointV11SchemaDefinitions + mapOf(
    "ceiling_operation_results" to """
CREATE TABLE ceiling_operation_results (
 operationUuid TEXT NOT NULL PRIMARY KEY REFERENCES ceiling_operations(uuid),
 measurementVersion INTEGER NOT NULL CHECK(typeof(measurementVersion)='integer' AND measurementVersion>=0),
 pointVersions TEXT NOT NULL CHECK(json_valid(pointVersions) AND json_type(pointVersions)='object'),
 elementVersions TEXT NOT NULL CHECK(json_valid(elementVersions) AND json_type(elementVersions)='object'),
 lineVersions TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(lineVersions) AND json_type(lineVersions)='object')
);
""".trimIndent(),
    "line_placements" to """
CREATE TABLE line_placements (
 uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid))>0),
 elementUuid TEXT NOT NULL REFERENCES measurement_elements(uuid),
 startX INTEGER NOT NULL CHECK(typeof(startX)='integer' AND startX BETWEEN -1000000 AND 1000000),
 startY INTEGER NOT NULL CHECK(typeof(startY)='integer' AND startY BETWEEN -1000000 AND 1000000),
 endX INTEGER NOT NULL CHECK(typeof(endX)='integer' AND endX BETWEEN -1000000 AND 1000000),
 endY INTEGER NOT NULL CHECK(typeof(endY)='integer' AND endY BETWEEN -1000000 AND 1000000),
 lengthMm INTEGER NOT NULL CHECK(typeof(lengthMm)='integer' AND lengthMm BETWEEN 1 AND 2828427
 AND 4*((endX-startX)*(endX-startX)+(endY-startY)*(endY-startY)) >= (2*lengthMm-1)*(2*lengthMm-1)
 AND 4*((endX-startX)*(endX-startX)+(endY-startY)*(endY-startY)) < (2*lengthMm+1)*(2*lengthMm+1)),
 widthMm INTEGER CHECK(widthMm IS NULL OR (typeof(widthMm)='integer' AND widthMm BETWEEN 1 AND 1000000)),
 note TEXT NOT NULL,
 startMounting TEXT NOT NULL CHECK(json_valid(startMounting) AND json_type(startMounting)='object'),
 endMounting TEXT NOT NULL CHECK(json_valid(endMounting) AND json_type(endMounting)='object'),
 needsReview INTEGER NOT NULL CHECK(needsReview IN (0,1)),
 unresolved INTEGER NOT NULL CHECK(unresolved IN (0,1) AND (unresolved=0 OR needsReview=1)),
 contourUpdatedAt INTEGER NOT NULL CHECK(typeof(contourUpdatedAt)='integer' AND contourUpdatedAt>=0),
 createdAt INTEGER NOT NULL CHECK(typeof(createdAt)='integer' AND createdAt>=0),
 updatedAt INTEGER NOT NULL CHECK(typeof(updatedAt)='integer' AND updatedAt>=createdAt),
 deletedAt INTEGER CHECK(deletedAt IS NULL OR (typeof(deletedAt)='integer' AND deletedAt=updatedAt)),
 lastCeilingEditUuid TEXT NOT NULL REFERENCES ceiling_operations(uuid)
);
""".trimIndent(),
    "index_line_placements_element" to """
CREATE INDEX index_line_placements_element ON line_placements(elementUuid,deletedAt,createdAt,uuid);
""".trimIndent(),
    "ceiling_operation_lines" to """
CREATE VIEW ceiling_operation_lines AS
 SELECT o.uuid AS operationUuid,json_extract(p.value,'$.uuid') AS lineUuid,
 json_extract(p.value,'$.elementUuid') AS elementUuid,
 json_extract(p.value,'$.expectedUpdatedAt') AS expectedUpdatedAt,
 json_extract(p.value,'$.expectedElementUpdatedAt') AS expectedElementUpdatedAt,
 json_extract(p.value,'$.startX') AS startX,json_extract(p.value,'$.startY') AS startY,
 json_extract(p.value,'$.endX') AS endX,json_extract(p.value,'$.endY') AS endY,
 json_extract(p.value,'$.lengthMm') AS lengthMm,json_extract(p.value,'$.widthMm') AS widthMm,
 json_extract(p.value,'$.note') AS note,json_extract(p.value,'$.startMounting') AS startMounting,
 json_extract(p.value,'$.endMounting') AS endMounting,
 json_extract(p.value,'$.needsReview') AS needsReview,json_extract(p.value,'$.unresolved') AS unresolved,
 json_extract(p.value,'$.deleted') AS deleted,json_extract(p.value,'$.write') AS shouldWrite,
 json_extract(p.value,'$.confirm') AS confirmed,
 json_extract(p.value,'$.remeasuredStart') AS remeasuredStart,json_extract(p.value,'$.remeasuredEnd') AS remeasuredEnd
 FROM ceiling_operations o,json_each(o.payload,'$.lines') p;
""".trimIndent(),
    "ceiling_line_endpoints" to """
CREATE VIEW ceiling_line_endpoints AS
 SELECT l.operationUuid,l.lineUuid || ':start' AS pointUuid,l.lineUuid,l.startX AS xMm,l.startY AS yMm,l.startMounting AS mountingJson,
 l.unresolved,l.needsReview,l.shouldWrite,l.deleted,q.startMounting AS oldMountingJson
 FROM ceiling_operation_lines l LEFT JOIN line_placements q ON q.uuid=l.lineUuid
 UNION ALL
 SELECT l.operationUuid,l.lineUuid || ':end',l.lineUuid,l.endX,l.endY,l.endMounting,l.unresolved,l.needsReview,l.shouldWrite,l.deleted,q.endMounting
 FROM ceiling_operation_lines l LEFT JOIN line_placements q ON q.uuid=l.lineUuid;
""".trimIndent(),
    "ceiling_line_feet" to """
CREATE VIEW ceiling_line_feet AS
 SELECT p.operationUuid,p.pointUuid,w.ordinal,p.xMm AS px,p.yMm AS py,
 w.ax+(w.bx-w.ax)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fx,
 w.ay+(w.by-w.ay)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fy
 FROM ceiling_line_endpoints p JOIN ceiling_operation_edges w ON w.operationUuid=p.operationUuid
 AND (w.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') OR w.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall'));
""".trimIndent(),
    "ceiling_line_rays" to """
CREATE VIEW ceiling_line_rays AS
 SELECT f.operationUuid,f.pointUuid,(f.fx-f.px) AS rx,(f.fy-f.py) AS ry,
 (f.fx-f.px)*(f.fx-f.px)+(f.fy-f.py)*(f.fy-f.py) AS r2,
 (f.fx-f.px)*(w.by-w.ay)-(f.fy-f.py)*(w.bx-w.ax) AS den,
 (w.ax-f.px)*(w.by-w.ay)-(w.ay-f.py)*(w.bx-w.ax) AS t,
 (w.ax-f.px)*(f.fy-f.py)-(w.ay-f.py)*(f.fx-f.px) AS u,
 (w.ax-f.px)*(f.fx-f.px)+(w.ay-f.py)*(f.fy-f.py) AS da,
 (w.bx-f.px)*(f.fx-f.px)+(w.by-f.py)*(f.fy-f.py) AS db
 FROM ceiling_line_feet f JOIN ceiling_operation_edges w ON w.operationUuid=f.operationUuid AND w.ordinal!=f.ordinal;
""".trimIndent(),
    "ceiling_line_hidden_offsets" to """
CREATE VIEW ceiling_line_hidden_offsets AS SELECT DISTINCT operationUuid,pointUuid FROM ceiling_line_rays
 WHERE (abs(den)>0.0000001 AND t/den>0.00000001 AND t/den<0.99999999 AND u/den>=-0.00000001 AND u/den<=1.00000001)
 OR (abs(den)<=0.0000001 AND abs(u)<=0.0000001 AND max(0.0,min(da,db))<min(r2,max(da,db))-0.0000001);
""".trimIndent(),
    "ceiling_declared_lengths" to """
CREATE VIEW ceiling_declared_lengths AS SELECT o.uuid AS operationUuid,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType
 FROM ceiling_operations o,json_each(o.payload,'$.declaredLengths') k;
""".trimIndent(),
    "ceiling_line_mounting_fields" to """
CREATE VIEW ceiling_line_mounting_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_line_endpoints p,json_each(p.mountingJson) k;
""".trimIndent(),
    "ceiling_line_anchor_fields" to """
CREATE VIEW ceiling_line_anchor_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_line_endpoints p,json_each(p.mountingJson,'$.anchor') k;
""".trimIndent(),
    "line_insert_guard" to """
CREATE TRIGGER line_insert_guard BEFORE INSERT ON line_placements BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_lines l ON l.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid JOIN measurement_elements e ON e.uuid=l.elementUuid
 WHERE o.uuid=new.lastCeilingEditUuid AND l.lineUuid=new.uuid AND l.elementUuid=new.elementUuid AND l.expectedUpdatedAt IS NULL
 AND l.shouldWrite=1 AND l.deleted=0 AND new.deletedAt IS NULL AND l.startX IS new.startX AND l.startY IS new.startY AND l.endX IS new.endX AND l.endY IS new.endY AND l.lengthMm IS new.lengthMm AND l.widthMm IS new.widthMm AND l.note IS new.note AND l.startMounting IS new.startMounting AND l.endMounting IS new.endMounting AND l.needsReview IS new.needsReview AND l.unresolved IS new.unresolved
 AND e.measurementUuid=m.uuid AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND new.contourUpdatedAt=m.updatedAt
 AND new.createdAt=o.createdAt AND new.updatedAt=o.createdAt
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)) THEN RAISE(ABORT,'line_requires_atomic_operation') END;
END;
""".trimIndent(),
    "line_update_guard" to """
CREATE TRIGGER line_update_guard BEFORE UPDATE ON line_placements BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.elementUuid IS NOT old.elementUuid OR new.createdAt IS NOT old.createdAt OR old.deletedAt IS NOT NULL OR new.updatedAt<=old.updatedAt
 THEN RAISE(ABORT,'line_identity_or_stale') END;
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM measurement_elements e WHERE e.uuid=old.elementUuid AND e.deletedAt IS NOT NULL AND new.deletedAt=new.updatedAt AND new.updatedAt=max(old.updatedAt+1,e.updatedAt) AND new.contourUpdatedAt=old.contourUpdatedAt AND new.lastCeilingEditUuid=old.lastCeilingEditUuid
 AND new.startX IS old.startX AND new.startY IS old.startY AND new.endX IS old.endX AND new.endY IS old.endY AND new.lengthMm IS old.lengthMm AND new.widthMm IS old.widthMm AND new.note IS old.note AND new.startMounting IS old.startMounting AND new.endMounting IS old.endMounting AND new.needsReview IS old.needsReview AND new.unresolved IS old.unresolved)
 AND NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_lines l ON l.operationUuid=o.uuid JOIN room_measurements m ON m.uuid=o.measurementUuid
 WHERE o.uuid=new.lastCeilingEditUuid AND l.lineUuid=old.uuid AND l.expectedUpdatedAt=old.updatedAt AND l.shouldWrite=1
 AND l.startX IS new.startX AND l.startY IS new.startY AND l.endX IS new.endX AND l.endY IS new.endY AND l.lengthMm IS new.lengthMm AND l.widthMm IS new.widthMm AND l.note IS new.note AND l.startMounting IS new.startMounting AND l.endMounting IS new.endMounting AND l.needsReview IS new.needsReview AND l.unresolved IS new.unresolved
 AND new.deletedAt IS CASE WHEN l.deleted=1 THEN new.updatedAt ELSE NULL END
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.contourUpdatedAt=m.updatedAt AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)) THEN RAISE(ABORT,'line_requires_atomic_operation') END;
END;
""".trimIndent(),
    "line_delete_guard" to """
CREATE TRIGGER line_delete_guard BEFORE DELETE ON line_placements BEGIN SELECT RAISE(ABORT,'line_requires_tombstone'); END;
""".trimIndent(),
    "line_element_guard" to """
CREATE TRIGGER line_element_guard BEFORE UPDATE ON measurement_elements BEGIN
 SELECT CASE WHEN new.deletedAt IS NULL AND new.placementKind='line' AND (
 new.placedQuantityMilli!=(SELECT coalesce(sum(lengthMm),0) FROM line_placements WHERE elementUuid=old.uuid AND deletedAt IS NULL AND unresolved=0)
 OR new.placementStatus!=CASE WHEN NOT EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=old.uuid AND deletedAt IS NULL AND unresolved=0) THEN 'unplaced'
 WHEN EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=old.uuid AND deletedAt IS NULL AND unresolved=0 AND (json_extract(startMounting,'$.anchor') IS NULL OR json_extract(endMounting,'$.anchor') IS NULL)) THEN 'approximate' ELSE 'exact' END)
 THEN RAISE(ABORT,'line_summary_requires_records') END;
 SELECT CASE WHEN new.deletedAt IS NULL AND EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=old.uuid AND deletedAt IS NULL) AND (
 new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR new.kind!='catalog_item' OR new.placementKind IS NOT 'line' OR new.unit IS NOT 'м.п.'
 OR new.quantityMode NOT IN ('manual','entered_length') OR new.quantityKind NOT IN ('unknown','exact','range')
 OR (new.quantityKind IN ('exact','range') AND CASE WHEN new.quantityKind='exact' THEN new.quantityMilli ELSE new.quantityMaxMilli END <
 (SELECT coalesce(sum(lengthMm),0) FROM line_placements WHERE elementUuid=old.uuid AND deletedAt IS NULL))) THEN RAISE(ABORT,'line_quantity_in_use') END;
END;
""".trimIndent(),
    "line_element_identity" to """
CREATE TRIGGER line_element_identity BEFORE UPDATE ON measurement_elements
WHEN (new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid)
 AND EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=old.uuid)
BEGIN SELECT RAISE(ABORT,'line_parent_identity_immutable'); END;
""".trimIndent(),
    "line_element_delete_guard" to """
CREATE TRIGGER line_element_delete_guard BEFORE DELETE ON measurement_elements
WHEN EXISTS(SELECT 1 FROM line_placements WHERE elementUuid=old.uuid)
BEGIN SELECT RAISE(ABORT,'line_parent_requires_tombstone'); END;
""".trimIndent(),
    "line_measurement_delete_guard" to """
CREATE TRIGGER line_measurement_delete_guard BEFORE DELETE ON room_measurements
WHEN EXISTS(SELECT 1 FROM line_placements l JOIN measurement_elements e ON e.uuid=l.elementUuid WHERE e.measurementUuid=old.uuid)
BEGIN SELECT RAISE(ABORT,'line_parent_requires_tombstone'); END;
""".trimIndent(),
    "line_project_delete_guard" to """
CREATE TRIGGER line_project_delete_guard BEFORE DELETE ON projects
WHEN EXISTS(SELECT 1 FROM line_placements l JOIN measurement_elements e ON e.uuid=l.elementUuid JOIN room_measurements m ON m.uuid=e.measurementUuid WHERE m.projectId=old.id)
BEGIN SELECT RAISE(ABORT,'line_parent_requires_tombstone'); END;
""".trimIndent(),
    "line_element_tombstone" to """
CREATE TRIGGER line_element_tombstone AFTER UPDATE OF deletedAt ON measurement_elements
WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL BEGIN
 UPDATE line_placements SET updatedAt=max(updatedAt+1,new.updatedAt),deletedAt=max(updatedAt+1,new.updatedAt) WHERE elementUuid=new.uuid AND deletedAt IS NULL;
END;
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
 INSERT INTO ceiling_operation_results(operationUuid,measurementVersion,pointVersions,elementVersions,lineVersions)
 SELECT new.uuid,updatedAt,
 (SELECT json_group_object(uuid,updatedAt) FROM point_placements WHERE uuid IN (SELECT pointUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid)),
 (SELECT json_group_object(uuid,updatedAt) FROM measurement_elements WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid UNION SELECT elementUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid)),
 (SELECT json_group_object(uuid,updatedAt) FROM line_placements WHERE elementUuid IN (SELECT uuid FROM measurement_elements WHERE measurementUuid=new.measurementUuid) AND deletedAt IS NULL)
 FROM room_measurements WHERE uuid=new.measurementUuid;
END;
""".trimIndent(),
    "point_contour_guard" to """
CREATE TRIGGER point_contour_guard BEFORE UPDATE ON room_measurements
WHEN ((new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters
 OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters)
 AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL)))

BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=old.uuid AND o.expectedUpdatedAt=old.updatedAt AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson')
 AND new.shape=json_extract(o.payload,'$.shape') AND new.lengthMeters=json_extract(o.payload,'$.length')
 AND new.widthMeters=json_extract(o.payload,'$.width') AND new.areaSquareMeters=json_extract(o.payload,'$.area')
 AND new.perimeterMeters=json_extract(o.payload,'$.perimeter') AND new.name=json_extract(o.payload,'$.name') AND new.notes=json_extract(o.payload,'$.notes'))
 THEN RAISE(ABORT,'ceiling_requires_atomic_operation') END;
END;
""".trimIndent(),
    "point_element_update_guard" to """
CREATE TRIGGER point_element_update_guard BEFORE UPDATE ON measurement_elements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NULL AND coalesce(new.placementKind,'none')!='line' AND (
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
    "ceiling_wall_update_guard" to """
CREATE TRIGGER ceiling_wall_update_guard BEFORE UPDATE ON ceiling_walls
BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR (NOT EXISTS (
 SELECT 1 FROM room_measurements m JOIN ceiling_operations o ON o.measurementUuid=m.uuid
 WHERE m.uuid=old.measurementUuid AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid) AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND ((new.active=0 AND old.active=1 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND old.geometryVersion<m.updatedAt AND json_extract(o.payload,'$.drawingJson')!=json_extract(o.payload,'$.oldDrawingJson')) OR EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=o.uuid AND w.wallUuid=new.uuid
 AND new.ordinal=w.ordinal AND new.ax=w.ax AND new.ay=w.ay AND new.bx=w.bx AND new.by=w.by AND new.geometryVersion=m.updatedAt)))
 AND (new.active=0 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.measurementUuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.measurementUuid AND q.deletedAt IS NULL))=0)=0)
 THEN RAISE(ABORT,'ceiling_wall_requires_operation') END;
END;
""".trimIndent(),
    "ceiling_unplaced_geometry_walls" to """
CREATE TRIGGER ceiling_unplaced_geometry_walls AFTER UPDATE OF drawingJson ON room_measurements
WHEN new.drawingJson IS NOT old.drawingJson AND NOT EXISTS(SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=new.uuid AND new.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson'))
 AND (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.uuid AND q.deletedAt IS NULL))=0
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
WHEN (EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL))
 AND (typeof(new.updatedAt)!='integer' OR new.updatedAt<old.updatedAt OR ((new.name IS NOT old.name OR new.notes IS NOT old.notes OR new.deletedAt IS NOT old.deletedAt
 OR new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters) AND new.updatedAt<=old.updatedAt))
BEGIN SELECT RAISE(ABORT,'ceiling_version_must_increase'); END;
""".trimIndent()
)
