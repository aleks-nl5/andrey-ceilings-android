package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlin.test.*

/** Wire fixtures from the JSONObject field layout at main 0dfcbf9, using the
 * existing Android MeasurementPolygonPersistenceTest's measured examples.
 * The emulator compatibility test also exercises that original writer/parser. */
object AndroidMeasurementFixtures {
    val rectangleV2 = """{"shape":"rectangle","geometrySchemaVersion":2,"vertices":[{"xMm":0,"yMm":0},{"xMm":5000,"yMm":0},{"xMm":5000,"yMm":4000},{"xMm":0,"yMm":4000}],"measurementsMm":{"length":5000,"width":4000},"areaSquareMeters":20,"perimeterMeters":18,"precisionStepMeters":0.01}"""
    val lShapeV2 = """{"shape":"l_shaped","geometrySchemaVersion":2,"vertices":[{"xMm":0,"yMm":0},{"xMm":5000,"yMm":0},{"xMm":5000,"yMm":2000},{"xMm":2000,"yMm":2000},{"xMm":2000,"yMm":4000},{"xMm":0,"yMm":4000}],"measurementsMm":{"A":5000,"B":4000,"C":2000,"D":2000},"areaSquareMeters":14,"perimeterMeters":18,"precisionStepMeters":0.01}"""
    val polygonV2 = """{"shape":"polygon","geometrySchemaVersion":2,"vertices":[{"xMm":0,"yMm":0},{"xMm":6000,"yMm":0},{"xMm":6000,"yMm":2000},{"xMm":3000,"yMm":2000},{"xMm":3000,"yMm":5000},{"xMm":0,"yMm":5000}],"measurementsMm":{},"areaSquareMeters":21,"perimeterMeters":22,"precisionStepMeters":0.01}"""
    val sidesV3 = """{"shape":"polygon","geometrySchemaVersion":3,"vertices":[{"xMm":0,"yMm":0},{"xMm":5000,"yMm":0},{"xMm":5000,"yMm":2000},{"xMm":2000,"yMm":2000},{"xMm":2000,"yMm":4000},{"xMm":0,"yMm":4000}],"measurementsMm":{},"areaSquareMeters":14,"perimeterMeters":18,"sides":[{"lengthMm":5000,"turnAfter":"RIGHT"},{"lengthMm":2000,"turnAfter":"RIGHT"},{"lengthMm":3000,"turnAfter":"LEFT"},{"lengthMm":2000,"turnAfter":"RIGHT"},{"lengthMm":2000,"turnAfter":"RIGHT"},{"lengthMm":4000,"turnAfter":"RIGHT"}],"precisionStepMeters":0.01}"""
    val all = listOf(rectangleV2, lShapeV2, polygonV2, sidesV3)
}

class MeasurementGeometryCodecTest {
    @Test fun previousAndroidFormatsKeepCoordinatesSidesTurnsPrecisionAndTotals() {
        for (source in AndroidMeasurementFixtures.all) {
            val value = MeasurementGeometryCodec.read(source)
            assertEquals(value, MeasurementGeometryCodec.read(MeasurementGeometryCodec.write(value)))
            assertEquals(0.01, value.precisionStepMeters)
        }
        val sides = MeasurementGeometryCodec.read(AndroidMeasurementFixtures.sidesV3)
        assertEquals(listOf(5000L, 2000L, 3000L, 2000L, 2000L, 4000L), sides.sides.map { it.lengthMm })
        assertEquals(OrthogonalTurn.LEFT, sides.sides[2].turnAfter)
        assertEquals(14.0, sides.areaSquareMeters)
        assertEquals(18.0, sides.perimeterMeters)
        assertTrue(MeasurementGeometryCodec.read(AndroidMeasurementFixtures.polygonV2).sides.isEmpty())
    }

