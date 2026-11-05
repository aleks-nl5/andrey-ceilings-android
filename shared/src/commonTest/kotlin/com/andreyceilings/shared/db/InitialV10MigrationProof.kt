package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*

/** Frozen independently from 2bc7641; recreates the FIRST v10 APK, not a current-schema approximation. */
object InitialV10MigrationProof {
    private val guards = mapOf(
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
""".trimIndent()
)
    val schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version=10L
        override fun create(driver:SqlDriver):QueryResult.Value<Unit> {
            WallAnchorMigrationProof.v10Schema.create(driver)
            guards.forEach { (name,sql) -> driver.execute(null,"DROP TRIGGER $name",0);driver.execute(null,sql,0) }
            return QueryResult.Unit
        }
        override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> = error("Historical fixture")
    }
}
