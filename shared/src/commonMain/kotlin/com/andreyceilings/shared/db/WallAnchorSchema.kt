package com.andreyceilings.shared.db

/** Canonical v10 schema, checked against both fresh and migrated encrypted files. */
internal val pointSchemaDefinitions = mapOf(
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
, precision TEXT NOT NULL DEFAULT 'approximate' CHECK(precision IN ('approximate','exact')), lastMoveUuid TEXT REFERENCES point_position_operations(uuid), mountingJson TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(mountingJson) AND json_type(mountingJson) = 'object'), lastCeilingEditUuid TEXT REFERENCES ceiling_operations(uuid));
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
    "index_point_placements_element_active_order" to """
CREATE INDEX index_point_placements_element_active_order ON point_placements(elementUuid, deletedAt, createdAt, uuid);
""".trimIndent(),
    "point_delete_guard" to """
CREATE TRIGGER point_delete_guard BEFORE DELETE ON point_placements
BEGIN
    SELECT RAISE(ABORT, 'point_requires_tombstone');
END;
""".trimIndent(),
    "point_element_insert_guard" to """
CREATE TRIGGER point_element_insert_guard BEFORE INSERT ON measurement_elements
WHEN new.placedQuantityMilli != 0 OR new.placementStatus != 'unplaced'
BEGIN
    SELECT RAISE(ABORT, 'point_summary_requires_records');
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
    "index_point_position_operations_point" to """
