package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteLongPressPolicyTest {

    @Test
    fun short_confirm_passes_through_to_open_the_card() {
        val policy = FavoriteLongPressPolicy()

        assertEquals(
            FavoriteLongPressResult.PASS_THROUGH,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 0,
                isLongPress = false
            )
        )
        assertEquals(
            FavoriteLongPressResult.PASS_THROUGH,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_UP,
                repeatCount = 0,
                isLongPress = false
            )
        )
    }

    @Test
    fun long_confirm_toggles_once_and_consumes_repeats_and_release() {
        val policy = FavoriteLongPressPolicy()

        assertEquals(
            FavoriteLongPressResult.PASS_THROUGH,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 0,
                isLongPress = false
            )
        )
        assertEquals(
            FavoriteLongPressResult.TOGGLE,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 1,
                isLongPress = true
            )
        )
        assertEquals(
            FavoriteLongPressResult.CONSUME,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 2,
                isLongPress = true
            )
        )
        assertEquals(
            FavoriteLongPressResult.CONSUME,
            policy.onKey(
                FavoriteLongPressPolicy.DPAD_CENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_UP,
                repeatCount = 0,
                isLongPress = false
            )
        )
    }

    @Test
    fun enter_long_press_uses_the_same_single_toggle_path() {
        val policy = FavoriteLongPressPolicy()

        assertEquals(
            FavoriteLongPressResult.TOGGLE,
            policy.onKey(
                FavoriteLongPressPolicy.ENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 0,
                isLongPress = true
            )
        )
        assertEquals(
            FavoriteLongPressResult.CONSUME,
            policy.onKey(
                FavoriteLongPressPolicy.ENTER_KEY_CODE,
                FavoriteLongPressPolicy.ACTION_CANCEL,
                repeatCount = 0,
                isLongPress = false
            )
        )
    }

    @Test
    fun unrelated_keys_are_always_left_to_the_view() {
        val policy = FavoriteLongPressPolicy()

        assertEquals(
            FavoriteLongPressResult.PASS_THROUGH,
            policy.onKey(
                keyCode = 21,
                action = FavoriteLongPressPolicy.ACTION_DOWN,
                repeatCount = 4,
                isLongPress = true
            )
        )
    }
}
