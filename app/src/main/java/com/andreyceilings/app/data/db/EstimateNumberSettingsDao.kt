package com.andreyceilings.app.data.db

import com.andreyceilings.shared.estimate.EstimateNumberPolicy
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android adapter for the same SQLDelight policy used by Android, Windows and iOS.
 * The policy is persisted in the encrypted business database, not SharedPreferences.
 */
internal class EstimateNumberSettingsDao(
    private val repository: ImmutableEstimateRepository
) {
    suspend fun load(): EstimateNumberPolicy = withContext(Dispatchers.IO) {
        repository.numberPolicy()
    }

    /** Only future estimate-series use the updated policy. */
    suspend fun save(policy: EstimateNumberPolicy): EstimateNumberPolicy = withContext(Dispatchers.IO) {
        repository.configureNumberPolicy(policy)
        repository.numberPolicy()
    }
}
