package com.andreyceilings.shared.calendar

import com.andreyceilings.shared.db.Calendar_exceptions
import com.andreyceilings.shared.db.Calendar_rules
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.SelectCalendarDetails
import com.andreyceilings.shared.db.newRecordUuid
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val TITLE = 1L
private const val TYPE = 2L
private const val STATUS = 4L
private const val NOTES = 8L
private const val LINKS = 16L
private const val ADDRESS = 32L
private const val SCHEDULE = 64L
private const val DATE = 128L
private const val CEILINGS = 256L
private const val CONTACT_NAME = 512L
private const val CONTACT_PHONE = 1024L
private const val CONTACT_EMAIL = 2048L
private const val ALL_FIELDS = 4095L

class CalendarRepository(
    private val database: PrototypeDatabase,
    private val clock: Clock = Clock.System,
    private val deviceZone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    private val queries = database.workCalendarQueries

    fun types(): List<CalendarWorkType> = queries.selectCalendarTypes().executeAsList().map {
        CalendarWorkType(it.code, it.label, it.color.toInt(), it.icon, it.durationMinutes.toInt(), it.archived == 1L)
    }

    fun settings(): CalendarSettings = queries.selectCalendarSettings().executeAsOne().let {
        CalendarSettings(it.zoneId?.let(TimeZone::of) ?: deviceZone(),
            it.firstWeekday?.let { day -> kotlinx.datetime.DayOfWeek.entries[day.toInt() - 1] }, it.workingDays.weekdays())
    }

    fun saveSettings(settings: CalendarSettings) {
        require(settings.zone.id in TimeZone.availableZoneIds || settings.zone == TimeZone.UTC)
        queries.saveCalendarSettings(settings.zone.id, settings.firstWeekday?.let { (it.ordinal + 1).toLong() },
            settings.workingDays.mask())
    }

    fun saveType(type: CalendarWorkType) = database.transaction {
        queries.claimCalendarWrite()
        val current = types().firstOrNull { it.code == type.code }
        require(type.label == null || type.label.isNotBlank())
        require(current?.label == null || type.label != null)
        queries.saveCalendarType(type.code, type.label?.trim(), type.color.toLong(), type.icon,
            type.durationMinutes.toLong(), type.archived.sql())
    }

    fun period(range: WorkDateRange, displayZone: TimeZone): List<CalendarWork> = database.transactionWithResult {
        readPeriod(range, displayZone)
    }

    fun exact(key: WorkOccurrenceKey): CalendarWork? = database.transactionWithResult { locate(key)?.work }

    fun upcoming(since: LocalDate, projectUuid: String?, limit: Int): List<CalendarWork> = database.transactionWithResult {
        val now = clock.now()
        val zone = settings().zone
        val near = readPeriod(WorkDateRange(since, since.shift(34)), zone)
            .filter { it.upcoming(now) && (projectUuid == null || it.draft.projectUuid == projectUuid) }
        if (near.size >= limit) return@transactionWithResult near.take(limit)

        val ids = queries.selectUpcomingCalendarWorkIds(since.shift(-2).dayNumber(), projectUuid).executeAsList()
        val records = load(ids)
        val base = records.values.filter { it.row.kind == "SEGMENT" || it.row.uuid == it.row.rootUuid }
        val exceptions = base.map { it.row.rootUuid }.distinct().chunked(250).flatMap {
            queries.selectCalendarExceptionsForRoots(it).executeAsList()
        }
        val complete = records + load(exceptions.mapNotNull { it.overrideUuid }.filterNot { it in records })
        val bySegment = exceptions.groupBy { it.segmentUuid }
        val reserved = exceptions.groupBy { it.rootUuid }.mapValues { (_, items) -> items.mapTo(mutableSetOf()) { it.originalDay } }
        val exact = base.flatMap { record ->
            val overrides = bySegment[record.row.uuid].orEmpty()
            val suppressed = overrides.mapTo(mutableSetOf()) { it.scheduledDay }
            val start = since.shift(-2 - (record.row.lastDay - record.row.dateDay).toInt())
            val far = LocalDate(9999, 12, 31)
            val dynamic = if (record.draft.status.active) record.dates(WorkDateRange(start, far))
                .filter { it.dayNumber() !in suppressed &&
                    record.identityDate(it).dayNumber() !in reserved[record.row.rootUuid].orEmpty() }
                .map(record::toWork).filter { it.upcoming(now) }.take(limit).toList() else emptyList()
            dynamic + overrides.mapNotNull { hydrate(record, it, complete) }.filter { it.upcoming(now) }
        }
        (near + exact).distinctBy { it.key }.filter { projectUuid == null || it.draft.projectUuid == projectUuid }
            .sortedWith(workOrder).take(limit)
    }

    fun search(text: String, horizonDays: Int = 90): List<CalendarWork> = database.transactionWithResult {
        require(horizonDays == 90 || horizonDays == 365)
        val tokens = text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return@transactionWithResult emptyList()
        val zone = settings().zone
        val today = clock.now().toLocalDateTime(zone).date
        val dynamic = readPeriod(WorkDateRange(today, today.shift(horizonDays - 1)), zone)
        val ids = queries.selectPersistedCalendarWorks().executeAsList().distinct()
        val overrides = ids.chunked(250).flatMap { queries.selectCalendarHistoricalExceptions(it).executeAsList() }
        val records = load(ids + overrides.mapNotNull { it.overrideUuid })
        val overridesBySegment = overrides.groupBy { it.segmentUuid }
        val persisted = ids.flatMap { id ->
            val record = records.getValue(id)
            if (record.row.kind == "SINGLE" && record.row.uuid == record.row.rootUuid) listOf(record.toWork(record.draft.date))
            else overridesBySegment[id].orEmpty().mapNotNull { hydrate(record, it, records) }
        }
        (dynamic + persisted).distinctBy { it.key }.filter { work ->
            val searchable = listOf(work.draft.title, work.draft.notes, work.projectLabel, work.contact.address,
                work.contact.name, work.contact.phone, work.contact.email).filterNotNull().joinToString(" ").lowercase()
            tokens.all(searchable::contains)
        }.sortedWith(compareByDescending<CalendarWork> { it.draft.status.active }.then(workOrder))
    }

    fun previewConflicts(command: CalendarSave): CalendarConflictPreview? = database.transactionWithResult {
        val target = command.target?.let(::locate)
        if (command.target != null && (target == null || target.work.version != command.expectedVersion))
            return@transactionWithResult null
        val draft = validate(draftForScope(command, target), target?.work?.draft)
        val rule = if (target == null) draft.recurrence else if (command.scope == CalendarEditScope.THIS_AND_FOLLOWING &&
            target.record.rule != null && target.work.draft.status.active && draft.status.active) nextRule(command, target, draft)
        else null
        val (conflicts, through) = checkConflicts(command, draft.copy(recurrence = rule), target)
        CalendarConflictPreview(conflicts.size, through)
    }

    fun save(command: CalendarSave, confirmation: CalendarConflictConfirmation? = null): CalendarSaveResult =
        database.transactionWithResult {
            queries.claimCalendarWrite()
            val target = command.target?.let(::locate)
            if (command.target != null && (target == null || target.work.version != command.expectedVersion)) {
                return@transactionWithResult CalendarSaveResult.Stale
            }
            val draft = validate(draftForScope(command, target), target?.work?.draft)
            val splitRule = if (target != null && command.scope == CalendarEditScope.THIS_AND_FOLLOWING &&
                target.record.rule != null && target.work.draft.status.active && draft.status.active) {
                nextRule(command, target, draft)
            } else null
            val bounds = checkConflicts(command, draft.copy(recurrence = if (target == null) draft.recurrence else splitRule), target)
            val requestedConfirmation = CalendarConflictConfirmation(command, bounds.first)
            if (bounds.first.isNotEmpty() && confirmation?.matches(requestedConfirmation) != true) {
                return@transactionWithResult CalendarSaveResult.Conflicts(requestedConfirmation, bounds.second)
            }
            val completedInstallation = draft.typeCode == "INSTALL" && draft.status == WorkStatus.COMPLETED &&
                target?.work?.draft?.status != WorkStatus.COMPLETED
            if (target == null) {
                val root = newRecordUuid()
                queries.insertCalendarRoot(root, draft.date.dayNumber())
                persist(root, root, if (draft.recurrence == null) "SINGLE" else "SEGMENT", draft, null)
                return@transactionWithResult CalendarSaveResult.Saved(WorkOccurrenceKey(root, draft.date), installationCompleted = completedInstallation)
            }

            queries.advanceCalendarRoot(target.work.key.rootUuid, target.work.version)
            val base = target.record
            val key = target.work.key
            when {
                base.row.kind == "SINGLE" -> {
                    persist(base.row.uuid, key.rootUuid, "SINGLE", draft.copy(recurrence = null), target.work, update = true)
                    CalendarSaveResult.Saved(key, installationCompleted = completedInstallation)
                }
                command.scope == CalendarEditScope.THIS_AND_FOLLOWING && target.work.draft.status.active &&
                    draft.status.active -> split(target, draft.copy(recurrence = splitRule))
                else -> {
                    override(target, draft)
                    CalendarSaveResult.Saved(key, installationCompleted = completedInstallation)
                }
            }
        }

    fun delete(key: WorkOccurrenceKey, version: Long, scope: CalendarEditScope): Boolean =
        database.transactionWithResult {
            queries.claimCalendarWrite()
            val target = locate(key) ?: return@transactionWithResult false
            if (target.work.version != version) return@transactionWithResult false
            queries.advanceCalendarRoot(key.rootUuid, version)
            when {
                target.record.row.kind == "SINGLE" -> queries.deleteCalendarWork(target.record.row.uuid)
                scope == CalendarEditScope.THIS_AND_FOLLOWING -> {
                    terminate(target, cancelled = false)
                    tombstone(target)
                }
                else -> tombstone(target)
            }
            true
        }

    fun cancelFollowing(key: WorkOccurrenceKey, version: Long): Boolean = database.transactionWithResult {
        queries.claimCalendarWrite()
        val target = locate(key) ?: return@transactionWithResult false
        if (target.work.version != version || target.record.rule == null || !target.work.draft.status.active)
            return@transactionWithResult false
        queries.advanceCalendarRoot(key.rootUuid, version)
        terminate(target, cancelled = true)
        override(target, target.work.draft.copy(status = WorkStatus.CANCELLED))
        true
    }

    internal fun beforeProjectDeletion(projectUuid: String, confirmation: CalendarDeletionImpact?) {
        val affected = queries.selectCalendarProjectImpact(projectUuid).executeAsList().filter { !it.frozen.bool() }
        verifyDeletionImpact(confirmation, "project", projectUuid, affected.map { it.uuid to it.rootUuid })
        val records = load(affected.map { it.uuid })
        for (record in records.values) {
            val work = record.toWork(record.draft.date)
            val localToday = clock.now().toLocalDateTime(work.draft.schedule.zone).date
            if (record.rule != null) {
                val stop = minOf(localToday.shift(1), record.stop ?: localToday.shift(1))
                queries.stopCalendarRule(stop.dayNumber(), record.row.uuid)
            }
            queries.freezeCalendarProjectWork(work.projectLabel, work.contact.address, work.contact.name,
                work.contact.phone, work.contact.email, record.row.uuid)
            if (record.row.kind == "OVERRIDE") queries.freezeCalendarException(record.row.uuid)
        }
        affected.map { it.rootUuid }.distinct().forEach(queries::touchCalendarRoot)
    }

    internal fun beforeClientDeletion(clientUuid: String, confirmation: CalendarDeletionImpact?) {
        val affected = queries.selectCalendarClientImpact(clientUuid).executeAsList().filter { !it.clientDetached.bool() }
        verifyDeletionImpact(confirmation, "client", clientUuid, affected.map { it.uuid to it.rootUuid })
        val client = queries.selectCalendarDirectClient(clientUuid).executeAsOne()
        if (affected.isNotEmpty()) queries.detachCalendarClient(client.name, client.phone, client.email, clientUuid)
        affected.map { it.rootUuid }.distinct().forEach(queries::touchCalendarRoot)
    }

    internal fun beforeCeilingDeletion(ceilingUuid: String, name: String, confirmation: CalendarDeletionImpact?) {
        val affected = ceilingImpact(ceilingUuid)
        verifyDeletionImpact(confirmation, "ceiling", ceilingUuid, affected)
        queries.markCalendarCeilingRemoved(name, ceilingUuid)
        affected.map { it.second }.distinct().forEach(queries::touchCalendarRoot)
    }

    fun projectDeletionImpact(projectUuid: String): CalendarDeletionImpact? = database.transactionWithResult {
        deletionImpact("project", projectUuid, queries.selectCalendarProjectImpact(projectUuid).executeAsList()
            .filter { !it.frozen.bool() }.map { it.uuid to it.rootUuid })
    }

    fun clientDeletionImpact(clientUuid: String): CalendarDeletionImpact? = database.transactionWithResult {
        deletionImpact("client", clientUuid, queries.selectCalendarClientImpact(clientUuid).executeAsList()
            .filter { !it.clientDetached.bool() }.map { it.uuid to it.rootUuid })
    }

    fun ceilingDeletionImpact(ceilingUuid: String): CalendarDeletionImpact? = database.transactionWithResult {
        deletionImpact("ceiling", ceilingUuid, ceilingImpact(ceilingUuid))
    }

    private fun ceilingImpact(uuid: String): List<Pair<String, String>> =
        queries.selectCalendarCeilingImpact(uuid).executeAsList().map { it.workUuid to it.rootUuid }

    private fun deletionImpact(kind: String, uuid: String, rows: List<Pair<String, String>>): CalendarDeletionImpact? {
        if (rows.isEmpty()) return null
        val versions = rows.map { it.second }.distinct().associateWith {
            queries.selectCalendarRoot(it).executeAsOne().version
        }
        return CalendarDeletionImpact(kind, uuid, rows.associate { (work, root) -> work to versions.getValue(root) })
    }

    private fun verifyDeletionImpact(
        confirmation: CalendarDeletionImpact?, kind: String, uuid: String, rows: List<Pair<String, String>>,
    ) {
        val current = deletionImpact(kind, uuid, rows)
        if (current != null && current != confirmation) throw CalendarDeletionConfirmationRequired(current)
    }

    private fun terminate(target: Located, cancelled: Boolean) {
        val segment = target.record.row.uuid
        val affected = followingSegments(target)
        for (item in affected.values) {
            val boundary = if (item.row.uuid == segment) target.scheduled else item.draft.date
            if (item.stop == null || item.stop > boundary) queries.stopCalendarRule(boundary.dayNumber(), item.row.uuid)
        }
        val records = queries.selectCalendarExceptionsForRoot(target.work.key.rootUuid).executeAsList()
            .filter { !it.deleted.bool() && it.segmentUuid in affected && it.originalDay > target.work.key.originalDate.dayNumber() }
        val loaded = load(records.mapNotNull { it.overrideUuid })
        for (record in records) {
            if (record.overrideUuid?.let(loaded::get)?.row?.kind == "SINGLE") continue
            val base = affected.getValue(record.segmentUuid)
            val work = hydrate(base, record, loaded) ?: continue
            if (!work.draft.status.active || !work.upcoming(clock.now())) continue
            val item = Located(base, record.scheduledDay.localDate(), record, work)
            if (cancelled) override(item, work.draft.copy(status = WorkStatus.CANCELLED))
            else tombstone(item)
        }
    }

    private fun tombstone(target: Located) {
        val key = target.work.key
        queries.setCalendarException(key.rootUuid, key.originalDate.dayNumber(), target.scheduled.dayNumber(),
            target.record.row.uuid, target.exception?.overrideUuid, target.exception?.fields ?: 0L, 1L)
    }

    private fun nextRule(command: CalendarSave, target: Located, draft: CalendarWorkDraft): WorkRecurrence? {
        val old = checkNotNull(target.record.rule)
        val advanced = draft.recurrence
        if (advanced == null && command.replaceRecurrence) return null
        val next = advanced ?: old.copy(anchor = draft.date,
            until = old.until?.let { maxOf(it, draft.date) },
            weekdays = if (old.frequency == WorkFrequency.CUSTOM && draft.date.dayOfWeek !in old.weekdays) {
                val adjustment = draft.date.dayOfWeek.ordinal - target.scheduled.dayOfWeek.ordinal
                old.weekdays.mapTo(mutableSetOf()) { kotlinx.datetime.DayOfWeek.entries[(it.ordinal + adjustment + 7) % 7] }
            } else old.weekdays,
            count = old.count?.let { it - old.ordinal(target.scheduled) },
            monthDay = if (draft.date == target.scheduled) old.monthDay else draft.date.day,
        )
        return if (advanced?.count != null && advanced.count == old.count &&
            advanced.frequency == old.frequency && advanced.step == old.step && advanced.weekdays == old.weekdays)
            next.copy(count = advanced.count - old.ordinal(target.scheduled))
        else next
    }

    private fun split(target: Located, draft: CalendarWorkDraft): CalendarSaveResult.Saved {
        val base = target.record
        val key = target.work.key
        val recurrence = draft.recurrence
        // A moved first occurrence keeps its old key. Later generated dates
        // occupy their own dates; an existing original key takes precedence.
        val identityShift = (key.originalDate.dayNumber() - draft.date.dayNumber()).toInt()
        val oldSegments = followingSegments(target)
        val next = if (recurrence != null) newRecordUuid().also {
            persist(it, key.rootUuid, "SEGMENT", draft, null, identityShift = identityShift)
        } else null
        oldSegments.values.forEach {
            val boundary = if (it.row.uuid == base.row.uuid) target.scheduled else it.draft.date
            if (it.stop == null || it.stop > boundary) queries.stopCalendarRule(boundary.dayNumber(), it.row.uuid)
        }
        val nextRecord = next?.let { load(listOf(it)).getValue(it) }
        val existing = queries.selectCalendarExceptionsForRoot(key.rootUuid).executeAsList()
        val records = load(existing.mapNotNull { it.overrideUuid })
        val reserved = existing.mapTo(mutableSetOf()) { it.originalDay }
        var detached = 0
        for (exception in existing.filter { it.segmentUuid in oldSegments && it.originalDay >= key.originalDate.dayNumber() }) {
            if (exception.overrideUuid?.let(records::get)?.row?.kind == "SINGLE") continue
            val previousSegment = oldSegments.getValue(exception.segmentUuid)
            val prior = hydrate(previousSegment, exception, records)
            if (prior != null && (!prior.draft.status.active || !prior.upcoming(clock.now()))) continue
            if (exception.originalDay == key.originalDate.dayNumber()) {
                if (next != null) {
                    queries.setCalendarException(key.rootUuid, exception.originalDay, draft.date.dayNumber(), next, null, 0, 0)
                } else makeStandalone(target, draft)
                continue
            }
            val scheduled = exception.scheduledDay.localDate()
            val remapped = exception.originalDay.localDate().shift(-identityShift)
            val canMap = next != null && remapped > draft.date && checkNotNull(recurrence).contains(remapped)
            if (canMap) {
                val moved = exception.copy(segmentUuid = checkNotNull(next), scheduledDay = remapped.dayNumber())
                queries.setCalendarException(key.rootUuid, moved.originalDay, moved.scheduledDay,
                    moved.segmentUuid, moved.overrideUuid, moved.fields, moved.deleted)
                if (exception.overrideUuid != null && !exception.deleted.bool()) {
                    val inherited = checkNotNull(hydrate(checkNotNull(nextRecord), moved, records))
                    persist(exception.overrideUuid, key.rootUuid, "OVERRIDE", inherited.draft.copy(recurrence = null),
                        inherited, update = true)
                }
            } else if (prior != null && exception.overrideUuid != null) {
                makeStandalone(Located(previousSegment, scheduled, exception, prior), prior.draft.copy(recurrence = null))
                detached++
            }
        }
        if (key.originalDate.dayNumber() !in reserved) {
            if (next != null) queries.setCalendarException(key.rootUuid, key.originalDate.dayNumber(), draft.date.dayNumber(),
                next, null, 0, 0)
            else makeStandalone(target, draft)
        }
        return CalendarSaveResult.Saved(key, detached)
    }

    private fun followingSegments(target: Located): Map<String, WorkRecord> =
        load(queries.selectCalendarWorksForRoot(target.work.key.rootUuid).executeAsList()
            .filter { it.kind == "SEGMENT" }.map { it.uuid }).filterValues {
            it.row.uuid == target.record.row.uuid || it.identityDate(it.draft.date) > target.work.key.originalDate
        }

    private fun makeStandalone(target: Located, draft: CalendarWorkDraft) {
        val key = target.work.key
        val uuid = target.exception?.overrideUuid ?: newRecordUuid()
        persist(uuid, key.rootUuid, "OVERRIDE", draft.copy(recurrence = null), target.work,
            update = target.exception?.overrideUuid != null)
        queries.detachCalendarOverride(uuid)
        queries.setCalendarException(key.rootUuid, key.originalDate.dayNumber(), target.scheduled.dayNumber(),
            target.record.row.uuid, uuid, ALL_FIELDS, 0)
    }

    private fun override(target: Located, draft: CalendarWorkDraft) {
        val key = target.work.key
        val uuid = target.exception?.overrideUuid ?: newRecordUuid()
        val fields = if (draft.status.active) difference(target.record.draft.copy(date = target.scheduled), draft) else ALL_FIELDS
        persist(uuid, key.rootUuid, "OVERRIDE", draft.copy(recurrence = null), target.work,
            update = target.exception?.overrideUuid != null)
        queries.setCalendarException(key.rootUuid, key.originalDate.dayNumber(), target.scheduled.dayNumber(),
            target.record.row.uuid, uuid, fields, 0L)
    }

    private fun locate(key: WorkOccurrenceKey): Located? {
        val ids = queries.selectCalendarWorksForRoot(key.rootUuid).executeAsList()
            .filter { !it.deleted.bool() && it.kind != "OVERRIDE" }.map { it.uuid }
        val exceptions = queries.selectCalendarExceptionsForRoot(key.rootUuid).executeAsList()
        val records = load(ids + exceptions.mapNotNull { it.overrideUuid })
        val exception = exceptions.firstOrNull { it.originalDay == key.originalDate.dayNumber() }
        if (exception != null) {
            val segment = records[exception.segmentUuid] ?: return null
            val work = hydrate(segment, exception, records) ?: return null
            val detached = exception.overrideUuid?.let(records::get)?.takeIf { it.row.kind == "SINGLE" }
            return Located(detached ?: segment, exception.scheduledDay.localDate(), exception, work)
        }
        val match = ids.asSequence().map(records::getValue).map { record ->
            record to record.scheduledDate(key.originalDate)
        }.firstOrNull { (record, scheduled) ->
            val matches = if (record.rule == null) record.row.uuid == record.row.rootUuid &&
                record.row.originalDay.localDate() == key.originalDate else record.includes(scheduled)
            matches && exceptions.none { it.segmentUuid == record.row.uuid && it.scheduledDay == scheduled.dayNumber() }
        } ?: return null
        val (record, scheduled) = match
        return Located(record, scheduled, null, record.toWork(if (record.rule == null) record.draft.date else scheduled))
    }

    private fun readPeriod(range: WorkDateRange, zone: TimeZone): List<CalendarWork> {
        val from = range.from.shift(-2)
        val through = range.through.shift(2)
        val ids = queries.selectCalendarCandidates(through.dayNumber(), from.dayNumber()).executeAsList().distinct()
        val base = load(ids)
        val exceptions = ids.chunked(250).flatMap {
            queries.selectCalendarExceptions(it, from.shift(-365).dayNumber(), through.dayNumber()).executeAsList()
        }
        val records = base + load(exceptions.mapNotNull { it.overrideUuid })
        val bySegment = exceptions.groupBy { it.segmentUuid }
        val reservedFrom = base.values.minOfOrNull { it.identityDate(from.shift(-365)) } ?: from
        val reservedThrough = base.values.maxOfOrNull { it.identityDate(through) } ?: through
        val reserved = base.values.map { it.row.rootUuid }.distinct().chunked(250).flatMap {
            queries.selectCalendarReservedKeys(it, reservedFrom.dayNumber(), reservedThrough.dayNumber()).executeAsList()
        }.groupBy { it.rootUuid }.mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.originalDay } }
        return (ids.flatMap { id ->
            val row = base.getValue(id)
            val overrides = bySegment[id].orEmpty()
            val suppressed = overrides.mapTo(mutableSetOf()) { it.scheduledDay }
            val dates = row.dates(WorkDateRange(from.shift(-(row.row.lastDay - row.row.dateDay).toInt()), through))
            dates.filter { it.dayNumber() !in suppressed &&
                row.identityDate(it).dayNumber() !in reserved[row.row.rootUuid].orEmpty() }
                .map(row::toWork).toList() + overrides.mapNotNull { hydrate(row, it, records) }
        }).filter { work -> work.displayDates(zone).any { it in range } }.distinctBy { it.key }.sortedWith(workOrder)
    }

    private fun hydrate(base: WorkRecord, exception: Calendar_exceptions, records: Map<String, WorkRecord>): CalendarWork? {
        if (exception.deleted.bool()) return null
        val keyDate = exception.originalDay.localDate()
        val scheduled = exception.scheduledDay.localDate()
        val replacement = exception.overrideUuid?.let(records::get)
        if (replacement == null) {
            return base.toWork(scheduled, keyDate).takeIf { base.includes(scheduled) }
        }
        if (replacement.row.deleted.bool()) return null
        val inherited = base.toWork(scheduled, keyDate)
        val personal = replacement.toWork(replacement.draft.date, keyDate)
        val fields = exception.fields
        fun has(field: Long) = fields and field != 0L
        val draft = inherited.draft
        val patch = personal.draft
        if (replacement.row.kind == "SINGLE") return personal.copy(draft = patch.copy(recurrence = null))
        val contact = WorkContact(
            if (has(ADDRESS)) patch.contactOverrides.address else draft.contactOverrides.address,
            if (has(CONTACT_NAME)) patch.contactOverrides.name else draft.contactOverrides.name,
            if (has(CONTACT_PHONE)) patch.contactOverrides.phone else draft.contactOverrides.phone,
            if (has(CONTACT_EMAIL)) patch.contactOverrides.email else draft.contactOverrides.email,
        )
        return inherited.copy(
            draft = draft.copy(
                date = if (has(DATE)) patch.date else scheduled,
                title = if (has(TITLE)) patch.title else draft.title,
                typeCode = if (has(TYPE)) patch.typeCode else draft.typeCode,
                status = if (has(STATUS)) patch.status else draft.status,
                notes = if (has(NOTES)) patch.notes else draft.notes,
                projectUuid = if (has(LINKS)) patch.projectUuid else draft.projectUuid,
                clientUuid = if (has(LINKS)) patch.clientUuid else draft.clientUuid,
                contactOverrides = contact,
                schedule = if (has(SCHEDULE)) patch.schedule else draft.schedule,
                ceilings = if (has(CEILINGS)) patch.ceilings else draft.ceilings,
                explicitCeilings = if (has(CEILINGS)) patch.explicitCeilings else draft.explicitCeilings,
            ),
            projectLabel = if (has(LINKS) || replacement.row.frozen.bool()) personal.projectLabel else inherited.projectLabel,
            contact = contact.over(if (has(LINKS)) replacement.liveContact else base.liveContact),
        )
    }

    private fun load(ids: List<String>): Map<String, WorkRecord> = ids.distinct().chunked(250).flatMap { batch ->
        val rules = queries.selectCalendarRules(batch).executeAsList().associateBy { it.workUuid }
        val days = queries.selectCalendarDays(batch).executeAsList().groupBy { it.workUuid }
        val ceilings = queries.selectCalendarCeilings(batch).executeAsList().groupBy { it.workUuid }
        queries.selectCalendarDetails(batch).executeAsList().map { row ->
            val date = row.dateDay.localDate()
            val persistedRule = rules[row.uuid]
            val rule = persistedRule?.toRule(date)
            val draft = CalendarWorkDraft(
                date, WorkSchedule(WorkTimeMode.valueOf(row.timeMode), TimeZone.of(row.zoneId),
                    days.getValue(row.uuid).map { WorkDay(it.dayOffset.toInt(),
                        it.startSecond?.let { seconds -> LocalTime.fromSecondOfDay(seconds.toInt()) },
                        it.endSecond?.let { seconds -> LocalTime.fromSecondOfDay(seconds.toInt()) }) }),
                row.title, row.typeCode, WorkStatus.valueOf(row.status), row.notes, row.projectUuid, row.clientUuid,
                WorkContact(row.addressOverride, row.nameOverride, row.phoneOverride, row.emailOverride),
                ceilings[row.uuid].orEmpty().map { WorkCeiling(it.ceilingUuid, it.label, it.removed.bool()) },
                row.explicitCeilings.bool(), rule,
            )
            row.uuid to WorkRecord(row, draft, rule, persistedRule?.stopBeforeDay?.localDate(),
                persistedRule?.identityShift?.toInt() ?: 0)
        }
    }.toMap()

    private fun draftForScope(command: CalendarSave, target: Located?): CalendarWorkDraft {
        val draft = command.draft
        if (target == null) return draft
        val changingSeries = command.scope == CalendarEditScope.THIS_AND_FOLLOWING && target.record.rule != null &&
            target.work.draft.status.active && draft.status.active
        return if (changingSeries && (command.replaceRecurrence || draft.recurrence != target.record.rule)) draft else draft.copy(recurrence = null)
    }

    private fun validate(draft: CalendarWorkDraft, prior: CalendarWorkDraft?): CalendarWorkDraft {
        require(draft.title.isNotBlank() && draft.date.year in 1..9998)
        require(draft.schedule.zone.id in TimeZone.availableZoneIds || draft.schedule.zone == TimeZone.UTC)
        require(draft.recurrence == null || draft.recurrence.anchor == draft.date)
        require(draft.recurrence?.until == null || draft.recurrence.until.year in 1..9998)
        val type = types().firstOrNull { it.code == draft.typeCode }
        require(type != null && (!type.archived || prior?.typeCode == draft.typeCode))
        require(draft.typeCode != "INSTALL" || draft.projectUuid != null)
        val project = draft.projectUuid?.let { queries.selectCalendarDirectProject(it).executeAsOneOrNull() }
        require(draft.projectUuid == null || project != null || prior?.projectUuid == draft.projectUuid && !draft.status.active)
        require(draft.clientUuid == null || queries.selectCalendarDirectClient(draft.clientUuid).executeAsOneOrNull() != null ||
            prior?.clientUuid == draft.clientUuid)
        require(draft.explicitCeilings || draft.ceilings.isEmpty())
        require(draft.ceilings.isEmpty() || draft.typeCode == "INSTALL")
        require(draft.ceilings.map { it.uuid }.distinct().size == draft.ceilings.size)
        require(draft.schedule.mode != WorkTimeMode.TIMED || draft.schedule.on(draft.date).all { it.interval != null })
        val ceilings = draft.ceilings.map {
            val row = queries.selectCalendarCeiling(it.uuid).executeAsOneOrNull()
            if (row == null) {
                checkNotNull(prior?.ceilings?.find { link -> link.uuid == it.uuid && link.removed })
            } else {
                require(row.projectId == project?.id)
                WorkCeiling(row.uuid, row.name)
            }
        }.sortedBy { it.uuid }
        return draft.copy(title = draft.title.trim(), ceilings = ceilings)
    }

    private fun persist(
        uuid: String, root: String, kind: String, draft: CalendarWorkDraft,
        prior: CalendarWork?, update: Boolean = false, identityShift: Int = 0,
    ) {
        val project = draft.projectUuid?.let { queries.selectCalendarDirectProject(it).executeAsOneOrNull() }
        val client = if (draft.clientUuid != null) queries.selectCalendarDirectClient(draft.clientUuid).executeAsOneOrNull()
            else project?.clientId?.let { database.businessSchemaQueries.selectClientById(it).executeAsOneOrNull() }
        val frozen = !draft.status.active
        val inherited = if (prior != null && !prior.draft.status.active) prior.contact else WorkContact(
            project?.address, client?.name, client?.phone, client?.email,
        )
        val contact = if (frozen) draft.contactOverrides.over(inherited) else draft.contactOverrides
        val projectLabel = if (frozen) project?.title ?: prior?.projectLabel else null
        val start = draft.date.dayNumber()
        val end = start + draft.schedule.days.last().offset
        if (update) {
            queries.updateCalendarWork(start, end, draft.typeCode, draft.status.name, draft.schedule.mode.name,
                draft.schedule.zone.id, draft.title, draft.notes, draft.projectUuid, draft.clientUuid, projectLabel,
                contact.address, contact.name, contact.phone, contact.email, frozen.sql(), 0L, draft.explicitCeilings.sql(), uuid)
            queries.deleteCalendarDays(uuid)
            queries.deleteCalendarCeilings(uuid)
        } else {
            queries.insertCalendarWork(uuid, root, kind, start, end, draft.typeCode, draft.status.name,
                draft.schedule.mode.name, draft.schedule.zone.id, draft.title, draft.notes, draft.projectUuid,
                draft.clientUuid, projectLabel, contact.address, contact.name, contact.phone, contact.email,
                frozen.sql(), 0L, draft.explicitCeilings.sql())
        }
        draft.schedule.days.forEach { queries.insertCalendarDay(uuid, it.offset.toLong(),
            it.start?.toSecondOfDay()?.toLong(), it.end?.toSecondOfDay()?.toLong()) }
        draft.ceilings.forEach { queries.insertCalendarCeiling(uuid, it.uuid, it.label, it.removed.sql()) }
        draft.recurrence?.let { rule ->
            require(rule.anchor == draft.date)
            queries.setCalendarRule(uuid, rule.frequency.name, rule.step.toLong(), rule.weekdays.mask(),
                rule.monthDay.toLong(), rule.until?.dayNumber(), rule.count?.toLong(), identityShift.toLong())
        }
    }

    private fun checkConflicts(
        command: CalendarSave, draft: CalendarWorkDraft, target: Located?,
    ): Pair<Set<CalendarConflict>, LocalDate?> {
        if (!draft.status.active) return emptySet<CalendarConflict>() to null
        val zone = draft.schedule.zone
        val rule = draft.recurrence.takeIf { target == null || command.scope == CalendarEditScope.THIS_AND_FOLLOWING }
        val today = clock.now().toLocalDateTime(zone).date
        val start = maxOf(today, draft.date)
        val end = rule?.let { minOf(start.shift(89), it.until ?: start.shift(89)) }
        val proposedDates = when {
            rule == null -> listOf(draft.date)
            end != null && end >= start -> rule.occurrences(WorkDateRange(start, end)).toList()
            else -> emptyList()
        }
        val lastProposed = proposedDates.lastOrNull()
        val checkedThrough = if (lastProposed != null && rule?.count != null && rule.ordinal(lastProposed) + 1 == rule.count)
            lastProposed else end
        val root = target?.work?.key?.rootUuid ?: ""
        val shiftingSeries = target?.takeIf { it.record.rule != null && it.work.draft.status.active &&
            command.scope == CalendarEditScope.THIS_AND_FOLLOWING }
        val identityShift = shiftingSeries?.let {
            (it.work.key.originalDate.dayNumber() - draft.date.dayNumber()).toInt()
        } ?: 0
        val affected = shiftingSeries?.let(::followingSegments).orEmpty()
        val exceptions = if (shiftingSeries == null) emptyMap() else
            queries.selectCalendarExceptionsForRoot(root).executeAsList().associateBy { it.originalDay }
        val overrides = load(exceptions.values.mapNotNull { it.overrideUuid })
        val replaced = mutableSetOf<WorkOccurrenceKey>()
        val now = clock.now()
        val proposed = proposedDates.flatMap { date ->
            val key = if (target != null && rule == null) target.work.key
                else WorkOccurrenceKey(root, date.shift(identityShift))
            val exception = exceptions[key.originalDate.dayNumber()]
            val patch = exception?.overrideUuid?.let(overrides::get)
            val base = exception?.segmentUuid?.let(affected::get)
            if (key != target?.work?.key && exception != null) {
                if (exception.deleted.bool() || patch?.row?.kind == "SINGLE" || base == null)
                    return@flatMap emptyList()
                val prior = hydrate(base, exception, overrides)
                if (prior != null && !prior.upcoming(now)) return@flatMap emptyList()
            }
            replaced += key
            val fields = if (key == target?.work?.key) 0L else exception?.fields ?: 0L
            val dateOnCalendar = if (fields and DATE != 0L && patch != null) patch.draft.date else date
            val schedule = if (fields and SCHEDULE != 0L && patch != null) patch.draft.schedule else draft.schedule
            schedule.on(dateOnCalendar).mapNotNull { it.interval?.let { interval -> key to interval } }
        }
        if (proposed.isEmpty()) return emptySet<CalendarConflict>() to checkedThrough
        val ranges = proposed.map { (_, interval) ->
            WorkDateRange(interval.start.toLocalDateTime(zone).date.shift(-2),
                interval.endExclusive.toLocalDateTime(zone).date.shift(2))
        }.distinct().sortedBy { it.from }.fold(mutableListOf<WorkDateRange>()) { merged, range ->
            val last = merged.lastOrNull()
            if (last == null || range.from > last.through.shift(1)) merged += range
            else merged[merged.lastIndex] = WorkDateRange(last.from, maxOf(last.through, range.through))
            merged
        }
        val others = ranges.flatMap { readPeriod(it, zone) }.distinctBy { it.key }.filter { work ->
            if (!work.draft.status.active || work.key == target?.work?.key || work.key in replaced) false
            else if (shiftingSeries == null || work.key.rootUuid != root ||
                work.key.originalDate < shiftingSeries.work.key.originalDate) true
            else exceptions[work.key.originalDate.dayNumber()]?.overrideUuid != null
        }.flatMap { work -> work.days.mapNotNull { day -> day.interval?.let { work.key to it } } }
        val entries = (proposed.map { (key, interval) -> ConflictInterval(key, interval, true) } +
            others.map { (key, interval) -> ConflictInterval(key, interval, false) }).sortedBy { it.interval.start }
        val overlapping = mutableListOf<ConflictInterval>()
        val conflicts = buildSet {
            for (entry in entries) {
                overlapping.removeAll { it.interval.endExclusive <= entry.interval.start }
                for (other in overlapping) {
                    if ((!entry.proposed && !other.proposed) || entry.key == other.key) continue
                    val candidate = if (entry.proposed) entry else other
                    val existing = if (entry.proposed) other else entry
                    add(CalendarConflict(candidate.key, existing.key, candidate.interval, existing.interval))
                }
                overlapping += entry
            }
        }
        return conflicts to checkedThrough
    }
}

