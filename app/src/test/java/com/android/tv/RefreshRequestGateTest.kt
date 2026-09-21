package com.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRequestGateTest {

    @Test
    fun late_result_from_an_older_refresh_is_not_current() {
        val gate = RefreshRequestGate()
        val oldRequest = gate.begin()
        val newestRequest = gate.begin()

        assertFalse(gate.isCurrent(oldRequest))
        assertTrue(gate.isCurrent(newestRequest))
    }

    @Test
    fun late_result_cannot_publish_through_the_gate() {
        val gate = RefreshRequestGate()
        val oldRequest = gate.begin()
        gate.begin()
        var published = false

        assertFalse(gate.runIfCurrent(oldRequest) { published = true })
        assertFalse(published)
    }
}
