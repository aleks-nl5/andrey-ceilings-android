#!/usr/bin/env python3
"""Regenerate v13 SQL and fingerprint literals from the pinned P3C5 handoff.
Requires that baseline commit in local git history and Python SQLite JSON support.
Historical migrations/fixtures are never rewritten. Run from any directory.
"""
from pathlib import Path
import re, sqlite3, subprocess
root=Path(__file__).resolve().parents[1]
sq=root/'shared/src/commonMain/sqldelight/com/andreyceilings/shared/db'
kt=root/'shared/src/commonMain/kotlin/com/andreyceilings/shared/db'
old=subprocess.check_output(['git','show','2027b172e7cc335a44dcacb3cb8b93c49c620ddd:shared/src/commonMain/sqldelight/com/andreyceilings/shared/db/BusinessSchema.sq'],cwd=root,text=True)
# Keep a physical deployed-v12 fixture, including all original SQL and v11 history.
if not (root/'shared/src/commonMain/sqldelight/12.db').exists():
    fixture=root/'shared/src/commonMain/sqldelight/12.db'
    c=sqlite3.connect(fixture)
    src=sqlite3.connect(root/'shared/src/commonMain/sqldelight/11.db')
    src.backup(c); src.close();c.executescript((sq/'11.sqm').read_text());c.execute('PRAGMA user_version=12');c.commit();c.close()
# Parse complete SQL statements without splitting trigger bodies.
def statements(s):
    result=[]; acc=''
    for line in s.splitlines(True):
        acc+=line
        if sqlite3.complete_statement(acc):result.append(acc.strip());acc=''
    assert not acc.strip(),acc
    return result
oldstmts=statements(old)
def name(s):
    m=re.search(r'CREATE\s+(?:TABLE|VIEW|TRIGGER|(?:UNIQUE\s+)?INDEX)\s+(\w+)',s,re.I)
    return m.group(1) if m else None
original={name(s):s for s in oldstmts if name(s)}
new={}
def add(s):
    s=s.strip();new[name(s)]=s
add('''CREATE TABLE area_features (
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
);''')
add('CREATE INDEX index_area_features_measurement ON area_features(measurementUuid,deletedAt,createdAt,uuid);')
add('CREATE INDEX index_area_features_element ON area_features(elementUuid,deletedAt);')
fields=['elementUuid','role','shapeKind','positionsJson','radiusMm','twiceAreaMm2','mountingsJson','radiusMeasured','needsReview','unresolved','note','expectedUpdatedAt','expectedElementUpdatedAt','remeasured','remeasuredRadius']
add('CREATE VIEW ceiling_operation_areas AS SELECT o.uuid AS operationUuid,json_extract(p.value,\'$.uuid\') AS areaUuid,\n'+',\n'.join(f"json_extract(p.value,'$.{f}') AS {f}" for f in fields)+",\njson_extract(p.value,'$.write') AS shouldWrite,json_extract(p.value,'$.deleted') AS deleted,json_extract(p.value,'$.confirm') AS confirmed FROM ceiling_operations o,json_each(o.payload,'$.areas') p;")
add('''CREATE VIEW ceiling_area_positions AS SELECT a.operationUuid,a.areaUuid,a.areaUuid || ':' || v.key AS pointUuid,
 CAST(v.key AS INTEGER) AS ordinal,json_extract(v.value,'$.xMm') AS xMm,json_extract(v.value,'$.yMm') AS yMm,
 json_extract(a.mountingsJson,'$[' || v.key || ']') AS mountingJson,
 a.unresolved,a.needsReview,a.shouldWrite,a.deleted,json_type(a.mountingsJson,'$[' || v.key || ']') AS mountingType,
 json_extract(q.mountingsJson,'$[' || v.key || ']') AS oldMountingJson
 FROM ceiling_operation_areas a,json_each(a.positionsJson) v LEFT JOIN area_features q ON q.uuid=a.areaUuid;''')
