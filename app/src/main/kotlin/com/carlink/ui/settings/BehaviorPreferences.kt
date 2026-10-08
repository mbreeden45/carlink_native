package com.carlink.ui.settings

import android.content.Context

/**
 * Small on/off switches for app behaviour that are risky enough to want a way out in the field.
 * SharedPreferences (not DataStore) so reads are synchronous at startup without risking an ANR.
 */
object BehaviorPreferences {
    private const val FILE = "carlink_behavior"
    private const val KEY_AUDIO_FOCUS = "audio_focus_enabled"

    /**
     * Whether the app holds Android audio focus while CarPlay audio plays. On by default: without it
     * the car's own sources (Bluetooth media, radio) are never told CarPlay is playing. Turn off if a
     * head unit's focus policy misbehaves (audio cutting out when switching sources).
     */
    fun audioFocusEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS, true)

    fun setAudioFocusEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_FOCUS, enabled)
            .apply()
    }
}
