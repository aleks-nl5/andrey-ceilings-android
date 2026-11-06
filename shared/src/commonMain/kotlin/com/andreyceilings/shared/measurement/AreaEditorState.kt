package com.andreyceilings.shared.measurement

import kotlinx.serialization.Serializable

@Serializable
enum class AreaTool { RECTANGLE, POLYGON, CIRCLE }

@Serializable
data class AreaPropertiesInput(val uuid: String, val note: String, val role: AreaRole, val diameter: String,
    val diameterMeasured: Boolean=false, val initialNote: String=note, val initialRole: AreaRole=role, val initialDiameter: String=diameter) {
    val dirty get()=note!=initialNote || role!=initialRole || diameter!=initialDiameter || diameterMeasured
}

@Serializable
data class AreaEditorState(
    val edits: Map<String,AreaFeatureEdit> = emptyMap(), val quantities: Map<String,Long> = emptyMap(),
    val selected: String? = null, val tool: AreaTool? = null, val role: AreaRole = AreaRole.OBSTACLE,
    val element: String? = null, val vertices: List<PointPositionMm> = emptyList(),
    val diameter: String = "", val moveVertex: Int? = null, val drag: AreaFeature? = null,
    val mountingIndex: Int? = null, val properties: AreaPropertiesInput? = null,
    val dismissProperties: Boolean = false, val deleteConfirm: Boolean = false, val quantityConfirm: String? = null
) {
    val dirty get()=edits.isNotEmpty() || quantities.isNotEmpty() || tool!=null || properties?.dirty==true
    fun history()=copy(tool=null,vertices=emptyList(),drag=null,mountingIndex=null,properties=null,
        dismissProperties=false,deleteConfirm=false,quantityConfirm=null)
}

fun areaRoleLabel(role: AreaRole)=when(role) {
    AreaRole.GENERAL_AREA -> "Площинний об'єкт"
    AreaRole.OBSTACLE -> "Перешкода"
    AreaRole.VOID_MARKER -> "Позначка отвору"
}
fun areaDescription(area: AreaFeature): String {
    val half=area.twiceAreaMm2
    val mm="${half/2}${if(half%2==1L) ",5" else ""} мм²"
    val milli=(half+1000)/2000
    val dimensions=when(val shape=area.shape) {
        is AreaShape.Circle -> "діаметр ${shape.radiusMm*2} мм"
        is AreaShape.Polygon -> "${shape.vertices.size} вершин · габарит ${shape.vertices.maxOf {it.xMm}-shape.vertices.minOf {it.xMm}} × ${shape.vertices.maxOf {it.yMm}-shape.vertices.minOf {it.yMm}} мм"
    }
    val status=if(area.unresolved)"Потребує уточнення" else if(area.needsReview)"Перевірити" else if(area.precision==PointPrecision.EXACT)"Точне" else "Приблизне"
    return "${areaRoleLabel(area.role)} · $dimensions · $mm (${formatElementMilli(milli)} м²) · $status"
}

internal fun encodeAreaElementSnapshots(elements: List<MeasurementElement>): kotlinx.serialization.json.JsonElement =
    mountingCodec.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MeasurementElement.serializer()),elements)
internal fun decodeAreaElementSnapshots(value: kotlinx.serialization.json.JsonElement?): List<MeasurementElement> =
    value?.let {mountingCodec.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(MeasurementElement.serializer()),it)} ?: emptyList()