add('''CREATE VIEW ceiling_area_edges AS SELECT p.operationUuid,p.areaUuid,p.ordinal,p.xMm AS ax,p.yMm AS ay,n.xMm AS bx,n.yMm AS by
 FROM ceiling_area_positions p JOIN ceiling_operation_areas a ON a.operationUuid=p.operationUuid AND a.areaUuid=p.areaUuid
 JOIN ceiling_area_positions n ON n.operationUuid=p.operationUuid AND n.areaUuid=p.areaUuid AND n.ordinal=(p.ordinal+1)%json_array_length(a.positionsJson)
 WHERE a.shapeKind='POLYGON';''')
for suffix in ['feet','rays','hidden_offsets','mounting_fields','anchor_fields']:
    s=original['ceiling_line_'+suffix].replace('ceiling_line_','ceiling_area_').replace('ceiling_area_endpoints','ceiling_area_positions')
    add(s)
add("CREATE VIEW ceiling_declared_areas AS SELECT o.uuid AS operationUuid,k.key AS fieldName,k.value AS fieldValue,k.type AS fieldType FROM ceiling_operations o,json_each(o.payload,'$.declaredAreas') k;")
add("CREATE VIEW ceiling_area_position_fields AS SELECT a.operationUuid,a.areaUuid,CAST(v.key AS INTEGER) AS ordinal,k.key AS fieldName,k.type AS fieldType FROM ceiling_operation_areas a,json_each(a.positionsJson) v,json_each(v.value) k;")
add("CREATE VIEW ceiling_area_remeasurements AS SELECT a.operationUuid,a.areaUuid,r.value AS ordinal,r.type AS valueType FROM ceiling_operation_areas a,json_each(a.remeasured) r;")
add("CREATE VIEW area_feature_mountings AS SELECT a.uuid AS featureUuid,m.value AS mountingJson FROM area_features a,json_each(a.mountingsJson) m;")
add("CREATE VIEW ceiling_area_invalid_positions AS SELECT a.operationUuid,a.areaUuid FROM ceiling_operation_areas a,json_each(a.positionsJson) v WHERE v.type!='object' OR json_type(v.value,'$.xMm') IS NOT 'integer' OR json_type(v.value,'$.yMm') IS NOT 'integer' UNION SELECT operationUuid,areaUuid FROM ceiling_area_position_fields GROUP BY operationUuid,areaUuid,ordinal HAVING count(*)!=2 OR count(DISTINCT fieldName)!=2 OR sum(CASE WHEN fieldName NOT IN ('xMm','yMm') THEN 1 ELSE 0 END)>0;")
# One integer sum and one rounding; never sum independently rounded features.
add('''CREATE VIEW ceiling_area_totals AS SELECT a.operationUuid,a.elementUuid,
 CASE WHEN e.unit='шт.' THEN sum(CASE WHEN a.deleted=0 AND a.unresolved=0 THEN 1000 ELSE 0 END)
 ELSE (sum(CASE WHEN a.deleted=0 AND a.unresolved=0 THEN a.twiceAreaMm2 ELSE 0 END)+1000)/2000 END AS validMilli,
 CASE WHEN e.unit='шт.' THEN sum(CASE WHEN a.deleted=0 THEN 1000 ELSE 0 END)
 ELSE (sum(CASE WHEN a.deleted=0 THEN a.twiceAreaMm2 ELSE 0 END)+1000)/2000 END AS reservedMilli
 FROM ceiling_operation_areas a JOIN measurement_elements e ON e.uuid=a.elementUuid GROUP BY a.operationUuid,a.elementUuid;''')
