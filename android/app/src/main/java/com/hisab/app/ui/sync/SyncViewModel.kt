package com.hisab.app.ui.sync

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.sync.HttpSyncApi
import com.hisab.app.data.sync.NotSignedInException
import com.hisab.app.data.sync.SyncEngine
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.data.sync.SyncReport
import com.hisab.app.data.sync.SyncSettings
import kotlinx.coroutines.launch

/** Everything the sync screen shows, and the one action it can take. */
class SyncViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = HisabDatabase.get(application)
    private val settings = SyncSettings(application)

    var serverUrl by mutableStateOf(settings.serverUrl)
        private set
    var email by mutableStateOf(settings.email)
        private set
    var password by mutableStateOf("")
        private set

    var running by mutableStateOf(false)
        private set
    var lastReport by mutableStateOf<SyncReport?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var needsSignIn by mutableStateOf(false)
        private set
    var waitingToSend by mutableIntStateOf(0)
        private set

    /** Changes the server would not take. They stay on the phone until Step 72 (D045). */
    var refused by mutableIntStateOf(0)
        private set

    /**
     * Whether this phone has ever finished a sync. Read from the saved cursor
     * rather than from `lastReport`, so reopening the screen does not make a
     * shop that has synced for weeks read as "never synced".
     */
    var hasSyncedBefore by mutableStateOf(false)
        private set

    /** What the screen shows, in one plain sentence (Step 66). */
    val status: SyncStatus
        get() =
            syncStatusFor(
                running = running,
                needsSignIn = needsSignIn,
                lastAttemptFailed = failure != null,
                hasSyncedBefore = hasSyncedBefore,
                waitingToSend = waitingToSend,
                refused = refused,
            )

    init {
        refreshWaitingCount()
    }

    fun onServerUrlChange(value: String) {
        serverUrl = value
    }

    fun onEmailChange(value: String) {
        email = value
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    fun syncNow() {
        if (running) return
        running = true
        failure = null
        needsSignIn = false

        viewModelScope.launch {
            settings.serverUrl = serverUrl
            settings.email = email
            try {
                val engine = SyncEngine(database, HttpSyncApi(settings.serverUrl), settings)
                lastReport = engine.sync(password.ifBlank { null })
                // The password did its job; it is never kept.
                password = ""
            } catch (_: NotSignedInException) {
                needsSignIn = true
            } catch (error: Exception) {
                failure = error.message ?: error::class.simpleName ?: "unknown error"
            } finally {
                running = false
                refreshWaitingCount()
            }
        }
    }

    private fun refreshWaitingCount() {
        viewModelScope.launch {
            val outbox = database.syncOutboxDao()
            waitingToSend = outbox.withStatus(SyncOutboxEntity.STATUS_PENDING).size
            refused =
                outbox.withStatus(SyncOutboxEntity.STATUS_CONFLICT).size +
                outbox.withStatus(SyncOutboxEntity.STATUS_REJECTED).size
            hasSyncedBefore = database.syncMetadataDao().get() != null
        }
    }
}
