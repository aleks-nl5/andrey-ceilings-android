package com.andreyceilings.shared.measurement

/** Display names derived from contour order, never persistent wall/feature identities. */
fun measurementVertexLabel(index: Int): String {
    require(index >= 0)
    var value = index.toLong() + 1
    return buildString {
        while (value > 0) {
            value--
            append(('A'.code + (value % 26).toInt()).toChar())
            value /= 26
        }
    }.reversed()
}

fun measurementSideLabels(vertexCount: Int): List<String> {
    require(vertexCount >= 3)
    return List(vertexCount) { i ->
        val a = measurementVertexLabel(i)
        val b = measurementVertexLabel((i + 1) % vertexCount)
        if (a.length == 1 && b.length == 1) a + b else "$a–$b"
    }
}

/** Template parameters retain their domain keys; labels refer to the same ordered contour. */
fun measurementTemplateFieldLabel(shape: String, key: String): String {
    val labels = measurementSideLabels(if (shape == "l_shaped") 6 else 4)
    return when (shape to key) {
        "rectangle" to "length" -> "Сторона ${labels[0]} (= ${labels[2]}), м"
        "rectangle" to "width" -> "Сторона ${labels[1]} (= ${labels[3]}), м"
        "l_shaped" to "A" -> "Сторона ${labels[0]}, м"
        "l_shaped" to "B" -> "Загальна ширина ${labels[1]} + ${labels[3]}, м"
        "l_shaped" to "C" -> "Сторона ${labels[4]}, м"
        "l_shaped" to "D" -> "Сторона ${labels[1]}, м"
        else -> key
    }
}