private data class ConflictInterval(val key: WorkOccurrenceKey, val interval: WorkInterval, val proposed: Boolean)

private data class Located(val record: WorkRecord, val scheduled: LocalDate, val exception: Calendar_exceptions?, val work: CalendarWork)

private data class WorkRecord(
    val row: SelectCalendarDetails, val draft: CalendarWorkDraft, val rule: WorkRecurrence?, val stop: LocalDate?,
    val identityShift: Int,
) {
    val liveContact get() = if (row.frozen.bool()) WorkContact()
        else WorkContact(row.liveAddress, row.liveName, row.livePhone, row.liveEmail)

    fun identityDate(scheduled: LocalDate): LocalDate = scheduled.shift(identityShift)
    fun scheduledDate(identity: LocalDate): LocalDate = identity.shift(-identityShift)
    fun toWork(date: LocalDate): CalendarWork = toWork(date, if (rule == null) row.originalDay.localDate() else identityDate(date))
    fun toWork(date: LocalDate, identity: LocalDate): CalendarWork = CalendarWork(
        WorkOccurrenceKey(row.rootUuid, identity), row.version, draft.copy(date = date),
        if (row.frozen.bool()) row.projectLabel else row.liveProjectLabel, draft.contactOverrides.over(liveContact)
    )

    fun dates(range: WorkDateRange): Sequence<LocalDate> {
        val candidates = rule?.occurrences(range) ?: sequenceOf(draft.date).filter { it in range }
        return candidates.takeWhile { stop == null || it < stop }
    }

    fun includes(date: LocalDate): Boolean = dates(WorkDateRange(date, date)).any()
}

