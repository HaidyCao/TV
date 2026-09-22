package com.android.tv

/**
 * Keeps a confirm-key long press separate from the normal card click.
 *
 * The policy is deliberately independent of Android views so the important
 * repeat and release behavior can be tested on the JVM.
 */
internal class FavoriteLongPressPolicy {
    private var pressedKeyCode: Int? = null
    private var toggledForPress = false

    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isLongPress: Boolean
    ): FavoriteLongPressResult {
        if (keyCode != DPAD_CENTER_KEY_CODE && keyCode != ENTER_KEY_CODE) {
            return FavoriteLongPressResult.PASS_THROUGH
        }

        return when (action) {
            ACTION_DOWN -> {
                if (pressedKeyCode == null) {
                    pressedKeyCode = keyCode
                } else if (pressedKeyCode != keyCode) {
                    reset()
                    pressedKeyCode = keyCode
                }

                if (!toggledForPress && (repeatCount > 0 || isLongPress)) {
                    toggledForPress = true
                    FavoriteLongPressResult.TOGGLE
                } else if (toggledForPress) {
                    FavoriteLongPressResult.CONSUME
                } else {
                    FavoriteLongPressResult.PASS_THROUGH
                }
            }

            ACTION_UP,
            ACTION_CANCEL -> {
                val wasLongPress = toggledForPress
                reset()
                if (wasLongPress) {
                    FavoriteLongPressResult.CONSUME
                } else {
                    FavoriteLongPressResult.PASS_THROUGH
                }
            }

            else -> FavoriteLongPressResult.PASS_THROUGH
        }
    }

    internal fun reset() {
        pressedKeyCode = null
        toggledForPress = false
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_CANCEL = 3
        const val DPAD_CENTER_KEY_CODE = 23
        const val ENTER_KEY_CODE = 66
    }
}

internal enum class FavoriteLongPressResult {
    PASS_THROUGH,
    TOGGLE,
    CONSUME
}
