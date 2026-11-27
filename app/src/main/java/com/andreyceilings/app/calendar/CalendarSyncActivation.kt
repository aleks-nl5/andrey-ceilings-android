package com.andreyceilings.app.calendar

import com.andreyceilings.shared.calendar.CalendarIntegrationSnapshot
import com.andreyceilings.shared.calendar.CalendarSyncRule
import com.andreyceilings.shared.calendar.CalendarWork

internal fun CalendarSyncRule.deviceToken() = "${key.deviceKey()}/${if (following) "series" else "occurrence"}"

internal fun syncAllowed(snapshot: CalendarIntegrationSnapshot, work: CalendarWork, suspendedRules: Set<String>): Boolean =
    snapshot.managed(work) && snapshot.syncRule(work.key)?.deviceToken() !in suspendedRules

internal fun reconcileSyncCandidate(allowed: Boolean, leftHistory: Boolean, activeMapping: Boolean): Boolean =
    allowed && (!leftHistory || activeMapping)