private fun Calendar_rules.toRule(anchor: LocalDate) = WorkRecurrence(
    anchor, WorkFrequency.valueOf(frequency), step.toInt(), weekdays.weekdays(),
    untilDay?.localDate(), occurrenceCount?.toInt(), monthDay.toInt(),
)

private fun difference(base: CalendarWorkDraft, changed: CalendarWorkDraft): Long {
    var fields = 0L
    if (base.title != changed.title) fields = fields or TITLE
    if (base.typeCode != changed.typeCode) fields = fields or TYPE
    if (base.status != changed.status) fields = fields or STATUS
    if (base.notes != changed.notes) fields = fields or NOTES
    if (base.projectUuid != changed.projectUuid || base.clientUuid != changed.clientUuid) fields = fields or LINKS
    if (base.contactOverrides.address != changed.contactOverrides.address) fields = fields or ADDRESS
    if (base.contactOverrides.name != changed.contactOverrides.name) fields = fields or CONTACT_NAME
    if (base.contactOverrides.phone != changed.contactOverrides.phone) fields = fields or CONTACT_PHONE
    if (base.contactOverrides.email != changed.contactOverrides.email) fields = fields or CONTACT_EMAIL
    if (base.schedule != changed.schedule) fields = fields or SCHEDULE
    if (base.date != changed.date) fields = fields or DATE
    if (base.ceilings != changed.ceilings || base.explicitCeilings != changed.explicitCeilings) fields = fields or CEILINGS
    return fields
}

private val workOrder = compareBy<CalendarWork> { it.draft.date }
    .thenBy { it.draft.schedule.days.first().start }.thenBy { it.key.rootUuid }.thenBy { it.key.originalDate }

internal fun LocalDate.dayNumber(): Long = toEpochDays()
internal fun Long.localDate(): LocalDate = LocalDate.fromEpochDays(this)
internal fun LocalDate.shift(days: Int): LocalDate = plus(DatePeriod(days = days))
internal fun Boolean.sql(): Long = if (this) 1L else 0L
internal fun Long.bool(): Boolean = this != 0L
private fun WorkRecurrence.contains(date: LocalDate) = occurrences(WorkDateRange(date, date)).any()
