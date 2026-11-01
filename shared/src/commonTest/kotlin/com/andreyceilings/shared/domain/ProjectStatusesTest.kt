package com.andreyceilings.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectStatusesTest {
    @Test
    fun workAndPaymentCanDescribeDifferentAspectsOfTheSameProject() {
        assertEquals("Монтаж", ProjectStatusRules.workLabel("in_progress"))
        assertEquals("Оплачено", ProjectStatusRules.paymentLabel("paid"))
        assertEquals("Доопрацювання", ProjectStatusRules.workLabel("rework"))
        assertEquals("Призупинено", ProjectStatusRules.workLabel("paused"))
        assertEquals(ProjectWorkStage.PAUSED, ProjectWorkStage.fromWireId("paused"))
        assertTrue(ProjectStatusRules.isKnownWorkStage("paused"))
        assertEquals("Частково оплачено", ProjectStatusRules.paymentLabel("partial"))
    }

    @Test
    fun oldPaidWorkStatusNeverClaimsInstallationWasCompleted() {
        assertEquals("Етап не визначено", ProjectStatusRules.workLabel("paid"))
        assertEquals("Здано", ProjectStatusRules.workLabel("completed"))
        assertEquals("Невідомий етап", ProjectStatusRules.workLabel("future_status"))
        assertEquals("Оплата не визначена", ProjectStatusRules.paymentLabel("future_payment"))
        assertTrue(ProjectStatusRules.isKnownPaymentStatus("unknown"))
        assertFalse(ProjectStatusRules.isKnownWorkStage("paid"))
        assertFalse(ProjectStatusRules.isKnownPaymentStatus("invented"))
    }
}
