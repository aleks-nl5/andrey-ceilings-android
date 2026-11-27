package com.andreyceilings.shared.registry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

internal sealed interface RegistryRead<out T> {
    data object Loading : RegistryRead<Nothing>
    data object Unavailable : RegistryRead<Nothing>
    data class Ready<T>(val data: T) : RegistryRead<T>
}

/** Retain one immutable read snapshot for the unlocked session, not a second database.
 * Each collector still subscribes to the original reactive SQL query.
 * Closing atomically prevents any late collector from repopulating the snapshot.
 */
internal class SessionReadCache<T : Any> {
    private val state = MutableStateFlow<RegistryRead<T>?>(RegistryRead.Loading)
    val read: RegistryRead<T> get() = state.value ?: RegistryRead.Unavailable
    val value: T? get() = (read as? RegistryRead.Ready)?.data

    fun record(value: T?) {
        state.update { previous ->
            if (previous == null) null else value?.let { RegistryRead.Ready(it) } ?: RegistryRead.Unavailable
        }
    }

    fun close() { state.value = null }

    fun observe(source: Flow<T?>): Flow<T?> = CachedRegistryFlow(
        source.onEach { record(it) }.catch { failure ->
            if (failure is CancellationException) throw failure
            record(null)
            throw failure
        }, this
    )
}

internal class CachedRegistryFlow<T : Any>(
    source: Flow<T?>, private val cache: SessionReadCache<T>
) : Flow<T?> by source {
    val initial: RegistryRead<T> get() = cache.read
}