add('''CREATE VIEW area_element_totals AS SELECT e.uuid AS elementUuid,
 CASE WHEN e.unit='шт.' THEN (SELECT count(*)*1000 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0)
 ELSE ((SELECT coalesce(sum(twiceAreaMm2),0) FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0)+1000)/2000 END AS validMilli,
 CASE WHEN e.unit='шт.' THEN (SELECT count(*)*1000 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL)
 ELSE ((SELECT coalesce(sum(twiceAreaMm2),0) FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL)+1000)/2000 END AS reservedMilli,
 CASE WHEN NOT EXISTS(SELECT 1 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0) THEN 'unplaced'
 WHEN EXISTS(SELECT 1 FROM area_features a WHERE a.elementUuid=e.uuid AND a.deletedAt IS NULL AND a.unresolved=0 AND
 ((a.shapeKind='CIRCLE' AND a.radiusMeasured=0) OR EXISTS(SELECT 1 FROM area_feature_mountings m WHERE m.featureUuid=a.uuid AND json_extract(m.mountingJson,'$.anchor') IS NULL))) THEN 'approximate' ELSE 'exact' END AS placementStatus
 FROM measurement_elements e WHERE e.placementKind='area';''')
# Match every persisted field against the pending immutable command, even foreign_keys=OFF.
persist=['elementUuid','role','shapeKind','positionsJson','radiusMm','twiceAreaMm2','mountingsJson','radiusMeasured','needsReview','unresolved','note']
match=' AND '.join(f'a.{f} IS new.{f}' for f in persist)
retain=' AND '.join(f'new.{f} IS old.{f}' for f in persist)
add(f'''CREATE TRIGGER area_insert_guard BEFORE INSERT ON area_features BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_areas a ON a.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid JOIN projects pr ON pr.id=m.projectId
 WHERE a.areaUuid=new.uuid AND o.uuid=new.lastCeilingEditUuid AND new.measurementUuid=o.measurementUuid
 AND m.deletedAt IS NULL AND pr.deletedAt IS NULL AND new.deletedAt IS NULL AND a.deleted=0 AND a.shouldWrite=1 AND a.expectedUpdatedAt IS NULL
 AND {match} AND new.createdAt=o.createdAt AND new.updatedAt=o.createdAt AND new.contourUpdatedAt=m.updatedAt
 AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt) AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid))
 THEN RAISE(ABORT,'area_requires_atomic_operation') END;
END;''')
add(f'''CREATE TRIGGER area_update_guard BEFORE UPDATE ON area_features BEGIN
 SELECT CASE WHEN new.uuid IS NOT old.uuid OR new.measurementUuid IS NOT old.measurementUuid OR new.elementUuid IS NOT old.elementUuid
 OR new.createdAt IS NOT old.createdAt OR old.deletedAt IS NOT NULL OR new.updatedAt<=old.updatedAt THEN RAISE(ABORT,'area_identity_or_stale') END;
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o JOIN ceiling_operation_areas a ON a.operationUuid=o.uuid
 JOIN room_measurements m ON m.uuid=o.measurementUuid WHERE o.uuid=new.lastCeilingEditUuid AND a.areaUuid=old.uuid AND o.measurementUuid=old.measurementUuid
 AND a.expectedUpdatedAt=old.updatedAt AND a.shouldWrite=1 AND {match}
 AND new.deletedAt IS CASE WHEN a.deleted=1 THEN new.updatedAt ELSE NULL END
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.contourUpdatedAt=m.updatedAt AND m.updatedAt=max(o.expectedUpdatedAt+1,o.createdAt)
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid))
 AND ((EXISTS(SELECT 1 FROM room_measurements WHERE uuid=old.measurementUuid AND deletedAt IS NOT NULL AND new.updatedAt=max(old.updatedAt+1,updatedAt))
 OR EXISTS(SELECT 1 FROM measurement_elements WHERE uuid=old.elementUuid AND deletedAt IS NOT NULL AND new.updatedAt=max(old.updatedAt+1,updatedAt)))
 AND new.deletedAt=new.updatedAt AND new.contourUpdatedAt=old.contourUpdatedAt AND new.lastCeilingEditUuid=old.lastCeilingEditUuid AND {retain})=0
 THEN RAISE(ABORT,'area_requires_atomic_operation') END;
END;''')
add("CREATE TRIGGER area_delete_guard BEFORE DELETE ON area_features BEGIN SELECT RAISE(ABORT,'area_requires_tombstone'); END;")
add('''CREATE TRIGGER area_element_guard BEFORE UPDATE ON measurement_elements BEGIN
 SELECT CASE WHEN new.deletedAt IS NULL AND new.placementKind='area' AND (old.placementKind='area' OR new.placedQuantityMilli!=0 OR new.placementStatus!='unplaced') AND NOT EXISTS(SELECT 1 FROM area_element_totals t
 WHERE t.elementUuid=old.uuid AND t.validMilli=new.placedQuantityMilli AND t.placementStatus=new.placementStatus)
 THEN RAISE(ABORT,'area_summary_requires_records') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM area_features WHERE elementUuid=old.uuid AND deletedAt IS NULL) AND new.deletedAt IS NULL AND
 (new.kind IS NOT 'catalog_item' OR new.placementKind IS NOT 'area' OR new.quantityMode IS NOT 'manual' OR new.unit NOT IN ('м²','шт.')
 OR new.quantityKind NOT IN ('unknown','exact','range') OR (new.quantityKind IN ('exact','range') AND
 CASE WHEN new.quantityKind='exact' THEN new.quantityMilli ELSE new.quantityMaxMilli END < (SELECT reservedMilli FROM area_element_totals WHERE elementUuid=old.uuid)))
 THEN RAISE(ABORT,'area_quantity_in_use') END;
END;''')
for table,condition in [('measurement_elements','elementUuid=old.uuid'),('room_measurements','measurementUuid=old.uuid'),('projects','measurementUuid IN (SELECT uuid FROM room_measurements WHERE projectId=old.id)')]:
    add(f"CREATE TRIGGER area_{table}_delete_guard BEFORE DELETE ON {table} WHEN EXISTS(SELECT 1 FROM area_features WHERE {condition}) BEGIN SELECT RAISE(ABORT,'area_parent_requires_tombstone'); END;")
    idfields=['uuid','measurementUuid','kind','priceItemUuid','priceItemCode','label','unit','quantityMode','placementKind','createdAt'] if table=='measurement_elements' else ['uuid','projectId','id'] if table=='room_measurements' else ['id']
    add(f"CREATE TRIGGER area_{table}_identity BEFORE UPDATE ON {table} WHEN ({' OR '.join('new.'+f+' IS NOT old.'+f for f in idfields)}) AND EXISTS(SELECT 1 FROM area_features WHERE {condition}) BEGIN SELECT RAISE(ABORT,'area_parent_identity_immutable'); END;")