    @Test fun integerMillimetreRoundTripsForEveryExistingTemplateAndPrecision() {
        for (precision in MeasurementPrecision.entries) for (template in CeilingShapeTemplates.templates) {
            val step = (precision.stepMeters * 1000).toLong()
            val dimensions = if (template.shapeType == "rectangle")
                mapOf("length" to 5000L + step, "width" to 4000L + step)
            else mapOf("A" to 5000L + step, "B" to 4000L + step, "C" to 2000L, "D" to 2000L)
            val frozen = FrozenPolygonGeometryContract.capture(template.shapeType,
                template.build(dimensions).vertices, dimensions, precision.stepMeters)
            assertEquals(frozen, MeasurementGeometryCodec.read(MeasurementGeometryCodec.write(frozen)))
        }
    }

    @Test fun malformedUnknownOrAmbiguousJsonAndInvalidGeometryFailClosed() {
        val source = AndroidMeasurementFixtures.rectangleV2
        val invalid = listOf("", "{}", "null", "[]", source.dropLast(1), source + " trailing",
            source.replace("\"geometrySchemaVersion\":2", "\"geometrySchemaVersion\":4"),
            source.replace("\"geometrySchemaVersion\":2", "\"geometrySchemaVersion\":2.0"),
            source.replace("\"xMm\":5000", "\"xMm\":5000.0"),
            source.replace("\"xMm\":5000", "\"xMm\":5e3"),
            source.replace("\"xMm\":5000", "\"xMm\":\"5000\""),
            source.replace("\"xMm\":5000", "\"xMm\":9007199254740993"),
            source.replace("\"xMm\":5000", "\"xMm\":9223372036854775808"),
            source.replace("\"xMm\":5000", "\"xMm\":4999"),
            source.replace("\"length\":5000", "\"length\":4000"),
            source.replace("\"precisionStepMeters\":0.01", "\"precisionStepMeters\":0.02"),
            source.replace("\"areaSquareMeters\":20", "\"areaSquareMeters\":21"),
            source.replace("\"areaSquareMeters\":20", "\"areaSquareMeters\":NaN"),
            source.replace("\"shape\":\"rectangle\"", "\"shape\":\"unsupported\""),
            source.replaceFirst("{", "{\"unknown\":0,"),
            source.replaceFirst("{", "{\"shape\":\"rectangle\","),
            source.replaceFirst("{", "{\"sh\\u0061pe\":\"rectangle\","),
            source.replaceFirst("\"xMm\":0", "\"xMm\":0,\"xMm\":0"),
            source.replace(",\"precisionStepMeters\":0.01", ""),
            source.replace("\"vertices\":[", "\"vertices\":[null,"),
            AndroidMeasurementFixtures.sidesV3.replace("\"lengthMm\":5000", "\"lengthMm\":5100"),
            AndroidMeasurementFixtures.sidesV3.replace("\"LEFT\"", "\"UP\""))
        for (value in invalid) assertFails("Invalid geometry must be rejected") { MeasurementGeometryCodec.read(value) }
    }

    @Test fun legacyRectangleUsesOnlySavedDimensionsAndCorruptRecordsNeverFallBack() {
        val view = measurementGeometryForViewing("rectangle", "{ }", 3.12345, 2.0)
        assertIs<MeasurementGeometryView.HistoricalRectangle>(view)
        val drawing = assertNotNull(measurementDrawingContour(view))
        assertEquals(3123.45, drawing.points[1].x)
        assertTrue(drawing.historical)
        assertEquals("3.12345 м", drawing.sideLabels[0])
        assertEquals(MeasurementGeometryView.Unavailable, measurementGeometryForViewing("rectangle", "broken", 3.0, 2.0))
        assertEquals(MeasurementGeometryView.Unavailable, measurementGeometryForViewing("l_shaped", "{}", 3.0, 2.0))
        for (dimension in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1001.0))
            assertNull(measurementDrawingContour(MeasurementGeometryView.HistoricalRectangle(dimension, 2.0)))
    }
}
