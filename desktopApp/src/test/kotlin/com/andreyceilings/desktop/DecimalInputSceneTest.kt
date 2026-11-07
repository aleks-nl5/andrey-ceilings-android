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
import com.andreyceilings.shared.measurement.parseElementMilli
import com.andreyceilings.shared.measurement.parsePointMetres
import com.andreyceilings.shared.prices.PriceCatalogRules
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlin.test.*

class DecimalInputSceneTest {
    @Test fun realPriceDialogKeepsPartialTextAndOnlyCommitsTwoDigitPrices() {
        val group = PriceCatalogGroup("g", "Роботи", 1, "м.п.", QuantityMode.MANUAL, PlacementKind.NONE)
        val item = PriceCatalogItem(1, "work", "Робота", group.name, "м.п.", 1.0, groupUuid = group.uuid)
        for (dark in listOf(false, true)) {
            var saved: String? = null
            val scene = ImageComposeScene(900, 1100) {
                CeilingsTheme(dark) {
                    PriceCatalogScreen(listOf(item), listOf(group), true,
                        onSave = { _, _ -> error("Expected quick price") },
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
                    it.config.getOrNull(SemanticsActions.OnClick) != null && text(it).contains("1,00 ₴")
                }
                assertTrue(quick.config[SemanticsActions.OnClick].action!!.invoke())
                render(); render()
                for (value in listOf(".", ",", "0.", ".007", "..5")) {
                    assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                    render()
                    assertEquals(value, field().config[SemanticsProperties.EditableText].text)
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
                assertEquals(",5", saved)
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
            fun set(value: String) {
                assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                render()
                assertEquals(value, text)
                assertEquals(value, field().config[SemanticsProperties.EditableText].text)
            }
            try {
                render()
                assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
                render()
                for (value in listOf(".", ".5", ".007", "", ",", ",5", "-", "-.", "-.5", "0.", "..5")) {
                    set(value)
                    tick++
                    render()
                    assertTrue(field().config[SemanticsProperties.Focused])
                    assertEquals(value, field().config[SemanticsProperties.EditableText].text)
                }
                set("05")
                assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(0, 1, false))
                render()
                val comma = scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Вставити десяткову кому") == true
                }
                assertTrue(comma.config[SemanticsActions.OnClick].action!!.invoke())
                render()
                assertEquals(",5", text)
                assertEquals(TextRange(1), field().config[SemanticsProperties.TextSelectionRange])
                assertTrue(field().config[SemanticsProperties.Focused])
                // Validation during composition never replaces text with zero or the last valid value.
                set(".")
                assertNull(parseElementMilli(text, "м.п."))
                assertNull(parsePointMetres(text))
                assertNull(PriceCatalogRules.parseSalePrice(text))
                set(".007")
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
