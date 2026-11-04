package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.prices.PlacementKind

data class PointPlacement(
    val uuid: String, val elementUuid: String, val xMm: Long, val yMm: Long,
    val contourUpdatedAt: Long, val createdAt: Long, val updatedAt: Long, val deletedAt: Long?
)

sealed class PointPlacementFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ParentUnavailable : PointPlacementFailure("Parent unavailable")
    class UnsupportedElement : PointPlacementFailure("Point snapshot and integer pieces required")
    class StaleContour : PointPlacementFailure("Contour changed; reload")
    class InvalidContour : PointPlacementFailure("Verified captured contour required")
    class OutsideContour : PointPlacementFailure("Point must be strictly inside the contour")
    class CapacityReached : PointPlacementFailure("All requested pieces are placed")
    class StaleVersion : PointPlacementFailure("Element or point changed; reload")
    class RetryConflict : PointPlacementFailure("Point UUID already used")
    class InvalidInput : PointPlacementFailure("Invalid coordinates, UUID or timestamp")
    class DatabaseUnavailable(cause: Throwable) : PointPlacementFailure("Database unavailable", cause)
}

class MeasurementContourHasPoints : IllegalStateException(
    "Спочатку приберіть розміщені позначки, потім змінюйте контур.")

fun MeasurementElement.pointCapacity(): Long? {
    val snapshot = source as? ElementSource.CatalogSelection ?: return null
    val exact = quantity as? ElementQuantity.Exact ?: return null
    return (exact.milli / 1000).takeIf { deletedAt == null && snapshot.placementKind == PlacementKind.POINT &&
        unit == "шт." && exact.milli in 1000..ElementQuantityRules.MAX_MILLI && exact.milli % 1000 == 0L }
}

fun pointPlacementExplanation(element: MeasurementElement): String? = when {
    element.source == ElementSource.Wish -> "Для розміщення явно оберіть точкову позицію прайсу."
    (element.source as? ElementSource.CatalogSelection)?.placementKind == PlacementKind.NONE ->
        "У збереженому виборі немає типу «Точка». Змініть тип у прайсі й явно додайте новий елемент; попередній вибір не змінюється."
    (element.source as? ElementSource.CatalogSelection)?.placementKind != PlacementKind.POINT ->
        "На цьому етапі доступні лише точкові елементи."
    element.pointCapacity() == null -> "Потрібна підтверджена ціла кількість в одиниці шт."
    else -> null
}

/** Integer cross products stay below 8e12 under the existing ±1e6 mm bounds. No snap. */
fun pointStrictlyInside(geometry: FrozenPolygonGeometry, xMm: Long, yMm: Long): Boolean {
    val bound = CeilingPolygonCalculator.MAX_COORDINATE_MM
    if (xMm !in -bound..bound || yMm !in -bound..bound) return false
    FrozenPolygonGeometryContract.requireValid(geometry)
    val p = CeilingPointMm(xMm, yMm)
    var inside = false
    geometry.vertices.forEachIndexed { i, a ->
        val b = geometry.vertices[(i + 1) % geometry.vertices.size]
        val cross = (b.xMm - a.xMm) * (p.yMm - a.yMm) - (b.yMm - a.yMm) * (p.xMm - a.xMm)
        if (cross == 0L && p.xMm in minOf(a.xMm, b.xMm)..maxOf(a.xMm, b.xMm) &&
            p.yMm in minOf(a.yMm, b.yMm)..maxOf(a.yMm, b.yMm)) return false
        if ((a.yMm > yMm) != (b.yMm > yMm) && (cross > 0) == (b.yMm > a.yMm)) inside = !inside
    }
    return inside
}

fun pointFailureMessage(failure: Throwable): String = when (failure) {
    is PointPlacementFailure.ParentUnavailable -> "Замір, елемент або об'єкт уже недоступний."
    is PointPlacementFailure.UnsupportedElement -> "Потрібна точкова позиція з цілою кількістю шт."
    is PointPlacementFailure.StaleContour -> "Контур змінився. Завершіть режим і відкрийте актуальний замір."
    is PointPlacementFailure.InvalidContour -> "Розміщення потребує збереженого виміряного контуру."
    is PointPlacementFailure.OutsideContour -> "Торкніться всередині контуру. Точки на стіні та зовні не зберігаються."
    is PointPlacementFailure.CapacityReached -> "Усю заявлену кількість уже розміщено."
    is PointPlacementFailure.StaleVersion -> "Елемент або позначка змінилися. Оберіть актуальний запис."
    is PointPlacementFailure.RetryConflict -> "Ця спроба вже має інші дані або позначку видалено. Перевірте креслення."
    is PointPlacementFailure.InvalidInput -> "Координата поза допустимими межами."
    else -> "Захищена база недоступна. Повторіть ту саму спробу після перевірки сесії."
}
