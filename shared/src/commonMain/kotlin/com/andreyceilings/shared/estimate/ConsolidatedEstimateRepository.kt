package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.newRecordUuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString

internal class ConsolidatedEstimateRepository(private val database: PrototypeDatabase) {
    private val quotes = database.consolidatedQuotesQueries
    private val individual = ImmutableEstimateRepository(database)
    private val business = database.businessSchemaQueries
    private val issuance = database.estimateIssuanceQueries

    fun head(projectId: Long) = quotes.seriesForProject(projectId).executeAsOneOrNull()?.let {
        quotes.head(it).executeAsOne().revisionUuid
    }

    fun save(command: ConsolidatedSave, date: LocalDate, now: Long): ConsolidatedRevisionIdentity = write {
        require(isEstimateUuid(command.operationUuid) && isEstimateUuid(command.projectUuid) && now > 0)
        require(validEstimateTitle(command.draft.title) == command.draft.title)
        val encodedCommand = frozenEstimateJson.encodeToString(command)
        quotes.receipt(command.operationUuid).executeAsOneOrNull()?.let {
            if (it.command != encodedCommand) throw ConsolidatedConflict()
            val saved = read(it.revisionUuid, historical = true) ?: throw ConsolidatedConflict()
            return@write saved.frozen.identity()
        }
        with(command.operationUuid) {
            require(issuance.selectIssuanceReceipt(this).executeAsOneOrNull() == null &&
                database.frozenEstimateQueries.selectEstimateSaveReceipt(this).executeAsOneOrNull() == null &&
                database.frozenRevisionQueries.selectRevisionReceipt(this).executeAsOneOrNull() == null)
        }
        val project = business.selectProjectById(command.projectId).executeAsOneOrNull()
            ?.takeIf { it.uuid == command.projectUuid } ?: throw ConsolidatedConflict()
        val series = quotes.seriesForProject(project.id).executeAsOneOrNull()
        val head = series?.let { quotes.head(it).executeAsOne() }
        if (head?.revisionUuid != command.expectedHead || head?.versionNumber == Long.MAX_VALUE) {
            throw ConsolidatedConflict()
        }
        if (command.basedOn != null) {
            val base = read(command.basedOn) ?: throw ConsolidatedConflict()
            require(base.frozen.seriesUuid == series)
        }
        val draftProof = consolidatedProof(frozenEstimateJson.encodeToString(command.draft))
        if (series != null && quotes.identicalDraft(series, draftProof).executeAsOneOrNull() != null) {
            throw DuplicateConsolidatedContent()
        }
        val sources = sources(project.id, project.uuid, command.draft.selection, historical = false)
        val members = sources.zip(command.draft.selection) { saved, ref -> saved.member(ref) }
        val amounts = consolidatedAmounts(members.map { it.finalCents }, command.draft.discount)
            ?: throw ConsolidatedConflict()
        // Every admitted revision must be renderable from its frozen source.
        sources.forEach { FrozenEstimateDocument.from(it, null, TimeZone.UTC) }
        val revisionUuid = newRecordUuid()
        require(individual.revision(revisionUuid) == null)
        fun insert(seriesUuid: String): ConsolidatedRevisionIdentity {
            val number = issuance.selectReservation(seriesUuid).executeAsOne()
            check(number.estimateType == EstimateType.CONSOLIDATED.name && number.operationUuid == null)
            val frozen = FrozenConsolidatedEstimate(
                seriesUuid = seriesUuid, revisionUuid = revisionUuid, version = (head?.versionNumber ?: 0) + 1,
                parentRevisionUuid = head?.revisionUuid, projectUuid = project.uuid, projectTitle = project.title,
                projectAddress = project.address.takeIf(String::isNotBlank), issueDate = number.issueDate,
                publicNumber = number.publicNumber, createdAt = now, operationUuid = command.operationUuid,
                commandProof = consolidatedProof(encodedCommand), draft = command.draft, members = members,
                currency = "UAH", amounts = amounts
            )
            val payload = frozenEstimateJson.encodeToString(frozen)
            quotes.insertRevision(revisionUuid, seriesUuid, project.id, frozen.version, frozen.parentRevisionUuid,
                now, command.draft.title, amounts.finalCents, command.draft.discount.mode.name, command.draft.discount.value,
                payload, consolidatedProof(payload), draftProof, command.operationUuid, encodedCommand)
            members.forEachIndexed { position, member ->
                quotes.insertMember(revisionUuid, position.toLong(), member.ref.measurementUuid, member.ref.revisionUuid)
            }
            quotes.insertWorkflow(revisionUuid, project.id, now)
            return frozen.identity()
        }
        if (series != null) insert(series)
        else EstimateNumberAllocator(database).issue(EstimateType.CONSOLIDATED, date) { reservation ->
            quotes.insertSeries(reservation.seriesUuid, project.id)
            insert(reservation.seriesUuid)
        }
    }

