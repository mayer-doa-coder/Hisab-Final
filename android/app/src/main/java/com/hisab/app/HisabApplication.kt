package com.hisab.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class HisabApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // On the very first launch nothing is stored yet, and Android would
        // otherwise fall back to the phone's language — which would show
        // English on an English phone. Bangla is the default (D009), so pin it
        // explicitly. Done here rather than in the Activity to avoid a visible
        // recreate on first open.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            setAppLanguage(AppLanguage.BANGLA)
        }
    }
}