CREATE INDEX index_point_position_operations_point ON point_position_operations(pointUuid, createdAt, uuid);
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
    "ceiling_operations" to """
CREATE TABLE ceiling_operations (
 uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid)) > 0),
 measurementUuid TEXT NOT NULL REFERENCES room_measurements(uuid),
 expectedUpdatedAt INTEGER NOT NULL CHECK(typeof(expectedUpdatedAt)='integer' AND expectedUpdatedAt>=0),
 payload TEXT NOT NULL CHECK(json_valid(payload) AND json_type(payload)='object'),
 createdAt INTEGER NOT NULL CHECK(typeof(createdAt)='integer' AND createdAt>=0 AND createdAt<9223372036854775807)
);
""".trimIndent(),
    "ceiling_operation_results" to """
CREATE TABLE ceiling_operation_results (
 operationUuid TEXT NOT NULL PRIMARY KEY REFERENCES ceiling_operations(uuid),
 measurementVersion INTEGER NOT NULL CHECK(typeof(measurementVersion)='integer' AND measurementVersion>=0),
 pointVersions TEXT NOT NULL CHECK(json_valid(pointVersions) AND json_type(pointVersions)='object'),
 elementVersions TEXT NOT NULL CHECK(json_valid(elementVersions) AND json_type(elementVersions)='object')
);
""".trimIndent(),
    "index_ceiling_operations_measurement" to """
CREATE INDEX index_ceiling_operations_measurement ON ceiling_operations(measurementUuid,createdAt,uuid);
""".trimIndent(),
    "ceiling_walls" to """
CREATE TABLE ceiling_walls (
 uuid TEXT NOT NULL PRIMARY KEY CHECK(length(trim(uuid))>0),
 measurementUuid TEXT NOT NULL REFERENCES room_measurements(uuid),
 ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 127),
 ax INTEGER NOT NULL, ay INTEGER NOT NULL, bx INTEGER NOT NULL, by INTEGER NOT NULL,
 geometryVersion INTEGER NOT NULL CHECK(typeof(geometryVersion)='integer' AND geometryVersion>=0),
 active INTEGER NOT NULL CHECK(active IN (0,1))
);
""".trimIndent(),
    "index_ceiling_walls_current" to """
CREATE UNIQUE INDEX index_ceiling_walls_current ON ceiling_walls(measurementUuid,ordinal) WHERE active=1;
""".trimIndent(),
    "ceiling_measurement_walls" to """
CREATE TRIGGER ceiling_measurement_walls AFTER INSERT ON room_measurements BEGIN
INSERT INTO ceiling_walls(uuid,measurementUuid,ordinal,ax,ay,bx,by,geometryVersion,active)
 SELECT lower(hex(randomblob(16))),m.uuid,CAST(v.key AS INTEGER),
 json_extract(v.value,'$.xMm'),json_extract(v.value,'$.yMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].xMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].yMm'),m.updatedAt,1
 FROM room_measurements m, json_each(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') v
 WHERE m.uuid=new.uuid AND json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.precisionStepMeters') IN (0.1,0.01,0.005,0.001)
 AND json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') BETWEEN 3 AND 128;
END;
""".trimIndent(),
    "ceiling_operation_points" to """
CREATE VIEW ceiling_operation_points AS
 SELECT o.uuid AS operationUuid, json_extract(p.value,'$.uuid') AS pointUuid,
 json_extract(p.value,'$.elementUuid') AS elementUuid,
 json_extract(p.value,'$.expectedUpdatedAt') AS expectedUpdatedAt,
 json_extract(p.value,'$.expectedElementUpdatedAt') AS expectedElementUpdatedAt,
 json_extract(p.value,'$.xMm') AS xMm,json_extract(p.value,'$.yMm') AS yMm,
 json_extract(p.value,'$.precision') AS precision,json_extract(p.value,'$.mountingJson') AS mountingJson,
 json_extract(p.value,'$.write') AS shouldWrite,
 coalesce(json_extract(json_extract(p.value,'$.mountingJson'),'$.unresolved'),0) AS unresolved,
 coalesce(json_extract(json_extract(p.value,'$.mountingJson'),'$.needsReview'),0) AS needsReview
 FROM ceiling_operations o,json_each(o.payload,'$.points') p;
""".trimIndent(),
    "ceiling_operation_edges" to """
CREATE VIEW ceiling_operation_edges AS
 SELECT o.uuid AS operationUuid,json_extract(w.value,'$.uuid') AS wallUuid,
 CAST(w.key AS INTEGER) AS ordinal,
 json_extract(json_extract(o.payload,'$.drawingJson'),'$.vertices[' || w.key || '].xMm') AS ax,
 json_extract(json_extract(o.payload,'$.drawingJson'),'$.vertices[' || w.key || '].yMm') AS ay,
 json_extract(json_extract(o.payload,'$.drawingJson'),'$.vertices[' || ((CAST(w.key AS INTEGER)+1)%json_array_length(o.payload,'$.walls')) || '].xMm') AS bx,
 json_extract(json_extract(o.payload,'$.drawingJson'),'$.vertices[' || ((CAST(w.key AS INTEGER)+1)%json_array_length(o.payload,'$.walls')) || '].yMm') AS by
 FROM ceiling_operations o,json_each(o.payload,'$.walls') w;
""".trimIndent(),
    "ceiling_operation_lengths" to """
CREATE VIEW ceiling_operation_lengths AS
 WITH RECURSIVE roots(operationUuid,ordinal,n,r,i) AS (
 SELECT operationUuid,ordinal,(bx-ax)*1.0*(bx-ax)+(by-ay)*1.0*(by-ay),
 max(1.0,(bx-ax)*1.0*(bx-ax)+(by-ay)*1.0*(by-ay)),0 FROM ceiling_operation_edges
 UNION ALL SELECT operationUuid,ordinal,n,(r+n/r)/2.0,i+1 FROM roots WHERE i<40)
 SELECT operationUuid,sum(r)/1000.0 AS perimeter FROM roots WHERE i=40 GROUP BY operationUuid;
""".trimIndent(),
    "ceiling_geometry_fields" to """
CREATE VIEW ceiling_geometry_fields AS SELECT o.uuid AS operationUuid,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType
 FROM ceiling_operations o,json_each(json_extract(o.payload,'$.drawingJson')) k;
""".trimIndent(),
    "ceiling_geometry_vertex_fields" to """
CREATE VIEW ceiling_geometry_vertex_fields AS SELECT o.uuid AS operationUuid,CAST(v.key AS INTEGER) AS ordinal,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType
 FROM ceiling_operations o,json_each(json_extract(o.payload,'$.drawingJson'),'$.vertices') v,json_each(v.value) k;
""".trimIndent(),
    "ceiling_geometry_measurements" to """
CREATE VIEW ceiling_geometry_measurements AS SELECT o.uuid AS operationUuid,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType
 FROM ceiling_operations o,json_each(json_extract(o.payload,'$.drawingJson'),'$.measurementsMm') k;
""".trimIndent(),
    "ceiling_geometry_side_fields" to """
CREATE VIEW ceiling_geometry_side_fields AS SELECT o.uuid AS operationUuid,CAST(v.key AS INTEGER) AS ordinal,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType
 FROM ceiling_operations o,json_each(json_extract(o.payload,'$.drawingJson'),'$.sides') v,json_each(v.value) k;
""".trimIndent(),
    "ceiling_mounting_fields" to """
CREATE VIEW ceiling_mounting_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_operation_points p,json_each(p.mountingJson) k;
""".trimIndent(),
    "ceiling_anchor_fields" to """
CREATE VIEW ceiling_anchor_fields AS SELECT p.operationUuid,p.pointUuid,k.key AS fieldName,k.type AS fieldType,k.value AS fieldValue
 FROM ceiling_operation_points p,json_each(p.mountingJson,'$.anchor') k;
""".trimIndent(),
    "ceiling_operation_feet" to """
CREATE VIEW ceiling_operation_feet AS
 SELECT p.operationUuid,p.pointUuid,w.ordinal,p.xMm AS px,p.yMm AS py,
 w.ax+(w.bx-w.ax)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fx,
 w.ay+(w.by-w.ay)*((p.xMm-w.ax)*1.0*(w.bx-w.ax)+(p.yMm-w.ay)*1.0*(w.by-w.ay))/((w.bx-w.ax)*1.0*(w.bx-w.ax)+(w.by-w.ay)*1.0*(w.by-w.ay)) AS fy
 FROM ceiling_operation_points p JOIN ceiling_operation_edges w ON w.operationUuid=p.operationUuid
 AND (w.wallUuid=json_extract(p.mountingJson,'$.anchor.firstWall') OR w.wallUuid=json_extract(p.mountingJson,'$.anchor.secondWall'));
""".trimIndent(),
    "ceiling_operation_rays" to """
CREATE VIEW ceiling_operation_rays AS
 SELECT f.operationUuid,f.pointUuid,(f.fx-f.px) AS rx,(f.fy-f.py) AS ry,
 (f.fx-f.px)*(f.fx-f.px)+(f.fy-f.py)*(f.fy-f.py) AS r2,
 (f.fx-f.px)*(w.by-w.ay)-(f.fy-f.py)*(w.bx-w.ax) AS den,
 (w.ax-f.px)*(w.by-w.ay)-(w.ay-f.py)*(w.bx-w.ax) AS t,
 (w.ax-f.px)*(f.fy-f.py)-(w.ay-f.py)*(f.fx-f.px) AS u,
 (w.ax-f.px)*(f.fx-f.px)+(w.ay-f.py)*(f.fy-f.py) AS da,
 (w.bx-f.px)*(f.fx-f.px)+(w.by-f.py)*(f.fy-f.py) AS db
 FROM ceiling_operation_feet f JOIN ceiling_operation_edges w ON w.operationUuid=f.operationUuid AND w.ordinal!=f.ordinal;
""".trimIndent(),
    "ceiling_hidden_offsets" to """
CREATE VIEW ceiling_hidden_offsets AS SELECT DISTINCT operationUuid,pointUuid FROM ceiling_operation_rays
 WHERE (abs(den)>0.0000001 AND t/den>0.00000001 AND t/den<0.99999999 AND u/den>=-0.00000001 AND u/den<=1.00000001)
 OR (abs(den)<=0.0000001 AND abs(u)<=0.0000001 AND max(0.0,min(da,db))<min(r2,max(da,db))-0.0000001);
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
 INSERT INTO ceiling_operation_results(operationUuid,measurementVersion,pointVersions,elementVersions)
 SELECT new.uuid,updatedAt,
 (SELECT json_group_object(uuid,updatedAt) FROM point_placements WHERE uuid IN (SELECT pointUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid)),
 (SELECT json_group_object(uuid,updatedAt) FROM measurement_elements WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_points WHERE operationUuid=new.uuid))
 FROM room_measurements WHERE uuid=new.measurementUuid;
END;
""".trimIndent(),
    "ceiling_operation_no_update" to """
CREATE TRIGGER ceiling_operation_no_update BEFORE UPDATE ON ceiling_operations BEGIN SELECT RAISE(ABORT,'ceiling_operation_immutable'); END;
""".trimIndent(),
    "ceiling_operation_no_delete" to """
CREATE TRIGGER ceiling_operation_no_delete BEFORE DELETE ON ceiling_operations BEGIN SELECT RAISE(ABORT,'ceiling_operation_immutable'); END;
""".trimIndent(),
    "point_contour_guard" to """
CREATE TRIGGER point_contour_guard BEFORE UPDATE ON room_measurements
WHEN ((new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters
 OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters)
 AND EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL))

BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=old.uuid AND o.expectedUpdatedAt=old.updatedAt AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson')
 AND new.shape=json_extract(o.payload,'$.shape') AND new.lengthMeters=json_extract(o.payload,'$.length')
 AND new.widthMeters=json_extract(o.payload,'$.width') AND new.areaSquareMeters=json_extract(o.payload,'$.area')
 AND new.perimeterMeters=json_extract(o.payload,'$.perimeter') AND new.name=json_extract(o.payload,'$.name') AND new.notes=json_extract(o.payload,'$.notes'))
 THEN RAISE(ABORT,'ceiling_requires_atomic_operation') END;
END;
""".trimIndent(),
    "ceiling_measurement_identity" to """
CREATE TRIGGER ceiling_measurement_identity BEFORE UPDATE ON room_measurements
WHEN (new.uuid IS NOT old.uuid OR new.projectId IS NOT old.projectId OR new.createdAt IS NOT old.createdAt) AND
 EXISTS(SELECT 1 FROM ceiling_walls WHERE measurementUuid=old.uuid)
BEGIN SELECT RAISE(ABORT,'ceiling_identity_immutable'); END;
""".trimIndent(),
    "point_identity_guard" to """
CREATE TRIGGER point_identity_guard BEFORE UPDATE ON point_placements
BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.elementUuid IS NOT old.elementUuid
 OR new.contourUpdatedAt IS NOT old.contourUpdatedAt OR new.createdAt IS NOT old.createdAt
 OR old.deletedAt IS NOT NULL OR new.updatedAt<=old.updatedAt THEN RAISE(ABORT,'point_immutable_or_stale') END;
 SELECT CASE WHEN new.deletedAt IS NOT NULL AND (new.xMm!=old.xMm OR new.yMm!=old.yMm OR new.precision!=old.precision
 OR new.lastMoveUuid IS NOT old.lastMoveUuid OR new.mountingJson!=old.mountingJson OR new.lastCeilingEditUuid IS NOT old.lastCeilingEditUuid)
 THEN RAISE(ABORT,'point_delete_cannot_move') END;
 SELECT CASE WHEN new.deletedAt IS NULL AND (
 EXISTS (SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_points p ON p.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid WHERE o.uuid=new.lastCeilingEditUuid AND o.uuid IS NOT old.lastCeilingEditUuid
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid) AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND m.drawingJson=json_extract(o.payload,'$.drawingJson') AND p.pointUuid=old.uuid AND p.expectedUpdatedAt=old.updatedAt AND p.shouldWrite=1
 AND p.xMm=new.xMm AND p.yMm=new.yMm AND p.precision=new.precision AND p.mountingJson=new.mountingJson
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.lastMoveUuid IS old.lastMoveUuid)
 OR EXISTS (SELECT 1 FROM point_position_operations o JOIN measurement_elements e ON e.uuid=old.elementUuid
 JOIN room_measurements m ON m.uuid=e.measurementUuid JOIN projects pr ON pr.id=m.projectId
 WHERE o.uuid=new.lastMoveUuid AND o.uuid IS NOT old.lastMoveUuid AND o.pointUuid=old.uuid
 AND o.expectedPointUpdatedAt=old.updatedAt AND o.expectedElementUpdatedAt=e.updatedAt AND o.contourUpdatedAt=m.updatedAt
 AND o.xMm=new.xMm AND o.yMm=new.yMm AND o.precision=new.precision AND new.updatedAt=max(old.updatedAt+1,o.createdAt)
 AND new.lastCeilingEditUuid IS old.lastCeilingEditUuid AND json_extract(new.mountingJson,'$.anchor') IS NULL
 AND coalesce(json_extract(new.mountingJson,'$.unresolved'),0)=0
 AND (new.mountingJson=old.mountingJson OR (coalesce(json_extract(new.mountingJson,'$.needsReview'),0)=1 AND json_extract(new.mountingJson,'$.confirmedAt') IS NULL))
 AND e.deletedAt IS NULL AND m.deletedAt IS NULL AND pr.deletedAt IS NULL AND e.updatedAt<9223372036854775807
 AND e.kind='catalog_item' AND e.placementKind='point' AND e.unit='шт.' AND e.quantityKind='exact')
 )=0 THEN RAISE(ABORT,'point_move_requires_cas_receipt') END;
END;
""".trimIndent(),
    "point_insert_summary" to """
CREATE TRIGGER point_insert_summary AFTER INSERT ON point_placements BEGIN
UPDATE measurement_elements SET
 placedQuantityMilli=(SELECT count(*)*1000 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0),
 placementStatus=CASE WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0) THEN 'unplaced'
 WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0 AND precision!='exact') THEN 'exact' ELSE 'approximate' END,
 updatedAt=CASE WHEN deletedAt IS NULL THEN max(updatedAt+1,new.updatedAt) ELSE updatedAt END WHERE uuid=new.elementUuid;
END;
""".trimIndent(),
    "point_remove_summary" to """
CREATE TRIGGER point_remove_summary AFTER UPDATE OF deletedAt ON point_placements BEGIN
UPDATE measurement_elements SET
 placedQuantityMilli=(SELECT count(*)*1000 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0),
 placementStatus=CASE WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0) THEN 'unplaced'
 WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0 AND precision!='exact') THEN 'exact' ELSE 'approximate' END,
 updatedAt=CASE WHEN deletedAt IS NULL THEN max(updatedAt+1,new.updatedAt) ELSE updatedAt END WHERE uuid=new.elementUuid;
END;
""".trimIndent(),
    "point_move_summary" to """
CREATE TRIGGER point_move_summary AFTER UPDATE OF xMm,yMm,precision,lastMoveUuid,mountingJson ON point_placements BEGIN
UPDATE measurement_elements SET
 placedQuantityMilli=(SELECT count(*)*1000 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0),
 placementStatus=CASE WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0) THEN 'unplaced'
 WHEN NOT EXISTS(SELECT 1 FROM point_placements WHERE elementUuid=new.elementUuid AND deletedAt IS NULL AND coalesce(json_extract(mountingJson,'$.unresolved'),0)=0 AND precision!='exact') THEN 'exact' ELSE 'approximate' END,
 updatedAt=CASE WHEN deletedAt IS NULL THEN max(updatedAt+1,new.updatedAt) ELSE updatedAt END WHERE uuid=new.elementUuid;
END;
""".trimIndent(),
    "point_element_update_guard" to """
CREATE TRIGGER point_element_update_guard BEFORE UPDATE ON measurement_elements
BEGIN
    SELECT CASE WHEN new.deletedAt IS NULL AND (
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
        AND e.placedQuantityMilli = (SELECT count(*) * 1000 FROM point_placements a WHERE a.elementUuid = e.uuid AND a.deletedAt IS NULL AND coalesce(json_extract(a.mountingJson,'$.unresolved'),0)=0)
        AND (SELECT count(*)*1000 FROM point_placements a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL)<=e.quantityMilli AND (
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
""".trimIndent(),
    "point_insert_guard" to """
CREATE TRIGGER point_insert_guard BEFORE INSERT ON point_placements
BEGIN
    SELECT CASE WHEN new.mountingJson!='{}' OR new.lastCeilingEditUuid IS NOT NULL OR new.deletedAt IS NOT NULL OR new.precision != 'approximate' OR new.lastMoveUuid IS NOT NULL OR NOT EXISTS (
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
    "ceiling_wall_update_guard" to """
