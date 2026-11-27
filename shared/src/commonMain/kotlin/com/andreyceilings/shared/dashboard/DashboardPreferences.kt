package com.andreyceilings.shared.dashboard

enum class DashboardBlock(val mandatory: Boolean, val defaultVisible: Boolean, internal val columns: Int) {
    WORK(true, true, 0),
    FINANCE(false, true, 1),
    AREA(false, true, 1),
    CEILINGS(true, true, 2),
    QUOTES(true, true, 2),
    OBJECTS(false, false, 2),
    CLIENTS(false, false, 2),
    RATES(true, true, 0)
}

data class DashboardPreferences private constructor(val order: List<DashboardBlock>, val hidden: Set<DashboardBlock>) {
    fun isVisible(block: DashboardBlock) = block !in hidden

    fun toggle(block: DashboardBlock): DashboardPreferences =
        if (block.mandatory) this else copy(hidden = if (block in hidden) hidden - block else hidden + block)

    fun move(block: DashboardBlock, distance: Int): DashboardPreferences {
        val index = order.indexOf(block)
        val target = (index + distance).coerceIn(order.indices)
        if (index == target) return this
        return copy(order = order.toMutableList().apply { add(target, removeAt(index)) })
    }

    internal fun rows(): List<List<DashboardBlock>> {
        val visible = order.filter(::isVisible)
        return buildList {
            var index = 0
            while (index < visible.size) {
                val block = visible[index++]
                val next = visible.getOrNull(index)
                if (block.columns != 0 && next?.columns == block.columns) {
                    add(listOf(block, next))
                    index++
                } else add(listOf(block))
            }
        }
    }

    fun encode(): String = order.joinToString(",") { block -> block.name + if (isVisible(block)) ":1" else ":0" }

    companion object {
        val default = DashboardPreferences(DashboardBlock.entries, DashboardBlock.entries.filterNot { it.defaultVisible }.toSet())

        fun decode(value: String?): DashboardPreferences {
            val stored = linkedMapOf<DashboardBlock, Boolean>()
            value.orEmpty().split(',').forEach { item ->
                val parts = item.split(':')
                if (parts.size == 2) {
                    val block = DashboardBlock.entries.firstOrNull { it.name == parts[0] }
                    val visible = when (parts[1]) { "1" -> true; "0" -> false; else -> null }
                    if (block != null && visible != null && block !in stored) stored[block] = visible
                }
            }
            DashboardBlock.entries.forEach { block -> if (block !in stored) stored[block] = block.defaultVisible }
            return DashboardPreferences(stored.keys.toList(), stored.filter { (block, visible) -> !block.mandatory && !visible }.keys)
        }
    }
}
