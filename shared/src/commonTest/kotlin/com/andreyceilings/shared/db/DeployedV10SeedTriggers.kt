package com.andreyceilings.shared.db

internal val deployedV10SeedTriggers = listOf(
    """
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
    """
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
""".trimIndent()
)