    fun read(revisionUuid: String, historical: Boolean = false): SavedConsolidatedEstimate? {
        val row = quotes.revision(revisionUuid).executeAsOneOrNull() ?: return null
        val workflow = quotes.workflow(revisionUuid).executeAsOne()
        val project = quotes.projectIdentity(row.projectId).executeAsOne()
        if (!historical && (workflow.deletedAt != null || project.deletedAt != null)) return null
        val frozen = decode(row.payload, row.proof)
        val command = frozenEstimateJson.decodeFromString<ConsolidatedSave>(row.command)
        val reservation = issuance.selectReservation(row.seriesUuid).executeAsOne()
        check(frozen.revisionUuid == row.revisionUuid && frozen.seriesUuid == row.seriesUuid &&
            isEstimateUuid(frozen.revisionUuid) && isEstimateUuid(frozen.operationUuid) &&
            isEstimateUuid(frozen.projectUuid) && frozen.version == row.versionNumber &&
            frozen.parentRevisionUuid == row.parentRevisionUuid && frozen.createdAt == row.createdAt &&
            frozen.draft.title == row.title && validEstimateTitle(row.title) == row.title &&
            frozen.amounts.finalCents == row.finalCents && frozen.currency == "UAH" &&
            frozen.draft.discount.mode.name == row.discountMode && frozen.draft.discount.value == row.discountValue &&
            frozen.operationUuid == row.operationUuid && command.operationUuid == row.operationUuid &&
            frozen.commandProof == consolidatedProof(row.command) &&
            row.draftProof == consolidatedProof(frozenEstimateJson.encodeToString(frozen.draft)) &&
            command.projectId == row.projectId &&
            command.projectUuid == frozen.projectUuid && command.draft == frozen.draft &&
            command.expectedHead == row.parentRevisionUuid &&
            reservation.estimateType == EstimateType.CONSOLIDATED.name && reservation.operationUuid == null &&
            reservation.publicNumber == frozen.publicNumber && reservation.issueDate == frozen.issueDate &&
            project.uuid == frozen.projectUuid)
        row.parentRevisionUuid?.let {
            val parent = quotes.revision(it).executeAsOne()
            check(parent.seriesUuid == row.seriesUuid && parent.versionNumber == row.versionNumber - 1)
        }
        command.basedOn?.let {
            val base = quotes.revision(it).executeAsOne()
            check(base.seriesUuid == row.seriesUuid && base.versionNumber < row.versionNumber &&
                decode(base.payload, base.proof).draft != frozen.draft)
        }
        val members = quotes.members(revisionUuid).executeAsList()
        check(members.size == frozen.members.size && members.withIndex().all { (index, member) ->
            val ref = frozen.members[index].ref
            member.position == index.toLong() && member.measurementUuid == ref.measurementUuid &&
                member.individualRevisionUuid == ref.revisionUuid
        })
        val sources = sources(row.projectId, frozen.projectUuid, frozen.draft.selection, historical = true)
        check(sources.zip(frozen.draft.selection) { saved, ref -> saved.member(ref) } == frozen.members &&
            consolidatedAmounts(frozen.members.map { it.finalCents }, frozen.draft.discount) == frozen.amounts)
        val status = EstimateWorkflowStatus.valueOf(workflow.status)
        if (status == EstimateWorkflowStatus.APPROVED) {
            check(project.deletedAt == null && workflow.deletedAt == null)
            for (source in sources) {
                val ref = checkNotNull(individual.exactIndividual(source.seriesUuid, source.revisionUuid))
                val room = business.selectMeasurementByUuid(ref.frozen.source.measurementUuid).executeAsOne()
                check(ref.status == EstimateWorkflowStatus.APPROVED && room.deletedAt == null && room.projectId == row.projectId)
            }
        }
        return SavedConsolidatedEstimate(frozen, status, workflow.version, workflow.deletedAt != null, sources)
    }

