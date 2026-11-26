package com.andreyceilings.shared.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow

internal fun registryAnchor(previous: List<String>, index: Int, current: List<String>): Int {
    if (current.isEmpty() || previous.isEmpty()) return 0
    val positions = current.withIndex().associate { it.value to it.index }
    val origin = index.coerceIn(previous.indices)
    for (i in origin..previous.lastIndex) positions[previous[i]]?.let { return it }
    for (i in origin - 1 downTo 0) positions[previous[i]]?.let { return it }
    return origin.coerceIn(current.indices)
}

private class ScrollAnchor(var index: Int = 0, var offset: Int = 0, var order: List<String> = emptyList())

private val scrollSaver = Saver<ScrollAnchor, ArrayList<String>>(
    save = { anchor ->
        arrayListOf(anchor.index.toString(), anchor.offset.toString()).apply { addAll(anchor.order) }
    },
    restore = { saved -> ScrollAnchor(saved[0].toInt(), saved[1].toInt(), saved.drop(2)) }
)

/** Keep the last loaded order by reference. Scrolling updates only two integers. */
@Composable
internal fun rememberRegistryScroll(
    keys: List<String>?,
    scopeId: String = "",
    visible: Boolean = true
): LazyListState {
    val anchor = rememberSaveable(scopeId, saver = scrollSaver) { ScrollAnchor() }
    val list = remember(scopeId) {
        LazyListState(keys?.let { registryAnchor(anchor.order, anchor.index, it) } ?: 0,
            if (keys != null) anchor.offset else 0)
    }
    val restored = remember(list, visible) { ScrollRestore() }
    val latestKeys by rememberUpdatedState(keys)

    fun capture(index: Int, pixels: Int, order: List<String>, visibleKey: Any?) {
        if (index in order.indices && order[index] == visibleKey) {
            anchor.index = index
            anchor.offset = pixels
            anchor.order = order
        }
    }

    LaunchedEffect(list, visible, keys) {
        if (!visible || keys.isNullOrEmpty()) return@LaunchedEffect
        if (!restored.done) {
            if (anchor.order.isNotEmpty()) list.scrollToItem(registryAnchor(anchor.order, anchor.index, keys), anchor.offset)
            restored.done = true
        } else if (anchor.order.isNotEmpty() && anchor.order.getOrNull(anchor.index) !in keys) {
            // The list can remain composed under a dialog while its anchor is deleted.
            list.scrollToItem(registryAnchor(anchor.order, anchor.index, keys), anchor.offset)
        }
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            Triple(index, list.firstVisibleItemScrollOffset, row?.key)
        }.collect { (index, pixels, visibleKey) -> capture(index, pixels, keys, visibleKey) }
    }
    DisposableEffect(list, visible) {
        onDispose {
            if (visible && restored.done) {
                val index = list.firstVisibleItemIndex
                val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                capture(index, list.firstVisibleItemScrollOffset, latestKeys.orEmpty(), row?.key)
            }
        }
    }
    return list
}

private class ScrollRestore(var done: Boolean = false)