for table,col in [('measurement_elements','elementUuid'),('room_measurements','measurementUuid')]:
    add(f'''CREATE TRIGGER area_{table}_tombstone AFTER UPDATE OF deletedAt ON {table} WHEN old.deletedAt IS NULL AND new.deletedAt IS NOT NULL BEGIN
 UPDATE area_features SET updatedAt=max(updatedAt+1,new.updatedAt),deletedAt=max(updatedAt+1,new.updatedAt) WHERE {col}=new.uuid AND deletedAt IS NULL;
END;''')
# Narrowly extend existing guards to AREA without changing historical fingerprints.
new['point_element_update_guard']=original['point_element_update_guard'].replace("coalesce(new.placementKind,'none')!='line'","coalesce(new.placementKind,'none') NOT IN ('line','area')")
for n in ['point_contour_guard','ceiling_wall_update_guard','ceiling_unplaced_geometry_walls','ceiling_measurement_version_guard']:
    s=original[n]
    s=s.replace('OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL)', 'OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid=old.uuid AND q.deletedAt IS NULL)')
    for parent in ['new.uuid','old.measurementUuid']:
        s=s.replace(f'OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid={parent} AND q.deletedAt IS NULL)', f'OR EXISTS(SELECT 1 FROM area_features WHERE measurementUuid={parent} AND deletedAt IS NULL) OR EXISTS(SELECT 1 FROM line_placements q JOIN measurement_elements e ON e.uuid=q.elementUuid WHERE e.measurementUuid={parent} AND q.deletedAt IS NULL)')
    new[n]=s
