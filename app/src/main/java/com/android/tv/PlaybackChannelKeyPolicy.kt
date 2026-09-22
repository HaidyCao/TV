package com.android.tv

/** Maps TV remote keys to the direction used by the live channel navigator. */
internal object PlaybackChannelKeyPolicy {

    const val PREVIOUS_CHANNEL = -1
    const val NEXT_CHANNEL = 1

    fun directionFor(keyCode: Int): Int? {
        return when (keyCode) {
            KEYCODE_DPAD_UP,
            KEYCODE_CHANNEL_UP,
            KEYCODE_PAGE_UP -> PREVIOUS_CHANNEL

            KEYCODE_DPAD_DOWN,
            KEYCODE_CHANNEL_DOWN,
            KEYCODE_PAGE_DOWN -> NEXT_CHANNEL

            else -> null
        }
    }

    private const val KEYCODE_DPAD_UP = 19
    private const val KEYCODE_DPAD_DOWN = 20
    private const val KEYCODE_CHANNEL_UP = 166
    private const val KEYCODE_CHANNEL_DOWN = 167
    private const val KEYCODE_PAGE_UP = 92
    private const val KEYCODE_PAGE_DOWN = 93
}
