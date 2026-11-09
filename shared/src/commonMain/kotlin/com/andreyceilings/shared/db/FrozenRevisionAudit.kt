package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.estimate.*

/** Full import/reopen audit, including tombstones. Does not reprice any historical revision. */
fun verifyFrozenEstimateContents(driver: SqlDriver) {
    fun rejectRows(sql: String) {
        val invalid = driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0).value
        check(invalid == 0L) { "Invalid exact revision provenance" }
    }
    check(!driver.executeQuery(null, "PRAGMA foreign_key_check", { c -> QueryResult.Value(c.next().value) }, 0).value) { "Broken frozen revision foreign key" }
    rejectRows("""SELECT count(*) FROM estimate_workflow WHERE typeof(version) != 'integer' OR version <= 0
        OR typeof(updatedAt) != 'integer' OR updatedAt <= 0 OR status NOT IN ('DRAFT','SENT','APPROVED','REJECTED')""")
    rejectRows("""SELECT count(*) FROM estimate_revisions r WHERE
        (r.versionNumber = 1 AND r.parentRevisionUuid IS NOT NULL) OR
        (r.versionNumber > 1 AND NOT EXISTS (SELECT 1 FROM estimate_revisions p
         WHERE p.revisionUuid = r.parentRevisionUuid AND p.seriesUuid = r.seriesUuid AND p.versionNumber = r.versionNumber - 1))""")
    rejectRows("""SELECT count(*) FROM estimate_v1_frozen f JOIN estimate_revisions r USING(revisionUuid)
        WHERE r.versionNumber != 1 OR NOT EXISTS (SELECT 1 FROM estimate_save_receipts o
        WHERE o.revisionUuid = r.revisionUuid AND o.seriesUuid = r.seriesUuid)""")
    rejectRows("""SELECT count(*) FROM estimate_revision_frozen f JOIN estimate_revisions r USING(revisionUuid)
        WHERE r.versionNumber <= 1 OR NOT EXISTS (SELECT 1 FROM estimate_revision_receipts o
        WHERE o.revisionUuid = r.revisionUuid AND o.seriesUuid = r.seriesUuid)
        OR NOT EXISTS (SELECT 1 FROM estimate_v1_frozen v JOIN estimate_revisions p USING(revisionUuid)
        WHERE p.seriesUuid = r.seriesUuid AND p.versionNumber = 1)""")
    rejectRows("""SELECT count(*) FROM estimate_revisions r WHERE
        EXISTS (SELECT 1 FROM estimate_v1_frozen v JOIN estimate_revisions p USING(revisionUuid) WHERE p.seriesUuid = r.seriesUuid)
        AND NOT EXISTS (SELECT 1 FROM (SELECT revisionUuid FROM estimate_v1_frozen UNION ALL SELECT revisionUuid FROM estimate_revision_frozen) f
        WHERE f.revisionUuid = r.revisionUuid)""")
    rejectRows("""SELECT count(*) FROM estimate_save_receipts o JOIN estimate_revision_receipts n USING(operationUuid)""")
    for ((table, receipts, followUp) in listOf(Triple("estimate_v1_frozen", "estimate_save_receipts", false),
        Triple("estimate_revision_frozen", "estimate_revision_receipts", true))) {
        rejectRows("""SELECT count(*) FROM $table WHERE typeof(subtotalCents) != 'integer' OR subtotalCents < 0
            OR typeof(discountBasisPoints) != 'integer' OR discountBasisPoints NOT BETWEEN 0 AND 10000
            OR typeof(discountCents) != 'integer' OR discountCents < 0 OR discountCents > subtotalCents
            OR typeof(totalCents) != 'integer' OR totalCents != subtotalCents - discountCents""")
        rejectRows("""SELECT count(*) FROM $receipts o LEFT JOIN estimate_revisions r USING(revisionUuid)
            WHERE r.revisionUuid IS NULL OR o.seriesUuid != r.seriesUuid""")
        driver.executeQuery(null, """SELECT f.payload, f.subtotalCents, f.discountBasisPoints, f.discountCents, f.totalCents,
            s.publicNumber, s.projectId, s.measurementUuid, r.seriesUuid, r.revisionUuid, r.versionNumber, r.parentRevisionUuid,
            r.detailsJson, r.sourceMeasurementJson, o.operationUuid, o.command
            FROM $table f JOIN estimate_revisions r USING(revisionUuid) JOIN estimate_series s USING(seriesUuid)
            JOIN $receipts o USING(revisionUuid)""", { c ->
            while (c.next().value) {
                val payload = checkNotNull(c.getString(0))
                val frozen = decodeFrozenIndividualEstimate(payload)
                verifyFrozenStoredIdentity(frozen, checkNotNull(c.getLong(6)), c.getString(7), c.getString(5),
                    checkNotNull(c.getLong(1)), checkNotNull(c.getLong(2)), checkNotNull(c.getLong(3)), checkNotNull(c.getLong(4)))
                verifyFrozenRevisionIdentity(frozen, checkNotNull(c.getString(8)), checkNotNull(c.getString(9)), checkNotNull(c.getLong(10)), c.getString(11))
                check(c.getString(12) == payload && c.getString(13) == frozen.source.drawingJson)
                if (followUp) verifyFrozenRevisionReceipt(checkNotNull(c.getString(14)), checkNotNull(c.getString(15)), frozen)
                else verifyFrozenSaveReceipt(checkNotNull(c.getString(14)), checkNotNull(c.getString(15)), frozen)
            }
            QueryResult.Unit
        }, 0).value
    }
    rejectRows("""SELECT count(*) FROM estimate_series s WHERE EXISTS
        (SELECT 1 FROM estimate_revisions r JOIN estimate_v1_frozen f USING(revisionUuid) WHERE r.seriesUuid = s.seriesUuid)
        AND (s.publicNumber IS NULL OR NOT EXISTS (SELECT 1 FROM estimate_workflow w WHERE w.seriesUuid = s.seriesUuid))""")
}
