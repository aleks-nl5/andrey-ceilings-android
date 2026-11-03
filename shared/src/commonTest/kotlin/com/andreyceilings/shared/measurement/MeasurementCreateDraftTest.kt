package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.*
import kotlin.test.*

object MeasurementCreateFixtures {
    fun draft(projectId: Long, shape: String, precision: MeasurementPrecision): MeasurementCreateDraft {
        val outline = when (shape) {
            "rectangle" -> MeasurementOutlineDraft.Template(shape, mapOf("length" to "5,1", "width" to "4"))
            "l_shaped" -> MeasurementOutlineDraft.Template(shape, mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2"))
            else -> MeasurementOutlineDraft.Orthogonal(listOf("5", "2", "3", "2", "2", "4").mapIndexed { i, value ->
                MeasurementSideInput(value, if (i == 2) OrthogonalTurn.LEFT else OrthogonalTurn.RIGHT)
            })
        }
        return MeasurementCreateDraft(projectId, precision, " Кухня $shape ", " Примітки ", outline)
    }
}

class MeasurementCreateDraftTest {
    @Test fun allSupportedShapesAndPrecisionsUseTheExistingGeometryAndWireContract() {
        for (precision in MeasurementPrecision.entries) for (shape in listOf("rectangle", "l_shaped", "polygon")) {
            val draft = MeasurementCreateFixtures.draft(7, shape, precision)
            val validation = validateMeasurementCreate(draft)
            assertTrue(validation.canSave, validation.toString())
            val geometry = assertNotNull(validation.geometry)
            assertEquals(precision.stepMeters, geometry.precisionStepMeters)
            assertEquals(shape, geometry.shapeType)
            assertEquals(if (shape == "rectangle") 20.4 else 14.0, geometry.areaSquareMeters, 0.000001)
            assertEquals(if (shape == "rectangle") 18.2 else 18.0, geometry.perimeterMeters, 0.000001)
            assertEquals(geometry, MeasurementGeometryCodec.read(MeasurementGeometryCodec.write(geometry)))
            val drawing = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
            assertEquals(measurementSideLabels(geometry.vertices.size), drawing.namedSideLabels.map { it.substringBefore(':') })
            assertEquals(geometry.vertices.indices.map(::measurementVertexLabel), drawing.vertexNames)
            if (shape == "polygon") {
                assertEquals(listOf(5000L, 2000L, 3000L, 2000L, 2000L, 4000L), geometry.sides.map { it.lengthMm })
                assertEquals(OrthogonalTurn.LEFT, geometry.sides[2].turnAfter)
            }
        }
    }

    @Test fun decimalParsingIsExactAndNeverRoundsOffTheCapturedGrid() {
        assertEquals(2005L, measurementLengthMm(" 2,005 ", MeasurementPrecision.FIVE_MILLIMETERS))
        assertEquals(123001L, measurementLengthMm("123.001000", MeasurementPrecision.ONE_MILLIMETER))
        assertEquals(1L, measurementLengthMm(".001", MeasurementPrecision.ONE_MILLIMETER))
        assertEquals(1000000L, measurementLengthMm("1000", MeasurementPrecision.ONE_MILLIMETER))
        for (precision in MeasurementPrecision.entries) {
            val step = (precision.stepMeters * 1000).toLong()
            assertEquals(step, measurementLengthMm("0.${step.toString().padStart(3, '0')}", precision))
            if (step > 1) assertFails { measurementLengthMm("0.001", precision) }
        }
        for (text in listOf("", " ", "0", "-1", "NaN", "Infinity", "1e2", "2,3.4", "abc", "0.0001", "1000.001", "999999999999999999999999999")) {
            assertFailsWith<IllegalArgumentException>(text) { measurementLengthMm(text, MeasurementPrecision.ONE_MILLIMETER) }
        }
    }

