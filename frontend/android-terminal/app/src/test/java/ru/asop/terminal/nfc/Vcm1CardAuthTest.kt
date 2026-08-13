package ru.asop.terminal.nfc

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vcm1CardAuthTest {
    @Test fun outcome_kind_for_auth_failed_does_not_throw() {
        // Real Tag requires device NFC hardware — only check typing compiles.
        val ok: Vcm1CardAuth.Outcome.Ok? = null
        val failed: Vcm1CardAuth.Outcome.Failed? = null
        assertNull(ok)
        assertNull(failed)
    }

    @Test fun status_enum_has_expected_values() {
        // Frozen contract: эти имена статусов используются в user-message mapping.
        val s = Vcm1CardAuth.Outcome.Status.values().map { it.name }
        assertTrue(s.contains("NOT_MIFARE_CLASSIC"))
        assertTrue(s.contains("AUTH_FAILED"))
        assertTrue(s.contains("READ_FAILED"))
        assertTrue(s.contains("NOT_VCM1"))
        assertEquals(4, s.size)
    }
}