add("""CREATE TRIGGER area_measurement_write_guard BEFORE UPDATE ON room_measurements
WHEN new.deletedAt IS NULL AND EXISTS(SELECT 1 FROM area_features WHERE measurementUuid=old.uuid AND deletedAt IS NULL)
 AND (new.updatedAt IS NOT old.updatedAt OR new.name IS NOT old.name OR new.notes IS NOT old.notes)
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM ceiling_operations o WHERE o.measurementUuid=old.uuid AND o.expectedUpdatedAt=old.updatedAt
 AND new.updatedAt=max(old.updatedAt+1,o.createdAt) AND new.name=json_extract(o.payload,'$.name') AND new.notes=json_extract(o.payload,'$.notes')
 AND NOT EXISTS(SELECT 1 FROM ceiling_operation_results WHERE operationUuid=o.uuid)) THEN RAISE(ABORT,'area_requires_complete_ceiling_operation') END;
END;""")
# Extend receipt table additively.
new['ceiling_operation_results']=original['ceiling_operation_results'].replace("lineVersions)='object')","lineVersions)='object'),\n areaVersions TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(areaVersions) AND json_type(areaVersions)='object')")
# Full-set and CAS independent of KMP. Missing array remains compatible with old empty-area commands only.
validate='''
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
 OR (a.shouldWrite=0 AND (q.uuid IS NULL OR a.deleted!=0 OR MATCH_OLD))
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
'''.replace('MATCH_OLD',' OR '.join(f'a.{f} IS NOT q.{f}' for f in persist))
# Shared exact intersection predicate, all integer products within bounds.
def cross(ax,ay,bx,by,cx,cy):return f'(({bx}-{ax})*({cy}-{ay})-({by}-{ay})*({cx}-{ax}))'
def intersects(a,b):
    ax,ay,bx,by=[a+'.'+f for f in ['ax','ay','bx','by']];cx,cy,dx,dy=[b+'.'+f for f in ['ax','ay','bx','by']]
    u,v,w,z=cross(ax,ay,bx,by,cx,cy),cross(ax,ay,bx,by,dx,dy),cross(cx,cy,dx,dy,ax,ay),cross(cx,cy,dx,dy,bx,by)
    return f'max(min({ax},{bx}),min({cx},{dx}))<=min(max({ax},{bx}),max({cx},{dx})) AND max(min({ay},{by}),min({cy},{dy}))<=min(max({ay},{by}),max({cy},{dy})) AND ({u}=0 OR {v}=0 OR ({u}>0)!=({v}>0)) AND ({w}=0 OR {z}=0 OR ({w}>0)!=({z}>0))'