    @Test fun invalidNameFieldsClosureTurnsAndTemplateParametersBlockSaving() {
        val rectangle = MeasurementCreateFixtures.draft(1, "rectangle", MeasurementPrecision.ONE_CENTIMETER)
        assertFalse(validateMeasurementCreate(rectangle.copy(name = "  ")).canSave)
        for (text in listOf("", "0", "wrong", "0.01", "1001")) {
            assertFalse(validateMeasurementCreate(rectangle.reduce(MeasurementCreateIntent.Dimension("length", text))).canSave)
        }
        val l = MeasurementCreateFixtures.draft(1, "l_shaped", MeasurementPrecision.ONE_CENTIMETER)
        assertFalse(validateMeasurementCreate(l.reduce(MeasurementCreateIntent.Dimension("C", "6"))).canSave)
        val polygon = MeasurementCreateFixtures.draft(1, "polygon", MeasurementPrecision.ONE_CENTIMETER)
        val unclosed = validateMeasurementCreate(polygon.reduce(MeasurementCreateIntent.SideLength(5, "3")))
        assertFalse(unclosed.canSave)
        assertTrue(unclosed.geometryError.orEmpty().contains("не замкнено"))
        val wrongTurn = validateMeasurementCreate(polygon.reduce(MeasurementCreateIntent.SideTurn(5, OrthogonalTurn.LEFT)))
        assertFalse(wrongTurn.canSave)
        assertTrue(wrongTurn.geometryError.orEmpty().contains("Напрям"))
        val badField = validateMeasurementCreate(polygon.reduce(MeasurementCreateIntent.SideLength(5, "")))
        assertTrue(badField.fieldErrors.values.single().contains("FA"))
        // A closed walk that retraces/touches the same vertices is still invalid.
        val r = OrthogonalTurn.RIGHT
        val doubled = polygon.copy(outline = MeasurementOutlineDraft.Orthogonal(List(8) { MeasurementSideInput("2", r) }))
        assertFalse(validateMeasurementCreate(doubled).canSave)
    }

    @Test fun topologyLabelsAreDerivedAndSurvivingInputIdentitiesRemainStable() {
        assertEquals(listOf("AB", "BC", "CD", "DA"), measurementSideLabels(4))
        assertEquals(listOf("AB", "BC", "CD", "DE", "EF", "FA"), measurementSideLabels(6))
        assertEquals("ZA", measurementSideLabels(26).last())
        assertEquals(listOf("YZ", "Z–AA", "AA–A"), measurementSideLabels(27).takeLast(3))
        assertEquals("DX–A", measurementSideLabels(128).last())
        val draft = MeasurementCreateFixtures.draft(7, "polygon", MeasurementPrecision.ONE_CENTIMETER)
        val original = (draft.outline as MeasurementOutlineDraft.Orthogonal).sides
        val changed = draft.reduce(MeasurementCreateIntent.RemoveSide(2)).reduce(MeasurementCreateIntent.AddSide)
        val sides = (changed.outline as MeasurementOutlineDraft.Orthogonal).sides
        assertEquals(original[3], sides[2])
        assertEquals("CD", measurementSideLabels(sides.size)[2])
        assertNotEquals(original.last().inputId, sides.last().inputId)
        for (count in listOf(4, 6, 27, 128)) {
            assertEquals(count, measurementSideLabels(count).toSet().size)
            assertEquals("A", measurementVertexLabel(0))
        }
        assertTrue(measurementTemplateFieldLabel("l_shaped", "C").contains("EF"))
        assertTrue(measurementTemplateFieldLabel("l_shaped", "B").contains("BC + DE"))
    }

    @Test fun snapshotRestoresOnlyDraftDataAndKeepsPrecisionWithoutStartingCommands() {
        for (shape in listOf("rectangle", "l_shaped", "polygon")) {
            val draft = MeasurementCreateFixtures.draft(7, shape, MeasurementPrecision.FIVE_MILLIMETERS)
            val restored = assertNotNull(MeasurementCreateDraft.restore(draft.snapshot(), 7))
            assertEquals(draft, restored)
            assertNull(MeasurementCreateDraft.restore(draft.snapshot(), 8))
            val model = MeasurementCreateModel(null, restored)
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.savedId)
        }
        assertNull(MeasurementCreateDraft.restore("{}", 7))
        assertNull(MeasurementCreateDraft.restore("bad", 7))
    }
}
