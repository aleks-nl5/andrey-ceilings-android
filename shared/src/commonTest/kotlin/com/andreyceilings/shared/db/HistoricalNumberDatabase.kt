package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver

/** Historical fixtures use the SQL available in the version being tested. */
fun isP4C1AddedSnapshotColumn(table: String, column: String) =
    table == "estimate_series" && column == "publicNumber" ||
        table == "estimate_number_settings" && column in setOf("template", "suffixLabel")

val preV22EstimateTables = setOf(
    "estimates", "estimate_number_settings", "estimate_series", "estimate_revisions", "estimate_revision_lines",
    "estimate_workflow", "estimate_save_receipts", "estimate_v1_frozen", "estimate_revision_frozen",
    "estimate_revision_receipts", "estimate_revision_workflow", "estimate_revision_choice", "estimate_revision_tombstones"
)

fun withoutPreV22Estimates(rows: List<List<String?>>) = rows.filterNot { it.firstOrNull() in preV22EstimateTables }

data class HistoricalEstimate(val series: String, val first: String, val latest: String, val payload: String)

fun seedHistoricalEstimate(
    driver: SqlDriver,
    projectId: Long,
    measurementUuid: String?,
    version: Int,
    status: String = "APPROVED",
    followUp: Boolean = version >= 17
): HistoricalEstimate {
    val series = newRecordUuid()
    val first = newRecordUuid()
    val latest = if (followUp) newRecordUuid() else first
    val payload = """{"legacyEstimate":"$series"}"""
    driver.execute(null, """INSERT INTO estimate_series
        (seriesUuid,documentNumber,projectId,measurementUuid,policyTemplate,policyPrefix,policySequenceWidth,localSequence,createdAt)
        VALUES (?,?,$projectId,?,'{prefix}-{year}-{sequence}-{uuid}','AC',5,1,13)""", 3) {
        bindString(0, series)
        bindString(1, "AC-2026-00001-$series")
        bindString(2, measurementUuid)
    }
    fun revision(id: String, parent: String?, number: Int) {
        driver.execute(null, "INSERT INTO estimate_revisions VALUES (?,?,?,$number,13,'{}',?,0.01,'kmp-1','UAH','HALF_AWAY_FROM_ZERO',9999)", 4) {
            bindString(0, id); bindString(1, series); bindString(2, parent); bindString(3, payload)
        }
        driver.execute(null, "INSERT INTO estimate_revision_lines VALUES (?,0,'canvas.pvc_matte','old-uuid','OLD TEST PRICE','м²',1,9999,9999)", 1) {
            bindString(0, id)
        }
    }
    revision(first, null, 1)
    if (version >= 16) {
        driver.execute(null, "UPDATE estimate_series SET publicNumber=? WHERE seriesUuid=?", 2) {
            bindString(0, "AC-2026-00001-${series.take(8).uppercase()}"); bindString(1, series)
        }
        driver.execute(null, "INSERT INTO estimate_v1_frozen VALUES (?,?,999900,0,0,999900)", 2) {
            bindString(0, first); bindString(1, payload)
        }
        driver.execute(null, "INSERT INTO estimate_workflow VALUES (?,?,1,13)", 2) {
            bindString(0, series); bindString(1, status)
        }
        driver.execute(null, "INSERT INTO estimate_save_receipts VALUES (?,'{}',?,?)", 3) {
            bindString(0, newRecordUuid()); bindString(1, series); bindString(2, first)
        }
        if (followUp) {
            revision(latest, first, 2)
            driver.execute(null, "INSERT INTO estimate_revision_frozen VALUES (?,?,999900,0,0,999900)", 2) {
                bindString(0, latest); bindString(1, payload)
            }
            driver.execute(null, "INSERT INTO estimate_revision_receipts VALUES (?,'{}',?,?)", 3) {
                bindString(0, newRecordUuid()); bindString(1, series); bindString(2, latest)
            }
        }
    }
    if (version >= 18) {
        driver.execute(null, "INSERT INTO estimate_revision_choice VALUES (?,?,1,13)", 2) {
            bindString(0, series); bindString(1, latest)
        }
        driver.execute(null, "INSERT INTO estimate_revision_workflow VALUES (?,?,1,13)", 2) {
            bindString(0, latest); bindString(1, status)
        }
        if (followUp) {
            driver.execute(null, "INSERT INTO estimate_revision_workflow VALUES (?,'SENT',1,13)", 1) { bindString(0, first) }
        }
    }
    return HistoricalEstimate(series, first, latest, payload)
}

fun nextIndividualSequence(db: PrototypeDatabase, year: Long = 2026L): Long =
    (db.estimateIssuanceQueries.selectHighWater(year, "INDIVIDUAL").executeAsOneOrNull() ?: 0L) + 1