validate+=f'''SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_edges a JOIN ceiling_area_edges b ON b.operationUuid=a.operationUuid AND b.areaUuid=a.areaUuid
 JOIN ceiling_operation_areas f ON f.operationUuid=a.operationUuid AND f.areaUuid=a.areaUuid
 WHERE a.operationUuid=new.uuid AND (a.ax=a.bx AND a.ay=a.by OR
 (b.ordinal>a.ordinal AND b.ordinal!=a.ordinal+1 AND (a.ordinal=0 AND b.ordinal=json_array_length(f.positionsJson)-1)=0 AND {intersects('a','b')}) OR
 (b.ordinal=(a.ordinal+1)%json_array_length(f.positionsJson) AND (a.bx-a.ax)*(b.by-b.ay)-(a.by-a.ay)*(b.bx-b.ax)=0 AND (a.bx-a.ax)*(b.bx-b.ax)+(a.by-a.ay)*(b.by-b.ay)<=0)))
 THEN RAISE(ABORT,'area_self_intersection_or_degenerate') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_edges a JOIN ceiling_operation_edges w ON w.operationUuid=a.operationUuid
 JOIN ceiling_operation_areas f ON f.operationUuid=a.operationUuid AND f.areaUuid=a.areaUuid
 WHERE a.operationUuid=new.uuid AND f.deleted=0 AND f.unresolved=0 AND {intersects('a','w')}) THEN RAISE(ABORT,'area_crosses_boundary') END;
'''
# Circle distance to EACH segment. Exact integer dot tests; division only in squared perpendicular distance.
add('''CREATE VIEW ceiling_area_circle_distances AS SELECT a.operationUuid,a.areaUuid,a.radiusMm,
 (w.bx-w.ax)*(w.bx-w.ax)+(w.by-w.ay)*(w.by-w.ay) AS length2,
 (p.xMm-w.ax)*(w.bx-w.ax)+(p.yMm-w.ay)*(w.by-w.ay) AS dot,
 (w.bx-w.ax)*(p.yMm-w.ay)-(w.by-w.ay)*(p.xMm-w.ax) AS cross,
 (p.xMm-w.ax)*(p.xMm-w.ax)+(p.yMm-w.ay)*(p.yMm-w.ay) AS da,
 (p.xMm-w.bx)*(p.xMm-w.bx)+(p.yMm-w.by)*(p.yMm-w.by) AS db
 FROM ceiling_operation_areas a JOIN ceiling_area_positions p ON p.operationUuid=a.operationUuid AND p.areaUuid=a.areaUuid
 JOIN ceiling_operation_edges w ON w.operationUuid=a.operationUuid WHERE a.shapeKind='CIRCLE' AND a.deleted=0 AND a.unresolved=0;''')
