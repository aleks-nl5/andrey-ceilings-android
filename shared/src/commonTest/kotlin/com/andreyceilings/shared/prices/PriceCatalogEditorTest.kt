package com.andreyceilings.shared.prices

import kotlin.test.*

class PriceCatalogEditorTest {
    private val area = PriceCatalogGroup("area", "Площа", 1, "м²", QuantityMode.CEILING_AREA, PlacementKind.AREA)
    private val empty = PriceCatalogGroup("empty", "Порожня", 1, null, QuantityMode.ENTERED_LENGTH, PlacementKind.LINE)

    @Test fun defaultsCopyOnceAndExplicitResetChangesOnlyParameters() {
        val draft = ConfiguredPriceDraft(title = "Моя позиція", salePriceText = "25,50").selectGroup(area)
        assertEquals("м²", draft.unit)
        assertEquals(QuantityMode.CEILING_AREA, draft.quantityMode)
        val custom = draft.copy(unit = "шт.", quantityMode = QuantityMode.MANUAL,
            placementKind = PlacementKind.POINT, parametersEdited = true)
        val moved = custom.selectGroup(empty)
        assertEquals(custom.copy(groupUuid = empty.uuid), moved)
        val reset = moved.withDefaults(empty)
        assertEquals("", reset.unit)
        assertEquals(empty.uuid, reset.groupUuid)
        assertEquals(custom.title, reset.title)
        assertEquals(custom.salePriceText, reset.salePriceText)
        assertEquals(QuantityMode.ENTERED_LENGTH, reset.quantityMode)
        assertEquals("Вкажіть одиницю вимірювання.", reset.validationError(listOf(empty)))
        assertNull(reset.copy(unit = "м.п.").validationError(listOf(empty)))
        val editedBeforeSelection = ConfiguredPriceDraft(unit = "год.", parametersEdited = true).selectGroup(area)
        assertEquals("год.", editedBeforeSelection.unit)
        assertEquals(QuantityMode.MANUAL, editedBeforeSelection.quantityMode)
    }

    @Test fun sequentialCategoryCreateCancelAndSuccessRetainItemDraftAndFocus() {
        val editor = PriceItemEditor.open(null).copy(draft = ConfiguredPriceDraft(
            title = "Введена назва", salePriceText = "29,50", unit = "шт.",
            placementKind = PlacementKind.POINT, parametersEdited = true), focusField = "price")
        // A child form owns no item state. Cancelling it returns this same immutable snapshot.
        assertEquals("Введена назва", editor.draft.title)
        assertEquals("price", editor.focusField)
        val afterCreate = editor.categoryCreated(area)
        assertEquals(editor.draft.copy(groupUuid = area.uuid), afterCreate.draft)
        assertEquals(editor.original, afterCreate.original)
        assertEquals(editor.focusField, afterCreate.focusField)
        assertTrue(afterCreate.draft.hasChanges(afterCreate.initial, false))
        assertEquals("29,50", afterCreate.draft.salePriceText)
    }

    @Test fun uuidFilterAndLabelsFollowGroupsEvenWhenItemsNeverEmitAgain() {
        val item = PriceCatalogItem(1, "stable.code", "Полотно", "Стара назва", "м²", 12.0, groupUuid = area.uuid)
        val renamed = area.copy(name = "Нові полотна", updatedAt = 2)
        assertEquals(area.uuid, activePriceFilter(area.uuid, listOf(renamed, empty)))
        val visible = visiblePriceItems(listOf(item), listOf(renamed, empty), "ПОЛОТНО", area.uuid)
        assertEquals("Нові полотна", visible.single().category)
        assertEquals(item.id, visible.single().id)
        assertTrue(visiblePriceItems(listOf(item), listOf(renamed, empty), "", empty.uuid).isEmpty())
        assertTrue(visiblePriceItems(listOf(item), listOf(renamed), "інше", area.uuid).isEmpty())
        assertNull(activePriceFilter(area.uuid, listOf(empty)))
        assertTrue(visiblePriceItems(listOf(item), listOf(empty), "", null).isEmpty())
        val draft = ConfiguredPriceDraft.from(item)
        assertEquals("Категорію видалено. Оберіть активну категорію.", draft.validationError(listOf(empty)))
    }

    @Test fun unitChangeRequiresExplicitCompatibleModeAndAllParametersAreUnsavedChanges() {
        val item = PriceCatalogItem(1, "code", "Полотно", area.name, "м²", 10.0,
            groupUuid = area.uuid, quantityMode = QuantityMode.CEILING_AREA, placementKind = PlacementKind.AREA)
        val initial = ConfiguredPriceDraft.from(item)
        val invalid = initial.copy(unit = "шт.")
        assertEquals(QuantityMode.CEILING_AREA, invalid.quantityMode)
        assertTrue(invalid.validationError(listOf(area))!!.contains("м²"))
        assertNull(invalid.copy(quantityMode = QuantityMode.MANUAL).validationError(listOf(area)))
        assertTrue(invalid.hasChanges(initial, false))
        assertTrue(initial.copy(placementKind = PlacementKind.LINE).hasChanges(initial, false))
        assertTrue(initial.copy(groupUuid = empty.uuid).hasChanges(initial, false))
        assertFalse(initial.copy(salePriceText = "10").hasChanges(initial, true))
        assertFalse(invalid.hasChanges(initial, true))
    }

    @Test fun nullGroupUnitAndNonManualModeAreAllowedWithoutInventingDefaults() {
        val draft = PriceGroupDraft(name = "Нова", defaultQuantityMode = QuantityMode.CEILING_AREA)
        assertNull(draft.validationError())
        assertNull(draft.copy(defaultUnit = "  ").validationError())
        assertNotNull(draft.copy(defaultUnit = "шт.").validationError())
        assertNull(draft.copy(defaultUnit = "м²").validationError())
        assertEquals(PriceGroupDraft(), PriceGroupDraft(name = ""))
    }
}