    fun confirmation(revisionUuid: String, status: EstimateWorkflowStatus): ConsolidatedStatusConfirmation? {
        val target = read(revisionUuid) ?: return null
        val projectId = quotes.revision(revisionUuid).executeAsOne().projectId
        val prior = if (status == EstimateWorkflowStatus.APPROVED || target.status == EstimateWorkflowStatus.APPROVED) {
            quotes.approved(projectId).executeAsOneOrNull()
        } else null
        val included = target.frozen.draft.selection
        val affected = if (status == EstimateWorkflowStatus.APPROVED) {
            val old = prior?.takeIf { it.revisionUuid != revisionUuid }?.let {
                checkNotNull(read(it.revisionUuid)).frozen.draft.selection
            }.orEmpty()
            val previous = (included + old).distinctBy { it.measurementUuid }.flatMap {
                database.revisionWorkflowQueries.selectApprovedQuoteVersions(projectId, it.measurementUuid)
                    .executeAsList().also { rows -> check(rows.size <= 1) }.map { row -> row.revisionUuid }
            }
            included.map { it.revisionUuid } + previous
        } else included.map { it.revisionUuid }
        val states = affected.distinct().associateWith {
            val row = database.revisionWorkflowQueries.selectActiveQuoteWorkflow(it).executeAsOneOrNull()
            row?.let { workflow -> workflow.status?.let(EstimateWorkflowStatus::valueOf) to workflow.version }
                ?: (null to 0L)
        }
        return ConsolidatedStatusConfirmation(revisionUuid, status, target.statusVersion,
            prior?.let { it.revisionUuid to it.version }, states)
    }

    fun changeStatus(confirmed: ConsolidatedStatusConfirmation, revokeIndividuals: Boolean, now: Long): Boolean =
        try {
            write {
                require(now > 0)
                val target = read(confirmed.revisionUuid) ?: throw ConsolidatedConflict()
                val requested = confirmed.targetStatus
                val actual = confirmation(confirmed.revisionUuid, requested)
                if (actual != confirmed || requested == EstimateWorkflowStatus.DRAFT) throw ConsolidatedConflict()
                if (target.status == requested) return@write true
                val included = target.frozen.draft.selection
                if (requested == EstimateWorkflowStatus.APPROVED) {
                    val projectId = quotes.revision(confirmed.revisionUuid).executeAsOne().projectId
                    sources(projectId, target.frozen.projectUuid, included, historical = false)
                    val old = confirmed.priorApproval
                    if (old != null) {
                        val previous = checkNotNull(read(old.first))
                        setConsolidated(old.first, old.second, EstimateWorkflowStatus.SENT, now)
                        if (revokeIndividuals) {
                            val ceilings = included.map { it.measurementUuid }.toSet()
                            previous.frozen.draft.selection.filter { it.measurementUuid !in ceilings }.forEach { ref ->
                                val state = confirmed.individuals.getValue(ref.revisionUuid)
                                check(state.first == EstimateWorkflowStatus.APPROVED)
                                setIndividual(ref.revisionUuid, state.second, EstimateWorkflowStatus.SENT, now)
                            }
                        }
                    }
                    for (ref in included) {
                        val state = confirmed.individuals.getValue(ref.revisionUuid)
                        val previous = database.revisionWorkflowQueries.selectApprovedQuoteVersions(projectId, ref.measurementUuid)
                            .executeAsList()
                        previous.filter { it.revisionUuid != ref.revisionUuid }.forEach {
                            setIndividual(it.revisionUuid, it.version, EstimateWorkflowStatus.SENT, now)
                        }
                        if (state.first != EstimateWorkflowStatus.APPROVED) {
                            setIndividual(ref.revisionUuid, state.second, EstimateWorkflowStatus.APPROVED, now)
                        }
                    }
                } else if (target.status == EstimateWorkflowStatus.APPROVED && revokeIndividuals) {
                    included.forEach { ref ->
                        val state = confirmed.individuals.getValue(ref.revisionUuid)
                        check(state.first == EstimateWorkflowStatus.APPROVED)
                        setIndividual(ref.revisionUuid, state.second, EstimateWorkflowStatus.SENT, now)
                    }
                }
                setConsolidated(confirmed.revisionUuid, confirmed.statusVersion, requested, now)
                true
            }
        } catch (_: ConsolidatedConflict) {
            false
        }

