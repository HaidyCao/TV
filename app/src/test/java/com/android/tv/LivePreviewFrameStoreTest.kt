package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewFrameStoreTest {

    @Test
    fun byte_budget_evicts_least_recently_used_entry() {
        val cache = ByteBoundedLruCache<String, String>(10) { it.length }

        assertTrue(cache.put("first", "1234"))
        assertTrue(cache.put("second", "5678"))
        assertEquals("1234", cache.get("first"))
        assertTrue(cache.put("third", "90ab"))

        assertEquals("1234", cache.get("first"))
        assertNull(cache.get("second"))
        assertEquals("90ab", cache.get("third"))
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun an_entry_larger_than_the_budget_is_not_retained() {
        val cache = ByteBoundedLruCache<String, String>(4) { it.length }

        assertFalse(cache.put("too-large", "12345"))

        assertNull(cache.get("too-large"))
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun replacing_a_url_updates_its_byte_accounting() {
        val cache = ByteBoundedLruCache<String, String>(10) { it.length }

        assertTrue(cache.put("https://example.test/live", "123456"))
        assertTrue(cache.put("https://example.test/live", "12"))

        assertEquals("12", cache.get("https://example.test/live"))
        assertEquals(2, cache.sizeBytes)
    }
}
