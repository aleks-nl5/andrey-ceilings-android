package com.andreyceilings.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.prices.PriceCatalogRules
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlin.test.*

class DecimalInputSceneTest {
    @Test fun measurementLengthEditorNormalizesFirstSeparatorAndKeepsPartialSaveDisabled() {
        for (dark in listOf(false, true)) {
            val model = MeasurementCreateModel(null, MeasurementCreateDraft(7,
                MeasurementPrecision.ONE_MILLIMETER, "Kitchen", outline =
                    MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "", "width" to "1"))))
            val scene = ImageComposeScene(900, 1600) {
                CeilingsTheme(dark) { MeasurementCreateScreen(model, MeasurementCreateParent.Active("Object"), onBack = {}, onSaved = {}) }
            }
            fun render() { Snapshot.sendApplyNotifications(); scene.render().close() }
            fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .joinToString { it.text } + node.children.joinToString { text(it) }
            fun field() = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null &&
                text(it).contains(measurementTemplateFieldLabel("rectangle", "length")) }
            fun save() = scene.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && text(it) == "Зберегти" }
            try {
                render()
                assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke()); render()
                assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("."))); render()
                assertEquals("0.", field().config[SemanticsProperties.EditableText].text)
                assertEquals(TextRange(2), field().config[SemanticsProperties.TextSelectionRange])
                assertTrue(SemanticsProperties.Disabled in save().config)
                assertTrue(field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("5"))); render()
                assertEquals("0.5", field().config[SemanticsProperties.EditableText].text)
                assertTrue(field().config[SemanticsProperties.Focused])
                assertFalse(SemanticsProperties.Disabled in save().config)
                assertTrue(validateMeasurementCreate(model.state.value.draft).canSave)
            } finally { scene.close() }
        }
    }

    @Test fun integerQuantityControlHasNoSeparatorActionAndRejectsFractionalPaste() {
        var text by mutableStateOf("")
        val scene = ImageComposeScene(600, 400) { CeilingsTheme(false) {
            DecimalInputField(text, { text = it }, "Штуки", allowDecimalSeparator = false)
        } }
        fun render() { Snapshot.sendApplyNotifications(); scene.render().close() }
        try {
            render()
            assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.contains("Вставити десяткову кому") == true })
            val field = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(".5"))); render()
            assertEquals("5", text)
            assertEquals(5000L, parseElementMilli(text, "шт."))
        } finally { scene.close() }
    }

    @Test fun realPriceDialogKeepsPartialTextAndOnlyCommitsTwoDigitPrices() {
        val group = PriceCatalogGroup("g", "Роботи", 1, "м.п.", QuantityMode.MANUAL, PlacementKind.NONE)
        val item = PriceCatalogItem(1, "work", "Робота", group.name, "м.п.", 1.0, groupUuid = group.uuid)
        for (dark in listOf(false, true)) for (quickMode in listOf(true, false)) {
            var saved: String? = null
            val scene = ImageComposeScene(900, 1400) {
                CeilingsTheme(dark) {
                    PriceCatalogScreen(listOf(item), listOf(group), true,
                        onSave = { _, draft -> saved = draft.salePriceText },
                        onQuickPrice = { _, text -> saved = text }, onSaveGroup = { _, _ -> "g" },
                        onDeleteGroup = {}, onDelete = {})
                }
            }
            fun render() { Snapshot.sendApplyNotifications(); scene.render().close() }
            fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .joinToString { it.text } + node.children.joinToString { text(it) }
            fun save() = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.OnClick) != null && text(it) == "Зберегти"
            }
            fun field() = scene.nodes().first {
                it.config.getOrNull(SemanticsActions.SetText) != null && text(it).contains("Ціна продажу")
            }
            try {
                render()
                val quick = scene.nodes().first {
                    it.config.getOrNull(SemanticsActions.OnClick) != null && (if (quickMode) text(it).contains("1,00 ₴") else text(it) == "Змінити")
                }
                assertTrue(quick.config[SemanticsActions.OnClick].action!!.invoke())
                render(); render()
                assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
                render(); render()
                for ((value, displayed) in listOf("." to "0.", "," to "0,", "0." to "0.", ".007" to "0.007")) {
                    assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                    render()
                    assertEquals(displayed, field().config[SemanticsProperties.EditableText].text)
                    assertTrue(field().config[SemanticsProperties.Focused])
                    assertTrue(SemanticsProperties.Disabled in save().config, value)
                    assertNull(saved)
                }
                assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(",5")))
                render()
                assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(1, 1, false))
                render(); render()
                assertEquals(TextRange(1), field().config[SemanticsProperties.TextSelectionRange])
                assertFalse(SemanticsProperties.Disabled in save().config)
                assertTrue(save().config[SemanticsActions.OnClick].action!!.invoke())
                render(); render()
                assertEquals("0,5", saved)
            } finally { scene.close() }
        }
    }

    @Test fun partialTypingSelectionPasteAndRecompositionKeepTextAndFocus() {
        for (dark in listOf(false, true)) {
            var text by mutableStateOf("")
            var tick by mutableStateOf(0)
            val scene = ImageComposeScene(600, 400) {
                CeilingsTheme(dark) { Column {
                    DecimalInputField(text, { text = it }, "Координата", signed = true)
                    Text("$tick")
                } }
            }
            fun render() { Snapshot.sendApplyNotifications(); scene.render().close() }
            fun field() = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            fun set(value: String, displayed: String = value) {
                assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                render()
                assertEquals(displayed, text)
                assertEquals(displayed, field().config[SemanticsProperties.EditableText].text)
            }
            try {
                render()
                assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
                render()
                for ((value, displayed) in listOf("." to "0.", ".5" to "0.5", ".007" to "0.007", "" to "", "," to "0,", ",5" to "0,5", "-" to "-", "-." to "-0.", "-.5" to "-0.5", "0." to "0.", "..5" to "0.5", "2.5" to "2.5", "12abc" to "12", "1a2,3x" to "12,3", "1.2.3" to "1.23")) {
                    set(value, displayed)
                    tick++
                    render()
                    assertTrue(field().config[SemanticsProperties.Focused])
                    assertEquals(displayed, field().config[SemanticsProperties.EditableText].text)
                }
                set("05")
                assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(0, 1, false))
                render()
                val comma = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Вставити десяткову кому") == true
                }
                assertTrue(comma.config[SemanticsActions.OnClick].action!!.invoke())
                render()
                assertEquals("0,5", text)
                assertEquals(TextRange(2), field().config[SemanticsProperties.TextSelectionRange])
                assertTrue(field().config[SemanticsProperties.Focused])
                // A display zero does not turn a partial edit into a committable number.
                set(".", "0.")
                assertNull(parseElementMilli(text, "м.п."))
                assertNull(parsePointMetres(text))
                assertNull(PriceCatalogRules.parseSalePrice(text))
                set(".007", "0.007")
                assertEquals(7L, parseElementMilli(text, "м.п."))
                assertNull(PriceCatalogRules.parseSalePrice(text))
                assertNull(parseElementMilli(text, "шт."))
            } finally { scene.close() }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
}