    fun shareDispatched(confirmed: ConsolidatedStatusConfirmation, now: Long): Boolean = try {
        write {
            require(now > 0 && confirmed.targetStatus == EstimateWorkflowStatus.SENT)
            val target = read(confirmed.revisionUuid) ?: throw ConsolidatedConflict()
            val actual = confirmation(confirmed.revisionUuid, EstimateWorkflowStatus.SENT)
                ?: throw ConsolidatedConflict()
            val drafts = confirmed.individuals.filterValues { it.first == EstimateWorkflowStatus.DRAFT }
            if (target.status == EstimateWorkflowStatus.SENT && confirmed.statusVersion < Long.MAX_VALUE &&
                actual == confirmed.copy(statusVersion = confirmed.statusVersion + 1,
                    individuals = confirmed.individuals.mapValues { (_, state) ->
                        if (state.first == EstimateWorkflowStatus.DRAFT && state.second < Long.MAX_VALUE) {
                            EstimateWorkflowStatus.SENT to state.second + 1
                        } else state
                    })) return@write true
            if (actual != confirmed || target.status != EstimateWorkflowStatus.DRAFT ||
                confirmed.individuals.values.any { it.second == 0L }) throw ConsolidatedConflict()
            val projectId = quotes.revision(confirmed.revisionUuid).executeAsOne().projectId
            sources(projectId, target.frozen.projectUuid, target.frozen.draft.selection, historical = false)
            drafts.forEach { (revision, state) -> setIndividual(revision, state.second, EstimateWorkflowStatus.SENT, now) }
            setConsolidated(confirmed.revisionUuid, confirmed.statusVersion, EstimateWorkflowStatus.SENT, now)
            true
        }
    } catch (_: ConsolidatedConflict) {
        false
    }

    fun delete(projectId: Long, expected: Map<String, Long>, entireSeries: Boolean, now: Long): Boolean = try {
        write {
            require(now > 0 && expected.isNotEmpty() && (entireSeries || expected.size == 1))
            val active = quotes.activeVersions(projectId).executeAsList().associateBy { it.revisionUuid }
            if (entireSeries && active.keys != expected.keys) throw ConsolidatedConflict()
            for ((uuid, version) in expected) {
                if (active[uuid]?.version != version) throw ConsolidatedConflict()
                quotes.deleteRevision(now, uuid, version).executeAsOneOrNull() ?: throw ConsolidatedConflict()
            }
            true
        }
    } catch (_: ConsolidatedConflict) {
        false
    }

    private fun setConsolidated(revisionUuid: String, version: Long, status: EstimateWorkflowStatus, now: Long) {
        quotes.updateWorkflow(status.name, now, revisionUuid, version).executeAsOneOrNull()
            ?: throw ConsolidatedConflict()
    }

    private fun setIndividual(revisionUuid: String, version: Long, status: EstimateWorkflowStatus, now: Long) {
        database.revisionWorkflowQueries.updateRevisionWorkflow(status.name, now, revisionUuid, version)
            .executeAsOneOrNull() ?: throw ConsolidatedConflict()
    }

    private fun sources(projectId: Long, projectUuid: String, refs: List<ConsolidatedDocumentRef>,
        historical: Boolean): List<SavedIndividualQuote> {
        require(refs.isNotEmpty() && refs.distinctBy { it.measurementUuid }.size == refs.size &&
            refs.distinctBy { it.revisionUuid }.size == refs.size)
        return refs.map { ref ->
            if (!historical) {
                val ceiling = business.selectMeasurementByUuid(ref.measurementUuid).executeAsOneOrNull()
                if (ceiling == null || ceiling.projectId != projectId || ceiling.deletedAt != null) {
                    throw ConsolidatedConflict()
                }
            }
            val saved = (if (historical) individual.historicalIndividual(ref.seriesUuid, ref.revisionUuid)
                else individual.exactIndividual(ref.seriesUuid, ref.revisionUuid)) ?: throw ConsolidatedConflict()
            val frozen = saved.frozen
            require((historical || saved.status != null) &&
                saved.projectId == projectId && frozen.source.projectUuid == projectUuid &&
                frozen.source.measurementUuid == ref.measurementUuid && frozen.consolidatedSourceProof() == ref.fingerprint &&
                frozen.currency == "UAH")
            saved
        }
    }

    private fun SavedIndividualQuote.member(ref: ConsolidatedDocumentRef) =
        ConsolidatedMember(ref, frozen.source.measurementName, versionNumber, frozen.amounts.finalCents)

    private fun FrozenConsolidatedEstimate.identity() = ConsolidatedRevisionIdentity(seriesUuid, revisionUuid, version)

    private fun decode(payload: String, proof: String): FrozenConsolidatedEstimate {
        val frozen = frozenEstimateJson.decodeFromString<FrozenConsolidatedEstimate>(payload)
        check(frozen.formatVersion == 1 && consolidatedProof(frozenEstimateJson.encodeToString(frozen)) == proof)
        return frozen
    }

    internal fun <T> write(operation: () -> T): T = database.transactionWithResult {
        database.priceGroupsQueries.claimCatalogWrite()
        operation()
    }
}
