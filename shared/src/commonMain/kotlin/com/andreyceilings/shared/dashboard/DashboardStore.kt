package com.andreyceilings.shared.dashboard

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.andreyceilings.shared.db.DashboardSummary as DashboardRows
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.objects.ObjectsClientsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class DashboardStore private constructor(
    internal val rows: Flow<DashboardRows?>,
    internal val quoteCount: Flow<Long?>?,
    private val finance: (() -> Flow<Long?>)?
) {
    constructor(database: PrototypeDatabase) : this(
        database.registryQueries.dashboardSummary().asFlow().mapToOne(Dispatchers.Default), null, null
    )

    constructor(session: ObjectsClientsStore) : this(session.uiDashboardSummary(),
        session.uiDashboardQuoteCount(), session::uiDashboardFinance)

    val summary: Flow<DashboardSummary> = rows.filterNotNull().map {
        DashboardSummary(checkNotNull(it.projectCount).toInt(), it.ceilingCount.toInt(), it.area)
    }

    internal fun approvedFinance(): Flow<Long?>? = finance?.invoke()
}