CREATE TRIGGER ceiling_wall_update_guard BEFORE UPDATE ON ceiling_walls
BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR (NOT EXISTS (
 SELECT 1 FROM room_measurements m JOIN ceiling_operations o ON o.measurementUuid=m.uuid
 WHERE m.uuid=old.measurementUuid AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid) AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND ((new.active=0 AND old.active=1 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND old.geometryVersion<m.updatedAt AND json_extract(o.payload,'$.drawingJson')!=json_extract(o.payload,'$.oldDrawingJson')) OR EXISTS(SELECT 1 FROM ceiling_operation_edges w WHERE w.operationUuid=o.uuid AND w.wallUuid=new.uuid
 AND new.ordinal=w.ordinal AND new.ax=w.ax AND new.ay=w.ay AND new.bx=w.bx AND new.by=w.by AND new.geometryVersion=m.updatedAt)))
 AND (new.active=0 AND new.ordinal=old.ordinal AND new.ax=old.ax AND new.ay=old.ay AND new.bx=old.bx AND new.by=old.by AND new.geometryVersion=old.geometryVersion AND NOT EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.measurementUuid AND q.deletedAt IS NULL))=0)
 THEN RAISE(ABORT,'ceiling_wall_requires_operation') END;
END;
""".trimIndent(),
    "ceiling_wall_delete_guard" to """
