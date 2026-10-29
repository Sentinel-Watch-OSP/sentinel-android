package com.samourai.sentinel.core

/**
 * Explicit, leak-proof representation of the app's sync lifecycle.
 *
 * This replaces the old `MutableLiveData<MutableList<Boolean>>` counter, which
 * could permanently leak a `true` entry when a fetch failed on a path that did
 * not decrement it (notably "unable to resolve host"), leaving the UI spinning
 * forever.
 */
sealed interface SyncState {

    /** Nothing in flight, nothing to report. */
    data object Idle : SyncState

    /** Tor is required but not yet usable. [progress] is 0..100, -1 if unknown. */
    data class WaitingForTor(val progress: Int = -1) : SyncState

    /**
     * Actively syncing. [done] of [total] collections completed.
     *
     * [slow] is set once the sync has been running long enough to be worth
     * mentioning to the user. It is purely informational - the work is NEVER
     * cancelled just because it is slow, because Tor circuits can legitimately
     * take minutes to build.
     */
    data class Syncing(
        val done: Int,
        val total: Int,
        val slow: Boolean = false
    ) : SyncState

    /** Sync finished successfully at [atMillis]. */
    data class Success(val atMillis: Long) : SyncState

    /** Sync failed. [retryable] drives whether we offer a Retry affordance. */
    data class Failed(val reason: String, val retryable: Boolean = true) : SyncState
}

/**
 * The traffic-light state for the network indicator in the toolbar.
 *
 * - [GREEN]  Tor connected *and* all collections synced.
 * - [YELLOW] Connecting / bootstrapping / syncing. Rendered flashing.
 * - [RED]    Connection or sync failed.
 * - [NEUTRAL] Nothing attempted yet.
 */
enum class ConnectionIndicator {
    GREEN,
    YELLOW,
    RED,
    NEUTRAL
}
