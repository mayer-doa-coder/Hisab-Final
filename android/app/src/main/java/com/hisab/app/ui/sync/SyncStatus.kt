package com.hisab.app.ui.sync

/**
 * Where this shop's data stands, as a code that is the same whatever language
 * the app is in (D011). Only the sentence shown to the shopkeeper is
 * translated, and both translations must mean the same thing.
 *
 * Nothing here is technical: a shopkeeper is told whether their work is safe
 * on the phone and whether the server has it yet, never how many events were
 * pushed or which HTTP call failed (Step 66).
 */
enum class SyncStatusCode {
    SYNC_RUNNING,
    SYNC_NOT_SIGNED_IN,

    /** The server would not take some changes. Only a person can settle these. */
    SYNC_CONFLICT,

    /** Saved here, but the last attempt could not reach the server. */
    SYNC_UNREACHABLE,

    /** Saved here, waiting for the next sync to carry it. */
    SYNC_PENDING,

    SYNC_SYNCED,
    SYNC_NEVER,
}

data class SyncStatus(
    val code: SyncStatusCode,
    val waitingToSend: Int = 0,
    val refused: Int = 0,
)

/**
 * Picks the one thing worth telling the shopkeeper, in the order that matters
 * to them (Step 66). Kept as a plain function so the choice is tested without
 * a device, the same way `syncOutcomeFor` is.
 *
 * The order is deliberate. A refused change outranks a failed attempt because
 * it does not fix itself by waiting — someone has to look at it — while a
 * server that could not be reached usually does. Both outrank "waiting to
 * send", because in both of those cases something is waiting anyway and the
 * reason is the more useful half. "Nothing waiting" only reads as *synced*
 * once a sync has actually happened; before that it is *never synced*, which
 * is a different thing and should not be dressed up as success.
 */
fun syncStatusFor(
    running: Boolean,
    needsSignIn: Boolean,
    lastAttemptFailed: Boolean,
    hasSyncedBefore: Boolean,
    waitingToSend: Int,
    refused: Int,
): SyncStatus {
    val code =
        when {
            running -> SyncStatusCode.SYNC_RUNNING
            needsSignIn -> SyncStatusCode.SYNC_NOT_SIGNED_IN
            refused > 0 -> SyncStatusCode.SYNC_CONFLICT
            lastAttemptFailed -> SyncStatusCode.SYNC_UNREACHABLE
            waitingToSend > 0 -> SyncStatusCode.SYNC_PENDING
            hasSyncedBefore -> SyncStatusCode.SYNC_SYNCED
            else -> SyncStatusCode.SYNC_NEVER
        }
    return SyncStatus(code = code, waitingToSend = waitingToSend, refused = refused)
}