validate+='''SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_area_circle_distances WHERE operationUuid=new.uuid AND
 CASE WHEN dot<=0 THEN da WHEN dot>=length2 THEN db ELSE cross*1.0*cross/length2 END<=radiusMm*radiusMm+0.002*radiusMm+0.000001)
 THEN RAISE(ABORT,'circle_crosses_boundary') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas d LEFT JOIN measurement_elements e ON e.uuid=d.fieldName LEFT JOIN ceiling_area_totals t ON t.operationUuid=d.operationUuid AND t.elementUuid=d.fieldName
 WHERE d.operationUuid=new.uuid AND (e.uuid IS NULL OR e.measurementUuid IS NOT new.measurementUuid OR e.deletedAt IS NOT NULL OR e.placementKind IS NOT 'area' OR e.quantityMode IS NOT 'manual' OR e.unit NOT IN ('м²','шт.') OR d.fieldType!='integer' OR d.fieldValue NOT BETWEEN 1 AND 9000000000000000 OR d.fieldValue IS NOT t.validMilli))
 OR (SELECT count(*) FROM ceiling_declared_areas WHERE operationUuid=new.uuid)!=(SELECT count(DISTINCT fieldName) FROM ceiling_declared_areas WHERE operationUuid=new.uuid)
 OR EXISTS(SELECT 1 FROM ceiling_area_totals t JOIN measurement_elements e ON e.uuid=t.elementUuid WHERE t.operationUuid=new.uuid AND t.reservedMilli>coalesce((SELECT fieldValue FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=e.uuid),CASE WHEN e.quantityKind='exact' THEN e.quantityMilli WHEN e.quantityKind='range' THEN e.quantityMaxMilli ELSE NULL END))
 THEN RAISE(ABORT,'area_capacity_or_declared_quantity') END;
'''
# Reuse the established SQL wall-anchor proof for all defining positions.
trigger=original['ceiling_operation_validate']
start=trigger.index(' SELECT CASE WHEN EXISTS(SELECT 1 FROM ceiling_line_endpoints p')
end=trigger.index("THEN RAISE(ABORT,'line_endpoint_anchor_or_interior') END;",start)+len("THEN RAISE(ABORT,'line_endpoint_anchor_or_interior') END;")
anchor=trigger[start:end].replace('ceiling_line_','ceiling_area_').replace('ceiling_area_endpoints','ceiling_area_positions').replace('ceiling_operation_lines','ceiling_operation_areas').replace('lineUuid','areaUuid').replace('line_endpoint_anchor_or_interior','area_position_anchor_or_interior')
# unresolved preserves all physical anchors and their original geometry context.
anchor=anchor.replace("NOT json_valid(p.mountingJson)","(p.unresolved=1 AND (json_extract(p.mountingJson,'$.anchor') IS NOT json_extract(p.oldMountingJson,'$.anchor') OR json_extract(p.mountingJson,'$.anchorGeometryJson') IS NOT json_extract(p.oldMountingJson,'$.anchorGeometryJson'))) OR NOT json_valid(p.mountingJson)")
anchor=anchor.replace("NOT json_valid(p.mountingJson)","json_extract(p.mountingJson,'$.unresolved') IS NOT p.unresolved OR json_extract(p.mountingJson,'$.needsReview') IS NOT p.needsReview OR (json_extract(p.mountingJson,'$.confirmedAt') IS NOT NULL AND json_extract(p.mountingJson,'$.confirmedAt')<0) OR (json_extract(p.mountingJson,'$.deferredAt') IS NOT NULL AND json_extract(p.mountingJson,'$.deferredAt')<0) OR NOT json_valid(p.mountingJson)")
anchor=anchor.replace("NOT json_valid(p.mountingJson)", "coalesce(json_type(p.mountingJson,'$.anchorGeometryJson'),'missing') NOT IN ('text','null') OR coalesce(json_type(p.mountingJson,'$.lastValidGeometryJson'),'missing') NOT IN ('text','null') OR (json_extract(p.mountingJson,'$.anchor') IS NOT NULL AND (json_type(p.mountingJson,'$.anchor.kind') IS NOT 'text' OR json_type(p.mountingJson,'$.anchor.firstWall') IS NOT 'text' OR coalesce(json_type(p.mountingJson,'$.anchor.secondWall'),'missing') NOT IN ('text','null') OR json_type(p.mountingJson,'$.anchor.firstMm') IS NOT 'integer' OR json_type(p.mountingJson,'$.anchor.secondMm') IS NOT 'integer')) OR NOT json_valid(p.mountingJson)")
validate+=anchor
# Area write is inside the same trigger, before element summaries and immutable receipt.
write='''
 UPDATE area_features SET
'''+',\n'.join(f'{f}=(SELECT {f} FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid)' for f in persist)+''',
 updatedAt=max(updatedAt+1,new.createdAt),deletedAt=CASE WHEN (SELECT deleted FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND areaUuid=area_features.uuid)=1 THEN max(updatedAt+1,new.createdAt) ELSE NULL END,
 contourUpdatedAt=max(new.expectedUpdatedAt+1,new.createdAt),lastCeilingEditUuid=new.uuid
 WHERE uuid IN (SELECT areaUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND shouldWrite=1);
 INSERT INTO area_features(uuid,measurementUuid,'''+','.join(persist)+''',contourUpdatedAt,createdAt,updatedAt,deletedAt,lastCeilingEditUuid)
 SELECT areaUuid,new.measurementUuid,'''+','.join(persist)+''',max(new.expectedUpdatedAt+1,new.createdAt),new.createdAt,new.createdAt,NULL,new.uuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND expectedUpdatedAt IS NULL;
 UPDATE measurement_elements SET
 quantityKind=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN 'exact' ELSE quantityKind END,
 quantityMilli=coalesce((SELECT fieldValue FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid),quantityMilli),
 quantityMinMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMinMilli END,
 quantityMaxMilli=CASE WHEN EXISTS(SELECT 1 FROM ceiling_declared_areas WHERE operationUuid=new.uuid AND fieldName=measurement_elements.uuid) THEN NULL ELSE quantityMaxMilli END,
 placedQuantityMilli=(SELECT validMilli FROM area_element_totals WHERE elementUuid=measurement_elements.uuid),
 placementStatus=(SELECT placementStatus FROM area_element_totals WHERE elementUuid=measurement_elements.uuid),updatedAt=max(updatedAt+1,new.createdAt)
 WHERE uuid IN (SELECT elementUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid AND shouldWrite=1 UNION SELECT fieldName FROM ceiling_declared_areas WHERE operationUuid=new.uuid);
'''
trigger=trigger.replace(' UPDATE room_measurements SET name=',validate+'\n UPDATE room_measurements SET name=',1)
trigger=trigger.replace(' INSERT INTO ceiling_operation_results(',write+'\n INSERT INTO ceiling_operation_results(',1)
trigger=trigger.replace('pointVersions,elementVersions,lineVersions)','pointVersions,elementVersions,lineVersions,areaVersions)')
trigger=trigger.replace('UNION SELECT elementUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid)),','UNION SELECT elementUuid FROM ceiling_operation_lines WHERE operationUuid=new.uuid UNION SELECT elementUuid FROM ceiling_operation_areas WHERE operationUuid=new.uuid)),')
trigger=trigger.replace('AND deletedAt IS NULL)\n FROM room_measurements WHERE uuid=new.measurementUuid;',"AND deletedAt IS NULL),\n (SELECT json_group_object(uuid,updatedAt) FROM area_features WHERE measurementUuid=new.measurementUuid AND deletedAt IS NULL)\n FROM room_measurements WHERE uuid=new.measurementUuid;")
new['ceiling_operation_validate']=trigger
# Emit current fingerprint only, retaining v12 as exact immutable map.
p=kt/'LinePlacementSchema.kt';txt=p.read_text();p.write_text(txt.replace('internal val pointSchemaDefinitions =','internal val pointV12SchemaDefinitions ='))
header='package com.andreyceilings.shared.db\n\n/** Complete v13 fingerprints. Historical definitions are retained verbatim. */\ninternal val pointSchemaDefinitions = pointV12SchemaDefinitions + mapOf(\n'
(kt/'AreaFeatureSchema.kt').write_text(header+',\n'.join('    "'+n+'" to """\n'+s+'\n""".trimIndent()' for n,s in new.items())+'\n)\n')
# Fresh schema and migration use the exact same definitions (ALTER normalized by SQLite).
fresh=[]
for s in oldstmts:
    n=name(s)
    fresh.append(new[n] if n in new else s)
