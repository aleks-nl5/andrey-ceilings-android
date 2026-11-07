package com.andreyceilings.shared.db

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*

/** Explicit per-measurement main selections, reused by common, native and CMP scene proofs. */
object MeasurementPricingFixture {
    data class Fixture(val projectId: Long, val measurementUuid: String, val elementUuid: String, val priceId: Long, val profileUuid: String)
    fun seed(database: PrototypeDatabase): Fixture {
        val suffix = newRecordUuid()
        val project = ProjectRepository(database).createReturningId(null, "Pricing $suffix", createdAt = 100)
        val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Room", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), newRecordUuid())
        MeasurementCreateStore(database) { 101 }.create(draft)
        val group = PriceGroupRepository(database).create("Pricing $suffix", 102, "м²", QuantityMode.CEILING_AREA)
        val priceId = PriceRepository(database).createInGroup("pricing.$suffix", "Canvas $suffix", group, 10.0, 103)
        val item = PriceRepository(database).find(priceId)!!
        val element = MeasurementElementRepository(database).createCatalogSelection(draft.recordUuid, item.uuid, ElementQuantity.CeilingArea, 104,
            approval = ElementApproval.APPROVED, businessRole = BusinessRole.CANVAS)
        val profileId = PriceRepository(database).createInGroup("profile.$suffix", "Profile $suffix", group, 0.0, 103,
            unit = "м.п.", quantityMode = QuantityMode.CEILING_PERIMETER)
        val profile = MeasurementElementRepository(database).createCatalogSelection(draft.recordUuid,
            PriceRepository(database).find(profileId)!!.uuid, ElementQuantity.CeilingPerimeter, 104, approval = ElementApproval.APPROVED, businessRole = BusinessRole.PRIMARY_PROFILE)
        MeasurementElementRepository(database).createWish(draft.recordUuid, "Discuss this", 105)
        return Fixture(project, draft.recordUuid, element.uuid, priceId, profile.uuid)
    }

}