CREATE TRIGGER ceiling_wall_delete_guard BEFORE DELETE ON ceiling_walls BEGIN SELECT RAISE(ABORT,'ceiling_wall_identity_immutable'); END;
""".trimIndent(),
    "ceiling_wall_insert_guard" to """
CREATE TRIGGER ceiling_wall_insert_guard BEFORE INSERT ON ceiling_walls
BEGIN
 SELECT CASE WHEN new.active!=1 OR NOT EXISTS(SELECT 1 FROM room_measurements m WHERE m.uuid=new.measurementUuid
 AND new.geometryVersion=m.updatedAt AND new.ordinal<json_array_length(m.drawingJson,'$.vertices')
 AND new.ax=json_extract(m.drawingJson,'$.vertices['||new.ordinal||'].xMm')
 AND new.ay=json_extract(m.drawingJson,'$.vertices['||new.ordinal||'].yMm')
 AND new.bx=json_extract(m.drawingJson,'$.vertices['||((new.ordinal+1)%json_array_length(m.drawingJson,'$.vertices'))||'].xMm')
 AND new.by=json_extract(m.drawingJson,'$.vertices['||((new.ordinal+1)%json_array_length(m.drawingJson,'$.vertices'))||'].yMm'))
 THEN RAISE(ABORT,'ceiling_wall_invalid_geometry') END;