fresh += [s for n,s in new.items() if n not in original]
(sq/'BusinessSchema.sq').write_text('\n\n'.join(fresh)+'\n')
mig=["ALTER TABLE ceiling_operation_results ADD COLUMN areaVersions TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(areaVersions) AND json_type(areaVersions)='object');"]
for n,s in new.items():
    if n=='ceiling_operation_results':continue
    if n in original:
        typ=re.search(r'CREATE\s+(\w+)',s).group(1);mig.append('DROP '+typ+' '+n+';')
    mig.append(s)
(sq/'12.sqm').write_text('\n\n'.join(mig)+'\n')
# Audit the physical fresh and migrated fingerprints using real SQLite.
f=sqlite3.connect(':memory:');f.executescript('\n'.join(s for s in fresh if name(s)))
m=sqlite3.connect(':memory:');m.executescript('\n'.join(s for s in oldstmts if name(s)));m.executescript((sq/'12.sqm').read_text())
norm=lambda sql:re.sub(r'\s*([,()])\s*',r'\1',re.sub(r'\s+',' ',sql.strip().rstrip(';'))).lower()
lookup=lambda c:{n:norm(s) for n,s in c.execute("SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL")}
a,b=lookup(f),lookup(m);assert a==b,[n for n in a if a[n]!=b.get(n)]
print('v13 BusinessSchema fresh/migrated SQLite fingerprint matches:',len(a),'objects')
