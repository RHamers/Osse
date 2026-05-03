package com.rhamers.osse.model

import org.junit.Assert.*
import org.junit.Test

class TrackStateTest {

    @Test
    fun defaultValues() {
        val state = TrackState()
        assertNull(state.source)
        assertFalse(state.isPlaying)
        assertEquals(1.0f, state.volume, 0.001f)
        assertEquals(1.0f, state.speed, 0.001f)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertFalse(state.isLoading)
    }

    @Test
    fun copyPreservesUnchangedFields() {
        val updated = TrackState().copy(isPlaying = true, volume = 0.5f)
        assertTrue(updated.isPlaying)
        assertEquals(0.5f, updated.volume, 0.001f)
        assertEquals(1.0f, updated.speed, 0.001f)
        assertEquals(0L, updated.positionMs)
    }
}