END;
""".trimIndent(),
    "ceiling_unplaced_geometry_walls" to """
CREATE TRIGGER ceiling_unplaced_geometry_walls AFTER UPDATE OF drawingJson ON room_measurements
WHEN new.drawingJson IS NOT old.drawingJson AND NOT EXISTS(SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=new.uuid AND new.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND new.drawingJson=json_extract(o.payload,'$.drawingJson'))
 AND NOT EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=new.uuid AND q.deletedAt IS NULL)
BEGIN
 UPDATE ceiling_walls SET active=0 WHERE measurementUuid=new.uuid AND active=1;
INSERT INTO ceiling_walls(uuid,measurementUuid,ordinal,ax,ay,bx,by,geometryVersion,active)
 SELECT lower(hex(randomblob(16))),m.uuid,CAST(v.key AS INTEGER),
 json_extract(v.value,'$.xMm'),json_extract(v.value,'$.yMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].xMm'),
 json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices[' || ((CAST(v.key AS INTEGER)+1)%json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices')) || '].yMm'),m.updatedAt,1
 FROM room_measurements m, json_each(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') v
 WHERE m.uuid=new.uuid AND json_extract(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.precisionStepMeters') IN (0.1,0.01,0.005,0.001)
 AND json_array_length(CASE WHEN json_valid(m.drawingJson) THEN m.drawingJson ELSE '{}' END,'$.vertices') BETWEEN 3 AND 128;
END;
""".trimIndent(),
    "ceiling_result_no_update" to """
CREATE TRIGGER ceiling_result_no_update BEFORE UPDATE ON ceiling_operation_results BEGIN SELECT RAISE(ABORT,'ceiling_result_immutable'); END;
""".trimIndent(),
    "ceiling_result_no_delete" to """
CREATE TRIGGER ceiling_result_no_delete BEFORE DELETE ON ceiling_operation_results BEGIN SELECT RAISE(ABORT,'ceiling_result_immutable'); END;
""".trimIndent(),
    "ceiling_measurement_version_guard" to """
CREATE TRIGGER ceiling_measurement_version_guard BEFORE UPDATE ON room_measurements
WHEN EXISTS(SELECT 1 FROM point_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL)
 AND (typeof(new.updatedAt)!='integer' OR new.updatedAt<old.updatedAt OR ((new.name IS NOT old.name OR new.notes IS NOT old.notes OR new.deletedAt IS NOT old.deletedAt
 OR new.drawingJson IS NOT old.drawingJson OR new.shape IS NOT old.shape OR new.areaSquareMeters IS NOT old.areaSquareMeters OR new.perimeterMeters IS NOT old.perimeterMeters
 OR new.lengthMeters IS NOT old.lengthMeters OR new.widthMeters IS NOT old.widthMeters) AND new.updatedAt<=old.updatedAt))
BEGIN SELECT RAISE(ABORT,'ceiling_version_must_increase'); END;
""".trimIndent()
)
