package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.estimate.*

/** Full import/reopen audit, including tombstones. Does not reprice any historical revision. */
fun verifyFrozenEstimateContents(driver: SqlDriver, version: Long = PriceCatalogSchema.version) {
    fun rejectRows(sql: String) {
        val invalid = driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0).value
        check(invalid == 0L) { "Invalid exact revision provenance" }
    }
    check(!driver.executeQuery(null, "PRAGMA foreign_key_check", { c -> QueryResult.Value(c.next().value) }, 0).value) { "Broken frozen revision foreign key" }
    val rootPolicies = mutableMapOf<String, EstimateNumberPolicy>()
    if (version >= 22) {
        val database = PrototypeDatabase(driver)
        val issuance = database.estimateIssuanceQueries
        check(EstimateNumbering.validate(EstimateNumberAllocator(database).policy()).isEmpty())
        rejectRows("SELECT count(*) FROM estimate_number_settings")
        rejectRows("""SELECT count(*) FROM estimate_series s
            LEFT JOIN estimate_number_reservations n USING(seriesUuid)
            WHERE n.seriesUuid IS NULL OR n.estimateType != 'INDIVIDUAL'
            OR s.publicNumber IS NOT n.publicNumber OR s.localSequence IS NOT n.sequence
            OR s.documentNumber != n.publicNumber || '-' || s.seriesUuid""")
        rejectRows("""SELECT count(*) FROM estimate_number_reservations n
            LEFT JOIN estimate_series s USING(seriesUuid)
            WHERE n.estimateType = 'INDIVIDUAL' AND s.seriesUuid IS NULL""")
        rejectRows("""SELECT count(*) FROM estimate_number_reservations n WHERE n.operationUuid IN
            (SELECT operationUuid FROM estimate_save_receipts UNION ALL SELECT operationUuid FROM estimate_revision_receipts)""")
        for (reservation in issuance.selectAllReservations().executeAsList()) {
            val date = kotlinx.datetime.LocalDate.parse(reservation.issueDate)
            val policy = frozenEstimateJson.decodeFromString<EstimateNumberPolicy>(reservation.policy)
            rootPolicies[reservation.seriesUuid] = policy
            reservation.operationUuid?.let { check(isEstimateUuid(it) && !reservation.command.isNullOrBlank()) }
            check(isEstimateUuid(reservation.seriesUuid) && date.year.toLong() == reservation.issueYear &&
                date.toString() == reservation.issueDate &&
                EstimateNumbering.render(policy, date, reservation.sequence) == reservation.publicNumber)
        }
    }
    if (version >= 21) {
        rejectRows("""SELECT count(*) FROM estimate_revision_tombstones d
            LEFT JOIN estimate_revision_workflow w USING(revisionUuid)
            WHERE typeof(d.deletedAt) != 'integer' OR d.deletedAt <= 0
            OR w.status IS NULL OR w.status = 'APPROVED'
            OR d.revisionUuid NOT IN (SELECT revisionUuid FROM estimate_v1_frozen UNION ALL SELECT revisionUuid FROM estimate_revision_frozen)""")
    }
    if (version >= 18) {
        rejectRows("""SELECT count(*) FROM estimate_revision_workflow w WHERE typeof(version) != 'integer' OR version <= 0
            OR (status IS NULL AND updatedAt IS NOT NULL) OR (status IS NOT NULL AND
            (status NOT IN ('DRAFT','SENT','APPROVED','REJECTED') OR typeof(updatedAt) != 'integer' OR updatedAt <= 0))""")
        rejectRows("""SELECT count(*) FROM estimate_revision_choice c WHERE typeof(version) != 'integer' OR version <= 0
            OR (selectedRevisionUuid IS NULL AND updatedAt IS NOT NULL) OR (selectedRevisionUuid IS NOT NULL AND
            (typeof(updatedAt) != 'integer' OR updatedAt <= 0 OR NOT EXISTS (SELECT 1 FROM estimate_revisions r
            WHERE r.revisionUuid = c.selectedRevisionUuid AND r.seriesUuid = c.seriesUuid
            AND r.revisionUuid IN (SELECT revisionUuid FROM estimate_v1_frozen UNION ALL SELECT revisionUuid FROM estimate_revision_frozen))))""")
        rejectRows("""SELECT count(*) FROM estimate_revisions r WHERE r.revisionUuid IN
            (SELECT revisionUuid FROM estimate_v1_frozen UNION ALL SELECT revisionUuid FROM estimate_revision_frozen)
            AND (NOT EXISTS (SELECT 1 FROM estimate_revision_workflow w WHERE w.revisionUuid = r.revisionUuid)
            OR NOT EXISTS (SELECT 1 FROM estimate_revision_choice c WHERE c.seriesUuid = r.seriesUuid))""")
    }
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
                check(version >= 21 || frozen.formatVersion < 4) { "Fixed discounts require schema 21" }
                verifyFrozenStoredIdentity(frozen, checkNotNull(c.getLong(6)), c.getString(7), c.getString(5),
                    checkNotNull(c.getLong(1)), checkNotNull(c.getLong(2)), checkNotNull(c.getLong(3)), checkNotNull(c.getLong(4)))
                verifyFrozenRevisionIdentity(frozen, checkNotNull(c.getString(8)), checkNotNull(c.getString(9)), checkNotNull(c.getLong(10)), c.getString(11))
                if (version >= 22) check(rootPolicies[c.getString(8)] == frozen.numberPolicy)
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
