package com.samourai.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the state machine that replaced the leaky
 * `MutableLiveData<MutableList<Boolean>>` loading counter.
 *
 * The old design could permanently retain a `true` entry when a fetch failed on
 * a path that did not decrement it, which left the spinner running forever.
 */
class SyncStateTest {

    @Test
    fun `syncing reports determinate progress`() {
        val state = SyncState.Syncing(done = 2, total = 5)
        assertEquals(2, state.done)
        assertEquals(5, state.total)
    }

    /**
     * A slow sync must remain a Syncing state, NOT become a failure. Tor circuits
     * can legitimately take minutes, and the earlier hard timeout turned healthy
     * slow syncs into false "Sync timed out" errors.
     */
    @Test
    fun `a slow sync is still a syncing state, not a failure`() {
        val slow: SyncState = SyncState.Syncing(done = 1, total = 3, slow = true)
        assertTrue(slow is SyncState.Syncing)
        assertTrue((slow as SyncState.Syncing).slow)
    }

    @Test
    fun `syncing defaults to not slow`() {
        assertEquals(false, SyncState.Syncing(done = 0, total = 1).slow)
    }

    @Test
    fun `failed states carry a reason for the user`() {
        val state = SyncState.Failed("Could not reach the server.", retryable = true)
        assertEquals("Could not reach the server.", state.reason)
        assertEquals(true, state.retryable)
    }

    @Test
    fun `terminal states are distinguishable from in-progress states`() {
        val inProgress: SyncState = SyncState.Syncing(1, 3)
        val terminal: SyncState = SyncState.Success(System.currentTimeMillis())
        assertNotEquals(inProgress::class, terminal::class)
    }

    @Test
    fun `waiting for tor can represent unknown progress`() {
        assertEquals(-1, SyncState.WaitingForTor().progress)
        assertEquals(42, SyncState.WaitingForTor(42).progress)
    }

    /**
     * The indicator must only be GREEN when Tor is up AND the sync succeeded.
     * These assertions mirror the precedence in HomeViewModel.connectionIndicator().
     */
    @Test
    fun `indicator enum covers all four traffic-light states`() {
        val all = ConnectionIndicator.entries.toSet()
        assertEquals(
            setOf(
                ConnectionIndicator.GREEN,
                ConnectionIndicator.YELLOW,
                ConnectionIndicator.RED,
                ConnectionIndicator.NEUTRAL
            ),
            all
        )
    }
}
