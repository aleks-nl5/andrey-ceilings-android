package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.core.EstimateLineItems
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for PR #23: a saved line-item snapshot is not repriced by later
 * changes to the price list, even after closing and reopening SQLCipher.
 * Manual repricing and immutable revision history are future issues #24/#25.
 */
@RunWith(AndroidJUnit4::class)
class EstimateSnapshotPersistenceInstrumentedTest {
    @Test
    fun storedAmountsAndCodesSurvivePriceEditsDeletionDuplicationAndReopen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_estimate_snapshot.db"
        val key = ByteArray(32) { 41 }
        context.deleteDatabase(name)

        var estimateId = -1L
        var duplicateId = -1L
        val precision = MeasurementPrecision.ONE_CENTIMETER
        val details = JSONObject()
            .put("measurementPrecisionMeters", precision.stepMeters)
            .put(EstimateOptionId.LIGHT_LINE.code, precision.length(1.006))
            .put(EstimateOptionId.SPOT.code, 1)

        try {
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val database = PrototypeDatabase(driver)
                val projects = ProjectRepository(database)
                val prices = PriceRepository(database)
                val estimates = EstimateRepository(database)

                val projectId = projects.createReturningId(
                    clientId = null, title = "Snapshot test", createdAt = 100L
                )
                val spotPriceId = prices.upsertReturningId(
                    code = EstimateOptionId.SPOT.code,
                    title = "Точковий світильник", category = "lighting",
                    unit = "шт.", price = 220.0, updatedAt = 101L
                )
                val lightLinePriceId = prices.upsertReturningId(
                    code = EstimateOptionId.LIGHT_LINE.code,
                    title = "Світлова лінія", category = "lighting",
                    unit = "м.п.", price = 850.0, updatedAt = 102L
                )

                val estimateDraft = EstimateEntity(
                    projectId = projectId,
                    name = "Кухня",
                    material = "pvc_matte",
                    profileType = "standard_alu",
                    complexity = "multilevel",
                    lengthMeters = 1.01,
                    widthMeters = 1.01,
                    areaSquareMeters = 1.0201,
                    perimeterMeters = 4.04,
                    canvasCost = 296.0,
                    profileCost = 525.0,
                    complexityCost = 1300.0,
                    optionsCost = 1079.0,
                    totalCost = 3200.0
                )
                val priceSnapshots = prices.all().map { item ->
                    PriceItemEntity(
                        id = item.id,
                        code = item.code,
                        title = item.title,
                        category = item.category,
                        unit = item.unit,
                        price = item.price,
                        updatedAt = item.updatedAt
                    )
                }
                val lineItems = EstimateLineItems.create(estimateDraft, details, priceSnapshots, precision)
                assertEquals(3200.0, lineItems.sumOf { it.total }, 0.0)
                details.put("lineItems", EstimateLineItems.toJson(lineItems))
                estimateId = estimates.createReturningId(
                    projectId = projectId,
                    name = estimateDraft.name,
                    material = estimateDraft.material,
                    profileType = estimateDraft.profileType,
                    complexity = estimateDraft.complexity,
                    lengthMeters = estimateDraft.lengthMeters,
                    widthMeters = estimateDraft.widthMeters,
                    areaSquareMeters = estimateDraft.areaSquareMeters,
                    perimeterMeters = estimateDraft.perimeterMeters,
                    canvasCost = estimateDraft.canvasCost,
                    profileCost = estimateDraft.profileCost,
                    complexityCost = estimateDraft.complexityCost,
                    optionsCost = estimateDraft.optionsCost,
                    totalCost = estimateDraft.totalCost,
                    detailsJson = details.toString(),
                    createdAt = 103L
                )

                val stored = requireNotNull(estimates.find(estimateId))
                assertEquals(lineItems, EstimateLineItems.fromJson(stored.detailsJson))

                // A rename/price update must not modify an existing estimate snapshot.
                prices.update(
                    spotPriceId, EstimateOptionId.SPOT.code, "Новий світильник",
                    "lighting", "шт.", 900.0, 104L
                )
                prices.update(
                    lightLinePriceId, EstimateOptionId.LIGHT_LINE.code, "Нова лінія",
                    "lighting", "м.п.", 1250.0, 105L
                )
                prices.delete(spotPriceId)
                val afterPriceChanges = requireNotNull(estimates.find(estimateId))
                assertEquals(3200.0, afterPriceChanges.totalCost, 0.0)
                assertEquals(stored.detailsJson, afterPriceChanges.detailsJson)
                assertEquals(lineItems, EstimateLineItems.fromJson(afterPriceChanges.detailsJson))
                assertTrue(lineItems.any {
                    it.code == EstimateOptionId.SPOT.code &&
                        it.title == "Точковий світильник" && it.unitPrice == 220.0
                })

                // Duplicate remains a new database record with the same saved amounts,
                // not a recalculation against the current price list.
                duplicateId = estimates.createReturningId(
                    projectId = projectId,
                    name = "Кухня (копія)",
                    material = stored.material,
                    profileType = stored.profileType,
                    complexity = stored.complexity,
                    lengthMeters = stored.lengthMeters,
                    widthMeters = stored.widthMeters,
                    areaSquareMeters = stored.areaSquareMeters,
                    perimeterMeters = stored.perimeterMeters,
                    canvasCost = stored.canvasCost,
                    profileCost = stored.profileCost,
                    complexityCost = stored.complexityCost,
                    optionsCost = stored.optionsCost,
                    totalCost = stored.totalCost,
                    detailsJson = stored.detailsJson,
                    createdAt = 106L
                )
                assertNotEquals(estimateId, duplicateId)
                assertNotEquals(stored.uuid, requireNotNull(estimates.find(duplicateId)).uuid)
            }

            // Verify the actual encrypted SQLDelight file after the driver closes.
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val estimates = EstimateRepository(PrototypeDatabase(driver))
                val restoredOriginal = requireNotNull(estimates.find(estimateId))
                val restoredDuplicate = requireNotNull(estimates.find(duplicateId))
                for (entry in listOf(restoredOriginal, restoredDuplicate)) {
                    assertEquals(3200.0, entry.totalCost, 0.0)
                    assertEquals(precision.stepMeters, JSONObject(entry.detailsJson)
                        .getDouble("measurementPrecisionMeters"), 0.0)
                    val restoredRows = EstimateLineItems.fromJson(entry.detailsJson)
                    assertEquals(3200.0, restoredRows.sumOf { it.total }, 0.0)
                    assertTrue(restoredRows.any {
                        it.code == EstimateOptionId.LIGHT_LINE.code &&
                            it.quantity == 1.01 && it.unitPrice == 850.0
                    })
                    assertTrue(restoredRows.any {
                        it.code == EstimateOptionId.SPOT.code &&
                            it.unitPrice == 220.0
                    })
                }
                assertEquals(restoredOriginal.detailsJson, restoredDuplicate.detailsJson)
                assertNotEquals(restoredOriginal.uuid, restoredDuplicate.uuid)
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
