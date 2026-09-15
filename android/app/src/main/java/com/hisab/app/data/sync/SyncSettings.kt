package com.hisab.app.data.sync

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Where the server is, who this phone signs in as, and the session token it
 * got back. Kept in ordinary app storage, which is private to the app.
 *
 * The password is never stored — it is typed once, exchanged for a token, and
 * forgotten. Moving the token itself into encrypted storage is part of the
 * security hardening in M7 (docs/SECURITY.md).
 */
class SyncSettings(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = preferences.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    var email: String
        get() = preferences.getString(KEY_EMAIL, "") ?: ""
        set(value) = preferences.edit { putString(KEY_EMAIL, value.trim()) }

    var token: String?
        get() = preferences.getString(KEY_TOKEN, null)
        set(value) = preferences.edit { putString(KEY_TOKEN, value) }

    /** This phone's own id, so the saved sync position belongs to this device alone. */
    val deviceId: String
        get() =
            preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.edit { putString(KEY_DEVICE_ID, it) }
            }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "device_id"

        /**
         * Works over a USB cable with `adb reverse tcp:3000 tcp:3000`, which
         * makes the laptop's server look local to the phone. Over Wi-Fi, put
         * the computer's address here instead.
         */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:3000"
    }
}
