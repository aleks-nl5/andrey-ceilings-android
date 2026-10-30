package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.frozenLShapedGeometryFromSource
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.estimate.EstimateNumbering
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * A PDF view of ONE specific, already-saved SQLDelight revision.
 * All commercial numbers, units, names and quantities come from immutable
 * revision rows, never today's price catalog or the mutable legacy estimate.
 * Revision IDs remain internal and are not printed.
 */
internal data class ImmutableEstimatePdfSnapshot(
    val estimate: EstimateEntity,
    val projectTitle: String,
    val versionNumber: Long,
    val printableNumber: String
)

/**
 * Resolve by immutable series AND revision IDs. Never accept an unverified
 * caller-supplied amount or a preview. Fail closed on contradictory history.
 */
internal fun loadImmutableEstimatePdfSnapshot(
    database: PrototypeDatabase,
    seriesUuid: String,
    revisionUuid: String
): ImmutableEstimatePdfSnapshot {
    val history = ImmutableEstimateRepository(database)
    val series = checkNotNull(history.series(seriesUuid)) { "Кошторис не знайдено." }
    check(series.deletedAt == null) { "Кошторис видалено." }
    val revision = checkNotNull(history.revision(revisionUuid)) {
        "Збережену версію не знайдено."
    }
    check(revision.seriesUuid == seriesUuid && revision.versionNumber > 0L) {
        "Версія належить іншому кошторису."
    }
    val rows = history.lines(revisionUuid)
    check(rows.isNotEmpty() && revision.totalCost.isFinite() &&
        revision.totalCost >= 0.0 && rows.all {
            it.stableCode.isNotBlank() && it.titleSnapshot.isNotBlank() &&
                it.unitSnapshot.isNotBlank() && it.quantitySnapshot.isFinite() &&
                it.quantitySnapshot >= 0.0 && it.unitPriceSnapshot.isFinite() &&
                it.lineTotalSnapshot.isFinite()
        } && abs(rows.sumOf { it.lineTotalSnapshot } - revision.totalCost) < 0.01) {
        "Пошкоджено незмінні рядки або підсумок версії."
    }

    val original = JSONObject(revision.sourceMeasurementJson)
    // This is the exact immutable version, not the current estimate row.
    // The shared schema validates its complete geometry before any PDF bytes
    // are rendered. Old rectangle-only assumptions must not reject L rooms.
    val frozenLShape = frozenLShapedGeometryFromSource(
        revision.sourceMeasurementJson, revision.measurementPrecisionMeters
    )
    val dimensions = if (frozenLShape != null) {
        listOf(
            frozenLShape.sideA, frozenLShape.sideB,
            original.getDouble("areaSquareMeters"),
            original.getDouble("perimeterMeters")
        )
    } else {
        listOf(
            "lengthMeters", "widthMeters", "areaSquareMeters", "perimeterMeters"
        ).map(original::getDouble)
    }
    check(dimensions.all { it.isFinite() && it >= 0.0 }) {
        "Некоректні історичні розміри."
    }
    val name = original.getString("name")
    val material = original.getString("material")
    val profile = original.getString("profileType")
    val complexity = original.getString("complexity")
    check(name.isNotBlank() && material.isNotBlank() &&
        profile.isNotBlank() && complexity.isNotBlank())

    // Assert that the original details, when they contain an itemized list,
    // agree with the independently stored immutable revision lines. A
    // different value MUST NOT silently appear in a customer-facing PDF.
    val details = JSONObject(revision.detailsJson)
    val existing = details.optJSONArray("lineItems")
    if (existing != null) {
        check(existing.length() == rows.size) {
            "Деталі версії не збігаються зі збереженими рядками."
        }
        rows.forEachIndexed { index, row ->
            val item = existing.getJSONObject(index)
            check(item.getString("code") == row.stableCode &&
                item.getString("title") == row.titleSnapshot &&
                item.getString("unit") == row.unitSnapshot &&
                item.getDouble("quantity") == row.quantitySnapshot &&
                item.getDouble("unitPrice") == row.unitPriceSnapshot &&
                item.getDouble("total") == row.lineTotalSnapshot) {
                "Історичні рядки й деталі версії суперечать одне одному."
            }
        }
    }

    // Also support legacy v1 revisions lacking a lineItems array, but only
    // with the immutable rows saved in the history table (no price fallback).
    val printedRows = JSONArray()
    rows.forEach { row ->
        printedRows.put(JSONObject()
            .put("code", row.stableCode)
            .put("title", row.titleSnapshot)
            .put("unit", row.unitSnapshot)
            .put("quantity", row.quantitySnapshot)
            .put("unitPrice", row.unitPriceSnapshot)
            .put("total", row.lineTotalSnapshot))
    }
    details.put("lineItems", printedRows)

    val project = checkNotNull(ProjectRepository(database).find(series.projectId)) {
        "Об’єкт кошторису недоступний."
    }
    val publicNumber = EstimateNumbering.printable(
        series.documentNumber, series.localSequence,
        series.policyPrefix, series.policySequenceWidth.toInt()
    )
    val canvasCost = rows.filter { it.stableCode.startsWith("canvas.") ||
        it.stableCode == "legacy.canvas" }.sumOf { it.lineTotalSnapshot }
    val profileCost = rows.filter { it.stableCode.startsWith("profile.") ||
        it.stableCode == "legacy.profile" }.sumOf { it.lineTotalSnapshot }
    val complexityCost = rows.filter { it.stableCode == "work.multilevel" ||
        it.stableCode == "work.curved" ||
        it.stableCode == "legacy.complexity" }.sumOf { it.lineTotalSnapshot }
    return ImmutableEstimatePdfSnapshot(
        estimate = EstimateEntity(
            projectId = series.projectId,
            name = name,
            material = material,
            profileType = profile,
            complexity = complexity,
            lengthMeters = dimensions[0],
            widthMeters = dimensions[1],
            areaSquareMeters = dimensions[2],
            perimeterMeters = dimensions[3],
            canvasCost = canvasCost,
            profileCost = profileCost,
            complexityCost = complexityCost,
            optionsCost = revision.totalCost - canvasCost - profileCost - complexityCost,
            totalCost = revision.totalCost,
            detailsJson = details.toString(),
            createdAt = revision.createdAt,
            printableNumber = publicNumber,
            originalDocumentName = name,
            frozenGeometryInput = frozenLShape
        ),
        projectTitle = project.title,
        versionNumber = revision.versionNumber,
        printableNumber = publicNumber
    )
}
